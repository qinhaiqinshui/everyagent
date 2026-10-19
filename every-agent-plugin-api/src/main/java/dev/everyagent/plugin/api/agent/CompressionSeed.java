package dev.everyagent.plugin.api.agent;

/**
 * 上下文压缩的冷启动校准种子:上一运行最近一次「全量未压缩发送视图」的
 * (provider 实测 inputTokens, 同视图 token 估算, 模型名)三元组。
 *
 * <p>压缩 advisor 的运行内加性 offset 校准(lastSent/lastRound)是内存态、随运行销毁;
 * 再运行冷启动时首个请求无校准数据,退回固定 reserve 会被粗估高估误触发压缩
 * (2026-10 t_ngj2)。本种子随 {@code usage} 事件落盘({@code data.fullViewEstTokens})、
 * 随 {@code meta.usageByAgent} 回灌,经 {@code ExecContext.compressionSeed(agentId)}
 * 暴露;首个请求按比率折算 {@code used = 估算 × clamp(实测/估算)},本运行首个实测
 * 回来后自动回到每轮加性重算(§7.5)。
 *
 * @param measuredInputTokens  上一轮 provider 实测输入 token
 * @param estimatedSentTokens  同一发送视图的 token 估算(与压缩预检同口径)
 * @param model                该轮所用模型名(空 = 未知;换模型种子作废,由消费方判定)
 */
public record CompressionSeed(long measuredInputTokens, long estimatedSentTokens, String model) {

    /** 数值是否可用(实测与估算均为正;模型匹配由消费方另行判定)。 */
    public boolean usable() {
        return measuredInputTokens > 0 && estimatedSentTokens > 0;
    }
}
