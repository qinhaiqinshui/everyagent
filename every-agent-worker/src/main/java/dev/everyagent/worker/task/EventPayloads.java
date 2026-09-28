package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.worker.proto.Events.ToolCallPart;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * 事件 payload 格式化工具（供产生方构建 data / 文案）。
 *
 * <p>从 TaskEvents 搬出的格式化函数，供步骤 6-8 的调用方（WorkerToolEventAdvisor、
 * retry 插件、ContextCompressionAdvisor 等）在构建 {@code EmitEvent.data} 与
 * 展示文案时复用。
 */
public final class EventPayloads {

    private EventPayloads() {
    }

    // ---- retry 文案 ----

    /** 收起态摘要:重试进行中的「第 x/总 次 · y 秒后重试」文案。 */
    public static String retrySummary(int attempt, int maxAttempts, long remainingMs, String error) {
        String countdown = remainingMs > 0 ? formatDurationShort(remainingMs) + " 后重试" : "即将重试";
        return "第 " + attempt + "/" + maxAttempts + " 次失败," + countdown;
    }

    /** 展开态正文:错误原文 + 重试参数。 */
    public static String retryDetail(int attempt, int maxAttempts, long delayMs,
            long elapsedMs, long remainingMs, String error) {
        StringBuilder sb = new StringBuilder();
        sb.append("attempt: ").append(attempt).append('/').append(maxAttempts)
                .append(", delayMs: ").append(delayMs)
                .append(", elapsedMs: ").append(elapsedMs)
                .append(", remainingMs: ").append(remainingMs);
        if (error != null && !error.isEmpty()) {
            sb.append('\n').append(error);
        }
        return sb.toString();
    }

    /** retry data 结构化载荷。 */
    public static ObjectNode retryMeta(int attempt, int maxAttempts, long delayMs,
            long elapsedMs, long remainingMs, String error) {
        ObjectNode meta = Json.obj();
        meta.put("attempt", attempt);
        meta.put("maxAttempts", maxAttempts);
        meta.put("delayMs", delayMs);
        meta.put("elapsedMs", elapsedMs);
        meta.put("remainingMs", remainingMs);
        meta.put("error", error == null ? "" : error);
        meta.put("status", "retrying");
        return meta;
    }

    // ---- retry resolved / exhausted data ----

    /** retry resolved data。 */
    public static ObjectNode retryResolvedMeta(int attempts, long totalDelayMs) {
        ObjectNode meta = Json.obj();
        meta.put("attempts", attempts);
        meta.put("totalDelayMs", totalDelayMs);
        meta.put("status", "resolved");
        return meta;
    }

    /** retry exhausted data。 */
    public static ObjectNode retryExhaustedMeta(int attempts, int maxAttempts, String error) {
        ObjectNode meta = Json.obj();
        meta.put("attempts", attempts);
        meta.put("maxAttempts", maxAttempts);
        meta.put("error", error == null ? "" : error);
        meta.put("status", "exhausted");
        return meta;
    }

    // ---- 通用工具 ----

    /** 把毫秒格式化为简短的「Xs」/「XmY s」英文风格文案。 */
    public static String formatDurationShort(long ms) {
        long totalSeconds = Math.max(1, Math.round((double) ms / 1000));
        if (totalSeconds < 60) {
            return totalSeconds + "s";
        }
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return seconds == 0 ? (minutes + "m") : (minutes + "m" + seconds + "s");
    }

    /** 模型可读标签(厂商 · 模型;缺模型用 configId 兜底)。 */
    public static String modelLabel(ModelConfig s) {
        if (s == null) {
            return "(未知模型)";
        }
        String provider = s.provider() == null ? "" : s.provider();
        String model = s.model() == null ? "" : s.model();
        if (!model.isEmpty()) {
            return (provider.isEmpty() ? "" : provider + " · ") + model;
        }
        return s.configId() == null ? "" : s.configId();
    }

    // ---- ask questions ----

    /** 多问题选择题 → 前端 ask.create/ask.state 的 questions 数组(含 id/prompt/options)。 */
    public static tools.jackson.databind.node.ArrayNode questionsToJson(
            List<PendingAsks.AskQuestion> questions) {
        var arr = Json.arr();
        if (questions != null) {
            for (PendingAsks.AskQuestion q : questions) {
                ObjectNode o = Json.obj();
                o.put("id", q.id());
                o.put("prompt", q.prompt());
                o.set("options", Json.toJson(q.options()));
                arr.add(o);
            }
        }
        return arr;
    }

    // ---- tool calls ----

    /** message 事件的 toolCalls 数组构建。 */
    public static tools.jackson.databind.node.ArrayNode toolCallsToJson(
            List<ToolCallPart> toolCalls) {
        var arr = Json.arr();
        if (toolCalls != null) {
            for (ToolCallPart tc : toolCalls) {
                ObjectNode o = arr.addObject();
                o.put("id", tc.id());
                o.put("name", tc.name());
                o.put("arguments", tc.arguments() == null ? "" : tc.arguments());
            }
        }
        return arr;
    }
}
