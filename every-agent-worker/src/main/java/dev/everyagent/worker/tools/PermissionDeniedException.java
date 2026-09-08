package dev.everyagent.worker.tools;

/**
 * 用户拒绝(或超时未批)危险操作授权:由 {@link PermissionGate} 抛出,
 * 经 ToolExecutionExceptionProcessor 转为「[工具执行失败] ...」文本回灌模型
 * (agent 循环不中断,模型据此自行调整方案)。
 */
public class PermissionDeniedException extends RuntimeException {

    public PermissionDeniedException(String message) {
        super(message);
    }
}
