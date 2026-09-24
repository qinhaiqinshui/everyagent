package dev.everyagent.plugin.api.task;

/**
 * 任务结局（值对象；异常只在内核翻译一次，链上只传值）。
 * @param status 终态
 * @param error 错误摘要（FAILED 时有值，DONE/CANCELLED 时为 null）
 * @param startedAt 任务开始时间戳
 * @param endedAt 任务结束时间戳
 */
public record TaskOutcome(TaskEndStatus status, String error, long startedAt, long endedAt) {
    public enum TaskEndStatus { DONE, FAILED, CANCELLED }

    public static TaskOutcome done(long startedAt, long endedAt) {
        return new TaskOutcome(TaskEndStatus.DONE, null, startedAt, endedAt);
    }

    public static TaskOutcome failed(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isEmpty()) {
            msg = t.getClass().getSimpleName();
        }
        return new TaskOutcome(TaskEndStatus.FAILED, msg, 0, System.currentTimeMillis());
    }

    public static TaskOutcome failed(String error) {
        return new TaskOutcome(TaskEndStatus.FAILED, error, 0, System.currentTimeMillis());
    }

    public static TaskOutcome cancelled() {
        return new TaskOutcome(TaskEndStatus.CANCELLED, null, 0, System.currentTimeMillis());
    }
}
