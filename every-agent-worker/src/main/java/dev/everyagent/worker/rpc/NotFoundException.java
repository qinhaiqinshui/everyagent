package dev.everyagent.worker.rpc;

/** 目标不存在(任务/配置等)→ rpc.err NOT_FOUND。 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
