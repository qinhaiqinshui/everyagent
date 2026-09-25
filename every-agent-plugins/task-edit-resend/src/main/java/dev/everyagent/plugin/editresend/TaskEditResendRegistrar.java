package dev.everyagent.plugin.editresend;

import dev.everyagent.worker.plugin.registry.TaskLifecycleRegistry;
import org.springframework.stereotype.Component;

/**
 * task-edit-resend 插件装配入口。
 * 注册 EditResendNode（虚拟线程阶段 order=395.5）。
 */
@Component
public class TaskEditResendRegistrar {

    public TaskEditResendRegistrar(
            TaskLifecycleRegistry lifecycleRegistry,
            EditTruncateProcessor truncateProcessor) {
        lifecycleRegistry.register(new EditResendNode(truncateProcessor), "task-edit-resend");
    }
}
