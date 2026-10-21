package dev.everyagent.plugin.unattended;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.spi.ToolExecutionChain;
import dev.everyagent.plugin.api.spi.ToolExecutionContext;
import dev.everyagent.plugin.api.spi.ToolExecutionInterceptor;
import org.springframework.ai.util.JacksonUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 无人值守工具执行拦截器:任务级开关开启时,对 {@code ask_user} 调用<b>链下行合成</b>
 * 自动作答(架构 §7.14.3 方案 B 切口)。
 *
 * <p>开关判定按任务级 {@code unattended.mode} slash token(见 {@link UnattendedToken#KIND})
 * —— 经 {@link WorkerServices#slashTokens()} 以磁盘为准查询,不再读任务 {@code metadata} 副本。
 *
 * <p><b>per-call 合成</b>:逐调用判定——当前调用是 {@code ask_user} 且无人值守开启时
 * {@code ctx.synthesize(「题干:首选项」)} + 照常 {@code next.proceed}(链尾见哨兵即跳过
 * observation 与真实提问,合成串沿框架 ToolResponse 构造路径流经全部上行节点——
 * secret-redaction 照常脱敏、发射点照常发 tool.result/start 计时)。<b>混合轮天然正确</b>:
 * 同批下发的其他工具调用不走合成分支,照常真实执行;无历史轮整批短路的手工
 * conversationHistory 构造(原批级实现已删)。
 */
public class UnattendedToolInterceptor implements ToolExecutionInterceptor {

    private static final ObjectMapper MAPPER = JacksonUtils.getDefaultJsonMapper();

    private static final String ASK_USER = "ask_user";

    private final WorkerServices services;

    public UnattendedToolInterceptor(WorkerServices services) {
        this.services = services;
    }

    @Override
    public String id() { return "unattended-tool-interceptor"; }

    @Override
    public float order() { return 100f; }

    @Override
    public String invoke(ToolExecutionContext ctx, ToolExecutionChain next) throws Exception {
        // 开关真相源 = 任务级 unattended.mode token(磁盘为准,运行中/终态/重启一致)
        if (services == null || !services.slashTokens().hasKind(ctx.subjectId(), UnattendedToken.KIND)) {
            return next.proceed(ctx); // 不是无人值守模式,放行
        }
        if (ctx.toolCall() == null || !ASK_USER.equals(ctx.toolCall().name())) {
            return next.proceed(ctx); // 当前调用不是 ask_user,放行(混合轮:其他工具真执行)
        }
        // 链下行合成:哨兵串走框架 ToolResponse 构造路径(不可终止批,循环继续推进)
        String answer = autoAnswer(ctx.toolCall().arguments());
        ctx.synthesize(answer);
        return next.proceed(ctx);
    }

    private static String autoAnswer(String toolInput) {
        try {
            JsonNode root = MAPPER.readTree(toolInput);
            JsonNode questions = root.path("questions");
            if (!questions.isArray() || questions.isEmpty()) {
                return "未提供任何问题,跳过提问。";
            }
            java.util.List<String> lines = new java.util.ArrayList<>();
            for (JsonNode q : questions) {
                String question = textOrEmpty(q.path("question"));
                JsonNode options = q.path("options");
                String firstOption = options.isArray() && !options.isEmpty()
                        ? textOrEmpty(options.get(0)) : "";
                if (!question.isEmpty() && !firstOption.isEmpty()) {
                    lines.add(question + "：" + firstOption);
                }
            }
            if (lines.isEmpty()) {
                return "未提供任何有效问题,跳过提问。";
            }
            return String.join("\n", lines);
        } catch (Exception e) {
            return "问题解析失败,跳过提问。";
        }
    }

    private static String textOrEmpty(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isValueNode()) {
            return node.asText("");
        }
        if (node.isObject()) {
            for (String key : new String[]{"text", "label", "name", "value", "title", "option", "content"}) {
                JsonNode v = node.get(key);
                if (v != null && v.isValueNode()) {
                    return v.asText("");
                }
            }
        }
        return node.toString();
    }
}
