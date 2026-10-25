package dev.everyagent.plugin.taskwait;

/**
 * 等待失败异常（非受检）。
 *
 * <p>目标任务非 done 终态（failed / cancelled / missing）或非法等待
 * （自等 / 循环等待）时由 {@link WaitTaskAdvisor} 抛出，任务按失败收口。
 *
 * <p>位于 {@code ToolCallingAdvisor.DEFAULT_ORDER + 40}、在
 * TransientErrorRetry(DEF+200) 等<br>重试 advisor 外侧，异常不经过任何重试
 * advisor——天然非重试（等待失败不是瞬态错误，重试无意义）。
 */
public class WaitTaskFailedException extends RuntimeException {

    public WaitTaskFailedException(String message) {
        super(message);
    }
}
