package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.worker.task.RoundIndexStore;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskStore;
import dev.everyagent.worker.tools.PermissionGate;
import org.springframework.stereotype.Component;

/**
 * 任务生命周期上下文工厂：聚合任务级协作者（gate/roundIndexStore/store），
 * 供 TaskManager 组装 {@link TaskLifecycleContextImpl}。
 * gate 随任务上下文走（consumeInput 已迁入上下文），TaskManager 不再持有授权门。
 */
@Component
public class TaskLifecycleContextFactory {

    private final PermissionGate gate;
    private final RoundIndexStore roundIndexStore;
    private final TaskStore store;

    public TaskLifecycleContextFactory(PermissionGate gate, RoundIndexStore roundIndexStore, TaskStore store) {
        this.gate = gate;
        this.roundIndexStore = roundIndexStore;
        this.store = store;
    }

    public TaskLifecycleContextImpl create(TaskEntry taskEntry) {
        return new TaskLifecycleContextImpl(taskEntry, gate, roundIndexStore, store);
    }
}
