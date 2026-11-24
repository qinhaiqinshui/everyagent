package dev.everyagent.plugin.workflow.engine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.exception.BadParamsException;
import dev.everyagent.plugin.api.exception.NotFoundException;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;
import dev.everyagent.plugin.api.util.RootCause;
import dev.everyagent.plugin.workflow.engine.NodeRunners.AiNodeRunner;
import dev.everyagent.plugin.workflow.engine.NodeRunners.CommandNodeRunner;
import dev.everyagent.plugin.workflow.engine.NodeRunners.Decision;
import dev.everyagent.plugin.workflow.engine.NodeRunners.NodeResult;
import dev.everyagent.plugin.workflow.engine.NodeRunners.SupervisorRunner;
import dev.everyagent.plugin.workflow.model.Definition;
import dev.everyagent.plugin.workflow.model.EdgeDef;
import dev.everyagent.plugin.workflow.model.EventLine;
import dev.everyagent.plugin.workflow.model.InstanceMeta;
import dev.everyagent.plugin.workflow.model.InstanceStatus;
import dev.everyagent.plugin.workflow.model.NodeDef;
import dev.everyagent.plugin.workflow.model.RunRecord;
import dev.everyagent.plugin.workflow.store.WorkflowStore;

/**
 * 工作流调度器（实例运行语义的实现核心）。
 *
 * <p><b>调度模型</b>：开始节点先行；<b>边激活语义</b>——出边条件在源节点完成时求值一次，
 * 满足则标记已激活（内存调度器状态，不落盘）；节点就绪=存在已激活入边（运行时不重复求值）；
 * 全激活语义（所有满足条件的出边全部激活；存在条件出边但无一满足=节点走到尽头，不报错）；
 * 并行节点各自虚拟线程；<b>同 agent 名节点全局互斥</b>（fair 锁，锁粒度=整个 run——
 * 读种子→发指令→run→事件追加，保证后跑节点读到上游完整事件流）。
 *
 * <p><b>循环上限</b>：调度时 {@code runCount+1 > maxRuns} 触发错误策略（fail/supervisor/askUser）；
 * 错误策略的 retry 决策绕过上限（否则一次失败即永锁）。
 *
 * <p><b>完成判定</b>：无运行中节点、无挂起决策、无未消费激活（此时剩余 pending 节点均为
 * 不可达死分支——不再阻塞实例收束）。
 *
 * <p><b>状态机</b>：created/running/waiting-user/done/failed/cancelled；迁移点原子重写
 * meta.json（终态投影 nodeStates）。worker 重启扫描标 failed 已在 store 层（步骤 4）。
 *
 * <p><b>异常契约</b>：InterruptedException（含流取消）→ 取消路径；ModelCallException/其他 →
 * 节点失败 → 错误策略。agent 终态由 claimTerminal CAS 收口不重复（与 advisor 链双通道）。
 *
 * <p>引擎不阻塞 RPC 线程：{@link #start} 异步开跑（调度器跑虚拟线程），本类即
 * workflow.run RPC 的后端入口（RPC 装配在步骤 6）。任务内运行经
 * {@link #start(InstanceMeta, ExecContext)} 传入 origin 任务 ExecContext（AI 节点/督导
 * 装配与事件单写任务域的绑定源，§7.20.8）；无绑定的含 AI 节点定义在 start 即拒。
 */
public final class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    /** 长轮询内部轮询周期。 */
    private static final long POLL_INTERVAL_MS = 200;

    /** 终态收口时等待节点线程退出的单线程上限。 */
    private static final long JOIN_TIMEOUT_MS = 10_000;

    private final WorkflowStore store;
    private final WorkerServices services;
    /** instanceId → 运行中实例（终态即移除；查询回退磁盘 meta/events）。 */
    private final ConcurrentHashMap<String, InstanceRun> runs = new ConcurrentHashMap<>();

    public WorkflowEngine(WorkflowStore store, WorkerServices services) {
        this.store = store;
        this.services = services;
    }

    // ══════════════════════════ 公共 API（步骤 6 RPC 的后端） ══════════════════════════

    /**
     * 启动实例（异步开跑，无 origin 任务绑定）：created → running（meta 重写 + wf.status
     * 事件），调度开始节点。实例须为 store.createInstance 的产物（status=created）。
     *
     * @return 已迁移为 running 的实例元数据
     * @throws BadParamsException 实例非 created 态 / 重复启动 / 定义快照缺 start / 工作区不在注册表
     */
    public InstanceMeta start(InstanceMeta meta) {
        return start(meta, null);
    }

    /**
     * 启动实例并绑定 origin 任务（任务内运行的入口）：任务 ExecContext 存入实例运行态
     * （{@link WorkflowRuntime#taskExec()}），AI 节点/督导经它装配 agent 与单写任务事件流。
     *
     * <p><b>fail-fast 守卫</b>：定义快照含 AI 节点而绑定不可用（未传 / 任务事件口已失效）
     * 时拒绝启动（引导经任务运行，§7.20.8）；绑定与 meta.originTaskId 不一致同样拒绝。
     *
     * @param taskExec origin 任务的执行上下文（null=无绑定，仅限无 AI 节点定义）
     * @throws BadParamsException 含 AI 节点而无可用任务绑定 / originTaskId 与绑定不一致 / 其余同 {@link #start(InstanceMeta)}
     */
    public InstanceMeta start(InstanceMeta meta, ExecContext taskExec) {
        if (meta == null || meta.id() == null || meta.id().isBlank()) {
            throw new BadParamsException("实例 meta 缺 id");
        }
        if (meta.status() != InstanceStatus.CREATED) {
            throw new BadParamsException("实例不可启动(当前状态 " + meta.status() + "): " + meta.id());
        }
        if (runs.containsKey(meta.id())) {
            throw new BadParamsException("实例已在运行: " + meta.id());
        }
        Definition snapshot = meta.definitionSnapshot();
        if (snapshot == null || snapshot.nodesOrEmpty().isEmpty()) {
            throw new BadParamsException("实例缺定义快照: " + meta.id());
        }
        String startId = startNodeOf(snapshot);
        if (startId == null) {
            throw new BadParamsException("定义快照缺少 start 节点: " + meta.id());
        }
        String root = resolveWorkspaceRoot(meta.workspaceId());
        if (root == null) {
            throw new BadParamsException("工作区不在注册表(无法解析 workspaceRoot): " + meta.workspaceId());
        }
        if (taskExec != null && meta.originTaskId() != null
                && !meta.originTaskId().equals(taskExec.subjectId())) {
            throw new BadParamsException("originTaskId 与绑定的任务不一致(meta=" + meta.originTaskId()
                    + ", 绑定=" + taskExec.subjectId() + "): " + meta.id());
        }
        if (containsAiNode(snapshot) && !NodeRunners.usableTaskBinding(taskExec)) {
            throw new BadParamsException("工作流含 AI 节点,拒绝无任务绑定运行: " + meta.id()
                    + "。" + NodeRunners.NO_TASK_BINDING_MESSAGE);
        }
        InstanceRun r = new InstanceRun(meta, snapshot, root, taskExec);
        InstanceRun prev = runs.putIfAbsent(meta.id(), r);
        if (prev != null) {
            throw new BadParamsException("实例已在运行: " + meta.id());
        }
        log.info("[workflow] 实例启动 id={} 定义={} 节点数={} originTask={} 绑定任务={}",
                meta.id(), meta.definitionId(), snapshot.nodesOrEmpty().size(),
                meta.originTaskId(), taskExec == null ? "-" : taskExec.subjectId());
        synchronized (r.schedLock) {
            transitionLocked(r, InstanceStatus.RUNNING, null, null, null);
            r.pendingActivations.merge(startId, 1, Integer::sum);
            tryScheduleLocked(r, startId);
        }
        return r.meta;
    }

    /** 定义快照是否含 AI 节点（含 = 运行必须有可用任务绑定，§7.20.8 fail-fast 守卫）。 */
    private static boolean containsAiNode(Definition snapshot) {
        for (NodeDef n : snapshot.nodesOrEmpty()) {
            if (n != null && NodeDef.TYPE_AI.equals(n.type())) {
                return true;
            }
        }
        return false;
    }

    /** 实例是否在运行（终态即不在）。 */
    public boolean isAlive(String instanceId) {
        return runs.containsKey(instanceId);
    }

    /**
     * 取消实例：中断节点线程（agent 终态由 claimTerminal CAS 收口不重复），
     * 挂起决策一并唤醒作废；已终态抛 {@link BadParamsException}。
     */
    public void cancel(String instanceId, String by) {
        InstanceRun r = runs.get(instanceId);
        if (r == null) {
            InstanceMeta meta = store.findInstance(instanceId)
                    .orElseThrow(() -> new NotFoundException("实例不存在: " + instanceId));
            throw new BadParamsException("实例未在运行(当前状态 " + meta.status() + "): " + instanceId);
        }
        if (r.status.get().terminal()) {
            throw new BadParamsException("实例已终态: " + instanceId);
        }
        log.info("[workflow] 实例取消 id={} by={}", instanceId, by);
        finish(r, InstanceStatus.CANCELLED, null, by == null || by.isBlank() ? "user" : by);
    }

    /**
     * 用户决策（askUser 挂起的唤醒口，workflow.instance.decide RPC 的后端）：
     * 仅 waiting-user 且 pendingDecision.runNo 匹配时生效；唤醒后实例回 running。
     */
    public boolean decide(String instanceId, int runNo, String decision, String input) {
        InstanceRun r = runs.get(instanceId);
        if (r == null) {
            throw new BadParamsException("实例不在运行中(可能已终态): " + instanceId);
        }
        if (decision == null || !List.of(Decision.RETRY, Decision.SKIP, Decision.FAIL,
                Decision.RETRY_WITH_INPUT).contains(decision)) {
            throw new BadParamsException("decision 必须为 retry/skip/fail/retryWithInput: " + decision);
        }
        if (Decision.RETRY_WITH_INPUT.equals(decision) && (input == null || input.isBlank())) {
            throw new BadParamsException("retryWithInput 必须携带 input");
        }
        CompletableFuture<Decision> future;
        synchronized (r.schedLock) {
            InstanceMeta.PendingDecision pd = r.pendingDecision;
            if (pd == null) {
                throw new BadParamsException("实例无挂起决策: " + instanceId);
            }
            if (pd.runNo() == null || pd.runNo() != runNo) {
                throw new BadParamsException("runNo 与挂起决策不匹配(期望 " + pd.runNo() + "): " + runNo);
            }
            r.pendingDecision = null;
            future = r.decisionFuture;
            r.decisionFuture = null;
            transitionLocked(r, InstanceStatus.RUNNING, null, null, null);
        }
        if (future != null) {
            future.complete(new Decision(decision, input, "user"));
        }
        return true;
    }

    /**
     * 实例事件查询（instance.events 长轮询后端）：seq &gt; afterSeq 升序；
     * 实例仍在运行且 waitMs 未到期时空轮等待（内部 200ms 轮询磁盘——追加写的
     * 完整事实源；终态/超时/有数据即返回）。
     */
    public List<EventLine> queryEvents(String instanceId, long afterSeq, long waitMs, int limit) {
        long deadline = System.currentTimeMillis() + Math.max(0, waitMs);
        while (true) {
            InstanceRun r = runs.get(instanceId);
            InstanceMeta meta = r != null ? r.meta
                    : store.findInstance(instanceId).orElse(null);
            if (meta == null) {
                return List.of();
            }
            List<EventLine> events = store.readEvents(meta.workspaceId(), instanceId, afterSeq, limit);
            boolean alive = runs.containsKey(instanceId);
            if (!events.isEmpty() || !alive || waitMs <= 0
                    || System.currentTimeMillis() >= deadline) {
                return events;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return events;
            }
        }
    }

    /** 测试/运维用：等待实例终态（true=已终态；false=超时）。 */
    public boolean awaitTerminal(String instanceId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        while (runs.containsKey(instanceId)) {
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            Thread.sleep(25);
        }
        return true;
    }

    // ══════════════════════════ 实例运行态 ══════════════════════════

    /** 单实例调度器状态（全部内存态——重启即 failed，无需恢复，计划已定）。 */
    private final class InstanceRun {
        final String wsId;
        final String instanceId;
        final WorkflowRuntime runtime;
        final ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor();
        /** 调度器状态锁（激活集/节点状态/完成判定/状态迁移全部在此临界区内）。 */
        final Object schedLock = new Object();
        /** 定义快照（retryWithInput 会替换节点输入——整快照换引用）。 */
        volatile Definition snapshot;
        final Map<String, List<EdgeDef>> outEdges = new LinkedHashMap<>();
        final Map<String, EdgeDef> edgeById = new LinkedHashMap<>();
        final ConcurrentHashMap<String, NodeState> nodeStates = new ConcurrentHashMap<>();
        /** 节点未消费激活数（就绪=有激活；调度时全量消费）。 */
        final ConcurrentHashMap<String, Integer> pendingActivations = new ConcurrentHashMap<>();
        /** 同 agent 名 fair 锁（会话串行不变式 + per-agent jsonl 单写者假设）。 */
        final ConcurrentHashMap<String, ReentrantLock> agentLocks = new ConcurrentHashMap<>();
        /** 运行中节点线程（取消/失败收口时中断）。 */
        final ConcurrentHashMap<String, Thread> nodeThreads = new ConcurrentHashMap<>();
        /** runNo → 已落盘 RunRecord（supervisor 决策链追加时重写）。 */
        final ConcurrentHashMap<Integer, RunRecord> runRecords = new ConcurrentHashMap<>();
        /** nodeId:runNo → supervisor 介入链。 */
        final ConcurrentHashMap<String, List<RunRecord.SupervisorEntry>> supervisorChains =
                new ConcurrentHashMap<>();
        /** nodeId → supervisor 已介入次数（上限 3）。 */
        final ConcurrentHashMap<String, Integer> supervisorAttempts = new ConcurrentHashMap<>();
        final AtomicInteger runCounter;
        final AtomicReference<InstanceStatus> status;
        volatile InstanceMeta meta;
        volatile InstanceMeta.PendingDecision pendingDecision;
        volatile CompletableFuture<Decision> decisionFuture;
        /** 收口中（fail/cancel）——抑制新调度、节点线程按取消路径静默退场。 */
        volatile boolean stopping;

        InstanceRun(InstanceMeta meta, Definition snapshot, String workspaceRoot, ExecContext taskExec) {
            this.meta = meta;
            this.snapshot = snapshot;
            this.wsId = meta.workspaceId();
            this.instanceId = meta.id();
            this.runCounter = new AtomicInteger(
                    meta.runCounter() == null ? 0 : meta.runCounter());
            this.status = new AtomicReference<>(InstanceStatus.CREATED);
            Path instanceDir = services.workspaces().systemDir()
                    .resolve("workspaces").resolve(wsId)
                    .resolve("workflows").resolve("instances").resolve(instanceId);
            this.runtime = new WorkflowRuntime(store, wsId, instanceId,
                    instanceDir, workspaceRoot, taskExec);
            for (NodeDef n : snapshot.nodesOrEmpty()) {
                nodeStates.put(n.id(), new NodeState());
                outEdges.put(n.id(), new ArrayList<>());
            }
            for (EdgeDef e : snapshot.edgesOrEmpty()) {
                edgeById.put(e.id(), e);
                outEdges.get(e.from()).add(e);
            }
        }

        /** 同 agent fair 锁（不存在则建；fair=排队唤醒防并行节点饥饿）。 */
        ReentrantLock agentLock(String agentName) {
            return agentLocks.computeIfAbsent(agentName, k -> new ReentrantLock(true));
        }

        NodeDef node(String nodeId) {
            for (NodeDef n : snapshot.nodesOrEmpty()) {
                if (n.id().equals(nodeId)) {
                    return n;
                }
            }
            return null;
        }
    }

    /** 节点调度态：pending → running → done|skipped（done/skipped 可被再次激活重回 running）。 */
    private static final class NodeState {
        volatile String status = "pending";
        int runCount;
        volatile int lastRunNo;
    }

    // ══════════════════════════ 调度 ══════════════════════════

    /** 节点就绪调度（schedLock 内调用）：有未消费激活且空闲 → 开跑；超上限 → 错误策略。 */
    private void tryScheduleLocked(InstanceRun r, String nodeId) {
        if (r.stopping || r.status.get().terminal()) {
            return;
        }
        NodeState st = r.nodeStates.get(nodeId);
        if (st == null) {
            log.warn("[workflow] 调度到未知节点(忽略) instance={} node={}", r.instanceId, nodeId);
            return;
        }
        if ("running".equals(st.status)) {
            return; // 运行中：本次激活留待其完成时自查重调
        }
        if (r.pendingActivations.getOrDefault(nodeId, 0) <= 0) {
            return; // 无已激活入边,不就绪
        }
        NodeDef node = r.node(nodeId);
        int maxRuns = node != null && node.maxRuns() != null ? node.maxRuns() : 1;
        if (st.runCount + 1 > maxRuns) {
            // 循环上限：拒绝第 N 次调度,按节点失败走错误策略
            st.status = "running";
            r.pendingActivations.put(nodeId, 0);
            int refusedRunNo = st.runCount + 1;
            String nodeName = node == null ? nodeId : display(node);
            String message = "节点「" + nodeName + "」达到最大运行次数上限(maxRuns=" + maxRuns
                    + ",已完成 " + st.runCount + " 次),拒绝第 " + refusedRunNo + " 次调度";
            r.vt.execute(() -> {
                r.nodeThreads.put(nodeId, Thread.currentThread());
                try {
                    if (r.stopping || r.status.get().terminal()) {
                        return;
                    }
                    emitNodeError(r, nodeId, nodeName, refusedRunNo, message, null);
                    handleNodeError(r, node, refusedRunNo, message);
                } finally {
                    // 条件移除：自环重调度的新线程可能已抢先注册同 key
                    r.nodeThreads.remove(nodeId, Thread.currentThread());
                }
            });
            return;
        }
        r.pendingActivations.put(nodeId, 0);
        startNodeRun(r, nodeId, st);
    }

    /** 开跑一次节点执行（schedLock 内调用；消费激活与状态迁移在此完成）。 */
    private void startNodeRun(InstanceRun r, String nodeId, NodeState st) {
        st.status = "running";
        int runNo = r.runCounter.incrementAndGet();
        st.lastRunNo = runNo;
        r.vt.execute(() -> executeNodeRun(r, nodeId, runNo));
    }

    /** 错误策略 retry 的再调度：绕过 maxRuns 上限与激活判定（决策即授权）。 */
    private void forceSchedule(InstanceRun r, NodeDef node) {
        synchronized (r.schedLock) {
            if (r.stopping || r.status.get().terminal()) {
                return;
            }
            NodeState st = r.nodeStates.get(node.id());
            if (st == null) {
                return;
            }
            r.pendingActivations.put(node.id(), 0);
            startNodeRun(r, node.id(), st);
        }
    }

    /** 节点执行体（虚拟线程）。 */
    private void executeNodeRun(InstanceRun r, String nodeId, int runNo) {
        r.nodeThreads.put(nodeId, Thread.currentThread());
        long startedAt = System.currentTimeMillis();
        NodeDef node = r.node(nodeId);
        RenderedInput in = null;
        try {
            if (node == null) {
                failInstance(r, "节点不在定义快照中: " + nodeId, nodeId, runNo);
                return;
            }
            in = renderInput(r, node);
            emitNodeStarted(r, node, runNo, in);
            NodeResult result;
            switch (node.type() == null ? "" : node.type()) {
                case NodeDef.TYPE_START ->
                    result = new NodeResult(0, "", null, null);
                case NodeDef.TYPE_COMMAND ->
                    result = new CommandNodeRunner().run(services, r.runtime.workspaceRoot(),
                            node, in.rendered());
                case NodeDef.TYPE_AI -> {
                    ReentrantLock lock = node.agent() != null && !node.agent().isBlank()
                            ? r.agentLock(node.agent()) : null;
                    result = new AiNodeRunner().run(r.runtime.taskExec(), r.instanceId,
                            r.snapshot, node, runNo, in.rendered(), in.visibleToAi(), lock);
                }
                default -> throw new IllegalStateException("未知节点类型: " + node.type());
            }
            completeNode(r, node, runNo, result, in, startedAt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            onNodeInterrupted(r, node != null ? node.id() : nodeId, runNo);
        } catch (Throwable t) {
            onNodeFailed(r, node != null ? node : unknownNode(nodeId), runNo, startedAt, in,
                    RootCause.summary(t));
        } finally {
            // 条件移除：自环/错误重试的新运行线程可能已抢先注册同 key
            r.nodeThreads.remove(nodeId, Thread.currentThread());
        }
    }

    /** 成功收口：写 runs 记录 + node.output 事件 + 出边一次性求值激活 + 下游调度 + 完成判定。 */
    private void completeNode(InstanceRun r, NodeDef node, int runNo, NodeResult result,
            RenderedInput in, long startedAt) {
        List<String> activated = new ArrayList<>();
        synchronized (r.schedLock) {
            if (r.stopping || r.status.get().terminal()) {
                return;
            }
            NodeState st = r.nodeStates.get(node.id());
            st.status = "done";
            st.runCount++;
            for (EdgeDef e : r.outEdges.getOrDefault(node.id(), List.of())) {
                if (NodeRunners.evaluate(e.condition(), result.exitCode(), result.conditionOutput())) {
                    activated.add(e.id());
                }
            }
            RunRecord rec = buildRecord(r, node, runNo, startedAt, in, result, null, activated);
            r.runRecords.put(runNo, rec);
            store.saveRunRecord(r.wsId, r.instanceId, rec);
            emitNodeOutput(r, node, runNo, result, activated);
            activateTargetsLocked(r, activated);
            // 自查重调：本节点运行期间新到的激活（环/多前驱）
            tryScheduleLocked(r, node.id());
            checkCompletionLocked(r);
        }
    }

    /** 激活目标节点（schedLock 内调用；全激活语义=逐边独立判定后全部激活）。 */
    private void activateTargetsLocked(InstanceRun r, List<String> activatedEdgeIds) {
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        for (String edgeId : activatedEdgeIds) {
            EdgeDef e = r.edgeById.get(edgeId);
            if (e != null) {
                targets.add(e.to());
            }
        }
        for (String target : targets) {
            r.pendingActivations.merge(target, 1, Integer::sum);
        }
        for (String target : targets) {
            tryScheduleLocked(r, target);
        }
    }

    /** 完成判定：无运行中节点 + 无挂起决策 + 无未消费激活 → done。 */
    private void checkCompletionLocked(InstanceRun r) {
        if (r.stopping || r.status.get().terminal() || r.pendingDecision != null) {
            return;
        }
        for (NodeState st : r.nodeStates.values()) {
            if ("running".equals(st.status)) {
                return;
            }
        }
        for (int p : r.pendingActivations.values()) {
            if (p > 0) {
                return;
            }
        }
        finish(r, InstanceStatus.DONE, null, null);
    }

    /** 节点被中断：实例收口中=取消路径静默退场；否则视为节点失败走错误策略。 */
    private void onNodeInterrupted(InstanceRun r, String nodeId, int runNo) {
        if (r.stopping || r.status.get().terminal()) {
            return;
        }
        NodeDef node = r.node(nodeId);
        onNodeFailed(r, node != null ? node : unknownNode(nodeId), runNo,
                System.currentTimeMillis(), null, "节点被中断");
    }

    /** 节点失败：runs 记录(含 error) + node.error 事件 + 错误策略。 */
    private void onNodeFailed(InstanceRun r, NodeDef node, int runNo, long startedAt,
            RenderedInput in, String message) {
        if (r.stopping || r.status.get().terminal()) {
            return;
        }
        RunRecord rec;
        synchronized (r.schedLock) {
            if (r.stopping || r.status.get().terminal()) {
                return;
            }
            NodeState st = r.nodeStates.get(node.id());
            if (st != null) {
                st.runCount++;
            }
            rec = buildRecord(r, node, runNo, startedAt, in, null,
                    new RunRecord.Error(truncate(message, 4000), null), List.of());
            r.runRecords.put(runNo, rec);
        }
        store.saveRunRecord(r.wsId, r.instanceId, rec);
        emitNodeError(r, node.id(), display(node), runNo, message, null);
        handleNodeError(r, node, runNo, message);
    }

    // ══════════════════════════ 错误策略 ══════════════════════════

    /** 错误策略分派：fail（实例失败）/ supervisor（督导决策）/ askUser（挂起等用户）。 */
    private void handleNodeError(InstanceRun r, NodeDef node, int runNo, String message) {
        if (r.stopping || r.status.get().terminal()) {
            return;
        }
        var onError = r.snapshot.onError();
        try {
            switch (onError == null ? dev.everyagent.plugin.workflow.model.OnError.SUPERVISOR : onError) {
                case FAIL -> failInstance(r, "节点「" + display(node) + "」失败: " + message,
                        node.id(), runNo);
                case ASK_USER -> askAndWait(r, node, runNo, message);
                case SUPERVISOR -> supervise(r, node, runNo, message);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            // 收口中断（取消/失败级联）：静默退场
        }
    }

    /** askUser：实例转 waiting-user + pendingDecision，park 等待 decide() 唤醒后执行决策。 */
    private void askAndWait(InstanceRun r, NodeDef node, int runNo, String errorMessage)
            throws InterruptedException {
        // 单决策槽：已有挂起决策时先等它消化（并行节点同时失败的串行化；
        // 占用与状态迁移在同一临界区内原子完成）
        while (true) {
            synchronized (r.schedLock) {
                if (r.stopping || r.status.get().terminal() || r.pendingDecision == null) {
                    break;
                }
            }
            Thread.sleep(100);
        }
        String question = "节点「" + display(node) + "」执行失败: " + truncate(errorMessage, 200)
                + "。请决策如何处理。";
        InstanceMeta.PendingDecision pd = new InstanceMeta.PendingDecision(
                node.id(), runNo, question,
                List.of(Decision.RETRY, Decision.SKIP, Decision.FAIL, Decision.RETRY_WITH_INPUT),
                truncate(errorMessage, 1000), System.currentTimeMillis());
        CompletableFuture<Decision> future = new CompletableFuture<>();
        while (true) {
            synchronized (r.schedLock) {
                if (r.stopping || r.status.get().terminal()) {
                    return;
                }
                if (r.pendingDecision == null) {
                    // 单决策槽的原子占用（与上面的等待循环合成一次原子的 check-and-set）
                    r.pendingDecision = pd;
                    r.decisionFuture = future;
                    transitionLocked(r, InstanceStatus.WAITING_USER, null, pd, null);
                    store.appendEvent(r.wsId, r.instanceId, "wf.askUser", Json.obj()
                            .put("nodeId", node.id())
                            .put("runNo", runNo)
                            .put("question", question)
                            .set("options", Json.toJson(pd.options()))
                            .put("context", pd.context() == null ? "" : pd.context()));
                    break;
                }
            }
            Thread.sleep(100);
        }
        Decision d;
        try {
            d = future.get();
        } catch (CancellationException e) {
            return; // 实例收口作废挂起
        } catch (java.util.concurrent.ExecutionException e) {
            // 不会发生（决策 future 只有 complete 正常值一条通路），防御返回
            log.warn("[workflow] 决策 future 异常完成(忽略) instance={}", r.instanceId, e);
            return;
        }
        log.info("[workflow] 用户决策到达 instance={} node={} action={}",
                r.instanceId, node.id(), d.action());
        applyDecision(r, node, runNo, d, errorMessage);
    }

    /** supervisor 策略：结构化错误上下文跑督导会话线，决策协议见 SupervisorRunner。 */
    private void supervise(InstanceRun r, NodeDef node, int runNo, String message)
            throws InterruptedException {
        int attempt = r.supervisorAttempts.merge(node.id(), 1, Integer::sum);
        if (attempt > NodeRunners.SUPERVISOR_MAX_ATTEMPTS) {
            failInstance(r, "节点「" + display(node) + "」supervisor 介入次数达上限("
                    + NodeRunners.SUPERVISOR_MAX_ATTEMPTS + ")", node.id(), runNo);
            return;
        }
        String context = NodeRunners.buildErrorContext(r.snapshot.name(), node, runNo, message,
                historyPreviews(r, node, runNo), priorDecisionLines(r, node),
                NodeRunners.SUPERVISOR_MAX_ATTEMPTS - attempt);
        Decision d = new SupervisorRunner().decide(r.runtime.taskExec(), r.instanceId,
                r.snapshot, context, r.agentLock(NodeRunners.SUPERVISOR_AGENT));
        if (d == null) {
            log.warn("[workflow] supervisor 决策不可解析,降级 askUser instance={} node={}",
                    r.instanceId, node.id());
            askAndWait(r, node, runNo, message + "(supervisor 决策不可解析,已降级为用户决策)");
            return;
        }
        recordSupervisorDecision(r, node, runNo, attempt, d);
        applyDecision(r, node, runNo, d, message);
    }

    /** 决策落地：retry（绕上限重跑）/ retryWithInput（替换输入重跑）/ skip（终态+全出边激活）/ fail。 */
    private void applyDecision(InstanceRun r, NodeDef node, int runNo, Decision d,
            String errorMessage) {
        if (r.stopping || r.status.get().terminal()) {
            return;
        }
        switch (d.action() == null ? "" : d.action()) {
            case Decision.RETRY -> forceSchedule(r, node);
            case Decision.RETRY_WITH_INPUT -> {
                replaceNodeInput(r, node, d.input());
                forceSchedule(r, node);
            }
            case Decision.SKIP -> skipNode(r, node, runNo);
            case Decision.FAIL -> failInstance(r, "节点「" + display(node) + "」被决策终止"
                    + (d.reason() == null || d.reason().isBlank() ? "" : "(" + truncate(d.reason(), 300) + ")")
                    + ": " + errorMessage, node.id(), runNo);
            default -> log.warn("[workflow] 未知决策动作(忽略) {}", d.action());
        }
    }

    /** skip：节点终态 skipped + 全部出边无条件激活（「跳过该节点继续下游」）。 */
    private void skipNode(InstanceRun r, NodeDef node, int runNo) {
        synchronized (r.schedLock) {
            if (r.stopping || r.status.get().terminal()) {
                return;
            }
            NodeState st = r.nodeStates.get(node.id());
            if (st != null) {
                st.status = "skipped";
            }
            store.appendEvent(r.wsId, r.instanceId, "node.skipped", Json.obj()
                    .put("nodeId", node.id()).put("runNo", runNo));
            // 被跳过节点无输出,条件求值无意义——全部出边激活
            List<String> all = new ArrayList<>();
            for (EdgeDef e : r.outEdges.getOrDefault(node.id(), List.of())) {
                all.add(e.id());
            }
            activateTargetsLocked(r, all);
            tryScheduleLocked(r, node.id());
            checkCompletionLocked(r);
        }
    }

    /** supervisor 决策留档：runs 记录介入链重写 + supervisor.decision 事件。 */
    private void recordSupervisorDecision(InstanceRun r, NodeDef node, int runNo, int attempt,
            Decision d) {
        String key = node.id() + ":" + runNo;
        List<RunRecord.SupervisorEntry> chain = r.supervisorChains.computeIfAbsent(key,
                k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        chain.add(new RunRecord.SupervisorEntry(attempt,
                new RunRecord.SupervisorDecision(d.action(), d.input(), d.reason()), d.reason()));
        RunRecord rec = r.runRecords.get(runNo);
        if (rec != null) {
            RunRecord rewritten = new RunRecord(rec.runNo(), rec.nodeId(), rec.nodeName(),
                    rec.nodeType(), rec.startedAt(), rec.endedAt(), rec.durationMs(), rec.input(),
                    rec.output(), rec.activatedEdges(), rec.agent(), rec.error(), List.copyOf(chain));
            r.runRecords.put(runNo, rewritten);
            store.saveRunRecord(r.wsId, r.instanceId, rewritten);
        }
        var payload = Json.obj().put("nodeId", node.id()).put("runNo", runNo)
                .set("decision", Json.obj().put("action", d.action())
                        .put("input", d.input() == null ? "" : d.input()));
        if (d.reason() != null && !d.reason().isBlank()) {
            payload.put("reason", d.reason());
        }
        store.appendEvent(r.wsId, r.instanceId, "supervisor.decision", payload);
    }

    // ══════════════════════════ 实例收口 ══════════════════════════

    private void failInstance(InstanceRun r, String message, String nodeId, int runNo) {
        // 失败节点投影标 failed（终态快照可读；其余运行中节点保持 running——取消/级联路径如实呈现）
        synchronized (r.schedLock) {
            NodeState st = r.nodeStates.get(nodeId);
            if (st != null && "running".equals(st.status)) {
                st.status = "failed";
            }
        }
        finish(r, InstanceStatus.FAILED, new InstanceMeta.Error(message, nodeId, runNo), null);
    }

    /**
     * 终态收口（done/failed/cancelled 共用；幂等）：置 stopping → 中断节点线程（含 park 中的
     * 决策等待）→ 有界等线程退场（跳过自身）→ meta 终态投影 + 终态事件 → 注销运行态。
     */
    private void finish(InstanceRun r, InstanceStatus terminal, InstanceMeta.Error error,
            String cancelledBy) {
        List<Thread> threads;
        synchronized (r.schedLock) {
            if (r.status.get().terminal()) {
                return;
            }
            r.stopping = true;
            threads = new ArrayList<>(r.nodeThreads.values());
            for (Thread t : threads) {
                t.interrupt();
            }
            CompletableFuture<Decision> f = r.decisionFuture;
            if (f != null && !f.isDone()) {
                f.cancel(false);
            }
        }
        for (Thread t : threads) {
            if (t == Thread.currentThread()) {
                continue;
            }
            try {
                t.join(JOIN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        synchronized (r.schedLock) {
            if (r.status.get().terminal()) {
                return;
            }
            r.pendingDecision = null;
            if (terminal == InstanceStatus.CANCELLED) {
                store.appendEvent(r.wsId, r.instanceId, "wf.cancelled",
                        Json.obj().put("by", cancelledBy == null ? "user" : cancelledBy));
            }
            if (terminal == InstanceStatus.DONE) {
                long duration = 0;
                InstanceMeta m = r.meta;
                if (m != null && m.startedAt() != null) {
                    duration = System.currentTimeMillis() - m.startedAt();
                }
                store.appendEvent(r.wsId, r.instanceId, "wf.done",
                        Json.obj().put("durationMs", duration));
            }
            transitionLocked(r, terminal, error, null, cancelledBy);
        }
        runs.remove(r.instanceId);
        r.vt.shutdownNow();
        log.info("[workflow] 实例终态 id={} status={}", r.instanceId, terminal);
    }

    /** 状态迁移（schedLock 内调用）：meta 原子重写 + wf.status 事件 + runtime.terminal 反映。 */
    private void transitionLocked(InstanceRun r, InstanceStatus to, InstanceMeta.Error error,
            InstanceMeta.PendingDecision pd, String cancelledBy) {
        InstanceStatus cur = r.status.get();
        if (cur.terminal()) {
            return;
        }
        long now = System.currentTimeMillis();
        InstanceMeta m = r.meta;
        Long startedAt = m.startedAt() != null ? m.startedAt()
                : (to == InstanceStatus.CREATED ? null : now);
        Long endedAt = to.terminal() ? now : null;
        Long durationMs = to.terminal() && startedAt != null ? now - startedAt : null;
        Map<String, InstanceMeta.NodeState> projection = to.terminal() ? projectNodeStates(r)
                : m.nodeStates();
        InstanceMeta updated = new InstanceMeta(
                InstanceMeta.SCHEMA_VERSION, m.id(), m.definitionId(), m.definitionName(),
                m.workspaceId(), m.originTaskId(), r.snapshot, m.variablesOrEmpty(), to, m.createdAt(),
                startedAt, endedAt, durationMs, error, pd, projection, r.runCounter.get());
        r.meta = updated;
        r.status.set(to);
        r.runtime.status(to);
        store.saveInstanceMeta(updated);
        var payload = Json.obj().put("status", statusWire(to));
        if (error != null) {
            payload.set("error", Json.obj()
                    .put("message", error.message() == null ? "" : error.message())
                    .put("nodeId", error.nodeId() == null ? "" : error.nodeId())
                    .put("runNo", error.runNo() == null ? 0 : error.runNo()));
        }
        store.appendEvent(r.wsId, r.instanceId, "wf.status", payload);
    }

    /** 终态节点投影（nodeStates 一次性快照，列表页不读 events 也能出进度）。 */
    private Map<String, InstanceMeta.NodeState> projectNodeStates(InstanceRun r) {
        Map<String, InstanceMeta.NodeState> out = new LinkedHashMap<>();
        for (Map.Entry<String, NodeState> e : r.nodeStates.entrySet()) {
            NodeState st = e.getValue();
            out.put(e.getKey(), new InstanceMeta.NodeState(st.runCount, st.status, st.lastRunNo));
        }
        return out;
    }

    // ══════════════════════════ 渲染与记录 ══════════════════════════

    /** 节点输入三视图（raw 模板 / rendered 变量渲染 / visibleToAi token 拍平；start 无输入）。 */
    private RenderedInput renderInput(InstanceRun r, NodeDef node) {
        Map<String, String> vars = r.meta.variablesOrEmpty();
        List<dev.everyagent.plugin.workflow.model.VariableDef> decls = r.snapshot.variablesOrEmpty();
        switch (node.type() == null ? "" : node.type()) {
            case NodeDef.TYPE_COMMAND -> {
                String raw = node.command() == null ? "" : node.command();
                String rendered = NodeRunners.render(raw, vars, decls);
                return new RenderedInput(raw, rendered, null, rendered);
            }
            case NodeDef.TYPE_AI -> {
                String raw = node.input() == null ? "" : node.input();
                String rendered = NodeRunners.render(raw, vars, decls);
                String visible = SlashTokenEncoder.stripTokensToLabels(rendered);
                return new RenderedInput(raw, rendered, visible, visible);
            }
            default -> {
                return new RenderedInput(null, null, null, null);
            }
        }
    }

    private record RenderedInput(String raw, String rendered, String visibleToAi, String preview) {
    }

    private RunRecord buildRecord(InstanceRun r, NodeDef node, int runNo, long startedAt,
            RenderedInput in, NodeResult result, RunRecord.Error error, List<String> activated) {
        long endedAt = System.currentTimeMillis();
        RunRecord.RunInput input = in == null || in.raw() == null ? null
                : new RunRecord.RunInput(in.raw(), in.rendered(), in.visibleToAi());
        List<RunRecord.SupervisorEntry> chain = r.supervisorChains.get(node.id() + ":" + runNo);
        return new RunRecord(runNo, node.id(), node.name(), node.type(), startedAt, endedAt,
                endedAt - startedAt, input,
                result == null ? null : result.output(), activated,
                result == null ? null : result.agentTrace(), error,
                chain == null || chain.isEmpty() ? null : List.copyOf(chain));
    }

    private void replaceNodeInput(InstanceRun r, NodeDef node, String newInput) {
        List<NodeDef> nodes = new ArrayList<>();
        for (NodeDef n : r.snapshot.nodesOrEmpty()) {
            nodes.add(n.id().equals(node.id())
                    ? new NodeDef(n.id(), n.type(), n.name(), n.x(), n.y(), n.command(),
                            n.timeoutMs(), n.maxRuns(), n.agent(), n.modelConfigId(), newInput)
                    : n);
        }
        Definition old = r.snapshot;
        r.snapshot = new Definition(old.schemaVersion(), old.id(), old.name(), old.description(),
                old.workspaceId(), old.createdAt(), old.updatedAt(), old.variables(), old.onError(),
                old.supervisor(), old.agents(), nodes, old.edges());
    }

    // ══════════════════════════ 事件发射 ══════════════════════════

    private void emitNodeStarted(InstanceRun r, NodeDef node, int runNo, RenderedInput in) {
        var payload = Json.obj()
                .put("nodeId", node.id())
                .put("nodeName", display(node))
                .put("runNo", runNo);
        if (node.agent() != null && !node.agent().isBlank()) {
            payload.put("agent", node.agent());
        }
        if (in != null && in.preview() != null) {
            payload.put("inputPreview", truncate(in.preview(), 1024));
        }
        store.appendEvent(r.wsId, r.instanceId, "node.started", payload);
    }

    private void emitNodeOutput(InstanceRun r, NodeDef node, int runNo, NodeResult result,
            List<String> activated) {
        var payload = Json.obj()
                .put("nodeId", node.id())
                .put("runNo", runNo)
                .put("exitCode", result.exitCode())
                .put("outputPreview", truncate(result.conditionOutput(), 1024))
                .set("activatedEdges", Json.toJson(activated));
        store.appendEvent(r.wsId, r.instanceId, "node.output", payload);
    }

    private void emitNodeError(InstanceRun r, String nodeId, String nodeName, int runNo,
            String message, Integer exitCode) {
        var payload = Json.obj()
                .put("nodeId", nodeId)
                .put("runNo", runNo)
                .put("message", message == null ? "" : message);
        if (exitCode != null) {
            payload.put("exitCode", exitCode);
        }
        store.appendEvent(r.wsId, r.instanceId, "node.error", payload);
    }

    // ══════════════════════════ 工具 ══════════════════════════

    private static String display(NodeDef node) {
        return node.name() == null || node.name().isBlank() ? node.id() : node.name();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…(截断)";
    }

    private static NodeDef unknownNode(String nodeId) {
        return new NodeDef(nodeId, "unknown", nodeId, null, null, null, null, null, null, null, null);
    }

    /** 状态 → wire 名（与 InstanceStatus @JsonProperty 序列化值一致）。 */
    private static String statusWire(InstanceStatus s) {
        return switch (s) {
            case CREATED -> "created";
            case RUNNING -> "running";
            case WAITING_USER -> "waiting-user";
            case DONE -> "done";
            case FAILED -> "failed";
            case CANCELLED -> "cancelled";
        };
    }

    private static String startNodeOf(Definition d) {
        for (NodeDef n : d.nodesOrEmpty()) {
            if (NodeDef.TYPE_START.equals(n.type())) {
                return n.id();
            }
        }
        return null;
    }

    /** workspaceId → root（经注册表；prune+idOfRoot 同 store 口径；未注册返回 null）。 */
    private String resolveWorkspaceRoot(String wsId) {
        try {
            for (Path root : services.workspaces().pruneStaleAndListMountRoots()) {
                String id = services.workspaces().idOfRoot(root.toString());
                if (wsId.equals(id)) {
                    return root.toString();
                }
            }
        } catch (RuntimeException e) {
            log.warn("[workflow] 工作区注册表解析失败 wsId={}", wsId, e);
        }
        return null;
    }

    /** 该节点此前完成的运行输出摘要（supervisor 错误上下文用）。 */
    private List<String> historyPreviews(InstanceRun r, NodeDef node, int currentRunNo) {
        List<String> out = new ArrayList<>();
        r.runRecords.values().stream()
                .filter(rec -> rec != null && node.id().equals(rec.nodeId())
                        && rec.runNo() != null && rec.runNo() < currentRunNo)
                .sorted(java.util.Comparator.comparing(RunRecord::runNo))
                .forEach(rec -> {
                    RunRecord.RunOutput o = rec.output();
                    String text = o == null ? "" : (o.text() != null ? o.text()
                            : (o.stdout() == null ? "" : o.stdout())
                                    + (o.stderr() == null ? "" : o.stderr()));
                    out.add("run #" + rec.runNo() + " → " + truncate(text, 200));
                });
        return out;
    }

    /** 该节点此前的介入决策链文本（supervisor 错误上下文用）。 */
    private List<String> priorDecisionLines(InstanceRun r, NodeDef node) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<RunRecord.SupervisorEntry>> e : r.supervisorChains.entrySet()) {
            if (!e.getKey().startsWith(node.id() + ":")) {
                continue;
            }
            for (RunRecord.SupervisorEntry entry : e.getValue()) {
                out.add("attempt " + entry.attempt() + ": " + entry.decision().action()
                        + (entry.reason() == null || entry.reason().isBlank()
                                ? "" : " (" + truncate(entry.reason(), 120) + ")"));
            }
        }
        return out;
    }
}
