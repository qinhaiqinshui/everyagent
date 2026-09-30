package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.proto.ShortIds;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.util.Map;

/**
 * RPC 阶段节点(order=30)：taskId 生成（新建路径）。
 * rerun（ctx.taskId() 非空）空转。
 * 生成与内存/磁盘均不冲突的 taskId（三重查重，同 TaskManager.uniqueTaskId 逻辑）。
 */
public final class TaskIdGenerateNode implements TaskLifecycleNode {

    private static final Logger log = LoggerFactory.getLogger(TaskIdGenerateNode.class);
    private static final int MAX_TASKID_ATTEMPTS = 10_000;

    private final Map<String, TaskEntry> tasks;
    private final Map<String, TaskStore.StoredTask> diskTasks;
    private final TaskStore store;

    public TaskIdGenerateNode(Map<String, TaskEntry> tasks,
            Map<String, TaskStore.StoredTask> diskTasks, TaskStore store) {
        this.tasks = tasks;
        this.diskTasks = diskTasks;
        this.store = store;
    }

    @Override
    public String id() { return "taskid.generate"; }

    @Override
    public float order() { return 30; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        var impl = (TaskLifecycleContextImpl) ctx;
        // rerun 路径：taskId 已有
        if (impl.taskId() != null && !impl.taskId().isEmpty()) {
            return next.proceed(ctx);
        }
        String workspaceId = impl.workspaceId();
        if (workspaceId == null || workspaceId.isEmpty()) {
            workspaceId = "default";
        }
        String taskId = uniqueTaskId(workspaceId);
        impl.taskId(taskId);
        return next.proceed(ctx);
    }

    private String uniqueTaskId(String workspaceId) {
        for (int i = 0; i < MAX_TASKID_ATTEMPTS; i++) {
            String id = ShortIds.taskId();
            if (tasks.containsKey(id) || diskTasks.containsKey(id)
                    || Files.isDirectory(store.dirOf(id, workspaceId))) {
                log.warn("taskId 与现有任务冲突,重生成: {}", id);
                continue;
            }
            return id;
        }
        throw new IllegalStateException("无法生成唯一 taskId(连续 " + MAX_TASKID_ATTEMPTS + " 次冲突)");
    }
}
