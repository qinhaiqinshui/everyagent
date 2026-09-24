package dev.everyagent.plugin.taskqueue;

import dev.everyagent.worker.plugin.registry.TaskAdmissionPolicyRegistry;
import dev.everyagent.worker.plugin.registry.TaskLifecycleRegistry;
import org.springframework.stereotype.Component;

/**
 * task-queue 插件注册入口。
 * <p>构造器注入 worker 注册表后立即注册 QueueAdmissionNode 和 TaskQueueAdmissionPolicy。
 * 与 git 插件 GitPluginRegistrar 同模式。
 */
@Component
public class TaskQueueRegistrar {

    public TaskQueueRegistrar(
            TaskLifecycleRegistry lifecycleRegistry,
            TaskAdmissionPolicyRegistry admissionPolicyRegistry,
            TaskQueue taskQueue) {
        lifecycleRegistry.register(new QueueAdmissionNode(taskQueue), "task-queue");
        admissionPolicyRegistry.register(new TaskQueueAdmissionPolicy());
    }
}
