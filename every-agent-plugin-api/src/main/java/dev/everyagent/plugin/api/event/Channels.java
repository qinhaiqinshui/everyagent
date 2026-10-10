package dev.everyagent.plugin.api.event;

/**
 * 任务端业务频道名构造(架构 §3.2 的任务域部分)。
 * K 即 ownerKey = sha256(apiKey);hub 不感知这些名字,只校验命名空间。
 */
public final class Channels {

    private Channels() {
    }

    public static String workers(String k) {
        return "u." + k + ".workers";
    }

    public static String workerCmd(String k, String workerId) {
        return "u." + k + ".worker." + workerId + ".cmd";
    }

    public static String workerEvt(String k, String workerId) {
        return "u." + k + ".worker." + workerId + ".evt";
    }

    /** worker 级输入频道:task.input / ask.reply 都走这里,taskId 入 payload(任务永久后不再按任务订阅)。 */
    public static String workerInput(String k, String workerId) {
        return "u." + k + ".worker." + workerId + ".input";
    }

    /**
     * 任务生命周期频道(worker 段必填)。
     *
     * <p>带 worker 段是<b>归属的物理隔离</b>:同一 apiKey(同 ownerKey K)下可有多台 worker,
     * 任务事件是「某一台 worker 的数据」而不是「命名空间的数据」。若共用 {@code u.<K>.tasks},
     * 一台 worker 的任务会混进另一台的前端列表(点开必报「任务不存在」,因为 RPC 打错了机器)。
     * 归属由频道名携带,禁止客户端按 ownerKey 前缀猜测(架构 §4.3/§8.2/§14.12)。
     */
    public static String tasks(String k, String workerId) {
        return "u." + k + ".worker." + workerId + ".tasks";
    }

    /**
     * 任务流频道(worker 段必填),形如 {@code u.<K>.worker.<id>.task.<taskId>.stream}。
     *
     * <p>同 tasks 频道的理由:定向推送器(DataPusher)必须建在「拥有该任务的那台 worker」上;
     * 无 worker 段时 hub 的 subscriber.join 会投给该 K 全部在线 worker,非寻址那台建的推送器
     * 收不到前端 ack(ack 只发到寻址那台的 input 频道),credit 窗口永不释放而永久阻塞(§7.13)。
     */
    public static String taskStream(String k, String workerId, String taskId) {
        return "u." + k + ".worker." + workerId + ".task." + taskId + ".stream";
    }

    /** @deprecated 无 worker 段 = 归属不可判定,只会把一台 worker 的任务混进另一台的前端。
     *  保留仅为已编译的外部插件二进制兼容(架构 §5.6);新代码一律用 {@link #tasks(String, String)}。 */
    @Deprecated
    public static String tasks(String k) {
        return "u." + k + ".tasks";
    }

    /** @deprecated 无 worker 段 = hub 的订阅通知无法定向。见 {@link #taskStream(String, String, String)}。 */
    @Deprecated
    public static String taskStream(String k, String taskId) {
        return "u." + k + ".task." + taskId + ".stream";
    }

    public static String termStream(String k, String termId) {
        return "u." + k + ".term." + termId + ".stream";
    }
}
