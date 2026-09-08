package dev.everyagent.worker.task;

/**
 * 上下文压缩用的「历史/工具过程 → 要点摘要」协作对象(红线:一个 advisor 只负责一个功能,
 * 摘要的模型调用逻辑不内嵌在 {@link ContextCompressionAdvisor} / {@link ContextCompressor} 中)。
 *
 * <p>由 {@link ContextCompressor} 在阶段 C(删除最旧历史轮)前调用,把将被丢弃的整段历史
 * 压成一段短摘要,以「不进上下文即丢弃」为兜底、以「保留关键信息」为目标。
 *
 * <p>实现约定(单函数式接口,便于单测用 lambda/fake 注入):
 * <ul>
 *   <li>入参 {@code text} 为待压缩的原始文本(可能较长);{@code maxTokens} 为摘要上限(估算口径)。</li>
 *   <li>返回短摘要文本;若摘要失败/超时/模型异常/无需摘要,必须返回 <b>空串</b>,
 *       由调用方降级为确定性保留(如保留 user 原句简版),绝不抛出异常、绝不阻塞主流程。</li>
 *   <li>实现必须同步、幂等、线程安全(每次调用独立,不共享可变状态)。</li>
 * </ul>
 */
@FunctionalInterface
public interface ContextSummarizer {

    /**
     * 把一段历史文本压缩成要点摘要。
     *
     * @param text      待压缩文本
     * @param maxTokens 摘要 token 上限(估算口径,调用方约束输出规模)
     * @return 短摘要;失败/不适用返回空串(调用方降级)
     */
    String summarize(String text, int maxTokens);
}
