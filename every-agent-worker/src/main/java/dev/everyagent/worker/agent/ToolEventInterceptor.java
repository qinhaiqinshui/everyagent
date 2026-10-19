package dev.everyagent.worker.agent;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.proto.SnowflakeId;
import dev.everyagent.plugin.api.shell.ExecResults;
import dev.everyagent.plugin.api.spi.ToolExecutionChain;
import dev.everyagent.plugin.api.spi.ToolExecutionContext;
import dev.everyagent.plugin.api.spi.ToolExecutionInterceptor;
import dev.everyagent.plugin.api.spi.ToolOutcomeLog;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 工具执行事件发射节点(per-call 拦截链唯一内置发射点,架构 §5.3/§7.14.3):
 * <ul>
 *   <li><b>下行段</b>发瞬态 {@code tool.started}(callId/name/入参/startedAt 毫秒)——
 *       callId 与 {@code message.toolCalls[].id} 直配,前端调用行点亮 running + 读秒
 *       ({@code now - startedAt}),不依赖执行顺序;瞬态不落盘(同 delta/thinking 路径),
 *       历史回放无此事件(直接展示 result 的 durationMs)。</li>
 *   <li><b>上行段</b>发落盘 {@code tool.result}(callId + 结果串 + startedAt/durationMs):
 *       结果串 = {@code next.proceed} 返回值——即内层全部改写节点处理后的文本
 *       (secret-redaction 掩码 / rg-hint 追加照常生效),<b>事件侧不截断</b>;status 按前缀
 *       约定 + 结构化账本判定(见 {@link #statusOf});同时按 <b>callId</b> 写账
 *       ({@link ToolOutcomeLog#recordCallOutcome},后置消费者的查询键)。</li>
 * </ul>
 *
 * <p><b>order=500</b>:位于 {@code secret-redaction}(900,最内层结果改写,上行第一个拿到
 * 原始串)<b>外侧</b>——本节点上行段读到的已是掩码后文本;位于 {@code unattended}(100,
 * ask_user 合成)<b>内侧</b>——合成串沿链上行照样流经本节点照常发 result。原批级发射点
 * ({@code WorkerToolEventAdvisor.doGetNextInstructionsForToolCallStream} 从
 * conversationHistory 回扫 +「名字+入参」查账)已退役。
 *
 * <p>leak-guard:执行主体已收口({@code ctx.terminal()})时不再发射(取消竞态窗口内
 * 残留调用直接静默)——中断批表现为「started 已发、result 缺失」,前端标「执行中被中断」。
 */
public final class ToolEventInterceptor implements ToolExecutionInterceptor {

    @Override
    public String id() {
        return "tool-events";
    }

    @Override
    public float order() {
        return 500f;
    }

    @Override
    public String invoke(ToolExecutionContext ctx, ToolExecutionChain next) throws Exception {
        emitStarted(ctx);
        String result = next.proceed(ctx);
        emitResult(ctx, result);
        return result;
    }

    /** 链下行:瞬态 tool.started(callId 直配,startedAt 供前端读秒)。 */
    private void emitStarted(ToolExecutionContext ctx) {
        if (ctx.terminal()) {
            return; // leak-guard:主体收口后不再发射
        }
        EventEmitterHolder.emit(ctx, () -> {
            ObjectNode data = Json.obj();
            data.put("callId", ctx.toolCall().id());
            data.put("name", ctx.toolCall().name());
            data.put("arguments", ctx.arguments() == null ? "" : ctx.arguments());
            data.put("startedAt", ctx.startedAt());
            return EmitEvent.transientOf(SnowflakeId.next(), Events.TOOL_STARTED,
                    null, null, null, null, null, data, EmitEvent.Mode.REPLACE);
        });
    }

    /** 链上行:落盘 tool.result(结果串原样不截断;durationMs 在切口计时间自然可得)。 */
    private void emitResult(ToolExecutionContext ctx, String result) {
        if (ctx.terminal()) {
            return; // leak-guard:中断批已发 started、result 缺失(前端标「执行中被中断」)
        }
        String summary = result == null ? "(无返回)" : result;
        String status = statusOf(ctx, result);
        recordOutcome(ctx, status);
        EventEmitterHolder.emit(ctx, () -> {
            ObjectNode data = Json.obj();
            data.put("callId", ctx.toolCall().id());
            data.put("name", ctx.toolCall().name());
            data.put("status", status);
            data.put("startedAt", ctx.startedAt());
            data.put("durationMs", Math.max(0, System.currentTimeMillis() - ctx.startedAt()));
            return EmitEvent.of(SnowflakeId.next(), Events.TOOL_RESULT,
                    null, null, summary, null, null, data, EmitEvent.Mode.REPLACE);
        });
    }

    /**
     * 链上行按 callId 写账(单点判定结果落账;后置消费者按 callId 直查,架构 §7.14.3)。
     */
    private static void recordOutcome(ToolExecutionContext ctx, String status) {
        ToolOutcomeLog log = ctx.toolOutcomes();
        if (log != null) {
            log.recordCallOutcome(ctx.toolCall().id(), "error".equals(status));
        }
    }

    /**
     * 成败判定(单点):
     * <ol>
     *   <li>结果串以 {@link ExecResults#TOOL_FAILURE_PREFIX} 开头 → error(前缀约定:
     *       异常处理器 {@code CANCELLING_PROCESSOR} 与缺工具兜底 {@code MissingToolCallbackResolver}
     *       的产出前缀);</li>
     *   <li>否则查结构化结果账本兜底:shell 退出码/超时(执行器按「工具名+命令」记账,
     *       <b>非零退出码即失败</b>,2026-12 用户决策)→ 工具抛异常(回调包装器按
     *       「工具名+入参」记账,与前缀同源的双保险);</li>
     *   <li>都查不到 → success(执行器未接入/正常返回)。账本为 null(未装配)一律 success。</li>
     * </ol>
     * 不做通用文案匹配——「读/grep 到失败文案源码」的命令会自指误判为失败。
     */
    static String statusOf(ToolExecutionContext ctx, String result) {
        if (result != null && result.startsWith(ExecResults.TOOL_FAILURE_PREFIX)) {
            return "error";
        }
        ToolOutcomeLog log = ctx.toolOutcomes();
        if (log == null) {
            return "success";
        }
        String toolName = ctx.toolCall().name();
        String arguments = ctx.arguments() == null ? "" : ctx.arguments();
        ToolOutcomeLog.ShellExit exit = log.consumeShellExit(toolName, commandOf(arguments));
        if (exit != null) {
            return exit.failed() ? "error" : "success";
        }
        return log.consumeToolThrow(toolName, arguments) ? "error" : "success";
    }

    /**
     * 从工具入参 JSON 取 {@code command}(shell 工具入参专有字段);取不到返回 null
     * (账本对 null 载荷不记账也不命中,回退「抛异常」判据)。
     */
    private static String commandOf(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            JsonNode node = Json.parse(arguments);
            JsonNode cmd = node == null ? null : node.path("command");
            return cmd != null && cmd.isTextual() ? cmd.asText() : null;
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    /** 发射辅助:emitter 缺席(裸用/单测)时静默跳过。 */
    private static final class EventEmitterHolder {

        static void emit(ToolExecutionContext ctx, java.util.function.Supplier<EmitEvent> event) {
            if (ctx.emitter() == null) {
                return;
            }
            ctx.emitter().emit(event.get());
        }
    }
}
