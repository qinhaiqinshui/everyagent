package dev.everyagent.plugin.api.agent;

/**
 * 事件出口端口（TaskEvents 实现/适配此接口）。
 * <p>agent 层通过此端口发射事件，不直接引用 TaskEvents。
 * 方法签名与 TaskEvents 的 public 方法一致。
 *
 * <p>proto 专属类型（{@code Usage}、{@code ModelSnapshot}、{@code List<ToolCallPart>}、
 * {@code List<PendingAsks.AskQuestion>}）在此接口中以 {@link Object} 呈现，
 * 因为 plugin-api 不依赖 worker proto；worker 侧实现时强转。
 */
public interface AgentEventChannel {

    // ---- 用户消息与轮次 ----

    long userMessage(String text);

    long userMessage(String text, String rawContent);

    long roundOpened(long startSeq, String user);

    long roundClosed(long startSeq, long endSeq, String finalReply);

    // ---- 流式增量 ----

    long delta(String agentId, String text);

    long thinking(String agentId, String text);

    // ---- 定型消息 ----

    long message(String agentId, String thinking, String text, Object toolCalls);

    // ---- usage ----

    long usage(String agentId, String model, Long contextWindowTokens, Object round, Object total);

    // ---- 工具返回 ----

    long toolResult(String callId, String name, String summary, boolean truncated, String agentId);

    // ---- ask 生命周期 ----

    long askCreate(String askId, String kind, Object questions, Long timeoutMs, String agentId);

    long askState(String askId, String kind, String status, Object questions, String agentId);

    long askResolved(String askId, String by, String status, String agentId);

    // ---- 子 agent 生命周期 ----

    long agentStarted(String agentId, String title, String input);

    long agentDone(String agentId, String result, Object usage);

    long agentStatus(String agentId, String status);

    // ---- 错误与取消 ----

    long error(String agentId, String message);

    long cancelled(String by);

    // ---- 重试生命周期 ----

    long retryAttempt(String agentId, int attempt, int maxAttempts, long delayMs, String error);

    long retryProgress(String agentId, int attempt, int maxAttempts, long delayMs,
            long elapsedMs, long remainingMs, String error);

    long retryResolved(String agentId, int attempts, long totalDelayMs);

    long retryExhausted(String agentId, int attempts, int maxAttempts, String error);

    // ---- 上下文压缩生命周期 ----

    long contextCompressStarted(String agentId, String summary);

    long contextCompressDone(String agentId, String summary);

    // ---- 模型切换 / 容灾切换 / 限流排队 ----

    long modelSwitch(Object snapshot, String oldConfigId);

    String modelFailoverSwitch(String traceId, Object snapshot);

    void modelFailoverClose(String traceId);

    String modelRateWait(String traceId, String configId, int waiters, int inFlight,
            long tpmPressure, long waitMs);

    // ---- AI 安全审议 ----

    long authReview(String agentId, String decision, String confidence, String reason,
            String scope, String grantKey, String prompt, String taskId);
}
