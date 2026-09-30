package dev.everyagent.plugin.api.spi;

/**
 * ID 生成 SPI：提供单调递增 long ID 与短 ID 生成能力。
 *
 * <p>实现方可基于 Snowflake / UUID / 数据库序列等策略；
 * 调用方仅依赖此接口，与具体算法解耦。
 */
public interface IdGenerator {

    /**
     * 生成下一个单调递增的 long ID（替代 {@code SnowflakeId.next()}）。
     *
     * @return 严格单调递增、进程内不重复的 long ID（恒为正数）
     */
    long next();

    /**
     * 生成短 ID（替代 {@code ShortIds.next(prefix)}）。
     *
     * @param prefix 短 ID 前缀（非空）
     * @return 形如 {@code prefix_xxx} 的短 ID
     */
    String shortId(String prefix);
}
