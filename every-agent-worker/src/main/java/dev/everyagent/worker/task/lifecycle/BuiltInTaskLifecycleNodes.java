package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.modules.WorkspaceActivityTracker;
import dev.everyagent.worker.plugin.registry.TaskLifecycleRegistry;
import dev.everyagent.worker.task.PendingAsks;
import dev.everyagent.worker.task.SubAgentManager;
import dev.everyagent.worker.task.TaskStore;
import dev.everyagent.worker.tools.PermissionGate;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 内置任务生命周期节点装配：Spring 启动时实例化 17 个节点并注册到 TaskLifecycleRegistry。
 * 节点顺序由各自的 order() 决定，注册顺序仅影响同 order 的稳定排序。
 */
@Component
public class BuiltInTaskLifecycleNodes {

    private final TaskLifecycleRegistry registry;
    private final TaskStore store;
    private final HubPool pool;
    private final PermissionGate gate;
    private final SubAgentManager subs;
    private final PendingAsks asks;
    private final WorkspaceActivityTracker activityTracker;

    public BuiltInTaskLifecycleNodes(
            TaskLifecycleRegistry registry,
            TaskStore store,
            HubPool pool,
            PermissionGate gate,
            SubAgentManager subs,
            PendingAsks asks,
            WorkspaceActivityTracker activityTracker) {
        this.registry = registry;
        this.store = store;
        this.pool = pool;
        this.gate = gate;
        this.subs = subs;
        this.asks = asks;
        this.activityTracker = activityTracker;
    }

    @PostConstruct
    void registerAll() {
        // 下行节点
        registry.register(new PersistenceTrackNode(store), "worker");
        registry.register(new TaskWiresNode(store, pool), "worker");
        registry.register(new StatusStartNode(pool), "worker");
        registry.register(new MainAgentNode(), "worker");
        // 上行节点（按 order 从高到低注册，仅影响同 order 的稳定排序兜底）
        registry.register(new SpawnedAwaitNode(subs), "worker");
        registry.register(new CascadeStopNode(subs, asks), "worker");
        registry.register(new StatusFinalizeNode(pool), "worker");
        registry.register(new ConcurrencyReleaseNode(), "worker");
        registry.register(new LogFlushNode(store), "worker");
        registry.register(new QueuePersistNode(store), "worker");
        registry.register(new StatusPersistNode(store), "worker");
        registry.register(new LedgerPersistNode(store), "worker");
        registry.register(new DiskIndexNode(store), "worker");
        registry.register(new PersistenceUntrackNode(store), "worker");
        registry.register(new GateEvictNode(gate), "worker");
        registry.register(new RegistryRemoveNode(), "worker");
        registry.register(new WorkspaceActivityNode(activityTracker), "worker");
    }
}
