package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.worker.agent.AgentBuilder;
import dev.everyagent.worker.modules.ConfigStore;
import dev.everyagent.worker.modules.ConfigStore.ResolvedConfig;
import dev.everyagent.worker.config.ChatModelFactory;
import dev.everyagent.worker.task.ConversationLoader;
import dev.everyagent.plugin.api.util.RootCause;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskManager;
import dev.everyagent.worker.task.TaskStore;
import dev.everyagent.plugin.api.task.UserInput;
import dev.everyagent.worker.proto.TaskDtos.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RPC 阶段节点(order=80)：线程切换。
 * 设置 VT 阶段 context 回调（mainAgentBuilder / concurrencyReleaser / diskIndexer / registryRemover /
 * initialInput / priorConversation），提交虚拟线程执行 next.proceed(ctx)（后续 100+节点+内核+上行）。
 * <p>RPC 线程返回 null（ctx 已在 ResponseAckNode ok 应答）。
 */
public final class ThreadSubmitNode implements TaskLifecycleNode {

    private static final Logger log = LoggerFactory.getLogger(ThreadSubmitNode.class);

    private final ExecutorService vt;
    private final AgentBuilder agentBuilder;
    private final ConfigStore configs;
    private final ChatModelFactory modelFactory;
    private final Map<String, TaskEntry> tasks;
    private final Map<String, TaskStore.StoredTask> diskTasks;
    private final AtomicInteger active;
    private final TaskStore store;
    private final List<TaskManager.TaskResumeListener> resumeListeners;

    public ThreadSubmitNode(ExecutorService vt, AgentBuilder agentBuilder,
            ConfigStore configs, ChatModelFactory modelFactory,
            Map<String, TaskEntry> tasks, Map<String, TaskStore.StoredTask> diskTasks,
            AtomicInteger active, TaskStore store,
            List<TaskManager.TaskResumeListener> resumeListeners) {
        this.vt = vt;
        this.agentBuilder = agentBuilder;
        this.configs = configs;
        this.modelFactory = modelFactory;
        this.tasks = tasks;
        this.diskTasks = diskTasks;
        this.active = active;
        this.store = store;
        this.resumeListeners = resumeListeners;
    }

    @Override
    public String id() { return "thread.submit"; }

    @Override
    public float order() { return 80; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        var impl = (TaskLifecycleContextImpl) ctx;
        var t = impl.taskEntryImpl();
        if (t == null) {
            // 短路场景（幂等命中、认领失败、运行中入队），不应到此
            return null;
        }
        // 已在运行中的任务（非新建/非 rerun 新建）：不提交 VT，直接返回
        if (t.runFuture != null) {
            return null;
        }

        // ---- 设置 VT 阶段 context 回调 ----
        impl.initialInput(UserInput.of(impl.input(), impl.rawContent()));
        impl.mainAgentBuilder(prior -> {
            ResolvedConfig cfg = configs.resolve(t.snapshot.configId());
            ChatModelFactory.AgentModel am = modelFactory.buildAgentModel(cfg, t.mainAgentId, t.events, null);
            // S2 起:TaskEntry(即 ExecContext)替代 emitter + props 黑盒四件套,
            // 过渡 map 由 AgentBuilder.create 内部重建(S4 advisor 迁移后删除)
            return agentBuilder.create(t.mainAgentId, am.chatModel(), am.options(), t)
                    .title("主 agent")
                    .conversation(prior)
                    .build();
        });
        impl.concurrencyReleaser(() -> active.decrementAndGet());
        impl.diskIndexer(st -> diskTasks.put(st.taskId(), st));
        impl.registryRemover(() -> tasks.remove(t.taskId, t));

        // ---- rerun 路径：加载 priorConversation + 设置 rerun 上下文 ----
        JsonNode rerunMeta = impl.rerunMeta();
        if (rerunMeta != null) {
            // 从 rerunMeta（StoredTask.summary）恢复
            String mainAgentId = rerunMeta.path("mainAgentId").asString("");
            java.nio.file.Path dir = store.dirOf(t.taskId, t.workspaceId);
            List<org.springframework.ai.chat.messages.Message> prior =
                    ConversationLoader.load(store, dir, mainAgentId);
            impl.priorConversation(prior);
            // seq 水位（track 前读盘）
            long seqLast = store.seqLastOf(dir);
            impl.rerunSeqLast(seqLast);
            // overrideConfigId 从 RPC params 读（输入箱切换模型时透传；为空则沿用 meta configId）
            String overrideConfigId = null;
            if (impl.rpcContext() instanceof dev.everyagent.worker.rpc.RpcContext rc) {
                overrideConfigId = rc.optStrParam("configId", null);
            }
            impl.overrideConfigId(overrideConfigId);
        } else {
            // 新建路径：无历史会话
            impl.priorConversation(List.of());
        }

        // ---- rerun 路径：通知定向推送器换挂新日志 ----
        if (rerunMeta != null) {
            for (TaskManager.TaskResumeListener l : resumeListeners) {
                try {
                    l.onTaskResumed(t.taskId);
                } catch (RuntimeException e) {
                    log.debug("再运行通知失败 task={}", t.taskId, e);
                }
            }
        }

        // ---- 提交虚拟线程 ----
        final TaskEntry taskRef = t;
        Future<?> f = vt.submit(() -> {
            log.debug("[vt] 虚拟线程链启动 task={} thread={}", taskRef.taskId,
                    Thread.currentThread().getName());
            try {
                next.proceed(ctx);
            } catch (Throwable ex) {
                log.error("虚拟线程链执行失败 task={}: {}", taskRef.taskId, RootCause.summary(ex));
                log.debug("虚拟线程链执行失败 task={} 完整堆栈", taskRef.taskId, ex);
            }
        });
        t.runFuture = f;
        log.debug("[vt] 虚拟线程已提交 task={} (RPC 线程={})", taskRef.taskId,
                Thread.currentThread().getName());

        return null;
    }
}
