package dev.everyagent.plugin.api.spi;

/**
 * Token 估算器 SPI —— 插件可实现此接口替换内置的 token 估算逻辑。
 *
 * <p>核心在多处需要 token 估算（输出预算耗尽护栏 {@code ModelLengthGuardAdvisor}、
 * 模型限流器 {@code ModelRateLimiter}、上下文压缩预检 {@code ContextCompressor}），
 * 统一经此接口获取估算值——<b>使用方完全不用管实现</b>。
 * 内置实现使用 CJK≈1 token / 其余≈4 字符 1 token 的粗估公式，
 * 并经真实 {@code completionTokens} 在线 EMA 校准（每 configId 独立系数），
 * 误差收敛后停止校准、漂移时自动恢复。
 *
 * <p>第三方插件可注册自定义实现替换内置（如接入精确 tokenizer）。
 */
public interface TokenEstimator {

    /**
     * 估算文本的 token 数。同文本恒得同值——分词与方向无关；系数取该 configId 已收敛的值
     * （无校准记录则 factor=1.0）。
     *
     * <p><b>单一口径</b>：输入侧不再有独立口径。估算函数本身是方向盲的，同一段文本无论将来
     * 当输入还是当输出，分词相同。输入规模由调用方（上下文压缩）用「上一轮实测 inputTokens −
     * 上轮发送视图粗估」的 offset 对账修正——这是一个加性、逐 agent、每轮重算的补偿项，
     * 由调用方掌握（只有它会改写发给模型的消息、也才能拿到上一轮实测输入），不依赖估算器内部
     * 的乘性系数。
     *
     * @param text     待估算文本
     * @param configId 模型配置 ID（校准系数按 configId 隔离）
     * @return 估算 token 数
     */
    long estimate(String text, String configId);
}
