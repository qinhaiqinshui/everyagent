package dev.everyagent.plugin.api.task;

/**
 * 任务输入拦截器（SPI）——插件拦截运行中任务收到的用户输入。
 * <p>核心 {@code onTaskInput} 运行中分支默认拒绝（ERR_BUSY）；
 * 注册了拦截器的插件可接管（如队列插件 offer 到内部队列）。
 * <p>同样拦截 {@code task.dialogInsert}（插入到当前对话）——核心无此概念，由插件处理。
 */
public interface TaskInputInterceptor {

    /**
     * 处理运行中任务收到的 task.input。
     * @return true = 已处理（核心不再拒绝）；false = 不处理（核心回退拒绝）。
     */
    boolean onRunningTaskInput(String taskId, String text, String rawContent);

    /**
     * 处理 task.dialogInsert（队列项插入到当前对话）。
     * 核心无此概念，无插件时静默忽略。
     * @param taskId 任务 ID
     * @param index 队列项下标（-1 = 缺失）
     * @param text 队列项正文
     */
    void onDialogInsert(String taskId, int index, String text);
}
