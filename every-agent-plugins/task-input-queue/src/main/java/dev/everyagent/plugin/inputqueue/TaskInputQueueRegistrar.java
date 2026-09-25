package dev.everyagent.plugin.inputqueue;

import dev.everyagent.worker.plugin.registry.TaskLifecycleRegistry;
import dev.everyagent.worker.plugin.registry.AdvisorProviderRegistry;
import dev.everyagent.worker.task.TaskManager;
import dev.everyagent.worker.task.TaskStore;
import org.springframework.stereotype.Component;

/**
 * task-input-queue 插件装配入口。
 * 注册 QueueDispatchNode（RPC 线程阶段 order=65）、QueueLoopNode（虚拟线程阶段 order=395）、
 * DialogInsertAdvisorProvider（advisor）。
 */
@Component
public class TaskInputQueueRegistrar {

    public TaskInputQueueRegistrar(
            TaskLifecycleRegistry lifecycleRegistry,
            AdvisorProviderRegistry advisorRegistry,
            TaskQueueRegistry queueRegistry,
            TaskStore store,
            TaskManager taskManager) {
        lifecycleRegistry.register(new QueueDispatchNode(queueRegistry, taskManager), "task-input-queue");
        lifecycleRegistry.register(new QueueLoopNode(queueRegistry, store), "task-input-queue");
        advisorRegistry.register(new DialogInsertAdvisorProvider(queueRegistry));
    }
}
