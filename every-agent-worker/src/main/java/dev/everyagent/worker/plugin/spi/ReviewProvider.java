package dev.everyagent.worker.plugin.spi;

/**
 * 授权审议策略 SPI —— 插件实现此接口提供不同审议方案。
 *
 * <p>现有 AI 审议（AiAuthReviewer）变为默认插件（review-ai）。
 * 可新增 review-human-only（纯人工审批）、
 * review-timeout-auto（超时自动允许/拒绝）。
 *
 * <p>改造前：AiAuthReviewer 硬编码为唯一审议策略。
 * 改造后：授权流程从 {@link dev.everyagent.worker.plugin.registry.ReviewProviderRegistry} 选择审议策略。
 *
 * <p>注意：此 SPI 在阶段一仅定义接口，实际改造在阶段三。
 */
public interface ReviewProvider {

    /** 策略 id。 */
    String id();

    /**
     * 审议一次授权请求。
     *
     * @param req 审议请求
     * @return 审议结果
     */
    ReviewResult review(ReviewRequest req);

    /**
     * 是否适用于当前任务（任务级开关）。
     */
    boolean appliesTo(ReviewContext ctx);

    /** 审议请求。 */
    record ReviewRequest(String taskId, String agentId, String operation,
            String path, String command, String reason) {}

    /** 审议结果。 */
    record ReviewResult(Decision decision, String reason, Double confidence) {
        public enum Decision { ALLOW, DENY, NEEDS_HUMAN }
    }

    /** 审议上下文。 */
    record ReviewContext(String taskId, boolean unattended, boolean aiReviewEnabled) {}
}
