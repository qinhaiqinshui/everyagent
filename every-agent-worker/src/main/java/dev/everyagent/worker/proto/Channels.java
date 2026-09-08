package dev.everyagent.worker.proto;

/**
 * 任务端业务频道名构造(架构 §3.2 的任务域部分,归 worker 所有)。
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

    public static String tasks(String k) {
        return "u." + k + ".tasks";
    }

    public static String taskStream(String k, String taskId) {
        return "u." + k + ".task." + taskId + ".stream";
    }
}
