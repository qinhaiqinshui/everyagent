package dev.everyagent.contract.frame;

/**
 * stream 频道解析工具(纯协议级,无业务依赖)。
 *
 * <p>解析 {@code u.<ownerKey>.task.<taskId>.stream} 频道,提取 ownerKey 与 taskId。
 * hub 作为零业务知识层通过此工具识别 stream 频道结构,而非自行硬编码解析。
 */
public final class StreamChannelParser {

    private StreamChannelParser() {
    }

    /** stream 频道解析结果:ownerKey 与 taskId。 */
    public record StreamRef(String ownerKey, String taskId) {
    }

    /**
     * 解析 stream 频道 {@code u.<ownerKey>.task.<taskId>.stream};
     * 非 stream 频道(或格式非法)返回 {@code null}。
     *
     * @param channel 频道名,可为 null
     * @return 解析结果,或 {@code null} 表示不匹配
     */
    public static StreamRef parse(String channel) {
        if (channel == null || !channel.startsWith("u.")) {
            return null;
        }
        int ownerEnd = channel.indexOf('.', 2);
        if (ownerEnd < 0) {
            return null;
        }
        String ownerKey = channel.substring(2, ownerEnd);
        if (ownerKey.isEmpty()) {
            return null;
        }
        String rest = channel.substring(ownerEnd + 1);
        if (!rest.startsWith("task.") || !rest.endsWith(".stream")) {
            return null;
        }
        String taskId = rest.substring("task.".length(), rest.length() - ".stream".length());
        if (taskId.isEmpty()) {
            return null;
        }
        return new StreamRef(ownerKey, taskId);
    }
}
