package dev.everyagent.plugin.inputqueue;

import dev.everyagent.worker.plugin.registry.TaskLifecycleRegistry;
import dev.everyagent.worker.plugin.registry.AdvisorProviderRegistry;
import dev.everyagent.worker.task.TaskStore;
import dev.everyagent.worker.hub.EventSink;
import org.springframework.stereotype.Component;

/**
 * task-input-queue 插件装配入口。
 * 注册 QueueLoopNode（生命周期节点）、DialogInsertAdvisorProvider（advisor）。
 */
@Component
public class TaskInputQueueRegistrar {

    public TaskInputQueueRegistrar(
            TaskLifecycleRegistry lifecycleRegistry,
            AdvisorProviderRegistry advisorRegistry,
            TaskQueueRegistry queueRegistry,
            TaskStore store) {
        lifecycleRegistry.register(new QueueLoopNode(queueRegistry, store), "task-input-queue");
        advisorRegistry.register(new DialogInsertAdvisorProvider(queueRegistry));
    }
}
