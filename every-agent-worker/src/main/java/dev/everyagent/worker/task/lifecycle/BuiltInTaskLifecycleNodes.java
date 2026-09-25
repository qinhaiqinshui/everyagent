package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.modules.WorkspaceActivityTracker;
import dev.everyagent.worker.plugin.registry.TaskLifecycleRegistry;
import dev.everyagent.worker.ship.StreamSourceRegistry;
import dev.everyagent.worker.slash.SlashTaskCallbacks;
import dev.everyagent.worker.task.PendingAsks;
import dev.everyagent.worker.task.SubAgentManager;
import dev.everyagent.worker.task.TaskStore;
import dev.everyagent.worker.tools.PermissionGate;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 内置任务生命周期节点装配：Spring 启动时实例化全部节点并注册到 TaskLifecycleRegistry。
 * 节点顺序由各自的 order() 决定，注册顺序仅影响同 order 的稳定排序。
 * <p>收敛后基线（§11）：
 * 下行: rerun.restore(50) → slash.notify(90) → persistence.track(100)
 *   → task.wires(200) → model.switch.trace(310) → main.agent(390)
 *   → status.down(840)
 * 内核: 轮次循环
 * 上行: spawned.await(950) → cascade.stop(900)
 *   → [临界段: status(840) → concurrency.release(800) → log.flush(750)
 *      → queue(700) → status.persist(650) → disk.index(550)
 *      → persistence.untrack(500) → gate.evict(450) → registry.remove(420)]
 *   → workspace.activity(350)
 */
@Component
public class BuiltInTaskLifecycleNodes {

    private final TaskLifecycleRegistry registry;
    private final TaskStore store;
    private final HubPool pool;
    private final StreamSourceRegistry streamSources;
    private final PermissionGate gate;
    private final SubAgentManager subs;
    private final PendingAsks asks;
    private final WorkspaceActivityTracker activityTracker;
    private final SlashTaskCallbacks slashCallbacks;

    public BuiltInTaskLifecycleNodes(
            TaskLifecycleRegistry registry,
            TaskStore store,
            HubPool pool,
            StreamSourceRegistry streamSources,
            PermissionGate gate,
            SubAgentManager subs,
            PendingAsks asks,
            WorkspaceActivityTracker activityTracker,
            SlashTaskCallbacks slashCallbacks) {
        this.registry = registry;
        this.store = store;
        this.pool = pool;
        this.streamSources = streamSources;
        this.gate = gate;
        this.subs = subs;
        this.asks = asks;
        this.activityTracker = activityTracker;
        this.slashCallbacks = slashCallbacks;
    }

    @PostConstruct
    void registerAll() {
        // 下行节点（order 升序）
        registry.register(new RerunRestoreNode(), "worker");
        registry.register(new SlashNotifyNode(slashCallbacks), "worker");
        registry.register(new PersistenceTrackNode(store, streamSources), "worker");
        registry.register(new TaskWiresNode(pool), "worker");
        registry.register(new ModelSwitchTraceNode(), "worker");
        registry.register(new MainAgentNode(), "worker");
        // 成对节点（下行在段边界外、上行在临界段内）
        registry.register(new StatusNode(pool), "worker");       // order=840
        // 上行节点（段外，按 order 从高到低注册，仅影响同 order 的稳定排序兜底）
        registry.register(new SpawnedAwaitNode(subs), "worker");
        registry.register(new CascadeStopNode(subs, asks), "worker");
        // 临界段内纯上行节点（UpstreamNode，order ∈ [420,850]）
        registry.register(new ConcurrencyReleaseNode(), "worker");
        registry.register(new LogFlushNode(store), "worker");
        registry.register(new StatusPersistNode(store), "worker");
        registry.register(new DiskIndexNode(store), "worker");
        registry.register(new PersistenceUntrackNode(store, streamSources), "worker");
        registry.register(new GateEvictNode(gate), "worker");
        registry.register(new RegistryRemoveNode(), "worker");
        // 段外尾部
        registry.register(new WorkspaceActivityNode(activityTracker), "worker");
    }
}
