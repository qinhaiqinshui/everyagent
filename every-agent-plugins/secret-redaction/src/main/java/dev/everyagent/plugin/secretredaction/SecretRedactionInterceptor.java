package dev.everyagent.plugin.secretredaction;

import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.proto.SnowflakeId;
import dev.everyagent.plugin.api.spi.ToolExecutionChain;
import dev.everyagent.plugin.api.spi.ToolExecutionContext;
import dev.everyagent.plugin.api.spi.ToolExecutionInterceptor;
import dev.everyagent.plugin.api.util.SecretPatterns;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具输出凭据脱敏拦截器（工具执行拦截链<b>上行段</b>，架构 §7.17「真实 key 不进事件日志」
 * 的执行落点）。
 *
 * <p>为什么挂在这里：工具结果文本是凭据外泄的<b>唯一还能「写入前补齐」的关口</b>——
 * 它下游的三条路（{@code tool.result} 落 EventLog 落盘、DataPusher 定向推送、
 * {@code ToolResponseMessage} 回灌下一轮模型）共用同一份 {@code conversationHistory}，
 * 在链上改写一次即三条路全覆盖；而 assistant 正文是流式边生成边落盘的，事后改写会破坏
 * seq 不可变语义（§5.4 运行中日志永不修剪），故不在此处处理。
 *
 * <p>只处理<b>本轮</b>刚下发的 callId 对应的工具结果：历史轮在产生那一轮已脱敏，且
 * {@link SecretPatterns#redact} 幂等，重复扫描只是每轮 O(全历史) 的正则开销，无收益。
 * 冷启动续跑时磁盘上的 {@code tool.result} 已是掩码文本，重建的历史同样干净。
 *
 * <p>审计走 {@code task.trace}（只报命中次数与工具名，<b>绝不报值</b>），前端可见
 * 「这条输出里有凭据、已被掩码」，用户不会误以为是工具本身出错。
 *
 * <p><b>禁用/删除本插件的影响面</b>：只失去输出侧掩码；§7.10 的 env 继承剔除住在
 * plugin-api + 沙箱/worker 的常驻链路里，<b>不随本插件装卸而失效</b>。规则源共用
 * {@code SecretPatterns}，故两半永远同口径（不会出现在环境侧已剔除、输出侧却漏掩的漂移）。
 *
 * <p>order=900：尽量靠链尾，即真实执行完成后第一个做后处理的节点——在权限门/审计等
 * 前置拦截之后，在事件发射（{@code WorkerToolEventAdvisor} 取本轮 ToolResponseMessage）之前。
 * 现役拦截链只有两环（{@code UnattendedToolInterceptor}=100 与本类=900），本类位于最内层；
 * Unattended 上行段只读计数不重建结果，不存在覆盖本类输出的路径。
 */
public class SecretRedactionInterceptor implements ToolExecutionInterceptor {

    private static final System.Logger LOG =
            System.getLogger(SecretRedactionInterceptor.class.getName());

    @Override
    public String id() {
        return "secret-redaction";
    }

    @Override
    public float order() {
        return 900f;
    }

    @Override
    public ToolExecutionResult invoke(ToolExecutionContext ctx, ToolExecutionChain next)
            throws Exception {
        ToolExecutionResult result = next.proceed(ctx);
        if (result == null) {
            return null;
        }
        List<Message> history = result.conversationHistory();
        if (history == null || history.isEmpty()) {
            return result;
        }
        Set<String> currentCallIds = currentCallIds(ctx);
        if (currentCallIds.isEmpty()) {
            return result;
        }

        List<Message> rebuilt = new ArrayList<>(history.size());
        List<String> affectedTools = new ArrayList<>();
        int hits = 0;
        boolean changed = false;

        for (Message m : history) {
            if (m instanceof ToolResponseMessage trm && trm.getResponses() != null) {
                List<ToolResponse> out = new ArrayList<>(trm.getResponses().size());
                boolean msgChanged = false;
                for (ToolResponse r : trm.getResponses()) {
                    if (r == null || !currentCallIds.contains(r.id())) {
                        out.add(r);
                        continue;
                    }
                    String data = r.responseData();
                    String redacted = SecretPatterns.redact(data);
                    // redact 无命中时返回同一实例,用身份比较即可判定「是否改写过」
                    if (redacted != data) {
                        out.add(new ToolResponse(r.id(), r.name(), redacted));
                        msgChanged = true;
                        hits += SecretPatterns.countSecrets(data);
                        if (r.name() != null && !affectedTools.contains(r.name())) {
                            affectedTools.add(r.name());
                        }
                    } else {
                        out.add(r);
                    }
                }
                rebuilt.add(msgChanged
                        ? ToolResponseMessage.builder().responses(out)
                                .metadata(trm.getMetadata()).build()
                        : m);
                if (msgChanged) {
                    changed = true;
                }
            } else {
                rebuilt.add(m);
            }
        }

        if (!changed) {
            return result;
        }

        LOG.log(System.Logger.Level.WARNING,
                "[secret-redaction] 工具输出含凭据形态,已掩码: hits={0} tools={1} subject={2}",
                new Object[] { hits, affectedTools, ctx.subjectId() });
        emitTrace(ctx, hits, affectedTools);

        return ToolExecutionResult.builder()
                .conversationHistory(rebuilt)
                .returnDirect(result.returnDirect())
                .build();
    }

    /** 本轮下发的 callId 集合（与 WorkerToolEventAdvisor 取本轮结果的口径一致）。 */
    private static Set<String> currentCallIds(ToolExecutionContext ctx) {
        List<AssistantMessage.ToolCall> toolCalls = ctx.toolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Set.of();
        }
        Set<String> ids = new HashSet<>();
        for (AssistantMessage.ToolCall tc : toolCalls) {
            if (tc != null && tc.id() != null) {
                ids.add(tc.id());
            }
        }
        return ids;
    }

    /** task.trace 审计事件：只携带次数/工具名，无任何凭据内容。 */
    private void emitTrace(ToolExecutionContext ctx, int hits, List<String> tools) {
        if (ctx.emitter() == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hits", hits);
        data.put("tools", tools);
        String summary = "工具输出含凭据形态,已掩码 " + hits + " 处(" + String.join(", ", tools) + ")";
        ctx.emitter().emit(EmitEvent.of(SnowflakeId.next(), Events.TASK_TRACE, null,
                "输出凭据脱敏", summary,
                "本轮工具输出中出现密钥/API token 形态的值,落盘与回灌前已替换为掩码"
                        + "(形如 sk-ant-sid…6280[len=78]);若需原文,请改用不落盘的凭据存放方式。",
                "done", data, EmitEvent.Mode.REPLACE));
    }
}
