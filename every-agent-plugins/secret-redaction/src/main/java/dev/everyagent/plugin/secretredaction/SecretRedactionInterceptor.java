package dev.everyagent.plugin.secretredaction;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.proto.SnowflakeId;
import dev.everyagent.plugin.api.spi.ToolExecutionChain;
import dev.everyagent.plugin.api.spi.ToolExecutionContext;
import dev.everyagent.plugin.api.spi.ToolExecutionInterceptor;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 工具输出凭据脱敏拦截器(per-call 工具执行拦截链<b>上行段</b>,架构 §7.17「真实 key 不进事件日志」
 * 的执行落点;per-call 粒度——每次工具调用一遍链,只处理<b>当前调用</b>的结果串,
 * {@code next.proceed} 返回什么就掩码什么,不扫描会话历史)。
 *
 * <p>为什么挂在这里：工具结果文本是凭据外泄的<b>唯一还能「写入前补齐」的关口</b>——
 * 它下游的三条路（{@code tool.result} 落 EventLog 落盘、DataPusher 定向推送、
 * {@code ToolResponseMessage} 回灌下一轮模型）共用同一份结果串,链上改写一次即三条路全覆盖；
 * 而 assistant 正文是流式边生成边落盘的，事后改写会破坏 seq 不可变语义（§5.4 运行中日志
 * 永不修剪），故不在此处处理。冷启动续跑时磁盘上的 {@code tool.result} 已是掩码文本,
 * 重建的历史同样干净。
 *
 * <p>审计走 {@code task.trace}（只报命中次数与工具名，<b>绝不报值</b>），前端可见
 * 「这条输出里有凭据、已被掩码」，用户不会误以为是工具本身出错。
 *
 * <p><b>禁用/删除本插件的影响面</b>：只失去输出侧掩码；§7.10 的 env 继承剔除住在
 * plugin-api + 沙箱/worker 的常驻链路里，<b>不随本插件装卸而失效</b>。
 *
 * <p><b>order=900(链上顺序语义,架构 §7.14.3)</b>:结果改写型节点最内层——下行段最后
 * 进入、上行段<b>第一个</b>拿到原始结果串(脱敏先于一切下游观察者:发射节点
 * {@code tool-events}=500 在本节点外侧,读到的已是掩码后文本;rg-hint=0 最外层,其判定与
 * 追加基于掩码后文本)。下行合成({@code ctx.synthesize})的合成串沿链上行照样流经
 * 本节点——合成答案照常脱敏(§7.14.3 方案 B)。
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
    public String invoke(ToolExecutionContext ctx, ToolExecutionChain next)
            throws Exception {
        String data = next.proceed(ctx);
        if (data == null || data.isEmpty()) {
            return data;
        }
        String redacted = SecretRedactor.redact(data);
        // redact 无命中时返回同一实例,用身份比较即可判定「是否改写过」
        if (redacted == data) {
            return data;
        }
        String toolName = ctx.toolCall().name();
        int hits = SecretRedactor.countSecrets(data);
        LOG.log(System.Logger.Level.WARNING,
                "[secret-redaction] 工具输出含凭据形态,已掩码: hits={0} tool={1} subject={2}",
                new Object[] { hits, toolName, ctx.subjectId() });
        emitTrace(ctx, hits, toolName);
        return redacted;
    }

    /** task.trace 审计事件：只携带次数/工具名，无任何凭据内容。 */
    private void emitTrace(ToolExecutionContext ctx, int hits, String toolName) {
        if (ctx.emitter() == null) {
            return;
        }
        ObjectNode data = Json.obj();
        data.put("hits", hits);
        data.set("tools", Json.toJson(List.of(toolName)));
        String summary = "工具输出含凭据形态,已掩码 " + hits + " 处(" + toolName + ")";
        ctx.emitter().emit(EmitEvent.of(SnowflakeId.next(), Events.TASK_TRACE, null,
                "输出凭据脱敏", summary,
                "本轮工具输出中出现密钥/API token 形态的值,落盘与回灌前已替换为掩码"
                        + "(形如 sk-ant-sid…6280[len=78]);若需原文,请改用不落盘的凭据存放方式。",
                "done", data, EmitEvent.Mode.REPLACE));
    }
}
