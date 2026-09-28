package dev.everyagent.plugin.adaptivemaxtokens;

/**
 * 自适应输出预算耗尽异常:maxTokens 已放大至 ceiling 仍未获得有效结果。
 *
 * <p>由 {@link AdaptiveMaxTokensAdvisor} 在升级次数超限或预算已达 ceiling 且仍收到
 * finish_reason=length 帧时抛出,交任务层收口为 error。
 *
 * <p>错误信息含已放大至的 ceiling 值与建议操作(精简输入/降低 reasoningEffort/任务拆分)。
 */
public class AdaptiveBudgetExhaustedException extends RuntimeException {

    public AdaptiveBudgetExhaustedException(String message) {
        super(message);
    }
}
