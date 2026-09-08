package dev.everyagent.worker.task;

/** 死循环检测收口(连续重复相同工具调用)→ 任务 FAILED,用户可再发消息冷启动续跑。 */
public class LoopRepeatException extends RuntimeException {
    public LoopRepeatException(String message) {
        super(message);
    }
}
