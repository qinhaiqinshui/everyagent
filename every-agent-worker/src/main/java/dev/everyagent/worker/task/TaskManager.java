package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.EventRecord;
import dev.everyagent.plugin.api.util.RootCause;
import dev.everyagent.contract.rpc.Rpc;
import dev.everyagent.plugin.api.event.EventLogReader;
import dev.everyagent.worker.agent.AgentBuilder;
import dev.everyagent.worker.agent.AgentEntity;
import dev.everyagent.worker.agent.AgentFactoryImpl;
import dev.everyagent.worker.agent.AgentRunner;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.config.ChatModelFactory;
import dev.everyagent.worker.hub.EventSink;
import dev.everyagent.worker.modules.ConfigStore;
import dev.everyagent.worker.modules.ConfigStore.ResolvedConfig;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.modules.WorkspaceCascadePort;
import dev.everyagent.plugin.api.event.Channels;
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.plugin.api.proto.ShortIds;
import dev.everyagent.worker.proto.TaskDtos.TaskStatus;
import dev.everyagent.worker.rpc.RpcContext;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.ship.TaskInputHandler;
import dev.everyagent.worker.interaction.EmitterLookup;
import dev.everyagent.worker.interaction.InteractionServiceImpl;
import dev.everyagent.worker.plugin.registry.TaskAdmissionPolicyRegistry;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.worker.plugin.registry.TaskLifecycleRegistry;
import dev.everyagent.worker.task.lifecycle.IdempotencyCheckNode;
import dev.everyagent.worker.task.lifecycle.ResponseAckNode;
import dev.everyagent.worker.task.lifecycle.TaskEntryCreateNode;
import dev.everyagent.worker.task.lifecycle.TaskIdGenerateNode;
import dev.everyagent.worker.task.lifecycle.TaskLifecycleContextFactory;
import dev.everyagent.worker.task.lifecycle.TaskLifecycleContextImpl;
import dev.everyagent.worker.task.lifecycle.TaskLifecycleExecutor;
import dev.everyagent.worker.task.lifecycle.ThreadSubmitNode;
import dev.everyagent.worker.task.lifecycle.WorkspaceResolveNode;
import dev.everyagent.plugin.api.task.AdmissionResult;
import dev.everyagent.plugin.api.task.TaskKernel;
import dev.everyagent.plugin.api.task.TaskOutcome;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务管理器(架构 §5):run/cancel/input/sync/delete 全闭环。
 * 生命周期纪律:agent 运行完成即销毁——finish 全程持任务监视器做
 * flush→updateMeta→写索引→untrack 双件套→tasks.remove,TaskEntry 连同 EventLog 丢弃;
 * 内存仅留磁盘路由索引(diskTasks,~150B/任务)。任务永久保留,用户主动 delete 是唯一删除路径。
 * 终态任务收到 task.input = 冷启动再运行(ConversationLoader 从磁盘重建上下文,一次普通运行)。
 * 多 hub:任务不做 owner 隔离;任务事件经 EventSink.fanout 扇出到全部连接的 tasks 频道。
 */
@Component
public class TaskManager implements TaskInputHandler, InteractionServiceImpl.StatusHook, WorkspaceCascadePort, EmitterLookup {

    private static final Logger log = LoggerFactory.getLogger(TaskManager.class);
    private static final long IDEM_WINDOW_MS = 600_000;
    /** task.rounds 惰性全量生成/未闭合轮扫描的单次窗口上限(记录数;EventLog 内存护栏 50 万,同量级封顶)。 */
    private static final int ROUNDS_REBUILD_MAX = 500_000;
    /** taskId 查重重生成的最大尝试次数(连续冲突即失败,防御死循环)。 */
    private static final int MAX_TASKID_ATTEMPTS = 10_000;

    /** 事件出口(基础设施·通信):任务事件扇出已收敛到 fanout(替代 HubPool.pubAllTasks/pubTaskStream)。 */
    private final EventSink eventSink;
    private final AgentBuilder agentBuilder;
    private final AgentRunner runner;
    /** Agent 工厂实现:TaskEntry.agentFactory() 预绑定端口的裸依赖(S2,经 TaskEntryCreateNode 注入)。 */
    private final AgentFactoryImpl agentFactory;
    /** 模型配置解析(configs + modelFactory 用于主 agent 装配时解析模型)。 */
    private final ConfigStore configs;
    private final ChatModelFactory modelFactory;
    /** 创建/再运行准备路径:workspace 解析与模型配置解析已迁 TaskBootstrap(TaskEntry 构造前的动作)。 */
    private final TaskBootstrap taskBootstrap;
    private final InteractionServiceImpl asks;
    private final WorkerProperties props;
    private final RpcDispatcher dispatcher;
    private final TaskStore store;
    private final RoundIndexStore roundIndexStore;
    /** 任务生命周期上下文工厂:聚合 gate/roundIndexStore/store 组装上下文(gate 不再由本类持有)。 */
    private final TaskLifecycleContextFactory lifecycleContextFactory;
    private final TaskLifecycleExecutor lifecycleExecutor;
    private final TaskLifecycleRegistry lifecycleRegistry;
    /** 任务准入策略注册表：队列插件注册后接管并发上限检查（always-admit → 排队）。 */
    private final TaskAdmissionPolicyRegistry admissionPolicyRegistry;

    /** 热任务(运行中驻留内存;finish 即驱逐)。 */
    private final Map<String, TaskEntry> tasks = new ConcurrentHashMap<>();
    /** 磁盘任务路由索引:taskId → {dir, summary}(永久保留;delete 认领/移除)。 */
    private final Map<String, TaskStore.StoredTask> diskTasks = new ConcurrentHashMap<>();
    private final Map<String, IdemEntry> idem = new ConcurrentHashMap<>();
    private final AtomicInteger active = new AtomicInteger();
    private final java.util.concurrent.ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor();
    /** 再运行监听器(DataPusherManager 注册:新 TaskEntry 入表后唤醒该任务的定向推送器)。 */
    private final java.util.concurrent.CopyOnWriteArrayList<TaskResumeListener> resumeListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 幂等键条目（public 供 RPC 阶段节点引用）。 */
    public record IdemEntry(String taskId, long ts) {
    }

    /** 任务被再运行(新 TaskEntry 已入表)时回调:定向推送器立即换挂新日志(§5.6 实时推送)。 */
    public interface TaskResumeListener {
        void onTaskResumed(String taskId);
    }

    public TaskManager(EventSink eventSink, AgentBuilder agentBuilder, AgentRunner runner,
            AgentFactoryImpl agentFactory,
            ConfigStore configs, ChatModelFactory modelFactory,
            TaskBootstrap taskBootstrap, InteractionServiceImpl asks,
            WorkerProperties props, RpcDispatcher dispatcher,
            TaskStore store, RoundIndexStore roundIndexStore,
            TaskLifecycleContextFactory lifecycleContextFactory,
            TaskLifecycleExecutor lifecycleExecutor, TaskLifecycleRegistry lifecycleRegistry,
            TaskAdmissionPolicyRegistry admissionPolicyRegistry) {
        this.eventSink = eventSink;
        this.agentBuilder = agentBuilder;
        this.runner = runner;
        this.agentFactory = agentFactory;
        this.configs = configs;
        this.modelFactory = modelFactory;
        this.taskBootstrap = taskBootstrap;
        this.asks = asks;
        this.props = props;
        this.dispatcher = dispatcher;
        this.store = store;
        this.roundIndexStore = roundIndexStore;
        this.lifecycleContextFactory = lifecycleContextFactory;
        this.lifecycleExecutor = lifecycleExecutor;
        this.lifecycleRegistry = lifecycleRegistry;
        this.admissionPolicyRegistry = admissionPolicyRegistry;
    }

    @PostConstruct
    void init() {
        asks.setHook(this);
        // 输入路由已迁 TaskMessageRouter(HubPool.Listener → TaskInputHandler 三方法,本类实现之);
        // 任务事件广播经 EventSink.fanout(见 eventSink 字段),本类不再挂 HubPool 监听。
        registerMethods(dispatcher);
        recoverFromDisk();
        registerLifecycleNodes();
    }

    /**
     * 注册 RPC 阶段生命周期节点（order 10~80）。
     * 这些节点需要 TaskManager 的内部 Map/Counter 引用，故在此注册而非 BuiltInTaskLifecycleNodes。
     */
    private void registerLifecycleNodes() {
        lifecycleRegistry.register(
                new IdempotencyCheckNode(idem, tasks), "worker");
        lifecycleRegistry.register(
                new WorkspaceResolveNode(taskBootstrap), "worker");
        lifecycleRegistry.register(
                new TaskIdGenerateNode(tasks, diskTasks, store), "worker");
        lifecycleRegistry.register(
                new TaskEntryCreateNode(taskBootstrap, props, tasks, diskTasks, active, store, idem,
                        admissionPolicyRegistry, agentFactory, asks), "worker");
        lifecycleRegistry.register(
                new ResponseAckNode(eventSink), "worker");
        lifecycleRegistry.register(
                new ThreadSubmitNode(vt, agentBuilder, configs, modelFactory, tasks, diskTasks, active, store, resumeListeners), "worker");
    }

    /**
     * 重启恢复:扫描全部用户目录构建磁盘索引;非终态标 failed("worker 重启中断")。
     * 不补发广播——前端 resync 走 tasks.list。任务永久保留,无 retention。
     */
    private void recoverFromDisk() {
        for (TaskStore.StoredTask st : store.scan()) {
            JsonNode s = st.summary();
            String status = s.path("status").asString("");
            boolean terminal = "done".equals(status) || "failed".equals(status)
                    || "cancelled".equals(status);
            if (!terminal) {
                try {
                    ObjectNode fixed = store.markRestartFailed(st, "worker 重启中断");
                    log.info("重启恢复:任务 {} 标为 failed(worker 重启中断)", st.taskId());
                    diskTasks.put(st.taskId(), new TaskStore.StoredTask(
                            st.taskId(), st.dir(), fixed, st.workspaceId()));
                    continue;
                } catch (java.io.IOException e) {
                    log.warn("重启恢复失败 task={}", st.taskId(), e);
                }
            }
            diskTasks.put(st.taskId(), st);
        }
    }

    // ---- TaskInputHandler:worker 输入频道消息(TaskMessageRouter 路由进来;ask.reply)----

    @Override
    public void onAskReply(JsonNode payload) {
        asks.resolve(payload.path("askId").asString(""),
                payload.path("answer").asString(""), "user");
    }

    // ---- RPC 方法注册 ----

    private void registerMethods(RpcDispatcher dispatcher) {
        dispatcher.register(RpcMethods.TASKS_LIST, this::rpcTasksList);
        dispatcher.register(RpcMethods.TASK_POLL, this::rpcTaskPoll);
        dispatcher.register(RpcMethods.TASK_ROUNDS, this::rpcTaskRounds);
        dispatcher.register(RpcMethods.TASK_ROUND_TAIL, this::rpcTaskRoundTail);
        dispatcher.register(RpcMethods.TASK_RUN, this::rpcTaskRun);
        dispatcher.register(RpcMethods.TASK_CANCEL, this::rpcTaskCancel);
        dispatcher.register(RpcMethods.TASK_DELETE, this::rpcTaskDelete);
        // config.get / config.reload / skill.reload 已迁 ConfigRpcHandler(基础设施·配置面,自行注册)。
    }

    /**
     * tasks.list:任务列表快照(内存运行中 + 磁盘索引合并,不做 owner 隔离)。
     * 参数:workspace(可选,过滤比 meta.workspace);limit(可选,>0 时分页大小,0/缺省=全量,向后兼容);
     * offset(可选,分页起始下标,默认 0);taskIds(可选,数组,定向拉取指定任务——前端懒加载单任务用,
     * 与 workspace 取交集,过滤后再分页)。
     * 排序:统一按「最近活跃」倒序(endedAt/startedAt/createdAt 兜底,与前端 TaskListEntry.updatedAt 口径一致),
     * 平局按 taskId 倒序 → 内存/磁盘混排也全局有序、分页稳定。
     * 应答:{tasks, total, hasMore}。
     */
    private void rpcTasksList(RpcContext ctx) {
        String wsFilter = ctx.optStrParam("workspace", "");
        long limit = Math.max(0, ctx.optLongParam("limit", 0));
        long offset = Math.max(0, ctx.optLongParam("offset", 0));
        java.util.Set<String> taskIds = null;
        JsonNode taskIdsNode = ctx.params().path("taskIds");
        if (taskIdsNode.isArray() && taskIdsNode.size() > 0) {
            taskIds = new java.util.HashSet<>();
            for (JsonNode id : taskIdsNode) {
                String v = id.asString("");
                if (!v.isEmpty()) {
                    taskIds.add(v);
                }
            }
            if (taskIds.isEmpty()) {
                taskIds = null;
            }
        }

        List<TaskEntry> sorted = new ArrayList<>(tasks.values());
        sorted.sort(Comparator.comparingLong((TaskEntry t) -> t.createdAt).reversed());
        List<TaskStore.StoredTask> disk = new ArrayList<>(diskTasks.values());
        disk.sort(Comparator.comparingLong(
                (TaskStore.StoredTask s) -> s.summary().path("createdAt").asLong(0)).reversed());
        // 内存优先(同 taskId 活任务赢),磁盘补充;createdAt 倒序合并
        java.util.LinkedHashMap<String, JsonNode> merged = new java.util.LinkedHashMap<>();
        for (TaskEntry t : sorted) {
            if (taskIds != null && !taskIds.contains(t.taskId)) {
                continue;
            }
            if (!wsFilter.isEmpty() && !wsFilter.equals(t.workspaceRoot)) {
                continue;
            }
            merged.put(t.taskId, t.runtimeSummaryJson()); // 内存行(队列概念已插件化)
        }
        for (TaskStore.StoredTask s : disk) {
            if (merged.containsKey(s.taskId())) {
                continue; // 热任务行已覆盖,磁盘行不参与
            }
            if (taskIds != null && !taskIds.contains(s.taskId())) {
                continue;
            }
            if (!wsFilter.isEmpty() && !wsFilter.equals(s.summary().path("workspace").asString(""))) {
                continue;
            }
            merged.put(s.taskId(), s.summary()); // 原样放行(共享引用,只读)
        }
        List<JsonNode> all = new ArrayList<>(merged.values());
        all.sort((a, b) -> {
            long ra = recency(a);
            long rb = recency(b);
            if (ra != rb) {
                return Long.compare(rb, ra);
            }
            return b.path("taskId").asString("").compareTo(a.path("taskId").asString(""));
        });
        long total = all.size();
        long from = Math.min(offset, total);
        long to = limit > 0 ? Math.min(from + limit, total) : total;
        ArrayNode arr = Json.arr();
        for (long i = from; i < to; i++) {
            arr.add(all.get((int) i));
        }
        ctx.ok(Json.obj()
                .set("tasks", arr)
                .put("total", total)
                .put("hasMore", to < total));
    }

    /** 任务「最近活跃」排序键:endedAt/startedAt/createdAt 兜底(与前端 TaskListEntry.updatedAt 口径一致)。 */
    private static long recency(JsonNode summary) {
        JsonNode ended = summary.path("endedAt");
        if (ended.isNumber()) {
            return ended.asLong();
        }
        JsonNode started = summary.path("startedAt");
        if (started.isNumber()) {
            return started.asLong();
        }
        return summary.path("createdAt").asLong(0);
    }

    /** task.poll 读取结果(按 seq 升序、截断后的批次 + hasMore 粗判)。 */
    private record MergedEvents(List<EventRecord> events, boolean hasMore) {
    }

    /** task.roundTail 截窗结果(seq 升序、同 seq 组不拆批;hasMore 见 cutTailWindow)。 */
    record TailWindow(List<EventRecord> events, boolean hasMore) {
    }

    /**
     * task.poll:任务流纯拉取(前端任务流 pull 模式的 worker 端服务)。
     * 参数:taskId 必填;mode(events/rounds,默认 events);afterSeq(增量游标);
     * beforeSeq(向前翻页游标);afterSeq+beforeSeq 同给 = (afterSeq, beforeSeq) 开区间
     * 查询(两端不含,区间内 seq 升序,超出 limit 从头部截断,以 lastSeq 推进 afterSeq
     * 续拉,供前端展开轮次一次拉全一轮);limit(默认 200,上限 500);
     * count(rounds 轮次数,默认 1);waitMs(events+afterSeq 且 beforeSeq==0 的
     * 长轮询等待毫秒;区间查询不挂起);roundId(events 模式可选:按轮稳定主键定位该轮区间,
     * 等价于 afterSeq=startSeq-1、beforeSeq=endSeq+1;endSeq 为空轮取到末尾。rounds 模式忽略)。
     * 数据读取:磁盘窗口 ∪ 内存尾部(同 seq 以内存为准),按 seq 归并升序。
     * 长轮询:仅 events 增量模式、初查为空、热任务非终态时挂起 EventLog 监听至 waitMs,
     * 唤醒后重读一次再应答。应答:先 ctx.data(批次),再 ctx.ok({hasMore,firstSeq,lastSeq,task:{status},live})。
     * 任务不做 owner 隔离(类注释 D17):只校验 taskId 存在,不存在 → NOT_FOUND。
     */
    private void rpcTaskPoll(RpcContext ctx) {
        String taskId = ctx.strParam("taskId");
        String mode = ctx.optStrParam("mode", "events");
        if (!"events".equals(mode) && !"rounds".equals(mode)) {
            ctx.err(Rpc.ERR_BAD_PARAMS, "mode 仅支持 events/rounds: " + mode);
            return;
        }
        String roundId = ctx.optStrParam("roundId", "");
        long afterSeq = ctx.optLongParam("afterSeq", 0);
        long beforeSeq = ctx.optLongParam("beforeSeq", 0);
        long waitMs = Math.max(0, ctx.optLongParam("waitMs", 0));
        int limit = (int) Math.max(1, Math.min(500, ctx.optLongParam("limit", 200)));
        int count = (int) Math.max(1, ctx.optLongParam("count", 1));
        // 存在性:目录在盘 或 内存任务/磁盘索引可见任一即存在(热任务 track 前目录可能未建,
        // 终态 finish 窗口内 tasks 仍驻留;三者全缺才算不存在/已删)
        boolean known = store.taskDirExists(taskId) || tasks.containsKey(taskId) || diskTasks.containsKey(taskId);
        if (log.isDebugEnabled()) {
            log.debug("[poll] task.poll known 判定 task={} dirExists={} inTasks={} inDiskTasks={}",
                    taskId, store.taskDirExists(taskId), tasks.containsKey(taskId),
                    diskTasks.containsKey(taskId));
        }
        if (!known) {
            ctx.err(Rpc.ERR_NOT_FOUND, "task 不存在: " + taskId);
            return;
        }
        Path dir = store.dirOf(taskId); // known → 已登记或已 scan,dirOf 不抛
        TaskEntry live = tasks.get(taskId);
        ObjectNode meta = live == null ? store.readMeta(dir) : null;
        String mainAgentId = live != null ? live.mainAgentId
                : (meta != null ? meta.path("mainAgentId").asString(null) : null);
        boolean rounds = "rounds".equals(mode);
        int effLimit = rounds ? 500 : limit;

        // roundId → 区间查询(仅 events 模式生效):按稳定主键定位该轮,等价于
        // afterSeq=startSeq-1、beforeSeq=endSeq+1(endSeq 为空轮取到末尾)。rounds 模式忽略。
        if (!rounds && !roundId.isEmpty()) {
            RoundIndex.Round target = null;
            for (RoundIndex.Round r : store.readRounds(dir)) {
                if (roundId.equals(r.roundId())) {
                    target = r;
                    break;
                }
            }
            if (target == null) {
                ctx.err(Rpc.ERR_BAD_PARAMS, "roundId 不存在: " + roundId);
                return;
            }
            // 下界取 max(调用方续拉游标, 轮起点-1):首拉 afterSeq=0 时落到轮起点;分批续拉时
            // 尊重调用方推进的 afterSeq,避免超长轮被重置回起点导致死循环/事件缺失。
            long startLower = target.startSeq() == Long.MIN_VALUE ? target.startSeq() : target.startSeq() - 1;
            afterSeq = Math.max(afterSeq, startLower);
            beforeSeq = target.endSeq() == null ? Long.MAX_VALUE
                    : (target.endSeq() == Long.MAX_VALUE ? Long.MAX_VALUE : target.endSeq() + 1);
        }

        MergedEvents m;
        try {
            m = readPollMerged(dir, live, mainAgentId, rounds, afterSeq, beforeSeq, count, effLimit);
            // 长轮询:仅 events 增量(afterSeq 语义,beforeSeq==0)、waitMs>0、初查为空、
            // 且热任务非终态——挂起等待新事件,超时放行后重读一次。
            boolean canWait = !rounds && beforeSeq == 0 && waitMs > 0 && m.events.isEmpty()
                    && live != null && !live.status.terminal();
            if (canWait) {
                CompletableFuture<Void> done = new CompletableFuture<>();
                EventLogReader.Listener l = () -> done.complete(null);
                live.log.addListener(l);
                try {
                    try {
                        done.get(waitMs, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException | CancellationException | java.util.concurrent.ExecutionException ignored) {
                        // 超时(无新事件)/future 被取消/异常完成(仅 source 主动放弃才可能):不抛,按现状应答(空 batch 也是合法应答)
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt(); // rpc.cancel 打断:交还中断位
                    }
                } finally {
                    live.log.removeListener(l);
                }
                m = readPollMerged(dir, live, mainAgentId, rounds, afterSeq, beforeSeq, count, effLimit);
            }
        } catch (java.io.IOException e) {
            ctx.err(Rpc.ERR_INTERNAL, "任务事件读取失败: " + e.getMessage());
            return;
        }

        List<JsonNode> batch = new ArrayList<>(m.events.size());
        for (EventRecord r : m.events) {
            batch.add(TaskEvents.wireEvent(r, mainAgentId));
        }
        boolean isLive = live != null && !live.status.terminal();
        String status = taskStatusOf(taskId, live, meta);
        ObjectNode result = Json.obj()
                .put("hasMore", m.hasMore)
                .put("firstSeq", m.events.isEmpty() ? 0 : m.events.get(0).seq())
                .put("lastSeq", m.events.isEmpty() ? 0 : m.events.get(m.events.size() - 1).seq())
                .set("task", Json.obj().put("status", status))
                .put("live", isLive);
        // 先 data 后 ok:空批次也发 data(空)+ok,保持前端契约稳定
        ctx.data(batch, false);
        ctx.ok(result);
    }

    /**
     * task.poll 数据源读取与归并(「磁盘窗口 ∪ 内存尾部」总原则,同 seq 以内存为准覆盖):
     * rounds:roundStartSeq(dir, count) 定位第 count 个 user.message(不足返回 1),磁盘
     * readSince(start-1, 500) 取 seq>=start 全量,热任务再以内存 readAfterSeq(磁盘最大 seq)
     * 补未落盘/瞬态段;
     * events+afterSeq+beforeSeq:区间查询——(afterSeq, beforeSeq) 开区间(两端不含)内
     * seq 升序,超出 effLimit 从头部截断;readSince 的窗口语义是「afterSeq 之后最近的一批」,
     * 不能直接当区间头部用,磁盘侧以 ROUNDS_REBUILD_MAX 大上限扫过区间(反向扫描到
     * afterSeq 即止,与 rounds 全量重建同口径),内存 readAfterSeq 天然升序头部窗口,
     * 两侧均过滤 seq&lt;beforeSeq;
     * events+beforeSeq:向前翻页——磁盘 readBefore 获得 beforeSeq 之前最近的一批,热任务叠加
     * 内存 readLastRecords 中 seq<beforeSeq 者(瞬态窗口),合并后取末尾 effLimit 条;
     * events 默认:增量——磁盘 readSince(afterSeq, limit),热任务以内存 readAfterSeq(磁盘最大
     * seq 或 afterSeq)补增量,合并去重升序截断 limit。
     */
    private MergedEvents readPollMerged(Path dir, TaskEntry live, String mainAgentId,
            boolean rounds, long afterSeq, long beforeSeq, int count, int effLimit)
            throws java.io.IOException {
        Map<Long, EventRecord> merged = new java.util.TreeMap<>();
        if (rounds) {
            long start = store.roundStartSeq(dir, mainAgentId, count);
            List<EventRecord> disk = store.readSince(dir, mainAgentId, start - 1, 500);
            long diskMax = 0;
            for (EventRecord r : disk) {
                merged.put(r.seq(), r);
                diskMax = Math.max(diskMax, r.seq());
            }
            if (live != null && !live.status.terminal()) {
                long floor = disk.isEmpty() ? start - 1 : diskMax;
                for (EventRecord r : live.log.readAfterSeq(floor, 500)) {
                    merged.put(r.seq(), r);
                }
            }
        } else if (afterSeq > 0 && beforeSeq > 0) {
            // 区间查询:(afterSeq, beforeSeq) 开区间(两端不含),升序取头部 effLimit 条
            for (EventRecord r : store.readSince(dir, mainAgentId, afterSeq, ROUNDS_REBUILD_MAX)) {
                if (r.seq() < beforeSeq) {
                    merged.put(r.seq(), r);
                }
            }
            if (live != null && !live.status.terminal()) {
                for (EventRecord r : live.log.readAfterSeq(afterSeq, effLimit)) {
                    if (r.seq() < beforeSeq) {
                        merged.put(r.seq(), r);
                    }
                }
            }
        } else if (beforeSeq > 0) {
            for (EventRecord r : store.readBefore(dir, mainAgentId, beforeSeq, effLimit)) {
                merged.put(r.seq(), r);
            }
            if (live != null && !live.status.terminal()) {
                for (EventRecord r : live.log.readLastRecords(effLimit)) {
                    if (r.seq() < beforeSeq) {
                        merged.put(r.seq(), r);
                    }
                }
            }
        } else {
            List<EventRecord> disk = store.readSince(dir, mainAgentId, afterSeq, effLimit);
            long diskMax = afterSeq;
            for (EventRecord r : disk) {
                merged.put(r.seq(), r);
                diskMax = Math.max(diskMax, r.seq());
            }
            if (live != null && !live.status.terminal()) {
                long floor = disk.isEmpty() ? afterSeq : diskMax;
                for (EventRecord r : live.log.readAfterSeq(floor, effLimit)) {
                    merged.put(r.seq(), r);
                }
            }
        }
        List<EventRecord> all = new ArrayList<>(merged.values());
        // 仅 beforeSeq(向前翻页)取末尾(最近的一批);增量与区间查询按 seq 升序取前 effLimit
        boolean takeTail = beforeSeq > 0 && afterSeq <= 0;
        List<EventRecord> out;
        if (all.size() > effLimit) {
            out = takeTail
                    ? new ArrayList<>(all.subList(all.size() - effLimit, all.size()))
                    : new ArrayList<>(all.subList(0, effLimit));
        } else {
            out = all;
        }
        return new MergedEvents(out, all.size() >= effLimit);
    }

    /**
     * task.rounds:任务轮次索引拉取(前端「双击打开任务」rounds 新流程的唯一取数口)。
     * 参数:taskId 必填。应答:{rounds:[...], open:{startSeq,user}|null, status, live}:
     * <ul>
     * <li>rounds = rounds.jsonl 全部行(wire:seq 一律字符串,endSeq 未闭合为 "",subs 恒数组);</li>
     * <li>rounds.jsonl 不存在(旧任务/Advisor 介入前创建)→ 惰性全量生成落盘后返回:
     *     磁盘全部事件 ∪ 运行中任务内存日志按 seq 归并(同 seq 以内存为准)一次扫描全部轮;
     *     终态任务全部轮一并落盘(含开轮路径写入的未闭合尾轮),运行中任务只落盘已闭合轮
     *     (未闭合轮已由开轮路径落盘,open 实时给出);与 Advisor 增量写以最后一行 startSeq
     *     为界天然幂等,不重复不遗漏;</li>
     * <li>open 仅运行中任务存在:实时扫描出的当前未闭合轮起点;
     *     终态任务未闭合尾轮已由开轮路径写入 rounds.jsonl,open=null;</li>
     * <li>status/live 口径与 task.poll 应答一致;任务不存在 → NOT_FOUND(同存在性判定)。</li>
     * </ul>
     */
    private void rpcTaskRounds(RpcContext ctx) {
        String taskId = ctx.strParam("taskId");
        // 存在性:与 task.poll 同口径(目录在盘 或 内存任务/磁盘索引可见任一即存在)
        boolean known = store.taskDirExists(taskId) || tasks.containsKey(taskId) || diskTasks.containsKey(taskId);
        if (log.isDebugEnabled()) {
            log.debug("[rounds] task.rounds known 判定 task={} dirExists={} inTasks={} inDiskTasks={}",
                    taskId, store.taskDirExists(taskId), tasks.containsKey(taskId),
                    diskTasks.containsKey(taskId));
        }
        if (!known) {
            ctx.err(Rpc.ERR_NOT_FOUND, "task 不存在: " + taskId);
            return;
        }
        Path dir = store.dirOf(taskId); // known → 已登记或已 scan,dirOf 不抛
        TaskEntry live = tasks.get(taskId);
        ObjectNode meta = live == null ? store.readMeta(dir) : null;
        String mainAgentId = live != null ? live.mainAgentId
                : (meta != null ? meta.path("mainAgentId").asString(null) : null);
        boolean isLive = live != null && !live.status.terminal();
        try {
            if (!Files.isRegularFile(dir.resolve("rounds.jsonl"))) {
                rebuildRoundsOnDemand(taskId, live, mainAgentId); // 旧任务:惰性全量生成落盘
            }
            List<RoundIndex.Round> roundList = store.readRounds(dir);
            ArrayNode rounds = Json.arr();
            for (RoundIndex.Round r : roundList) {
                rounds.add(wireRound(r));
            }
            ObjectNode open = null;
            if (isLive) {
                open = scanOpenRound(taskId, live, mainAgentId); // 运行中:当前未闭合轮起点(不落盘)
            }
            ObjectNode result = Json.obj()
                    .set("rounds", rounds)
                    .put("status", taskStatusOf(taskId, live, meta))
                    .put("live", isLive);
            if (open != null) {
                result.set("open", open);
            } else {
                result.putNull("open");
            }
            ctx.ok(result);
        } catch (java.io.IOException e) {
            ctx.err(Rpc.ERR_INTERNAL, "任务轮次索引读取失败: " + e.getMessage());
        }
    }

    /**
     * task.roundTail:一次性拉取「seq &gt;= startSeq 的最后 limit 条事件」(前端打开任务时,
     * 最后一轮未闭合要按轮起点渲染尾部)。无轮询/长轮询/waitMs 副作用。
     * 参数:taskId 必填;startSeq 必填(轮起点,雪花大数须字符串传输,转 long 失败 → BAD_PARAMS);
     * limit 可选,默认 50,钳制 [1,500](与 task.poll 上限一致)。
     * 语义:磁盘持久事件 ∪ 内存日志(含瞬态 delta/thinking),同 seq 以内存为准(内存已覆盖的
     * 落盘帧丢弃),按 seq 升序;取末尾 limit 条,同 seq 组不拆批(头部若被切开,把该组整体
     * 左扩补齐,避免丢 message)。应答复用 task.poll 形状:先 ctx.data 批次,再
     * ctx.ok({hasMore, firstSeq, lastSeq, task:{status}, live});hasMore=「startSeq 之后、
     * 返回窗口之前还有更早事件」(前端可向前续拉)。任务不存在 → NOT_FOUND(同存在性判定)。
     */
    private void rpcTaskRoundTail(RpcContext ctx) {
        String taskId = ctx.strParam("taskId");
        String startSeqRaw = ctx.strParam("startSeq");
        long startSeq;
        try {
            startSeq = Long.parseLong(startSeqRaw.trim());
        } catch (NumberFormatException e) {
            ctx.err(Rpc.ERR_BAD_PARAMS, "startSeq 非法: " + startSeqRaw);
            return;
        }
        if (startSeq < 0) {
            ctx.err(Rpc.ERR_BAD_PARAMS, "startSeq 需为非负整数: " + startSeqRaw);
            return;
        }
        int limit = (int) Math.max(1, Math.min(500, ctx.optLongParam("limit", 50)));
        // 存在性:与 task.poll / task.rounds 同口径(目录在盘 或 内存任务/磁盘索引可见任一即存在)
        boolean known = store.taskDirExists(taskId) || tasks.containsKey(taskId) || diskTasks.containsKey(taskId);
        if (log.isDebugEnabled()) {
            log.debug("[roundTail] known 判定 task={} dirExists={} inTasks={} inDiskTasks={}",
                    taskId, store.taskDirExists(taskId), tasks.containsKey(taskId),
                    diskTasks.containsKey(taskId));
        }
        if (!known) {
            ctx.err(Rpc.ERR_NOT_FOUND, "task 不存在: " + taskId);
            return;
        }
        Path dir = store.dirOf(taskId); // known → 已登记或已 scan,dirOf 不抛
        TaskEntry live = tasks.get(taskId);
        ObjectNode meta = live == null ? store.readMeta(dir) : null;
        String mainAgentId = live != null ? live.mainAgentId
                : (meta != null ? meta.path("mainAgentId").asString(null) : null);

        List<EventRecord> tail;
        boolean hasMore;
        try {
            List<EventRecord> all = readRoundTailMerged(dir, live, mainAgentId, startSeq, ROUNDS_REBUILD_MAX);
            TailWindow w = cutTailWindow(all, limit);
            tail = w.events();
            hasMore = w.hasMore();
        } catch (java.io.IOException e) {
            ctx.err(Rpc.ERR_INTERNAL, "任务事件读取失败: " + e.getMessage());
            return;
        }

        List<JsonNode> batch = new ArrayList<>(tail.size());
        for (EventRecord r : tail) {
            batch.add(TaskEvents.wireEvent(r, mainAgentId));
        }
        boolean isLive = live != null && !live.status.terminal();
        String status = taskStatusOf(taskId, live, meta);
        ObjectNode result = Json.obj()
                .put("hasMore", hasMore)
                .set("task", Json.obj().put("status", status))
                .put("live", isLive);
        if (tail.isEmpty()) {
            result.put("firstSeq", 0);
            result.put("lastSeq", 0);
        } else {
            result.put("firstSeq", String.valueOf(tail.get(0).seq()));
            result.put("lastSeq", String.valueOf(tail.get(tail.size() - 1).seq()));
        }
        // 先 data 后 ok:空批次也发 data(空)+ok,保持前端契约稳定
        ctx.data(batch, false);
        ctx.ok(result);
    }

    /**
     * task.roundTail 数据读取:磁盘 readSince 取 [startSeq, ∞) 最早 WINDOW 条(与
     * rounds 全量重建同量级上限,单轮事件数远小于窗口),热任务叠加内存 readAfterSeq
     * (含瞬态 delta/thinking,同 seq 组不拆批)。同 seq 以内存为准:丢弃磁盘上被内存
     * 覆盖的落盘帧,保留内存同组全部分帧;按 seq 升序稳定归并(同 seq 组保持追加顺序)。
     */
    private List<EventRecord> readRoundTailMerged(Path dir, TaskEntry live, String mainAgentId,
            long startSeq, int window) throws java.io.IOException {
        List<EventRecord> disk = store.readSince(dir, mainAgentId, startSeq - 1, window);
        List<EventRecord> mem = List.of();
        java.util.Set<Long> memSeqs = new java.util.HashSet<>();
        if (live != null && !live.status.terminal()) {
            mem = live.log.readAfterSeq(startSeq - 1, window);
            for (EventRecord r : mem) {
                memSeqs.add(r.seq());
            }
        }
        List<EventRecord> all = new ArrayList<>(disk.size() + mem.size());
        for (EventRecord r : disk) {
            if (!memSeqs.contains(r.seq())) {
                all.add(r);
            }
        }
        all.addAll(mem);
        all.sort(Comparator.comparingLong(EventRecord::seq)); // 稳定排序:同 seq 组保持追加顺序
        return all;
    }

    /**
     * 从升序事件列表中截取末尾 limit 条;同 seq 组不拆批:若截断把头部某个 seq 组
     * 从中间切开,则把该组整体左扩补齐(避免丢同组尾帧 message)。hasMore=截取窗口
     * 之前是否还有更早事件(startSeq 之后、返回窗口之前),供前端判断能否向前续拉。
     */
    static TailWindow cutTailWindow(List<EventRecord> all, int limit) {
        int size = all.size();
        int cut = Math.max(0, size - limit);
        if (cut == 0) {
            return new TailWindow(all, false);
        }
        long headSeq = all.get(cut).seq();
        while (cut > 0 && all.get(cut - 1).seq() == headSeq) {
            cut--;
        }
        return new TailWindow(new ArrayList<>(all.subList(cut, size)), cut > 0);
    }

    /** 任务状态字串(task.poll / task.rounds 应答共用口径:内存优先,冷任务磁盘索引 → meta → 缺失保护视为 done)。 */
    private String taskStatusOf(String taskId, TaskEntry live, ObjectNode meta) {
        if (live != null) {
            return live.status.wire();
        }
        TaskStore.StoredTask st = diskTasks.get(taskId);
        String s = st != null ? st.summary().path("status").asString("")
                : (meta != null ? meta.path("status").asString("") : "");
        return s.isEmpty() ? "done" : s; // 元数据缺失保护:视为终态
    }

    /**
     * 旧任务惰性全量生成(rounds.jsonl 不存在时的首次 task.rounds):
     * 磁盘全部持久事件 ∪ 运行中任务内存日志按 seq 归并(同 seq 以内存为准)→ 一次扫描全部轮:
     * 终态任务(live 不在或已终态)全部轮一并落盘(含开轮路径写入的未闭合尾轮);
     * 运行中任务只落盘已闭合轮(未闭合轮已由开轮路径落盘,open 实时给出)。
     * 幂等:与 Advisor 增量写共用 appendRound,以既有行的 startSeq 集合精确去重,不重复不遗漏。
     */
    private void rebuildRoundsOnDemand(String taskId, TaskEntry live, String mainAgentId)
            throws java.io.IOException {
        Path dir = store.dirOf(taskId);
        Map<Long, EventRecord> merged = new java.util.TreeMap<>();
        for (EventRecord r : store.readSince(dir, mainAgentId, 0, ROUNDS_REBUILD_MAX)) {
            merged.put(r.seq(), r);
        }
        if (live != null) {
            for (EventRecord r : live.log.readAfterSeq(0, ROUNDS_REBUILD_MAX)) {
                merged.put(r.seq(), r); // 同 seq 以内存为准(未落盘/瞬态段)
            }
        }
        if (merged.isEmpty()) {
            return;
        }
        List<RoundIndex.Round> found = roundIndexStore.scan(new ArrayList<>(merged.values()), mainAgentId);
        if (found.isEmpty()) {
            return;
        }
        boolean liveTask = live != null && !live.status.terminal();
        List<RoundIndex.Round> existing = store.readRounds(dir);
        java.util.Set<Long> existingStarts = new java.util.HashSet<>();
        for (RoundIndex.Round r : existing) {
            existingStarts.add(r.startSeq());
        }
        long lastIndex = existing.isEmpty() ? 0 : existing.getLast().index();
        for (RoundIndex.Round r : found) {
            if (existingStarts.contains(r.startSeq())) {
                continue; // 已写过的轮不重复(与 Advisor 增量写天然互斥)
            }
            if (liveTask && r.endSeq() == null) {
                continue; // 运行中未闭合轮不落盘(open 实时给出;落盘即永远无法闭合)
            }
            lastIndex++;
            store.appendRound(taskId, new RoundIndex.Round(ShortIds.next("round"), lastIndex,
                    r.startSeq(), r.endSeq(), r.user(), r.finalReply(),
                    r.agentRanges(), 0L, 0L, r.userMessage()));
            existingStarts.add(r.startSeq());
        }
    }

    /**
     * 运行中任务的当前未闭合轮起点(实时扫描,不落盘):
     * 窗口 = rounds.jsonl 最后一行 startSeq 起的「磁盘 ∪ 内存」合并事件(同 seq 以内存为准),
     * 下界<b>含锚点本身</b>——与 persistClosedRounds 的增量闭合窗口同口径
     * (readSince/readAfterSeq 均为开区间原语,传 anchor-1 得「seq ≥ anchor」):尾行未闭合时
     * 其开轮 user.message 事件(startSeq == anchor)本身要进窗口,续跑任务的<b>历史</b>开轮
     * 才能被重扫出来(否则 scan 开不出轮,open 恒为 null)。anchor 为 0(无 rounds.jsonl)
     * 时维持 0(全量)。扫描出的最后一轮未闭合时返回 {startSeq,user}——startSeq 即开轮输入,
     * 续跑后队列消费的新 user.message 属中间输入,并入当前轮不改起点;全部闭合(或无轮)返回
     * null(闭合行由 Advisor 增量闭合,职责不变)。
     */
    private ObjectNode scanOpenRound(String taskId, TaskEntry live, String mainAgentId)
            throws java.io.IOException {
        Path dir = store.dirOf(taskId);
        long anchor = store.lastRoundStartSeq(dir);
        long bound = anchor > 0 ? anchor - 1 : 0; // 含锚点本身(与增量闭合窗口一致)
        Map<Long, EventRecord> merged = new java.util.TreeMap<>();
        for (EventRecord r : store.readSince(dir, mainAgentId, bound, ROUNDS_REBUILD_MAX)) {
            merged.put(r.seq(), r);
        }
        for (EventRecord r : live.log.readAfterSeq(bound, ROUNDS_REBUILD_MAX)) {
            merged.put(r.seq(), r); // 同 seq 以内存为准
        }
        if (merged.isEmpty()) {
            return null;
        }
        List<RoundIndex.Round> found = roundIndexStore.scan(new ArrayList<>(merged.values()), mainAgentId);
        if (found.isEmpty() || found.getLast().endSeq() != null) {
            return null; // 无未闭合轮
        }
        RoundIndex.Round open = found.getLast();
        ObjectNode openNode = Json.obj()
                .put("startSeq", String.valueOf(open.startSeq()))
                .put("user", open.user());
        if (open.userMessage() != null) {
            openNode.set("userMessage", open.userMessage());
        }
        return openNode;
    }

    /** Round → wire 形态(seq 一律字符串;endSeq 未闭合为 "";durationMs 数值;roundId 非空才写;subs 恒数组;与 rounds.jsonl 行格式一致)。 */
    private static ObjectNode wireRound(RoundIndex.Round r) {
        ObjectNode n = Json.obj()
                .put("index", r.index())
                .put("startSeq", String.valueOf(r.startSeq()))
                .put("endSeq", r.endSeq() == null ? "" : String.valueOf(r.endSeq()))
                .put("user", r.user())
                .put("finalReply", r.finalReply())
                .put("durationMs", r.durationMs());
        if (r.roundId() != null && !r.roundId().isBlank()) {
            n.put("roundId", r.roundId()); // roundId 稳定主键:缺失(旧行)不写
        }
        if (r.userMessage() != null) {
            n.set("userMessage", r.userMessage()); // 完整 user.message payload:懒加载骨架起点(旧行缺失不写)
        }
        ArrayNode agentRanges = Json.arr();
        for (RoundIndex.AgentRange s : r.agentRanges()) {
            agentRanges.add(Json.obj()
                    .put("agentId", s.agentId())
                    .put("title", s.title())
                    .put("startSeq", s.startSeq() == null ? "" : String.valueOf(s.startSeq()))
                    .put("endSeq", s.endSeq() == null ? "" : String.valueOf(s.endSeq())));
        }
        n.set("agentRanges", agentRanges);
        return n;
    }

    /**
     * 生成与内存/磁盘均不冲突的 taskId(ShortIds 契约约定的「调用方查重兜底」)。
     * <p>ShortIds 的随机盐 + 每进程自增计数器在 worker 重启后会重置:盐空间仅 36² 时,
     * 重启频繁下生日悖论使「同盐 + 计数器从 1 重头」撞出旧 taskId 的概率不可忽略。
     * 一旦撞上,新建任务会复用旧任务目录——meta.json 被新标题覆盖、事件追加进旧 agent 日志,
     * 表现为「新建任务却打开旧任务、旧任务续跑且标题被顶成新标题」(实测 t_ct1/t_ct2 事故)。
     * 故此处对 tasks(内存)/diskTasks(磁盘索引)/任务目录(磁盘直查)三重查重,冲突则递增重生成。
     */
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

    /**
     * task.run:创建/续跑合一——无 taskId=新建(workspace 必填),有 taskId=运行中入队/终态冷启动续跑。
     * RPC 线程启动洋葱链（order 10~80），ThreadSubmitNode 切换到虚拟线程执行后续节点。
     */
    private void rpcTaskRun(RpcContext ctx) {
        String input = ctx.strParam("input");
        String rawContent = ctx.optStrParam("rawContent", null);
        String existingId = ctx.optStrParam("taskId", "");
        log.debug("[run] task.run 进入 existingId={} input={} metadataKeys={} thread={}",
                existingId.isEmpty() ? "(新建)" : existingId,
                input != null && input.length() > 60 ? input.substring(0, 60) + "…" : input,
                ctx.params().path("metadata").size(),
                Thread.currentThread().getName());

        // 读取 metadata（通用插件参数容器）；JsonNode 解包为 Java 标量，插件按类型直取
        java.util.Map<String, Object> metadata;
        JsonNode metaNode = ctx.params().path("metadata");
        if (metaNode.isObject()) {
            metadata = new java.util.HashMap<>();
            metaNode.properties().forEach(e -> metadata.put(e.getKey(), unwrap(e.getValue())));
        } else {
            metadata = java.util.Collections.emptyMap();
        }

        // 编辑重发（editSeq 截断）已迁入 task-edit-resend 插件（EditResendNode，order=395.5）。

        // 创建上下文（带 RPC 参数，无 TaskEntry）
        TaskLifecycleContextImpl lifecycleCtx = lifecycleContextFactory.createForRpc(
                input, rawContent, metadata, ctx);

        // 有 taskId → 再运行路径
        if (!existingId.isEmpty()) {
            lifecycleCtx.taskId(existingId);
        }

        // 定义内核
        TaskKernel kernel = taskKernel();

        // 启动链
        Object result = lifecycleExecutor.run(lifecycleRegistry.getNodes(), kernel, lifecycleCtx);
        log.debug("[run] task.run 链返回 task={} result={}",
                lifecycleCtx.taskId(), String.valueOf(result));

        // 处理结果：非 null 且非 DONE → 检查是否已应答
        if (result instanceof TaskOutcome to && to.status() != TaskOutcome.TaskEndStatus.DONE) {
            // 链异常退出（非短路），ctx 可能未被回答
            // ResponseAckNode 已在 RPC 线程 ok 过；VT 中异常由 ThreadSubmitNode catch
            // 此分支仅在链同步段异常时到达
        }
    }

    /** JsonNode → Java 标量（布尔/整型/浮点/文本），复杂结构原样保留。 */
    private static Object unwrap(JsonNode v) {
        if (v.isBoolean()) return v.asBoolean();
        if (v.isNumber()) {
            return v.isIntegralNumber() ? (Object) v.asLong() : (Object) v.asDouble();
        }
        if (v.isTextual()) return v.asString();
        return v;
    }

    private void rpcTaskCancel(RpcContext ctx) {
        TaskEntry t = tasks.get(ctx.strParam("taskId"));
        if (t == null) {
            ctx.err(Rpc.ERR_NOT_FOUND, "任务不存在");
            return;
        }
        if (t.status.terminal()) {
            ctx.ok(Json.obj().put("taskId", t.taskId).put("status", t.status.wire()).put("alreadyTerminal", true));
            return;
        }
        asks.cancelTask(t.taskId, "user");
        Future<?> f = t.runFuture;
        boolean cancelled = f != null && f.cancel(true);
        log.debug("[cancel] rpcTaskCancel taskId={} runFutureNull={} cancelled={} futureDone={} interruptFlagNow={} callerThread={}",
                t.taskId, f == null, cancelled, f != null && f.isDone(),
                Thread.currentThread().isInterrupted(), Thread.currentThread().getName());
        ctx.ok(Json.obj().put("taskId", t.taskId).put("status", "cancelling"));
    }

    /** task.delete:用户主动删除(唯一删除路径);运行中拒绝。 */
    private void rpcTaskDelete(RpcContext ctx) {
        String taskId = ctx.strParam("taskId");
        DeleteResult r = deleteTask(taskId);
        switch (r) {
            case RUNNING -> ctx.err(Rpc.ERR_BAD_PARAMS, "任务运行中,请先停止再删除");
            case NOT_FOUND -> ctx.err(Rpc.ERR_NOT_FOUND, "任务不存在");
            case OK -> ctx.ok(Json.obj().put("taskId", taskId).put("deleted", true));
        }
    }



    /** 删除结果(区分运行中/不存在;workspaces.remove 级联共用)。 */
    private enum DeleteResult { OK, RUNNING, NOT_FOUND }

    /**
     * 删除一个任务(task.delete 与 workspaces.remove 级联共用,不重复实现):
     * 运行中拒绝;原子认领 diskTasks 后删盘 + 广播 task.deleted。
     */
    private DeleteResult deleteTask(String taskId) {
        TaskEntry t = tasks.get(taskId);
        if (t != null) {
            if (!t.status.terminal()) {
                return DeleteResult.RUNNING;
            }
            synchronized (t) {
            } // 等 finish 驱逐完成(finish 全程持 t;随后任务必在 diskTasks)
        }
        TaskStore.StoredTask st = diskTasks.remove(taskId); // 原子认领(与 rerun/并发 delete 互斥)
        if (st == null) {
            return DeleteResult.NOT_FOUND;
        }
        store.delete(st.dir());
        store.forgetTask(taskId); // 忘记 workspaceId 映射(幂等:已删除/未登记均无害)
        eventSink.fanout(k -> Channels.tasks(k), Events.TASK_DELETED, null,
                Json.obj().put("taskId", taskId), null);
        return DeleteResult.OK;
    }

    /**
     * workspaces.remove 级联:删除挂靠指定工作区稳定 id 的全部任务数据
     * (meta.json/jsonl 等系统落盘,位于 workspaces/&lt;workspaceId&gt;/tasks/&lt;taskId&gt;/;
     * 绝不动工作区目录本身)。运行中任务跳过(与 task.delete 语义一致),返回实际删除数。
     */
    @Override
    public int deleteByWorkspaceId(String workspaceId) {
        int deleted = 0;
        // 冷任务(磁盘索引;含已驱逐的终态任务)
        for (TaskStore.StoredTask st : store.scan()) {
            if (!workspaceId.equals(st.workspaceId())) {
                continue;
            }
            if (deleteTask(st.taskId()) == DeleteResult.OK) {
                deleted++;
            }
        }
        // 热任务(运行中驻留内存;运行中由 deleteTask 拒绝,终态兜底删除)
        for (TaskEntry t : tasks.values()) {
            if (!workspaceId.equals(t.workspaceId)) {
                continue;
            }
            if (deleteTask(t.taskId) == DeleteResult.OK) {
                deleted++;
            }
        }
        if (deleted > 0) {
            log.info("workspaces.remove 级联删除 {} 个任务(工作区 {})", deleted, workspaceId);
        }
        return deleted;
    }

    /**
     * workspaces.resolveMissing(纠正路径)迁移:把挂靠旧工作区根的任务 meta.workspace
     * 改到新根,保证任务仍归属移动后的工作区、后续运行沙箱挂载新目录。运行中任务直接改
     * 内存字段并回写 meta;磁盘终态任务原地改 meta.json 后替换内存索引镜像。返回迁移数。
     */
    @Override
    public int redirectWorkspace(String oldRoot, String newRoot) {
        int moved = 0;
        for (TaskStore.StoredTask st : store.scan()) {
            if (!oldRoot.equals(st.summary().path("workspace").asString(""))) {
                continue;
            }
            ObjectNode copy = st.summary().deepCopy();
            copy.put("workspace", newRoot);
            try {
                store.writeMeta(st.dir(), copy);
            } catch (java.io.IOException e) {
                log.warn("任务 workspace 迁移写盘失败 task={}", st.taskId(), e);
                continue;
            }
            diskTasks.put(st.taskId(), new TaskStore.StoredTask(st.taskId(), st.dir(), copy,
                    st.workspaceId()));
            moved++;
        }
        for (TaskEntry t : tasks.values()) {
            if (!oldRoot.equals(t.workspaceRoot)) {
                continue;
            }
            t.workspaceRoot = newRoot;
            try {
                store.updateMeta(t.taskId); // 运行中任务:meta 供应商读最新 workspaceRoot
            } catch (RuntimeException e) {
                log.debug("运行中任务 workspace 迁移写盘失败 task={}", t.taskId, e);
            }
            moved++;
        }
        if (moved > 0) {
            log.info("workspaces.resolveMissing 迁移 {} 个任务({} -> {})", moved, oldRoot, newRoot);
        }
        return moved;
    }

    // config.get / config.reload / skill.reload 已迁 ConfigRpcHandler(方法体原样搬移)。

    // ---- 任务内核 ----

    /**
     * 任务内核：runner.run(main) 一次 → TaskOutcome。
     * 异常翻译为 TaskOutcome（值对象），链上只传值。
     */
    private TaskKernel taskKernel() {
        return c -> {
            try {
                TaskEntry te = ((TaskLifecycleContextImpl) c).taskEntryImpl();
                AgentEntity main = te.main;
                if (Thread.currentThread().isInterrupted()) {
                    log.debug("[cancel] 内核检测到中断标记 taskId={} thread={}",
                            te.taskId, Thread.currentThread().getName());
                    throw new InterruptedException("cancelled");
                }
                runner.run(main);
                log.debug("[run] runner.run 正常返回(本轮 agent 完成) taskId={} thread={}",
                        te.taskId, Thread.currentThread().getName());
                long startedAt = te.startedAt != null ? te.startedAt : 0;
                return new TaskOutcome(TaskOutcome.TaskEndStatus.DONE, null,
                        startedAt, System.currentTimeMillis());
            } catch (InterruptedException e) {
                log.debug("[cancel] 内核捕获 InterruptedException → CANCELLED thread={}",
                        Thread.currentThread().getName());
                Thread.currentThread().interrupt();
                return TaskOutcome.cancelled();
            } catch (EventLog.LogOverflowException loe) {
                return TaskOutcome.failed("LOG_OVERFLOW: " + loe.getMessage());
            } catch (Throwable e) {
                log.error("任务失败: {}", RootCause.summary(e));
                log.debug("任务失败完整堆栈", e);
                return TaskOutcome.failed(RootCause.summary(e));
            }
        };
    }

    // consumeInput 已迁 TaskLifecycleContextImpl(授权门 beginRun/开轮/入会话内存随任务上下文走)。

    // truncateForEdit / truncateForColdEdit 已迁入 task-edit-resend 插件（EditTruncateProcessor）。
    // buildMainAgent / resolveAgentConfig 已迁 AgentBuilder(agent 层);
    // isWindows 随装配代码一并清理。

    // finish() 已删除——终态收口逻辑分散进 13 个上行节点（TaskLifecycleExecutor 驱动）。
    // setStatus 仍保留：askPendingChanged 切换 WAITING_USER/RUNNING 用。

    private void setStatus(TaskEntry t, TaskStatus s) {
        synchronized (t) {
            if (t.status == s) {
                return;
            }
            t.status = s;
        }
        eventSink.fanout(k -> Channels.tasks(k), Events.TASK_UPDATED, null, t.runtimeSummaryJson(), null);
    }

// ---- InteractionServiceImpl.StatusHook:waiting-user ⇄ running ----

    @Override
    public void askPendingChanged(String taskId, boolean nowPending) {
        TaskEntry t = tasks.get(taskId);
        if (t == null || t.status.terminal()) {
            return;
        }
        if (nowPending && t.status == TaskStatus.RUNNING) {
            setStatus(t, TaskStatus.WAITING_USER);
        } else if (!nowPending && t.status == TaskStatus.WAITING_USER) {
            setStatus(t, TaskStatus.RUNNING);
        }
    }

    // ---- 停机(优雅停机尽力而为)----

    @PreDestroy
    void shutdown() {
        // 中断全部运行中任务线程，让洋葱上行段自然收口
        for (TaskEntry t : tasks.values()) {
            if (!t.status.terminal()) {
                asks.cancelTask(t.taskId, "worker");
                Future<?> f = t.runFuture;
                if (f != null) {
                    f.cancel(true);
                }
            }
        }
        // 有限等待任务收口（与现状 flush 30s 超时放行同档）
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            boolean anyRunning = false;
            for (TaskEntry t : tasks.values()) {
                if (!t.status.terminal()) {
                    anyRunning = true;
                    break;
                }
            }
            if (!anyRunning) break;
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        // 超时未退出的任务记 ERROR 放弃（应急语义）
        for (TaskEntry t : tasks.values()) {
            if (!t.status.terminal()) {
                log.error("停机超时：任务 {} 未完成收口，放弃", t.taskId);
            }
        }
        vt.shutdownNow();
    }

    private void purgeStaleIdem() {
        if (idem.size() < 100) {
            return;
        }
        long now = System.currentTimeMillis();
        idem.entrySet().removeIf(e -> now - e.getValue().ts() >= IDEM_WINDOW_MS);
    }

    // ---- 测试与监控辅助 ----

    public TaskEntry get(String taskId) {
        return tasks.get(taskId);
    }

    @Override
    public EventEmitter emitterFor(String subjectId) {
        TaskEntry entry = get(subjectId);
        return entry != null ? entry.events : null;
    }

    /**
     * 运行中任务实体(供业务注册方在 slash 建后回调里用 taskId 拿内存实体改自己的业务标记,
     * 并 t.persist() 落盘)。可能为 null(任务不在内存,如已终态驱逐/不存在),调用方自行判空。
     */
    public TaskEntry runningTask(String taskId) {
        return tasks.get(taskId);
    }

    /** 注册再运行监听(DataPusherManager:任务再运行时唤醒定向推送器换挂新日志)。 */
    public void addResumeListener(TaskResumeListener l) {
        resumeListeners.add(l);
    }

    // ---- slash 任务级 token 存储辅助(slash 层公共存储的只读/改写/广播入口,任务域职责集中)----

    /** 磁盘任务路由索引条目(终态不运行;可 null)。 */
    public TaskStore.StoredTask diskEntry(String taskId) {
        return diskTasks.get(taskId);
    }

    /** 替换/回写一条磁盘索引条目(slash 层改写磁盘 meta 后同步内存镜像;null 忽略)。 */
    public void replaceDiskEntry(TaskStore.StoredTask st) {
        if (st != null) {
            diskTasks.put(st.taskId(), st);
        }
    }

    /**
     * 任务归属查询:owner 隔离已移除(apiKey 只认证、不决定归属),恒返回 null。
     * 保留签名仅供 slash 层等既有调用方编译;调用方不应再据此做归属判断。
     */
    public String taskOwnerKey(String taskId) {
        return null;
    }

    /**
     * 广播 task.updated 到全部连接:运行中任务用内存 runtimeSummaryJson,
     * 磁盘终态任务用磁盘 summary(共享引用,只读广播);任务都不存在则不广播。
     */
    public void publishTaskUpdated(String taskId) {
        TaskEntry t = tasks.get(taskId);
        if (t != null) {
            eventSink.fanout(k -> Channels.tasks(k), Events.TASK_UPDATED, null, t.runtimeSummaryJson(), null);
            return;
        }
        TaskStore.StoredTask st = diskTasks.get(taskId);
        if (st != null) {
            eventSink.fanout(k -> Channels.tasks(k), Events.TASK_UPDATED, null, st.summary(), null);
        }
    }

    public int activeCount() {
        return active.get();
    }
}
