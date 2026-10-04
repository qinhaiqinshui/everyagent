package dev.everyagent.contract.frame;

/**
 * stream 频道解析工具(纯协议级,无业务依赖)。
 *
 * <p>解析任务流频道,提取 ownerKey、workerId 与 taskId:
 * {@code u.<ownerKey>.worker.<workerId>.task.<taskId>.stream}(worker 段 = 归属物理隔离,架构 §5.2);
 * 兼容无 worker 段的旧形态 {@code u.<ownerKey>.task.<taskId>.stream}(此时 workerId 为 null)。
 * hub 作为零业务知识层通过此工具识别 stream 频道结构,而非自行硬编码解析。
 */
public final class StreamChannelParser {

    private StreamChannelParser() {
    }

    /**
     * stream 频道解析结果。
     *
     * @param ownerKey 命名空间键
     * @param workerId 归属 worker 的 workerId;旧形态(无 worker 段)为 {@code null}
     * @param taskId   任务 ID
     */
    public record StreamRef(String ownerKey, String workerId, String taskId) {
    }

    /**
     * 解析任务流频道;非 stream 频道(或格式非法)返回 {@code null}。
     *
     * <p>解析从右往左做(taskId 不含点,workerId 可含点),因此对带点的 workerId 也成立。
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
        if (!rest.endsWith(".stream")) {
            return null;
        }
        String body = rest.substring(0, rest.length() - ".stream".length());
        int taskIdDot = body.lastIndexOf('.');
        if (taskIdDot < 0) {
            return null;
        }
        String taskId = body.substring(taskIdDot + 1);
        if (taskId.isEmpty()) {
            return null;
        }
        String prefix = body.substring(0, taskIdDot);
        if (prefix.equals("task")) {
            return new StreamRef(ownerKey, null, taskId); // 旧形态:归属不可判定
        }
        if (prefix.startsWith("worker.") && prefix.endsWith(".task")) {
            String workerId = prefix.substring("worker.".length(), prefix.length() - ".task".length());
            if (workerId.isEmpty()) {
                return null;
            }
            return new StreamRef(ownerKey, workerId, taskId);
        }
        return null;
    }
}
