package dev.everyagent.plugin.workflow.engine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.proto.SnowflakeId;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;
import dev.everyagent.plugin.api.util.RootCause;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.workflow.engine.NodeRunners.AiNodeRunner;
import dev.everyagent.plugin.workflow.engine.NodeRunners.CommandNodeRunner;
import dev.everyagent.plugin.workflow.engine.NodeRunners.Decision;
import dev.everyagent.plugin.workflow.engine.NodeRunners.NodeResult;
import dev.everyagent.plugin.workflow.model.Definition;
import dev.everyagent.plugin.workflow.model.EdgeDef;
import dev.everyagent.plugin.workflow.model.EventLine;
import dev.everyagent.plugin.workflow.model.InstanceMeta;
import dev.everyagent.plugin.workflow.model.InstanceStatus;
import dev.everyagent.plugin.workflow.model.NodeDef;
import dev.everyagent.plugin.workflow.model.RunRecord;
import dev.everyagent.plugin.workflow.model.VariableDef;
import dev.everyagent.plugin.workflow.store.WorkflowStore;

/**
 * 工作流调度器（实例运行语义的实现核心）。
 *
 * <p><b>调度模型</b>：开始节点先行；<b>边激活语义</b>——出边条件在源节点完成时求值一次，
 * 满足则标记已激活（内存调度器状态，不落盘）；节点就绪=存在已激活入边（运行时不重复求值）；
 * 全激活语义（所有满足条件的出边全部激活；存在条件出边但无一满足=节点走到尽头，不报错）；
 * 并行节点各自虚拟线程；<b>同 agent 名节点全局互斥</b>（fair 锁，锁粒度=整个 run——
 * 读种子→发指令→run→事件追加，保证后跑节点读到上游完整事件流）。
 * <b>节点级汇合屏障（joinMode=all）</b>：等齐全部入边各激活一次才触发一次执行（到达记账
 * 按<b>边 id 去重</b>——环内同一条边重复到达记一条；触发后记账清零，回指循环每轮重新
 * 等齐）；maxRuns 按<b>触发执行次数</b>计——并行分支→汇总节点不再需要按入边数手算
 * maxRuns。joinMode=none（缺省）语义不变：每条激活入边到达即触发一次。
 *
 * <p><b>循环上限</b>：调度时 {@code runCount+1 > maxRuns} 触发错误策略（fail/askUser）；
 * 错误策略的 retry 决策绕过上限（否则一次失败即永锁）。runCount 在 {@code startNodeRun}
 * 开跑时计入（<b>执行即消耗</b>——被中断的尝试同样占额度），与拒绝判定、终态投影及
 * {@code node.started}/{@code node.output} 事件的 runCount/maxRuns 水位（A3 可观测性）
 * 同一口径。
 *
 * <p><b>完成判定</b>：无运行中节点、无挂起决策、无<b>非 join 滞留</b>的未消费激活（此时
 * 剩余 pending 节点均为不可达死分支——不再阻塞实例收束；joinMode=all 未等齐的滞留激活
 * 同样不阻塞——整图静默时未齐 join 节点静默跳过，与死分支同语义）。
 *
 * <p><b>边数据传递</b>：边激活时写运行时变量（assigns=源节点输出→具名变量；边 input=
 * 模板渲染→变量，缺省名 input）——校验-回指重试等场景把输出/路径指令传递给下游
 * （命令与 AI 节点经 {@code {{var}}} 同机制消费）。写入在 schedLock 内先于下游调度
 * （先赋值后渲染，本边 assigns 可被本边模板引用）；变量解析优先级：边写入值 &gt;
 * 用户输入 &gt; 声明默认值；rerun 从 events.jsonl 重放恢复（详见
 * {@link #restoreRuntimeVars}）。
 *
 * <p><b>状态机</b>：created/running/waiting-user/done/failed/cancelled；迁移点原子重写
 * meta.json（终态投影 nodeStates）。worker 重启扫描标 failed 已在 store 层（步骤 4）。
 *
 * <p><b>异常契约</b>：InterruptedException（含流取消）→ 取消路径；ModelCallException/其他 →
 * 节点失败 → 错误策略。agent 终态由 claimTerminal CAS 收口不重复（与 advisor 链双通道）。
 *
 * <p><b>错误策略 fail-fast（§7.20）</b>：独立督导 agent（SupervisorRunner 模型会话）已删除
 * ——任务内运行失败即 fail-fast（supervisor 就是主 agent：错误上下文经 workflow_run 工具
 * 结果回传，由主 agent 判断后可用 workflow_rerun 从中断节点续跑）；OnError.SUPERVISOR
 * 枚举值仅为旧定义快照反序列化兼容保留，运行语义=fail。retry/retryWithInput 决策内核
 * 保留（Decision/applyDecision，供 standalone askUser 挂起的 decide RPC 与 rerun 复用）。
 *
 * <p>引擎不阻塞 RPC 线程：{@link #start} 异步开跑（调度器跑虚拟线程），本类即
 * workflow.run RPC 的后端入口。任务内运行经
 * {@link #start(InstanceMeta, ExecContext)} 传入 origin 任务 ExecContext（AI 节点
 * 装配与事件单写任务域的绑定源，§7.20.8）；无绑定的含 AI 节点定义在 start 即拒。
 * 失败实例的介入修复走 {@link #rerun}（failed→running、runNo 延续、从中断节点重开，
 * workflow_rerun 工具的后端入口）。
 */
public final class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    /** 长轮询内部轮询周期。 */
    private static final long POLL_INTERVAL_MS = 200;

    /** 终态收口时等待节点线程退出的单线程上限。 */
    private static final long JOIN_TIMEOUT_MS = 10_000;

    /** 边写入变量的单值上限（字符）——输出全文在 runs 文件留档，变量表与事件只承载截断值。 */
    private static final int MAX_VAR_VALUE_CHARS = 8_192;

    /** wf.node trace 的 wire kind（非 AI 节点过程呈现，经任务 emitter 落任务 EventLog，§7.20.8）。 */
    public static final String WF_NODE_TRACE_KIND = "wf.node";

    /** wf.node trace 的四态 status（payload.status 槽位；前端按 data.interrupted/error/exitCode 推导同值）。 */
    private static final String TRACE_RUNNING = "running";
    private static final String TRACE_DONE = "done";
    private static final String TRACE_FAILED = "failed";
    /** 实例终态收口中断的在飞运行（非节点自身失败；data.interrupted=true 标记）。 */
    private static final String TRACE_CANCELLED = "cancelled";

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
     * （{@link WorkflowRuntime#taskExec()}），AI 节点经它装配 agent 与单写任务事件流。
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

    /**
     * 失败实例从节点重开（workflow_rerun 工具的引擎内核，§7.20）：failed → running
     * （meta 原子重写 + wf.status 事件），调度目标节点重跑；阻塞等待在工具层
     * （{@link #awaitTerminalMeta} 同 run）。
     *
     * <p><b>运行态重建</b>：从磁盘 meta + definitionSnapshot 构造新 InstanceRun
     * （nodeStates/outEdges 从快照重建；runCounter 从 meta.runCounter() 恢复 → runNo
     * 延续，runs 文件名与 events.jsonl 追加续号；meta 残留 pendingDecision/error 不带入）。
     * 节点状态经 {@link #restoreNodeStates}：runCount 延续、done/skipped 保持不重跑、
     * 被失败中断的 running 节点重置 pending 但不激活。
     *
     * <p><b>目标节点重开 = 决策级授权</b>：走 {@link #forceSchedule}（与 decide 的 retry
     * 同源——绕过 maxRuns 上限；否则默认 maxRuns=1 的失败节点一次失败即永锁，重开形同
     * 虚设）。runCount 不清零：重开运行期内的后续调度（环回/再激活）仍受 maxRuns 约束，
     * 防无限循环；反复重开由调用方（主 agent 显式工具调用）自律，与督导删除后「决策即
     * 授权」的口径一致。
     *
     * <p><b>input 替换持久化</b>：input 非空时先 {@link #replaceNodeInput}（内存快照换
     * 引用），随后 failed→running 迁移经 transitionLocked 以 r.snapshot 原子重写 meta.json
     * ——节点重跑与后续再 rerun 读到的都是新输入（不持久化则重建态读回旧输入）。
     *
     * @param nodeId   重开节点（缺省=meta.error.nodeId 失败节点；可指定任意节点重开）
     * @param input    替换该节点输入（null/空白=不替换；替换 AI 节点 input，retryWithInput 语义）
     * @param taskExec origin 任务绑定（守卫与 start 同款：与 meta.originTaskId 一致；含 AI
     *                 节点时必须可用。无 originTaskId 的纯命令流允许 null）
     * @return 已迁移为 running 的实例元数据
     * @throws NotFoundException  实例不存在
     * @throws BadParamsException 非 failed 态 / 已在运行 / 缺快照 / 工作区不在注册表 /
     *                           无法确定重开节点 / 节点不在快照 / 绑定不一致或不可用
     */
    public InstanceMeta rerun(String instanceId, String nodeId, String input, ExecContext taskExec) {
        InstanceMeta meta = store.findInstance(instanceId)
                .orElseThrow(() -> new NotFoundException("实例不存在: " + instanceId));
        if (meta.status() != InstanceStatus.FAILED) {
            throw new BadParamsException("实例不可重跑(当前状态 " + statusWire(meta.status())
                    + ",仅 failed 实例可从中断节点续跑): " + instanceId);
        }
        if (runs.containsKey(instanceId)) {
            throw new BadParamsException("实例已在运行: " + instanceId);
        }
        Definition snapshot = meta.definitionSnapshot();
        if (snapshot == null || snapshot.nodesOrEmpty().isEmpty()) {
            throw new BadParamsException("实例缺定义快照: " + instanceId);
        }
        String root = resolveWorkspaceRoot(meta.workspaceId());
        if (root == null) {
            throw new BadParamsException("工作区不在注册表(无法解析 workspaceRoot): " + meta.workspaceId());
        }
        String targetId = nodeId != null && !nodeId.isBlank() ? nodeId.trim() : null;
        if (targetId == null && meta.error() != null) {
            targetId = meta.error().nodeId();
        }
        if (targetId == null) {
            throw new BadParamsException("无法确定重开节点(实例无失败节点记录),请显式指定 nodeId: " + instanceId);
        }
        NodeDef target = nodeOf(snapshot, targetId);
        if (target == null) {
            throw new BadParamsException("重开节点不在定义快照中: " + targetId);
        }
        if (input != null && !input.isBlank() && NodeDef.TYPE_COMMAND.equals(target.type())) {
            // BUG-02 修复：命令节点输入=command 本体（文档语义不变），但静默丢弃替换
            // 会让调用方白等一次注定失败的续跑——显式拒绝并指路
            throw new BadParamsException("命令节点的输入为 command 本体,不支持 input 替换;"
                    + "请改用 workflow_update 修改定义后重跑: " + targetId);
        }
        if (taskExec != null && meta.originTaskId() != null
                && !meta.originTaskId().equals(taskExec.subjectId())) {
            throw new BadParamsException("originTaskId 与绑定的任务不一致(meta=" + meta.originTaskId()
                    + ", 绑定=" + taskExec.subjectId() + "): " + instanceId);
        }
        if (containsAiNode(snapshot) && !NodeRunners.usableTaskBinding(taskExec)) {
            throw new BadParamsException("工作流含 AI 节点,拒绝无任务绑定重跑: " + instanceId
                    + "。" + NodeRunners.NO_TASK_BINDING_MESSAGE);
        }
        InstanceRun r = new InstanceRun(meta, snapshot, root, taskExec);
        InstanceRun prev = runs.putIfAbsent(instanceId, r);
        if (prev != null) {
            throw new BadParamsException("实例已在运行: " + instanceId);
        }
        r.resumedAt = System.currentTimeMillis();
        log.info("[workflow] 实例重跑 id={} 定义={} 重开节点={} 替换输入={} originTask={} 绑定任务={}",
                instanceId, meta.definitionId(), targetId, input == null || input.isBlank() ? "-" : "是",
                meta.originTaskId(), taskExec == null ? "-" : taskExec.subjectId());
        synchronized (r.schedLock) {
            restoreNodeStates(r, meta);
            restoreRuntimeVars(r, meta);
            if (input != null && !input.isBlank()) {
                replaceNodeInput(r, target, input);
            }
            // failed→running：meta 重写清空 error/pendingDecision + 替换后快照落盘 + wf.status 事件
            transitionLocked(r, InstanceStatus.RUNNING, null, null, null);
            forceSchedule(r, target);
        }
        return r.meta;
    }

    /**
     * 失败实例的节点状态重建（rerun 专用，schedLock 内调用）：runCount 从终态投影延续
     * （maxRuns 预算跨 rerun 不重置——防无限循环；目标节点经决策级授权绕上限重开）；
     * done/skipped 保持终态不重跑；cancelled（实例失败时被中断的并行节点——中断尝试已在
     * startNodeRun 计入 runCount，执行即消耗）与 failed 重置 pending 但<b>不激活</b>
     * （不在失败路径上，等待未来激活）。投影缺失（worker 重启中断实例 nodeStates=null）
     * 回退按磁盘 runs 记录数恢复 runCount。
     */
    private void restoreNodeStates(InstanceRun r, InstanceMeta meta) {
        Map<String, InstanceMeta.NodeState> projection = meta.nodeStates();
        Map<String, Integer> fromRecords = null;
        for (Map.Entry<String, NodeState> e : r.nodeStates.entrySet()) {
            String nodeId = e.getKey();
            NodeState st = e.getValue();
            InstanceMeta.NodeState p = projection == null ? null : projection.get(nodeId);
            if (p != null) {
                st.runCount = p.runCount() == null ? 0 : p.runCount();
                String s = p.status();
                st.status = "done".equals(s) || "skipped".equals(s) ? s : "pending";
            } else {
                if (fromRecords == null) {
                    fromRecords = new LinkedHashMap<>();
                    for (RunRecord rec : store.listRunRecords(r.wsId, r.instanceId)) {
                        if (rec.nodeId() != null) {
                            fromRecords.merge(rec.nodeId(), 1, Integer::sum);
                        }
                    }
                }
                st.runCount = fromRecords.getOrDefault(nodeId, 0);
                st.status = "pending";
            }
        }
    }

    /** 快照内查节点（id 精确匹配；无= null）。 */
    private static NodeDef nodeOf(Definition d, String nodeId) {
        for (NodeDef n : d.nodesOrEmpty()) {
            if (n != null && n.id().equals(nodeId)) {
                return n;
            }
        }
        return null;
    }

    /**
     * 运行时变量重放（rerun 专用，schedLock 内调用）：从 events.jsonl 按序折叠
     * {@code node.output}/{@code node.skipped} 的 {@code payload.vars} 恢复
     * {@link InstanceRun#runtimeVars}——done 节点不重跑，其 assigns 不会再触发，
     * 不重放则下游渲染读空。事件即完整事实源（追加写、seq 单调天然有序），
     * meta.json 不加字段；读失败按空表处理（warn 不抛——rerun 修复优先于变量完备）。
     */
    private void restoreRuntimeVars(InstanceRun r, InstanceMeta meta) {
        try {
            for (EventLine ev : store.readEvents(meta.workspaceId(), r.instanceId, 0, 0)) {
                if (!"node.output".equals(ev.event()) && !"node.skipped".equals(ev.event())) {
                    continue;
                }
                var vars = ev.payload() == null ? null : ev.payload().path("vars");
                if (vars == null || !vars.isObject() || vars.isEmpty()) {
                    continue;
                }
                for (Map.Entry<String, tools.jackson.databind.JsonNode> f : vars.properties()) {
                    String name = f.getKey();
                    String value = f.getValue().asString(null);
                    if (name != null && !name.isBlank() && value != null) {
                        r.runtimeVars.put(name, value);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("[workflow] 运行时变量重放失败(按空表处理) instance={}", r.instanceId, e);
        }
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

    /**
     * 实例终态等待口（workflow_run 工具的阻塞实现，§7.20）：可中断（虚拟线程上阻塞合法，
     * 任务停止的中断直达本等待），无忙轮询——实例终态收口（{@code finish}）时 latch
     * 放行。醒来后从 store 读终态 meta（finish 先写终态 meta 再注销运行态，故返回值恒为
     * 终态投影）。
     *
     * @return 终态 meta；超时仍在运行 → null；实例不存在 → null
     */
    public InstanceMeta awaitTerminalMeta(String instanceId, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        while (true) {
            InstanceRun r = runs.get(instanceId);
            if (r == null) {
                return store.findInstance(instanceId)
                        .filter(m -> m.status() != null && m.status().terminal())
                        .orElse(null);
            }
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                return null; // 仍在运行，本次等待窗口耗尽
            }
            r.terminalLatch.await(remain, TimeUnit.MILLISECONDS);
            // 醒来（终态/超时/虚假唤醒）→ 循环重读运行态
        }
    }

    // ══════════════════════════ 运行前校验（RPC 与 workflow_run 工具共用） ══════════════════════════

    /**
     * 运行前校验（workflow.run RPC 与 workflow_run 工具的<b>公共方法</b>，两处共用同一
     * 实现）：required 变量校验（缺→BAD_PARAMS 列出缺失名；默认值可满足 required——
     * 与渲染口径一致）+ 定义引用的模型配置存在性校验（AI 节点与督导模型全查，
     * 不存在→BAD_PARAMS 带定位；configId=null=用系统默认模型不在校验范围）。
     */
    public void validateForRun(Definition def, Map<String, String> variables) {
        List<String> missing = missingRequiredVariables(def, variables);
        if (!missing.isEmpty()) {
            throw new BadParamsException("缺少必填运行变量: " + String.join(", ", missing));
        }
        List<String> unknown = unknownModelConfigs(def);
        if (!unknown.isEmpty()) {
            throw new BadParamsException("模型配置不存在: " + String.join("; ", unknown));
        }
    }

    /** required 且运行变量既未提供也无默认值的名字（默认值可满足 required——渲染口径一致）。 */
    private static List<String> missingRequiredVariables(Definition def, Map<String, String> variables) {
        List<String> missing = new ArrayList<>();
        for (VariableDef v : def.variablesOrEmpty()) {
            if (v == null || v.name() == null || !Boolean.TRUE.equals(v.required())) {
                continue;
            }
            String provided = variables.get(v.name());
            if ((provided == null || provided.isEmpty())
                    && (v.defaultValue() == null || v.defaultValue().isEmpty())) {
                missing.add(v.name());
            }
        }
        return missing;
    }

    /**
     * 定义引用的模型配置存在性（经 {@code services.modelConfigs()}）：AI 节点的
     * modelConfigId、主 agent 配置与督导模型全查。configId=null=用系统默认模型，
     * 不在校验范围。引用处带定位（「节点名 → configId」逐项列出）。
     */
    private List<String> unknownModelConfigs(Definition def) {
        LinkedHashSet<String> known = new LinkedHashSet<>();
        for (ModelConfig c : services.modelConfigs()) {
            if (c != null && c.configId() != null) {
                known.add(c.configId());
            }
        }
        List<String> unknown = new ArrayList<>();
        LinkedHashSet<String> reported = new LinkedHashSet<>();
        if (def.agentConfigId() != null && !known.contains(def.agentConfigId())
                && reported.add(def.agentConfigId())) {
            unknown.add("主 agent → " + def.agentConfigId());
        }
        for (NodeDef n : def.nodesOrEmpty()) {
            if (n != null && n.modelConfigId() != null && !known.contains(n.modelConfigId())
                    && reported.add(n.modelConfigId())) {
                unknown.add("节点「" + (n.name() == null || n.name().isBlank() ? n.id() : n.name())
                        + "」→ " + n.modelConfigId());
            }
        }
        if (def.supervisor() != null && def.supervisor().modelConfigId() != null
                && !known.contains(def.supervisor().modelConfigId())
                && reported.add(def.supervisor().modelConfigId())) {
            unknown.add("督导 → " + def.supervisor().modelConfigId());
        }
        return unknown;
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
        /** 入边表（nodeId → 该节点的全部入边；joinMode=all 的等齐判定依据）。 */
        final Map<String, List<EdgeDef>> inEdges = new LinkedHashMap<>();
        final Map<String, EdgeDef> edgeById = new LinkedHashMap<>();
        final ConcurrentHashMap<String, NodeState> nodeStates = new ConcurrentHashMap<>();
        /** 节点未消费激活数（就绪=有激活；调度时全量消费）。 */
        final ConcurrentHashMap<String, Integer> pendingActivations = new ConcurrentHashMap<>();
        /** 同 agent 名 fair 锁（会话串行不变式 + per-agent jsonl 单写者假设）。 */
        final ConcurrentHashMap<String, ReentrantLock> agentLocks = new ConcurrentHashMap<>();
        /** 运行中节点线程（取消/失败收口时中断）。 */
        final ConcurrentHashMap<String, Thread> nodeThreads = new ConcurrentHashMap<>();
        /** runNo → 已落盘 RunRecord（终态后供失败上下文/rerun 取节点历史）。 */
        final ConcurrentHashMap<Integer, RunRecord> runRecords = new ConcurrentHashMap<>();
        /**
         * 边数据传递的运行时变量表（assigns + 边 input 写入；命令与 AI 节点的
         * command/input 模板经 {{var}} 消费）。schedLock 临界区内写、节点虚拟线程
         * 锁外渲染读 → ConcurrentHashMap；rerun 从 events.jsonl 的
         * node.output/node.skipped payload.vars 重放恢复（meta.json 不加字段）。
         */
        final ConcurrentHashMap<String, String> runtimeVars = new ConcurrentHashMap<>();
        /** 终态放行 latch（{@code finish} 收口尾 countDown；awaitTerminalMeta 的可中断等待点）。 */
        final CountDownLatch terminalLatch = new CountDownLatch(1);
        /** 最近一次 rerun 时刻（终态时折算 resumedDurationMs；null=从未续跑）。 */
        volatile Long resumedAt;
        /** nodeId:runNo → wf.node trace 固定 EmitEvent.id（started 生成、done/failed 同 id 重发 → 任务域 upsert 原地更新一行）。 */
        final ConcurrentHashMap<String, Long> traceIds = new ConcurrentHashMap<>();
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
                inEdges.put(n.id(), new ArrayList<>());
            }
            for (EdgeDef e : snapshot.edgesOrEmpty()) {
                edgeById.put(e.id(), e);
                outEdges.get(e.from()).add(e);
                inEdges.get(e.to()).add(e);
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

    /** 节点调度态：pending → running → done|skipped|cancelled（done/skipped 可被再次激活重回 running）。
     *  cancelled = 实例终态收口时被中断的在飞运行（非节点自身失败）。 */
    private static final class NodeState {
        volatile String status = "pending";
        /** 已消耗的执行次数（startNodeRun 开跑时 ++——执行即消耗，被中断的尝试同样计入；
         * schedLock 临界区内读写，拒绝判定/终态投影/事件水位共用此口径，完成路径不重复计）。 */
        int runCount;
        volatile int lastRunNo;
        /** 在飞真实运行的 runNo（0=无）——终态收口扫描判据；区别于「拒绝调度」置 running 的伪在飞。 */
        volatile int activeRunNo;
        /**
         * joinMode=all 的到达记账：本轮已激活到达的入边 id 集合（Set 天然对环内同一条边
         * 重复到达去重）。纯内存调度态，仅在 schedLock 临界区内读写（无需并发结构），
         * 与 {@link InstanceRun#pendingActivations} 同生命周期——restoreNodeStates
         * 不恢复（rerun 走快照重建，等齐从零开始）。
         */
        final Set<String> arrivedEdges = new LinkedHashSet<>();
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
        if (node != null && node.isJoinAll()) {
            // 汇合屏障：到达记账未覆盖全部入边 → 不消费、不启动（滞留等待其余分支；
            // 滞留计数留待完成判定按 join 滞留口径豁免）。无入边=恒等齐（等价 none）。
            if (!joinArrivedAll(r, nodeId, st)) {
                return;
            }
            // 等齐 → 清空到达记账触发本轮执行：回指循环下一轮（边重新激活）重新等齐；
            // 消耗的激活与 maxRuns 预算均按「触发执行一次」计（等齐一轮=1 次）
            st.arrivedEdges.clear();
        }
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
                    // 拒绝调度即失败留痕（虚拟 runNo=runCount+1，与本节点实跑同号时同 id 原地
                    // 翻转为 failed，异号时新 id 插行——id 契约统一按 nodeId:runNo）
                    if (node != null) {
                        emitNodeTrace(r, node, refusedRunNo, TRACE_FAILED, null, null, message,
                                List.of(), 0L, null);
                    }
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

    /**
     * 开跑一次节点执行（schedLock 内调用；消费激活、状态迁移与 runCount 计入在此完成）。
     * runCount 在此 <b>++</b>（执行即消耗）：拒绝判定 {@code runCount+1&gt;maxRuns} 与本计入
     * 同处 schedLock 临界区（先判后计，无竞态）；完成路径（completeNode/onNodeFailed）
     * 不再重复计入。计入值随 runNo 一并带出临界区，供 node.started/node.output 事件
     * 携带同值水位（同一运行 started 与 output 的 runCount 恒同值）。
     */
    private void startNodeRun(InstanceRun r, String nodeId, NodeState st) {
        st.status = "running";
        st.runCount++;
        int runNo = r.runCounter.incrementAndGet();
        st.lastRunNo = runNo;
        st.activeRunNo = runNo;
        int runCount = st.runCount;
        r.vt.execute(() -> executeNodeRun(r, nodeId, runNo, runCount));
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
            // 决策级授权重跑：到达记账同步清零（重跑后回指到达按新一轮等齐计）
            st.arrivedEdges.clear();
            startNodeRun(r, node.id(), st);
        }
    }

    /** 节点执行体（虚拟线程；runCount=本次运行开始后已消耗的执行次数，含本次——随
     * startNodeRun 的计入值带出，事件水位用）。 */
    private void executeNodeRun(InstanceRun r, String nodeId, int runNo, int runCount) {
        r.nodeThreads.put(nodeId, Thread.currentThread());
        long startedAt = System.currentTimeMillis();
        NodeDef node = r.node(nodeId);
        RenderedInput in = null;
        try {
            if (node == null) {
                failInstance(r, "节点不在定义快照中: " + nodeId, nodeId, runNo);
                return;
            }
            // 收口竞态护栏：finish 的中断/收口扫描可能早于本任务实际启动（nodeThreads
            // 快照未含未启动任务）——此时节点已按 cancelled 收口，不再发 starting 事件，
            // 避免把已翻转的 trace/投影盖回 running
            if (r.stopping || r.status.get().terminal()) {
                return;
            }
            in = renderInput(r, node);
            emitNodeStarted(r, node, runNo, runCount, in);
            emitNodeTrace(r, node, runNo, TRACE_RUNNING, in, null, null, List.of(), 0L, null);
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
            completeNode(r, node, runNo, runCount, result, in, startedAt);
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

    /**
     * 成功收口：写 runs 记录 + node.output 事件 + 出边一次性求值激活 + 下游调度 + 完成判定
     * （runCount 已在 startNodeRun 计入，收口不重复计——事件水位随 runCount 参数携带）。
     *
     * <p><b>命令节点非零退出码 = 节点失败</b>（BUG-01 修复）：节点状态置 failed、发
     * {@code node.error} 事件（失败上报/介入决策/rerun 的锚点——旧实现仅边路由按
     * exitCode 求值，状态与事件按成功口径，失败全链路静默）。失败后的两段分派
     * （A2 行为变更：<b>首败即分流，不做自动重试</b>——确定性失败重复到耗尽毫无意义，
     * 且 maxRuns 是节点终身额度，回指循环每轮消耗会让探针形同虚设；要重试要么经
     * onError/exitCode 出边显式建模，要么经错误策略的 retry 决策授权）：
     * ①有满足条件的出边（onError/exitCode/always）激活 → 分支接管（实例可正常收束，
     * 终态投影与 workflow_run 返回体携带 failed 告警）；②无人接住 → 定义级错误策略
     * （fail/askUser，在锁外执行）。
     */
    private void completeNode(InstanceRun r, NodeDef node, int runNo, int runCount,
            NodeResult result, RenderedInput in, long startedAt) {
        List<String> activated = new ArrayList<>();
        boolean failed = result.exitCode() != 0;
        boolean errorPolicy = false;
        synchronized (r.schedLock) {
            if (r.stopping || r.status.get().terminal()) {
                return;
            }
            NodeState st = r.nodeStates.get(node.id());
            st.status = failed ? "failed" : "done";
            st.activeRunNo = 0;
            // runCount 已在 startNodeRun 开跑时计入（执行即消耗），收口不重复计
            for (EdgeDef e : r.outEdges.getOrDefault(node.id(), List.of())) {
                if (NodeRunners.evaluate(e.condition(), result.exitCode(), result.conditionOutput())) {
                    activated.add(e.id());
                }
            }
            // 边数据传递：先 assigns 后边 input 模板（done/failed 两条路径共用——失败被
            // 条件出边接住时报告沿 onError 边回传是校验-重试环的核心形态），全部写入
            // 完成后才激活/调度下游——下游渲染时值必已就绪
            Map<String, String> varsWritten = applyEdgeDataLocked(r, activated, result);
            RunRecord rec = buildRecord(r, node, runNo, startedAt, in, result, null, activated);
            r.runRecords.put(runNo, rec);
            store.saveRunRecord(r.wsId, r.instanceId, rec);
            emitNodeOutput(r, node, runNo, runCount, result, activated, varsWritten);
            if (failed) {
                emitNodeError(r, node.id(), display(node), runNo,
                        "命令退出码非零: " + result.exitCode(), result.exitCode());
            }
            emitNodeTrace(r, node, runNo, failed ? TRACE_FAILED : TRACE_DONE, in,
                    outputTextOf(result), null, activated,
                    System.currentTimeMillis() - startedAt, result.exitCode());
            if (!failed) {
                activateTargetsLocked(r, activated);
                // 自查重调：本节点运行期间新到的激活（环/多前驱）
                tryScheduleLocked(r, node.id());
                checkCompletionLocked(r);
            } else if (!activated.isEmpty()) {
                // ① 分支接管：失败沿条件分支继续（节点保持 failed）——首败即分流，
                // maxRuns 不作失败重试预算（仅约束调度次数：环内重跑仍受上限保护）
                activateTargetsLocked(r, activated);
                tryScheduleLocked(r, node.id());
                checkCompletionLocked(r);
            } else {
                // ② 无人接住：错误策略（askAndWait 含 park 等待，须在锁外执行）
                errorPolicy = true;
            }
        }
        if (errorPolicy) {
            handleNodeError(r, node, runNo, "命令退出码非零: " + result.exitCode());
        }
    }

    /**
     * 激活目标节点（schedLock 内调用；全激活语义=逐边独立判定后全部激活）。
     * joinMode=all 的目标：激活边 id 记入其到达记账（{@code arrivedEdges}，Set 对环内
     * 同一条边重复到达天然去重；pendingActivations 计数照旧——供完成判定的滞留检测）。
     */
    /**
     * 边数据传递写入端（schedLock 内调用，completeNode/skipNode 收口处；激活边按
     * 定义 edges 列表序处理）：①assigns——源节点输出（conditionOutput，截断
     * {@link #MAX_VAR_VALUE_CHARS}；全文在 runs 文件留档）写入运行时变量；②边 input
     * 模板——经「用户变量 + 本批已写运行时变量」渲染后写入指定变量（缺省名 input，
     * render 的不递归/防注入纪律原样适用）。两步分轮：全部 assigns 先落，再渲染模板
     * ——本边 assigns 可被本边模板引用；同源多边写同名变量按列表序后写覆盖（LWW，
     * 确定性可审查）。写入完成后才 activateTargetsLocked 调度下游，下游渲染时值必已
     * 就绪（节点执行在锁外虚拟线程，但调度派发先于赋值完成不可能发生）。
     *
     * <p>result=null（skip 决策路径）：被跳过节点无输出可读——assigns 跳过，边 input
     * 模板照常写入（模板仅引用变量，渲染语义自洽；「边激活即写」不变式统一）。
     *
     * @return 写入快照（node.output/node.skipped 事件 payload.vars 留档——rerun 重放
     *         的依据；空表=本批无写入）
     */
    private Map<String, String> applyEdgeDataLocked(InstanceRun r, List<String> activatedEdgeIds,
            NodeResult result) {
        if (activatedEdgeIds == null || activatedEdgeIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> written = new LinkedHashMap<>();
        for (String edgeId : activatedEdgeIds) {
            EdgeDef e = r.edgeById.get(edgeId);
            if (e == null || e.assigns() == null || result == null) {
                continue;
            }
            for (EdgeDef.EdgeAssign a : e.assigns()) {
                if (a == null || a.var() == null || a.var().isBlank()) {
                    continue;
                }
                // v1 唯一来源 output（未知 from 校验期已拒，运行时按 output 兜底）
                String output = result.conditionOutput() == null ? "" : result.conditionOutput();
                String value = truncate(output, MAX_VAR_VALUE_CHARS);
                r.runtimeVars.put(a.var(), value);
                written.put(a.var(), value);
            }
        }
        for (String edgeId : activatedEdgeIds) {
            EdgeDef e = r.edgeById.get(edgeId);
            if (e == null || e.input() == null || e.input().template() == null) {
                continue;
            }
            String var = e.input().effectiveVar();
            String rendered = NodeRunners.render(e.input().template(), mergedVars(r),
                    r.snapshot.variablesOrEmpty());
            r.runtimeVars.put(var, rendered);
            written.put(var, rendered);
        }
        return written;
    }

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
            NodeDef t = r.node(target);
            if (t == null || !t.isJoinAll()) {
                continue;
            }
            NodeState ts = r.nodeStates.get(target);
            if (ts == null) {
                continue;
            }
            for (String edgeId : activatedEdgeIds) {
                EdgeDef e = r.edgeById.get(edgeId);
                if (e != null && target.equals(e.to())) {
                    ts.arrivedEdges.add(edgeId);
                }
            }
        }
        for (String target : targets) {
            tryScheduleLocked(r, target);
        }
    }

    /** joinMode=all 的等齐判定：到达记账覆盖全部入边 id（无入边=恒等齐，等价 none）。 */
    private static boolean joinArrivedAll(InstanceRun r, String nodeId, NodeState st) {
        List<EdgeDef> ins = r.inEdges.get(nodeId);
        if (ins == null || ins.isEmpty()) {
            return true;
        }
        for (EdgeDef e : ins) {
            if (!st.arrivedEdges.contains(e.id())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 完成判定：无运行中节点 + 无挂起决策 + 无<b>非 join 滞留</b>的未消费激活 → done。
     *
     * <p><b>join 滞留不阻断收束（死分支语义对齐）</b>：joinMode=all 的节点等不齐入边
     * （部分分支失败被 onError 接走、或条件不达、或回指循环第二轮对端未重跑）时，其滞留
     * 激活与「不可达死分支」同性质——不应让实例永久悬挂。判定改为整图静默（无 running、
     * 无挂起决策、无非 join 滞留的未消费激活）即收束：未齐的 join 节点置 skipped 终态
     * （node.skipped 事件留痕）并清空滞留计数，然后继续收束为 done。仅从未执行过的
     * （pending）节点落 skipped——已执行过的（done/failed）保留原终态与 runCount 投影
     * （分支接管的 failed 告警语义不因滞留清理被覆盖），只清滞留计数。skipped 非
     * running，与 finish 的收口扫描（running→cancelled）不冲突。
     */
    private void checkCompletionLocked(InstanceRun r) {
        if (r.stopping || r.status.get().terminal() || r.pendingDecision != null) {
            return;
        }
        for (NodeState st : r.nodeStates.values()) {
            if ("running".equals(st.status)) {
                return;
            }
        }
        List<String> stranded = null; // 整图静默后仍未等齐的 join 节点
        for (Map.Entry<String, Integer> en : r.pendingActivations.entrySet()) {
            int pending = en.getValue() == null ? 0 : en.getValue();
            if (pending <= 0) {
                continue;
            }
            String nodeId = en.getKey();
            NodeDef node = r.node(nodeId);
            NodeState st = r.nodeStates.get(nodeId);
            if (node != null && node.isJoinAll() && st != null && !joinArrivedAll(r, nodeId, st)) {
                if (stranded == null) {
                    stranded = new ArrayList<>();
                }
                stranded.add(nodeId);
                continue;
            }
            return; // 真实未消费激活：图未静默
        }
        if (stranded != null) {
            for (String nodeId : stranded) {
                NodeState st = r.nodeStates.get(nodeId);
                NodeDef node = r.node(nodeId);
                // 滞留清理对一切未齐 join 生效；skipped 终态与留痕事件只落在从未执行过的节点
                st.arrivedEdges.clear();
                r.pendingActivations.put(nodeId, 0);
                if (!"pending".equals(st.status)) {
                    continue;
                }
                st.status = "skipped";
                store.appendEvent(r.wsId, r.instanceId, "node.skipped", Json.obj()
                        .put("nodeId", nodeId)
                        .put("nodeName", node == null ? nodeId : display(node))
                        .put("runNo", st.lastRunNo)
                        .put("message", "join 汇合未等齐(部分入边未到达),整图静默,节点静默跳过"));
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
                // runCount 已在 startNodeRun 开跑时计入（执行即消耗），失败收口不重复计
                st.activeRunNo = 0;
            }
            rec = buildRecord(r, node, runNo, startedAt, in, null,
                    new RunRecord.Error(truncate(message, 4000), null), List.of());
            r.runRecords.put(runNo, rec);
        }
        store.saveRunRecord(r.wsId, r.instanceId, rec);
        emitNodeError(r, node.id(), display(node), runNo, message, null);
        emitNodeTrace(r, node, runNo, TRACE_FAILED, in, null, message, List.of(),
                Math.max(0, System.currentTimeMillis() - startedAt), null);
        handleNodeError(r, node, runNo, message);
    }

    // ══════════════════════════ 错误策略 ══════════════════════════

    /**
     * 错误策略分派：fail（实例失败）/ askUser（挂起等用户）。
     * <b>fail-fast（§7.20）</b>：SUPERVISOR（含缺省）语义=FAIL——独立督导 agent 已删除，
     * 任务内运行的「supervisor」就是主 agent（错误上下文经 workflow_run 工具结果回传）；
     * 枚举值仅为旧定义快照反序列化兼容保留。
     */
    private void handleNodeError(InstanceRun r, NodeDef node, int runNo, String message) {
        if (r.stopping || r.status.get().terminal()) {
            return;
        }
        var onError = r.snapshot.onError();
        try {
            switch (onError == null ? dev.everyagent.plugin.workflow.model.OnError.SUPERVISOR : onError) {
                case FAIL, SUPERVISOR -> failInstance(r, "节点「" + display(node) + "」失败: " + message,
                        node.id(), runNo);
                case ASK_USER -> askAndWait(r, node, runNo, message);
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

    /** skip：节点终态 skipped + 全部出边无条件激活（「跳过该节点继续下游」）。
     * 边数据传递同「边激活即写」不变式：assigns 无输出可写=跳过（result 传 null），
     * 边 input 模板照常渲染写入——下游拿到的指令材料不因跳过而缺席。 */
    private void skipNode(InstanceRun r, NodeDef node, int runNo) {
        synchronized (r.schedLock) {
            if (r.stopping || r.status.get().terminal()) {
                return;
            }
            NodeState st = r.nodeStates.get(node.id());
            if (st != null) {
                st.status = "skipped";
            }
            // 被跳过节点无输出,条件求值无意义——全部出边激活
            List<String> all = new ArrayList<>();
            for (EdgeDef e : r.outEdges.getOrDefault(node.id(), List.of())) {
                all.add(e.id());
            }
            Map<String, String> varsWritten = applyEdgeDataLocked(r, all, null);
            var payload = Json.obj()
                    .put("nodeId", node.id()).put("runNo", runNo);
            if (!varsWritten.isEmpty()) {
                var vars = Json.obj();
                varsWritten.forEach(vars::put);
                payload.set("vars", vars);
            }
            store.appendEvent(r.wsId, r.instanceId, "node.skipped", payload);
            activateTargetsLocked(r, all);
            tryScheduleLocked(r, node.id());
            checkCompletionLocked(r);
        }
    }

    // ══════════════════════════ 实例收口 ══════════════════════════

    private void failInstance(InstanceRun r, String message, String nodeId, int runNo) {
        // 失败节点投影标 failed（终态快照可读）；其余在飞节点不在此处理——由 finish 的
        // 收口扫描统一置 cancelled（终态投影零 running，见 finish 内注释）
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
            // 在飞节点收口扫描：被中断的节点线程按 stopping 静默退场（onNodeInterrupted/
            // onNodeFailed 的收口短路），若不在此统一补终态，终态投影将永久残留 running
            // ——已终态实例的节点在实例页/任务页/列表投影三处永远显示「运行中」。
            // 真实在飞（activeRunNo≠0）→ cancelled + node.interrupted 关闭事件 + trace 翻转
            // （runs 记录不补写：中断运行无完整输入输出，事件侧 live 行已如实呈现）；
            // 伪在飞（拒绝调度/错误策略窗口被收口打断）仅置 cancelled 不发事件——
            // 其错误留痕由未被打断的正常路径或实例级 error 携带。
            for (Map.Entry<String, NodeState> en : r.nodeStates.entrySet()) {
                NodeState st = en.getValue();
                if (!"running".equals(st.status)) {
                    continue;
                }
                st.status = "cancelled";
                String nodeId = en.getKey();
                int runNo = st.activeRunNo;
                if (runNo == 0) {
                    continue;
                }
                NodeDef node = r.node(nodeId);
                String message = "实例终态收口(" + statusWire(terminal) + "),节点运行被中断";
                store.appendEvent(r.wsId, r.instanceId, "node.interrupted", Json.obj()
                        .put("nodeId", nodeId)
                        .put("nodeName", node == null ? nodeId : display(node))
                        .put("runNo", runNo)
                        .put("message", message));
                if (node != null) {
                    emitNodeTrace(r, node, runNo, TRACE_CANCELLED, null, null, message,
                            List.of(), 0L, null);
                }
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
        r.terminalLatch.countDown();
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
        Long resumedDurationMs = to.terminal() && r.resumedAt != null ? now - r.resumedAt : null;
        Map<String, InstanceMeta.NodeState> projection = to.terminal() ? projectNodeStates(r)
                : m.nodeStates();
        InstanceMeta updated = new InstanceMeta(
                InstanceMeta.SCHEMA_VERSION, m.id(), m.definitionId(), m.definitionName(),
                m.workspaceId(), m.originTaskId(), r.snapshot, m.variablesOrEmpty(), to, m.createdAt(),
                startedAt, endedAt, durationMs, resumedDurationMs, error, pd, projection,
                r.runCounter.get());
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
        Map<String, String> vars = mergedVars(r);
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

    /**
     * 节点渲染的变量解析集：用户输入变量 ∪ 运行时边写入变量（边写入值覆盖同名用户值
     * ——校验器已禁同名，覆盖仅防御；{@link NodeRunners#resolveVariable} 的优先级
     * 链 = 本表值 &gt; 声明默认值）。空表时零拷贝直返 meta 视图（旧定义热路径不变）。
     */
    private static Map<String, String> mergedVars(InstanceRun r) {
        Map<String, String> user = r.meta.variablesOrEmpty();
        if (r.runtimeVars.isEmpty()) {
            return user;
        }
        Map<String, String> merged = new LinkedHashMap<>(user);
        merged.putAll(r.runtimeVars);
        return merged;
    }

    private record RenderedInput(String raw, String rendered, String visibleToAi, String preview) {
    }

    private RunRecord buildRecord(InstanceRun r, NodeDef node, int runNo, long startedAt,
            RenderedInput in, NodeResult result, RunRecord.Error error, List<String> activated) {
        long endedAt = System.currentTimeMillis();
        RunRecord.RunInput input = in == null || in.raw() == null ? null
                : new RunRecord.RunInput(in.raw(), in.rendered(), in.visibleToAi());
        // supervisor 介入链已随督导机制删除（RunRecord.supervisor 字段仅为旧 runs 文件
        // 反序列化兼容保留，恒 null——ModelJsonRoundTrip 兼容测试照旧过）
        return new RunRecord(runNo, node.id(), node.name(), node.type(), startedAt, endedAt,
                endedAt - startedAt, input,
                result == null ? null : result.output(), activated,
                result == null ? null : result.agentTrace(), error, null);
    }

    private void replaceNodeInput(InstanceRun r, NodeDef node, String newInput) {
        List<NodeDef> nodes = new ArrayList<>();
        for (NodeDef n : r.snapshot.nodesOrEmpty()) {
            nodes.add(n.id().equals(node.id())
                    ? new NodeDef(n.id(), n.type(), n.name(), n.x(), n.y(), n.command(),
                            n.timeoutMs(), n.maxRuns(), n.agent(), n.modelConfigId(), newInput,
                            n.systemPrompt(), n.joinMode())
                    : n);
        }
        Definition old = r.snapshot;
        r.snapshot = new Definition(old.schemaVersion(), old.id(), old.name(), old.description(),
                old.workspaceId(), old.createdAt(), old.updatedAt(), old.variables(),
                old.agentConfigId(), old.onError(),
                old.supervisor(), nodes, old.edges());
    }

    // ══════════════════════════ 事件发射 ══════════════════════════

    /**
     * node.started 事件：基础字段 + <b>运行水位</b>（A3 可观测性）——runCount=本次运行
     * 开始后该节点已消耗的执行次数（含本次，执行即消耗口径），maxRuns=节点额度（缺省 1）。
     * 循环预算从此运行时可观测（第几次/还剩几次），不再全靠试错。
     */
    private void emitNodeStarted(InstanceRun r, NodeDef node, int runNo, int runCount,
            RenderedInput in) {
        var payload = Json.obj()
                .put("nodeId", node.id())
                .put("nodeName", display(node))
                .put("runNo", runNo)
                .put("runCount", runCount)
                .put("maxRuns", node.maxRuns() == null ? 1 : node.maxRuns());
        if (node.agent() != null && !node.agent().isBlank()) {
            payload.put("agent", node.agent());
        }
        if (in != null && in.preview() != null) {
            payload.put("inputPreview", truncate(in.preview(), 1024));
        }
        store.appendEvent(r.wsId, r.instanceId, "node.started", payload);
    }

    /**
     * node.output 事件：基础字段 + <b>运行水位</b>——runCount 与本次 node.started 同值
     * （同一次运行的收口），maxRuns=节点额度（缺省 1）：失败后仅凭末条事件即可诊断
     * 「这是第几次/还剩几次」（node.error 本身不带水位，水位由收口前的 started/output 携带）。
     * 边数据传递时另携 {@code vars}（本次激活边写入的完整变量快照——rerun 重放依据，
     * 值已按 {@link #MAX_VAR_VALUE_CHARS} 截断）。
     */
    private void emitNodeOutput(InstanceRun r, NodeDef node, int runNo, int runCount,
            NodeResult result, List<String> activated, Map<String, String> varsWritten) {
        var payload = Json.obj()
                .put("nodeId", node.id())
                .put("runNo", runNo)
                .put("runCount", runCount)
                .put("maxRuns", node.maxRuns() == null ? 1 : node.maxRuns())
                .put("exitCode", result.exitCode())
                .put("outputPreview", truncate(result.conditionOutput(), 1024))
                .set("activatedEdges", Json.toJson(activated));
        if (varsWritten != null && !varsWritten.isEmpty()) {
            var vars = Json.obj();
            varsWritten.forEach(vars::put);
            payload.set("vars", vars);
        }
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

    // ══════════════════════════ wf.node 任务 trace ══════════════════════════

    /**
     * wf.node trace 发射（非 AI 节点过程呈现，经<b>任务 emitter</b> 落任务 EventLog，
     * §7.20.8 契约）：persist + REPLACE，每次节点运行<b>固定 EmitEvent.id</b>
     * （{@link InstanceRun#traceIds}：started 生成、done/failed 同 id 重发 → 前端
     * upsertTraceBySeq 原地更新一行）；data 携带 input/output/error <b>全量</b>
     * （截断留给前端展示层）。agentId 留 null（任务层兜底 mainAgentId——trace 归主线，
     * 与工具结果同视图）。
     *
     * <p>发射失败仅告警不阻断节点执行（与 {@code NodeRunners.emitInstruction} 同纪律）；
     * 无任务绑定（taskExec/emitter 缺失）跳过——非 AI 节点允许无绑定运行，此时无
     * trace 是预期。仅命令节点发射：start 无过程；AI 节点不发（其过程=子 agent 会话
     * 条目，任务页已完整可见）。
     */
    private void emitNodeTrace(InstanceRun r, NodeDef node, int runNo, String status,
            RenderedInput in, String outputText, String error, List<String> activatedEdges,
            long durationMs, Integer exitCode) {
        if (node == null || !NodeDef.TYPE_COMMAND.equals(node.type())) {
            return;
        }
        ExecContext taskExec = r.runtime.taskExec();
        if (taskExec == null || taskExec.emitter() == null) {
            return;
        }
        var data = Json.obj()
                .put("instanceId", r.instanceId)
                .put("definitionName",
                        r.meta.definitionName() == null ? "" : r.meta.definitionName())
                .put("nodeId", node.id())
                .put("nodeName", display(node))
                .put("nodeType", node.type() == null ? "" : node.type())
                .put("runNo", runNo)
                .put("input", in == null || in.rendered() == null ? "" : in.rendered())
                .put("output", outputText == null ? "" : outputText)
                .put("error", error == null ? "" : error)
                .put("durationMs", durationMs);
        if (exitCode != null) {
            data.put("exitCode", exitCode);
        }
        data.set("activatedEdges", Json.toJson(activatedEdges == null ? List.of() : activatedEdges));
        // cancelled（收口中断）打 interrupted 标记——前端 trace 卡片无法读 payload.status，
        // 依 data.interrupted 优先于 error/exitCode 判定「已中断」，与 failed（自身失败）区分
        if (TRACE_CANCELLED.equals(status)) {
            data.put("interrupted", true);
        }
        // content 承载输出/错误文本（done/failed/cancelled）；running 留空（折叠行由 summary 表达）
        String content = TRACE_DONE.equals(status) ? (outputText == null ? "" : outputText)
                : TRACE_FAILED.equals(status) || TRACE_CANCELLED.equals(status)
                        ? (error == null ? "" : error) : null;
        long id = r.traceIds.computeIfAbsent(node.id() + ":" + runNo, k -> SnowflakeId.next());
        try {
            taskExec.emitter().emit(EmitEvent.of(id, WF_NODE_TRACE_KIND, null,
                    display(node), traceSummary(node.type(), status, durationMs),
                    content, status, data, EmitEvent.Mode.REPLACE));
        } catch (RuntimeException e) {
            log.warn("[workflow] wf.node trace 发射失败(自吞) instance={} node={} runNo={}",
                    r.instanceId, node.id(), runNo, e);
        }
    }

    /** 节点结果 → trace 全量输出文本（命令=stdout+stderr；AI=text——现仅命令节点走到）。 */
    private static String outputTextOf(NodeResult result) {
        if (result == null || result.output() == null) {
            return "";
        }
        RunRecord.RunOutput o = result.output();
        if (o.text() != null) {
            return o.text();
        }
        return (o.stdout() == null ? "" : o.stdout()) + (o.stderr() == null ? "" : o.stderr());
    }

    /** trace summary：{@code <节点类型> · <状态> · <耗时>}（运行中/零耗时省略耗时段）。 */
    private static String traceSummary(String nodeType, String status, long durationMs) {
        String type = switch (nodeType == null ? "" : nodeType) {
            case NodeDef.TYPE_COMMAND -> "命令";
            case NodeDef.TYPE_AI -> "AI";
            case NodeDef.TYPE_START -> "开始";
            default -> nodeType == null ? "节点" : nodeType;
        };
        String label = switch (status) {
            case TRACE_RUNNING -> "运行中";
            case TRACE_DONE -> "完成";
            case TRACE_FAILED -> "失败";
            case TRACE_CANCELLED -> "已中断";
            default -> status;
        };
        return durationMs > 0
                ? type + " · " + label + " · " + formatDuration(durationMs)
                : type + " · " + label;
    }

    /** 毫秒人性化：&lt;1s 原样 ms；&lt;60s 一位小数秒；更长 分秒。 */
    private static String formatDuration(long ms) {
        if (ms < 1000) {
            return ms + "ms";
        }
        if (ms < 60_000) {
            return String.format(java.util.Locale.ROOT, "%.1fs", ms / 1000.0);
        }
        return ms / 60_000 + "分" + (ms % 60_000) / 1000 + "秒";
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
        return new NodeDef(nodeId, "unknown", nodeId, null, null, null, null, null, null, null, null, null, null);
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
}
