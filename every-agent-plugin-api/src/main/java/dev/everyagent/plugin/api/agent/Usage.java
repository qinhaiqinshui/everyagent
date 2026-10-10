package dev.everyagent.plugin.api.agent;

/**
 * 模型调用的 token 用量快照(plugin-api 通用类型)。
 *
 * <p>与 worker 的 {@code TaskDtos.Usage} 结构一致,供 {@link AgentContext#usage()} 返回。
 * 不可变记录,通过 {@link #plus(Usage)} 累加。
 */
public record Usage(long inputTokens, long outputTokens, long totalTokens) {

    public static Usage zero() {
        return new Usage(0, 0, 0);
    }

    public Usage plus(Usage other) {
        return new Usage(inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                totalTokens + other.totalTokens);
    }
}
