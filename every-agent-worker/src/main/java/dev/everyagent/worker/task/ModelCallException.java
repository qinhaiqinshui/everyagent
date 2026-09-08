package dev.everyagent.worker.task;

/** 模型调用失败(网络/鉴权/限流等)→ 任务失败。 */
public class ModelCallException extends RuntimeException {
    public ModelCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
