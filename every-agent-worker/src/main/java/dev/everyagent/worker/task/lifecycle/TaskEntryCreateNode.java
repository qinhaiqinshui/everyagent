package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.contract.rpc.Rpc;
import dev.everyagent.plugin.api.task.TaskChain;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.api.task.AdmissionResult;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.ConfigStore.ResolvedConfig;
import dev.everyagent.worker.plugin.registry.TaskAdmissionPolicyRegistry;
import dev.everyagent.plugin.api.proto.ShortIds;
import dev.everyagent.worker.rpc.RpcContext;
import dev.everyagent.worker.agent.AgentFactoryImpl;
import dev.everyagent.worker.interaction.InteractionServiceImpl;
import dev.everyagent.worker.task.TaskBootstrap;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskManager;
import dev.everyagent.worker.task.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RPC 阶段节点(order=50)：TaskEntry 构造（新建/再运行认领）。
 * <p>三条路径：
 * <ul>
 *   <li>新建：从 RPC params 读 title/configId/workspace 等 → 创建 TaskEntry → tasks.put</li>
 *   <li>再运行：diskTasks.remove 原子认领 → 从 meta 创建 TaskEntry</li>
 *   <li>运行中入队：tasks 中已有且非终态 → 空转（交给 queue.dispatch）</li>
 * </ul>
 */
public final class TaskEntryCreateNode implements TaskLifecycleNode {

    private static final Logger log = LoggerFactory.getLogger(TaskEntryCreateNode.class);

    private final TaskBootstrap taskBootstrap;
    private final WorkerProperties props;
    private final Map<String, TaskEntry> tasks;
    private final Map<String, TaskStore.StoredTask> diskTasks;
    private final AtomicInteger active;
    private final TaskStore store;
    private final Map<String, TaskManager.IdemEntry> idem;
    private final TaskAdmissionPolicyRegistry admissionPolicyRegistry;
    /** ExecContext 预绑定端口裸依赖:创建 TaskEntry 后注入(S2,agentFactory()/interaction() 槽位)。 */
    private final AgentFactoryImpl agentFactory;
    private final InteractionServiceImpl asks;

    public TaskEntryCreateNode(TaskBootstrap taskBootstrap, WorkerProperties props,
            Map<String, TaskEntry> tasks, Map<String, TaskStore.StoredTask> diskTasks,
            AtomicInteger active, TaskStore store,
            Map<String, TaskManager.IdemEntry> idem,
            TaskAdmissionPolicyRegistry admissionPolicyRegistry,
            AgentFactoryImpl agentFactory, InteractionServiceImpl asks) {
        this.taskBootstrap = taskBootstrap;
        this.props = props;
        this.tasks = tasks;
        this.diskTasks = diskTasks;
        this.active = active;
        this.store = store;
        this.idem = idem;
        this.admissionPolicyRegistry = admissionPolicyRegistry;
        this.agentFactory = agentFactory;
        this.asks = asks;
    }

    @Override
    public String id() { return "taskentry.create"; }

    @Override
    public float order() { return 50; }

    @Override
    public Object invoke(TaskLifecycleContext ctx, TaskChain next) throws Exception {
        var impl = (TaskLifecycleContextImpl) ctx;
        // 已有 TaskEntry（不应到此，防御）
        if (impl.taskEntryImpl() != null) return next.proceed(ctx);

        String taskId = impl.taskId();
        if (taskId == null || taskId.isEmpty()) {
            // 不应该发生（taskid.generate 在前面），防御性 error
            var rpcCtx = ctx.rpcContext();
            if (rpcCtx instanceof RpcContext rc) {
                rc.err(Rpc.ERR_INTERNAL, "taskId 未生成");
            }
            return null;
        }

        // ---- 检查 tasks（运行中）----
        TaskEntry existing = tasks.get(taskId);
        if (existing != null) {
            if (!existing.status.terminal()) {
                // 运行中：空转，让 queue.dispatch 处理
                log.debug("[entry] taskentry.create 运行中任务空转 task={}", taskId);
                impl.taskEntry(existing);
                return next.proceed(ctx);
            }
            // 终态：等 finish 驱逐
            synchronized (existing) {} // 等 flush 后 tasks.remove
        }

        // ---- 检查 diskTasks（原子认领 rerun）----
        TaskStore.StoredTask st = diskTasks.remove(taskId);
        if (st != null) {
            // rerun 路径：从 meta 创建 TaskEntry
            TaskEntry t = createRerunEntry(st, impl);
            if (t == null) {
                // 不可再运行（旧格式或目录缺失）
                diskTasks.putIfAbsent(taskId, st);
                var rpcCtx = ctx.rpcContext();
                if (rpcCtx instanceof RpcContext rc) {
                    rc.err(Rpc.ERR_INTERNAL, "任务不可再运行(旧格式或目录缺失)");
                }
                return null;
            }
            if (tasks.putIfAbsent(taskId, t) != null) {
                diskTasks.putIfAbsent(taskId, st);
                return next.proceed(ctx); // 并发兜底
            }
            active.incrementAndGet();
            impl.taskEntry(t);
            // 设置 rerunMeta 供后续 RerunRestoreNode 使用
            impl.rerunMeta(st.summary());
            log.debug("[entry] taskentry.create 再运行认领 task={} workspace={} dir={}",
                    taskId, t.workspaceId, t.taskDir());
            return next.proceed(ctx);
        }

        // ---- 认领输家兜底：并发再运行可能刚重建热任务 ----
        TaskEntry again = tasks.get(taskId);
        if (again != null && !again.status.terminal()) {
            impl.taskEntry(again);
            return next.proceed(ctx);
        }

        // ---- 新建路径：taskId 在 TaskIdGenerateNode 生成 ----
        var rpcCtx = ctx.rpcContext();
        RpcContext rc = rpcCtx instanceof RpcContext r ? r : null;

        // 检查 tasks 中是否有同 taskId（已处理 above），到此说明是全新任务
        if (tasks.containsKey(taskId)) {
            // 被上面漏掉的终态（极小概率），按 rerun 走认领
            if (rc != null) {
                rc.err(Rpc.ERR_INTERNAL, "任务状态异常");
            }
            return null;
        }

        // 新建 TaskEntry
        // 准入检查（新建路径；rerun 不查上限）
        if (rc != null) {
            if (!admissionPolicyRegistry.isRegistered()) {
                // 无队列插件时硬拒绝
                if (active.get() >= props.getLimits().getMaxConcurrentTasks()) {
                    rc.err(Rpc.ERR_BUSY, "并发任务已达上限 " + props.getLimits().getMaxConcurrentTasks());
                    return null;
                }
            } else {
                AdmissionResult ar = admissionPolicyRegistry.get().check(taskId, props.getLimits().getMaxConcurrentTasks());
                if (!ar.admitted()) {
                    rc.err(Rpc.ERR_BUSY, ar.rejectReason());
                    return null;
                }
            }
        }

        String title = rc != null ? rc.optStrParam("title", null) : null;
        if (title == null || title.isEmpty()) {
            String input = impl.input();
            title = input != null && input.length() > 40 ? input.substring(0, 40) + "…" : (input != null ? input : "任务");
        }
        String configId = rc != null ? rc.optStrParam("configId", null) : null;
        ResolvedConfig cfg = taskBootstrap.resolveConfig(configId);
        String mainAgentId = ShortIds.mainAgentId();
        TaskEntry t = new TaskEntry(taskId, title, cfg.snapshot(),
                impl.workspaceRoot(), impl.workspaceId(),
                mainAgentId, props.getLimits().getMaxEventsPerTask());
        t.taskDir(store.dirOf(taskId, impl.workspaceId()));
        t.bindExecPorts(agentFactory, asks);
        tasks.put(taskId, t);
        log.debug("[entry] taskentry.create 新建 task={} workspaceId={} workspaceRoot={} dir={} thread={}",
                taskId, impl.workspaceId(), impl.workspaceRoot(), t.taskDir(),
                Thread.currentThread().getName());

        active.incrementAndGet();

        // 幂等键记录
        if (rc != null) {
            String idemKey = rc.optStrParam("idempotencyKey", null);
            if (idemKey != null && !idemKey.isEmpty()) {
                idem.put(idemKey, new TaskManager.IdemEntry(taskId, System.currentTimeMillis()));
            }
        }

        impl.taskEntry(t);
        return next.proceed(ctx);
    }

    private TaskEntry createRerunEntry(TaskStore.StoredTask st, TaskLifecycleContextImpl impl) {
        JsonNode meta = st.summary();
        String taskId = st.taskId();
        String mainAgentId = meta.path("mainAgentId").asString("");
        if (mainAgentId.isEmpty() || !java.nio.file.Files.isDirectory(st.dir())) {
            log.warn("任务不可再运行(旧格式或目录缺失): {}", taskId);
            return null;
        }
        // overrideConfigId：从 RPC params 读取（输入箱切换模型时透传）
        String overrideConfigId = null;
        if (impl.rpcContext() instanceof RpcContext rc) {
            overrideConfigId = rc.optStrParam("configId", null);
        }
        // 模型配置：三级回退（override → meta configId → 默认）
        ResolvedConfig cfg = taskBootstrap.resolveRerunConfig(overrideConfigId,
                meta.path("configId").asString(null));
        // workspaceId
        String workspaceId = meta.path("workspaceId").asString(null);
        if (workspaceId == null || workspaceId.isEmpty()) {
            workspaceId = taskBootstrap.workspaceIdOf(meta.path("workspace").asString(""));
        }
        TaskEntry t = new TaskEntry(taskId,
                meta.path("title").asString("继续对话"), cfg.snapshot(),
                meta.path("workspace").asString(null), workspaceId, mainAgentId,
                props.getLimits().getMaxEventsPerTask());
        t.taskDir(st.dir());
        t.bindExecPorts(agentFactory, asks);
        return t;
    }
}
