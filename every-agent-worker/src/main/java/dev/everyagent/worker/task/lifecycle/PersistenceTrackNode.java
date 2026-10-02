package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.TaskOutcome;
import dev.everyagent.worker.ship.StreamSourceRegistry;
import dev.everyagent.worker.task.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 下行节点(order=100)：store.track——建目录 + 首写 meta.json + 挂 EventLog 监听。
 * 上行无动作（untrack 是独立节点 order=500）。
 */
public final class PersistenceTrackNode implements TaskLifecycleNode {

    private static final Logger log = LoggerFactory.getLogger(PersistenceTrackNode.class);

    private final TaskStore store;
    private final StreamSourceRegistry streamSources;

    public PersistenceTrackNode(TaskStore store, StreamSourceRegistry streamSources) {
        this.store = store;
        this.streamSources = streamSources;
    }

    @Override
    public String id() { return "persistence.track"; }

    @Override
    public float order() { return 100; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        var impl = (TaskLifecycleContextImpl) ctx;
        var t = impl.taskEntryImpl();
        log.debug("[track] persistence.track 进入 task={} workspace={} thread={}",
                t.taskId, t.workspaceId, Thread.currentThread().getName());
        try {
            store.track(t.taskId, t.workspaceId, t.log, t::summaryJson);
        } catch (IOException e) {
            log.error("任务落盘启动失败 task={}(继续内存运行,重启后丢失)", t.taskId, e);
        }
        // 挂接流源：StreamSourceRegistry.attach（推送器经此取日志，反转后正向依赖）
        try {
            streamSources.attach(t.taskId, t.log, t.mainAgentId);
            log.debug("[track] 流源挂接完成 task={}", t.taskId);
        } catch (RuntimeException e) {
            log.warn("流源挂接失败 task={}", t.taskId, e);
        }
        return next.proceed(ctx);
    }
}
