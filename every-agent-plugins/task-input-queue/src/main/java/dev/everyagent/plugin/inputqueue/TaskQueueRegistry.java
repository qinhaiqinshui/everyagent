package dev.everyagent.plugin.inputqueue;

import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 任务队列注册表：per-task 的输入队列 + 插入对话队列（队列项均为 ctx 引用）。
 * <p>QueueLoopNode 下行段注册（任务线程）。DialogInsertAdvisor 从插入队列 drain（agent 工具循环线程）。
 */
@Component
public class TaskQueueRegistry {

    private final ConcurrentHashMap<String, InputQueue> inputQueues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<TaskLifecycleContext>> dialogInsertQueues = new ConcurrentHashMap<>();

    public void register(String taskId, InputQueue queue) {
        inputQueues.put(taskId, queue);
    }

    public void unregister(String taskId) {
        inputQueues.remove(taskId);
        dialogInsertQueues.remove(taskId);
    }

    public InputQueue getInputQueue(String taskId) {
        return inputQueues.get(taskId);
    }

    public InputQueue getOrCreateInputQueue(String taskId) {
        return inputQueues.computeIfAbsent(taskId, k -> new InputQueue());
    }

    public ConcurrentLinkedQueue<TaskLifecycleContext> getOrCreateDialogInsertQueue(String taskId) {
        return dialogInsertQueues.computeIfAbsent(taskId, k -> new ConcurrentLinkedQueue<>());
    }

    public ConcurrentLinkedQueue<TaskLifecycleContext> getDialogInsertQueue(String taskId) {
        return dialogInsertQueues.get(taskId);
    }
}
