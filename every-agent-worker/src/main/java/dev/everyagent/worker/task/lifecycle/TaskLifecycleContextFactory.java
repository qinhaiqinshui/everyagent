package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.worker.task.RoundIndexStore;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskStore;
import dev.everyagent.worker.tools.PermissionGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 任务生命周期上下文工厂：聚合任务级协作者（gate/roundIndexStore/store），
 * 供 TaskManager 组装 {@link TaskLifecycleContextImpl}。
 * gate 随任务上下文走（consumeInput 已迁入上下文），TaskManager 不再持有授权门。
 */
@Component
public class TaskLifecycleContextFactory {

    private static final Logger log = LoggerFactory.getLogger(TaskLifecycleContextFactory.class);

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

    /**
     * RPC 阶段创建上下文（无 TaskEntry）：input/rawContent/metadata/rpcContext 由节点逐字段填充，
     * taskId/workspaceRoot 等由 RPC 阶段节点（idempotency.check/workspace.resolve/taskid.generate 等）设置。
     */
    public TaskLifecycleContextImpl createForRpc(String input, String rawContent,
            java.util.Map<String, Object> metadata, Object rpcContext) {
        TaskLifecycleContextImpl ctx = new TaskLifecycleContextImpl(null, gate, roundIndexStore, store);
        ctx.input(input);
        ctx.rawContent(rawContent);
        ctx.runParams(metadata);
        ctx.rpcContext(rpcContext);
        // [uref] @文件引用胶囊丢失排查:RPC → 生命周期上下文字段搬运(此处丢失 → 后续全链路无 rawContent)。
        if (log.isDebugEnabled()) {
            log.debug("[uref] createForRpc inputLen={} rawPresent={} rawLen={} rawHasToken={}",
                    input == null ? -1 : input.length(),
                    rawContent != null, rawContent == null ? -1 : rawContent.length(),
                    rawContent != null && rawContent.contains("[[[["));
        }
        return ctx;
    }
}
