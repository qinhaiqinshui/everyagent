package dev.everyagent.plugin.api.spi;

/**
 * Token 估算器 SPI —— 插件可实现此接口替换内置的 token 估算与校准逻辑。
 *
 * <p>核心在多处需要 token 估算（输出预算耗尽护栏 {@code ModelLengthGuardAdvisor}、
 * 模型限流器 {@code ModelRateLimiter}），统一经此接口获取估算值。
 * 内置实现使用 CJK≈1 token / 其余≈4 字符 1 token 的粗估公式，
 * 并经真实 {@code completionTokens} 在线 EMA 校准（每 configId 独立系数），
 * 误差收敛后停止校准、漂移时自动恢复。
 *
 * <p>第三方插件可注册自定义实现替换内置（如接入精确 tokenizer）。
 */
public interface TokenEstimator {

    /**
     * 估算文本的 token 数（使用 configId 对应的校准系数；无校准记录则 factor=1.0）。
     *
     * <p><b>输出口径</b>：校准系数由真实 {@code completionTokens} 收敛而来，适用于预测
     * 模型输出规模（输出预算护栏、限流 inflight 估算）。需要估算 <b>prompt（输入）</b>规模时
     * 请用 {@link #estimateInput(String)}——两者分词口径相同，差别只在是否套用校准系数。
     *
     * @param text     待估算文本
     * @param configId 模型配置 ID（校准系数按 configId 隔离）
     * @return 估算 token 数
     */
    long estimate(String text, String configId);

    /**
     * 估算文本的 token 数（<b>输入/prompt 口径</b>）——与 {@link #estimate(String, String)}
     * 同一分词口径，但<b>不套用输出 token 校准系数</b>。
     *
     * <p>为什么必须区分：校准系数是让「估算输出」逼近 {@code completionTokens} 的乘数，属
     * 输出口径（且输出计费含工具调用参数等，与 prompt 的文本构成不同）。把它套到 prompt 估算上
     * 会把输入规模放大数倍——实测某任务全量历史：基础式 28.9 万、真实输入 33.9 万，而套上当时
     * 的系数（2.34）后变 67.4 万（≈2 倍高估），会让上下文压缩在真实占用远未达阈值时就触发。
     * 故输入侧消费方（上下文压缩的预检）一律走本方法。
     *
     * <p>缺省实现回退 {@link #estimate(String, String)} 传入空 configId：对按 configId 隔离
     * 校准状态的实现即得到未校准值；自定义实现若本就不区分两口径，行为一致。
     *
     * @param text 待估算文本
     * @return 估算 token 数（未校准）
     */
    default long estimateInput(String text) {
        return estimate(text, "");
    }

    /**
     * 用真实 usage 校准估算系数（EMA）。
     *
     * <p>误差持续低于收敛阈值（默认 2%）后停止校准；
     * converged 后误差超过漂移阈值（默认 5%）时重置继续校准。
     *
     * @param configId        模型配置 ID
     * @param estimatedTokens 本次模型调用的估算输出 token 总量（调用方逐 chunk 累计）
     * @param actualTokens    模型返回的真实 completionTokens
     */
    void calibrate(String configId, long estimatedTokens, long actualTokens);

    /**
     * 当前校准系数（诊断/展示用；无记录返回 1.0）。
     *
     * @param configId 模型配置 ID
     * @return 校准系数
     */
    double factorOf(String configId);

    /**
     * 校准样本数（诊断用）。
     *
     * @param configId 模型配置 ID
     * @return 样本数（无记录返回 0）
     */
    long sampleCountOf(String configId);
}
