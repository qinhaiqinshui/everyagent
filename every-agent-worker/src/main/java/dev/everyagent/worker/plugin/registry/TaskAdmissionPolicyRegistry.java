package dev.everyagent.worker.plugin.registry;

import dev.everyagent.plugin.api.task.TaskAdmissionPolicy;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 任务准入策略注册表。
 * <p>至多一个策略（队列插件注册则接管准入逻辑）。
 * 使用 AtomicReference 保证线程安全。
 */
@Component
public class TaskAdmissionPolicyRegistry {

    private final AtomicReference<TaskAdmissionPolicy> policy = new AtomicReference<>();

    public void register(TaskAdmissionPolicy p) {
        policy.set(p);
    }

    public TaskAdmissionPolicy get() {
        return policy.get();
    }

    public boolean isRegistered() {
        return policy.get() != null;
    }
}
