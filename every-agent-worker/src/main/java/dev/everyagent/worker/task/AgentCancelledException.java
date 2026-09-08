package dev.everyagent.worker.task;

/** 任务/agent 被取消:必须穿透工具执行回栈,直达 runTask(不能被转成模型可读的工具错误)。 */
public class AgentCancelledException extends RuntimeException {
    public AgentCancelledException(String message) {
        super(message);
    }
}
