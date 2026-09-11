package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import dev.everyagent.contract.rpc.Rpc;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubLink;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.modules.ConfigStore;
import dev.everyagent.worker.modules.ConfigStore.ResolvedConfig;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.proto.Channels;
import dev.everyagent.worker.proto.ConfigDtos.ModelConfig;
import dev.everyagent.worker.proto.Events;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.worker.proto.ShortIds;
import dev.everyagent.worker.proto.TaskDtos.ModelSnapshot;
import dev.everyagent.worker.proto.TaskDtos.TaskStatus;
import dev.everyagent.worker.rpc.NotFoundException;
import dev.everyagent.worker.rpc.RpcContext;
import dev.everyagent.worker.slash.SlashCommandItem;
import dev.everyagent.worker.slash.SlashCommandRegistry;
import dev.everyagent.worker.slash.SlashTokenEncoder;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.tools.AskUserTool;
import dev.everyagent.worker.tools.BashTool;
import dev.everyagent.worker.tools.CommandExecutor;
import dev.everyagent.worker.tools.FileTools;
import dev.everyagent.worker.tools.FsToolSupport;
import dev.everyagent.worker.tools.PermissionGate;
import dev.everyagent.worker.tools.PowerShellTool;
import dev.everyagent.worker.tools.RipgrepBinary;
import dev.everyagent.worker.tools.SubAgentTools;
import dev.everyagent.worker.os.OsSandbox;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
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
 * 多 hub:任务不做 owner 隔离;任务事件经 HubPool.pubAllTasks 扇出到全部连接的 tasks 频道。
 */
@Component
public class TaskManager implements HubPool.Listener, PendingAsks.StatusHook {

    private static final Logger log = LoggerFactory.getLogger(TaskManager.class);
    private static final long IDEM_WINDOW_MS = 600_000;
    /** agent 台账定时持久化间隔(ms):运行中任务每 30s 把最新 agent 元数据写盘(崩溃后冷启动可恢复)。 */
    private static final long AGENT_META_PERSIST_INTERVAL_MS = 30_000;
    /** task.rounds 惰性全量生成/未闭合轮扫描的单次窗口上限(记录数;EventLog 内存护栏 50 万,同量级封顶)。 */
    private static final int ROUNDS_REBUILD_MAX = 500_000;

    private final HubPool pool;
    private final ConfigStore configs;
    private final ChatModelFactory modelFactory;
    private final AgentRunner runner;
    private final SubAgentManager subs;
    private final PendingAsks asks;
    private final WorkerProperties props;
    private final RpcDispatcher dispatcher;
    private final dev.everyagent.worker.modules.WorkspaceManager workspaces;
    private final FsToolSupport fs;
    private final OsSandbox sandbox;
    private final TaskStore store;
    private final PermissionGate gate;
    private final RipgrepBinary rgbin;
    private final SlashCommandRegistry slashRegistry;
    private final RoundIndexStore roundIndexStore;

    /** 热任务(运行中驻留内存;finish 即驱逐)。 */
    private final Map<String, TaskEntry> tasks = new ConcurrentHashMap<>();
    /** 磁盘任务路由索引:taskId → {dir, summary}(永久保留;delete 认领/移除)。 */
    private final Map<String, TaskStore.StoredTask> diskTasks = new ConcurrentHashMap<>();
    private final Map<String, IdemEntry> idem = new ConcurrentHashMap<>();
    private final AtomicInteger active = new AtomicInteger();
    private final java.util.concurrent.ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor();
    /** 运行中任务 agent 台账定时持久化(agent 元数据随 meta.json 落盘,崩溃不丢)。 */
    private final java.util.concurrent.ScheduledExecutorService agentMetaScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    /** 再运行监听器(DataPusherManager 注册:新 TaskEntry 入表后唤醒该任务的定向推送器)。 */
    private final java.util.concurrent.CopyOnWriteArrayList<TaskResumeListener> resumeListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private record IdemEntry(String taskId, long ts) {
    }

    /** 任务被再运行(新 TaskEntry 已入表)时回调:定向推送器立即换挂新日志(§5.6 实时推送)。 */
    public interface TaskResumeListener {
        void onTaskResumed(String taskId);
    }

    public TaskManager(HubPool pool, ConfigStore configs, ChatModelFactory modelFactory,
            AgentRunner runner, SubAgentManager subs, PendingAsks asks, WorkerProperties props,
            RpcDispatcher dispatcher, dev.everyagent.worker.modules.WorkspaceManager workspaces,
            FsToolSupport fs, OsSandbox sandbox, TaskStore store, PermissionGate gate, RipgrepBinary rgbin,
            SlashCommandRegistry slashRegistry, RoundIndexStore roundIndexStore) {
        this.pool = pool;
        this.configs = configs;
        this.modelFactory = modelFactory;
        this.runner = runner;
        this.subs = subs;
        this.asks = asks;
        this.props = props;
        this.dispatcher = dispatcher;
        this.workspaces = workspaces;
        this.fs = fs;
        this.sandbox = sandbox;
        this.store = store;
        this.gate = gate;
        this.rgbin = rgbin;
        this.slashRegistry = slashRegistry;
        this.roundIndexStore = roundIndexStore;
    }

    @PostConstruct
    void init() {
        pool.addListener(this);
        asks.setHook(this);
        // worker 级输入频道常订阅:每条连接各自的命名空间(连接建立/重连时由 HubLink 重放)
        for (HubLink conn : pool.conns()) {
            conn.sub(Channels.workerInput(conn.k(), props.getWorkerId()));
        }
        registerMethods(dispatcher);
        recoverFromDisk();
        // 运行中任务 agent 台账定时持久化:每 30s 落盘(子 agent 终态收口另有即时持久化)。
        agentMetaScheduler.scheduleAtFixedRate(this::persistAgentLedgers,
                AGENT_META_PERSIST_INTERVAL_MS, AGENT_META_PERSIST_INTERVAL_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS);
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
                            st.taskId(), st.dir(), fixed));
                    continue;
                } catch (java.io.IOException e) {
                    log.warn("重启恢复失败 task={}", st.taskId(), e);
                }
            }
            diskTasks.put(st.taskId(), st);
        }
    }

    // ---- 输入路由(worker 级频道;task.input / ask.reply)----

    @Override
    public void onHubMessage(HubLink conn, JsonNode frame) {
        String channel = frame.path("channel").asString("");
        if (!channel.equals(Channels.workerInput(conn.k(), props.getWorkerId()))) {
            return;
        }
        String event = frame.path("event").asString("");
        JsonNode payload = frame.path("payload");
        switch (event) {
            case Events.TASK_INPUT -> {
                String taskId = payload.path("taskId").asString("");
                String text = payload.path("text").asString("");
                String rawContent = payload.path("rawContent").isTextual()
                        ? payload.path("rawContent").asString()
                        : null;
                if (taskId.isEmpty() || text.isEmpty()) {
                    return;
                }
                TaskEntry t = tasks.get(taskId);
                if (t != null && !t.status.terminal()) {
                    t.inputQueue.offer(text, rawContent); // 运行中:本轮运行的输入循环内消化
                    t.touch();
                    publishQueue(t); // 队列变化即广播(pendingInputs 快照,前端镜像实时)
                    return;
                }
                // 不在内存或已终态(finish 驱逐窗口内):统一走再运行认领。
                // 热终态直接 return 会把输入无声丢弃——done 帧发布于 finish 持锁段头部,
                // flush/meta/驱逐完成前到达的输入都落在这个窗口。
                rerunTask(conn, taskId, text, rawContent);
            }
            case Events.TASK_DIALOG_INSERT -> {
                String taskId = payload.path("taskId").asString("");
                String text = payload.path("text").asString("");
                if (taskId.isEmpty() || text.isEmpty()) {
                    return;
                }
                // 只对运行中热任务生效:队列项插入到正在进行的 AI 对话循环,终态/不存在静默忽略
                TaskEntry t = tasks.get(taskId);
                if (t == null || t.status.terminal()) {
                    return;
                }
                // 插入只对本轮主 agent 生效:run 尚未建立主 agent(创建→开跑的极短窗口)时忽略,
                // 队列项保留;用户停止/任务终态后 t.main 已换新实体,旧积压随之作废。
                AgentEntity main = t.main;
                if (main == null) {
                    return;
                }
                // 把该条队列项从 pendingInputs 移除(插入即消费,避免后续被正常循环重复消化)。
                // 优先按前端下标删除;下标缺失/越界(并发消费导致漂移)时回退按正文删除第一条匹配。
                // 从被删队列项拿到原始内容(rawContent),随插入一起带进 user.message 回放。
                int index = payload.path("index").asInt(-1);
                UserInput removedInput = null;
                if (index >= 0) {
                    try {
                        removedInput = t.inputQueue.removeAt(index);
                    } catch (IndexOutOfBoundsException e) {
                        log.warn("task.dialogInsert 队列下标越界 task={} index={}(回退按正文删除)", taskId, index);
                    }
                }
                if (removedInput == null) {
                    removedInput = t.inputQueue.removeFirst(text);
                }
                // 加入本轮主 agent 的插入队列:DialogInsertAdvisor 在工具循环下行阶段随工具结果
                // 一起以 role=user 提交给 AI(停止即随 AgentEntity 作废,不跨 run 共享)
                main.pendingDialogInserts.offer(removedInput != null ? removedInput : UserInput.of(text));
                t.touch();
                publishQueue(t); // 队列移除后广播最新 pendingInputs(未移除则空广播无副作用)
            }
            case Events.ASK_REPLY -> asks.resolve(payload.path("askId").asString(""),
                    payload.path("answer").asString(""), "user");
            default -> {
            }
        }
    }

    // ---- RPC 方法注册 ----

    private void registerMethods(RpcDispatcher dispatcher) {
        dispatcher.register(RpcMethods.TASKS_LIST, this::rpcTasksList);
        dispatcher.register(RpcMethods.TASK_POLL, this::rpcTaskPoll);
        dispatcher.register(RpcMethods.TASK_ROUNDS, this::rpcTaskRounds);
        dispatcher.register(RpcMethods.TASK_ROUND_TAIL, this::rpcTaskRoundTail);
        dispatcher.register(RpcMethods.TASK_FILE_CHANGES, this::rpcTaskFileChanges);
        dispatcher.register(RpcMethods.TASK_RUN, this::rpcTaskRun);
        dispatcher.register(RpcMethods.TASK_CANCEL, this::rpcTaskCancel);
        dispatcher.register(RpcMethods.TASK_DELETE, this::rpcTaskDelete);
        dispatcher.register(RpcMethods.TASK_QUEUE_REMOVE, this::rpcTaskQueueRemove);
        dispatcher.register(RpcMethods.TASK_QUEUE_MOVE, this::rpcTaskQueueMove);
        dispatcher.register(RpcMethods.CONFIG_GET, this::rpcConfigGet);
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
            merged.put(t.taskId, t.runtimeSummaryJson()); // 内存行带 pendingInputs(队列外显)
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
            List<UserInput> queued = store.readQueue(s.dir());
            if (queued.isEmpty()) {
                merged.put(s.taskId(), s.summary()); // 无悬空队列:原样放行(共享引用,只读)
                continue;
            }
            ObjectNode copy = s.summary().deepCopy(); // 磁盘 summary 是共享引用,严禁原地修改
            ArrayNode pendingArr = Json.arr();
            for (UserInput item : queued) {
                pendingArr.add(item.text());
            }
            copy.set("pendingInputs", pendingArr);
            merged.put(s.taskId(), copy);
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
        Path dir = store.dirOf(taskId);
        // 存在性:目录在盘 或 内存任务/磁盘索引可见任一即存在(热任务 track 前目录可能未建,
        // 终态 finish 窗口内 tasks 仍驻留;三者全缺才算不存在/已删)
        boolean known = Files.isDirectory(dir) || tasks.containsKey(taskId) || diskTasks.containsKey(taskId);
        if (!known) {
            ctx.err(Rpc.ERR_NOT_FOUND, "task 不存在: " + taskId);
            return;
        }
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
                EventLog.Listener l = () -> done.complete(null);
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
        Path dir = store.dirOf(taskId);
        // 存在性:与 task.poll 同口径(目录在盘 或 内存任务/磁盘索引可见任一即存在)
        boolean known = Files.isDirectory(dir) || tasks.containsKey(taskId) || diskTasks.containsKey(taskId);
        if (!known) {
            ctx.err(Rpc.ERR_NOT_FOUND, "task 不存在: " + taskId);
            return;
        }
        TaskEntry live = tasks.get(taskId);
        ObjectNode meta = live == null ? store.readMeta(dir) : null;
        String mainAgentId = live != null ? live.mainAgentId
                : (meta != null ? meta.path("mainAgentId").asString(null) : null);
        boolean isLive = live != null && !live.status.terminal();
        try {
            if (!Files.isRegularFile(dir.resolve("rounds.jsonl"))) {
                rebuildRoundsOnDemand(taskId, live, mainAgentId); // 旧任务:惰性全量生成落盘
            }
            ArrayNode rounds = Json.arr();
            for (RoundIndex.Round r : store.readRounds(dir)) {
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
     * task.fileChanges:拉取单轮文件变更全文(轻量摘要已内联进 rounds.jsonl 行,全文单独落盘
     * file-changes/<roundId>.json)。参数:taskId 必填;roundId 必填。应答 {changes:[...]};
     * 文件不存在返回 {changes:[]};task 不存在返回 NOT_FOUND(与 task.rounds 同口径)。
     */
    private void rpcTaskFileChanges(RpcContext ctx) {
        String taskId = ctx.strParam("taskId");
        String roundId = ctx.strParam("roundId");
        Path dir = store.dirOf(taskId);
        boolean known = Files.isDirectory(dir) || tasks.containsKey(taskId) || diskTasks.containsKey(taskId);
        if (!known) {
            ctx.err(Rpc.ERR_NOT_FOUND, "task 不存在: " + taskId);
            return;
        }
        JsonNode full = store.readRoundFileChanges(taskId, roundId);
        JsonNode changes = full == null ? Json.arr() : full.path("changes");
        ctx.ok(Json.obj().set("changes", changes));
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
        Path dir = store.dirOf(taskId);
        // 存在性:与 task.poll / task.rounds 同口径(目录在盘 或 内存任务/磁盘索引可见任一即存在)
        boolean known = Files.isDirectory(dir) || tasks.containsKey(taskId) || diskTasks.containsKey(taskId);
        if (!known) {
            ctx.err(Rpc.ERR_NOT_FOUND, "task 不存在: " + taskId);
            return;
        }
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
                    r.subs(), 0L, null, r.userMessage()));
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

    /** Round → wire 形态(seq 一律字符串;endSeq 未闭合为 "";durationMs 数值;roundId 非空才写;fileChanges 非 null 写为数组;subs 恒数组;与 rounds.jsonl 行格式一致)。 */
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
        if (r.fileChanges() != null) {
            n.set("fileChanges", r.fileChanges()); // 本轮文件变更轻量摘要:无变更不写
        }
        if (r.userMessage() != null) {
            n.set("userMessage", r.userMessage()); // 完整 user.message payload:懒加载骨架起点(旧行缺失不写)
        }
        ArrayNode subs = Json.arr();
        for (RoundIndex.SubRange s : r.subs()) {
            subs.add(Json.obj()
                    .put("agentId", s.agentId())
                    .put("title", s.title())
                    .put("startSeq", s.startSeq() == null ? "" : String.valueOf(s.startSeq()))
                    .put("endSeq", s.endSeq() == null ? "" : String.valueOf(s.endSeq())));
        }
        n.set("subs", subs);
        return n;
    }

    /** task.run:创建/续跑合一——无 taskId=新建(workspace 必填),有 taskId=运行中入队/终态冷启动续跑。 */
    private void rpcTaskRun(RpcContext ctx) {
        String input = ctx.strParam("input");
        String rawContent = ctx.optStrParam("rawContent", null);
        String existingId = ctx.optStrParam("taskId", "");
        if (!existingId.isEmpty()) {
            runExistingTask(ctx, existingId, input, rawContent);
            return;
        }
        String title = ctx.optStrParam("title", null);
        if (title == null || title.isEmpty()) {
            title = input.length() > 40 ? input.substring(0, 40) + "…" : input;
        }
        String idemKey = ctx.optStrParam("idempotencyKey", null);
        if (idemKey != null && !idemKey.isEmpty()) {
            purgeStaleIdem();
            IdemEntry e = idem.get(idemKey);
            if (e != null && System.currentTimeMillis() - e.ts() < IDEM_WINDOW_MS && tasks.containsKey(e.taskId())) {
                ctx.ok(Json.obj().put("taskId", e.taskId()).put("deduplicated", true));
                return;
            }
        }
        if (active.get() >= props.getLimits().getMaxConcurrentTasks()) {
            ctx.err(Rpc.ERR_BUSY, "并发任务已达上限 " + props.getLimits().getMaxConcurrentTasks());
            return;
        }
        WorkspaceManager.Root root;
        try {
            root = workspaces.resolveAndRegister(ctx.strParam("workspace")); // 注册 + 校验(必填)
        } catch (java.io.IOException e) {
            ctx.err(Rpc.ERR_INTERNAL, "工作区目录不可用: " + e.getMessage());
            return;
        }
        String taskId = ShortIds.taskId();
        String mainAgentId = ShortIds.mainAgentId();
        ResolvedConfig cfg = configs.resolve(ctx.optStrParam("configId", null));
        TaskEntry t = new TaskEntry(taskId, title, cfg.snapshot(),
                cfg.apiKey(), root.path().toString(), mainAgentId,
                props.getLimits().getMaxEventsPerTask());
        tasks.put(taskId, t);
        // slash 任务级 token(task.run 可选入参 taskTokens):仅采纳合法 opaque(parseToken 非空);
        // 非法条目记日志跳过,不阻塞任务创建。add 先于 store.track,确保首落盘 meta 含 slashTaskTokens。
        JsonNode tt = ctx.params().path("taskTokens");
        if (tt.isArray()) {
            for (JsonNode e : tt) {
                if (!e.isTextual()) {
                    continue;
                }
                String opaque = e.asText();
                if (SlashTokenEncoder.parseToken(opaque) != null) {
                    t.addSlashTaskToken(opaque);
                } else {
                    log.warn("slash 任务 token 非法跳过 task={} token={}", taskId, opaque);
                }
            }
        }
        wireUsageBroadcast(t);
        wireAgentPersist(t);
        active.incrementAndGet();
        if (idemKey != null && !idemKey.isEmpty()) {
            idem.put(idemKey, new IdemEntry(taskId, System.currentTimeMillis()));
        }
        try {
            store.track(taskId, t.log, t::summaryJson);
        } catch (java.io.IOException e) {
            log.error("任务落盘启动失败 task={}(继续内存运行,重启后丢失)", taskId, e);
        }
        pool.pubAllTasks(Events.TASK_CREATED, null, t.runtimeSummaryJson(), null);
        ctx.ok(Json.obj().put("taskId", taskId).put("status", t.status.wire()));
        // slash 建后回调:对每个已写入 token 调业务 onSelect(taskId)(实现见 notifySlashCallbacks)。
        notifySlashCallbacks(t, taskId);
        t.runFuture = vt.submit(() -> runTask(t, UserInput.of(input, rawContent), List.of()));
    }

    /**
     * slash 任务级 token 建后回调:对每个已写入 token 解析 payload.slashId 反查 slash 条目并调业务
     * onSelect(taskId)——注册方用 taskId 把业务标记写入内存并落盘 meta(自身实现)。整段
     * try/catch(RuntimeException) 兜底,反查 NotFound 与 onSelect 异常仅记日志,
     * 绝不影响任务创建/运行/续跑。新建任务与冷启动续跑(startRerun)共用。
     */
    private void notifySlashCallbacks(TaskEntry t, String taskId) {
        try {
            for (String opaque : t.slashTaskTokens()) {
                SlashTokenEncoder.ParsedToken parsed = SlashTokenEncoder.parseToken(opaque);
                if (parsed == null) {
                    continue;
                }
                String slashId = parsed.payload().path("slashId").asString(null);
                if (slashId == null || slashId.isEmpty()) {
                    continue;
                }
                SlashCommandItem item = slashRegistry.itemById(slashId);
                item.selectHandler().onSelect(item, taskId);
            }
        } catch (RuntimeException e) {
            log.warn("slash 任务令牌建后回调失败 task={}", taskId, e);
        }
    }

    /**
     * 有 taskId 的 task.run:运行中(waiting-user 含)→入队并广播队列快照;
     * 内存终态/磁盘任务→认领后冷启动续跑(与 task.input 触发的再运行同一套认领纪律:
     * diskTasks.remove 原子互斥、不存在→NOT_FOUND、绕并发上限)。
     * 认领同步完成即应答,重活(载历史/运行)在虚拟线程。
     */
    private void runExistingTask(RpcContext ctx, String taskId, String input, String rawContent) {
        TaskEntry t = tasks.get(taskId);
        if (t != null) {
            if (!t.status.terminal()) {
                t.inputQueue.offer(input, rawContent); // 运行中:与 task.input 同路径入队
                t.touch();
                publishQueue(t);
                ctx.ok(Json.obj().put("taskId", taskId).put("status", t.status.wire()).put("queued", true));
                return;
            }
            synchronized (t) {
            } // 等 finish 驱逐(flush 后 tasks.remove;随后必在 diskTasks)
        }
        TaskStore.StoredTask st = diskTasks.remove(taskId); // 原子认领(与 delete/并发再运行互斥)
        if (st == null) {
            // 认领输家:并发再运行可能刚重建热任务,重查内存按运行中入队兜底
            TaskEntry again = tasks.get(taskId);
            if (again != null && !again.status.terminal()) {
                again.inputQueue.offer(input, rawContent);
                again.touch();
                publishQueue(again);
                ctx.ok(Json.obj().put("taskId", taskId).put("status", again.status.wire()).put("queued", true));
                return;
            }
            ctx.err(Rpc.ERR_NOT_FOUND, "任务不存在");
            return;
        }
        final TaskStore.StoredTask claimed = st;
        vt.submit(() -> {
            try {
                startRerun(claimed, UserInput.of(input, rawContent), ctx.optStrParam("configId", null));
            } catch (Throwable e) {
                log.error("再运行失败 task={}: {}", taskId, RootCause.summary(e));
                log.debug("再运行失败 task={} 完整堆栈", taskId, e);
                diskTasks.putIfAbsent(taskId, claimed); // 放回索引,保留可重试
            }
        });
        ctx.ok(Json.obj().put("taskId", taskId).put("status", "created"));
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
        t.stopRequested = true; // 阻止取消竞态窗口内再启动新子 agent
        asks.cancelTask(t.taskId, "user");
        subs.stopAll(t);
        Future<?> f = t.runFuture;
        if (f != null) {
            f.cancel(true);
        }
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

    /** task.queueRemove:删除某条队列输入(参数 taskId, index)。 */
    private void rpcTaskQueueRemove(RpcContext ctx) {
        String taskId = ctx.strParam("taskId");
        int index = ctx.params().path("index").asInt(-1);
        mutateQueue(ctx, taskId, QueueOp.REMOVE, index, -1, -1);
    }

    /** task.queueMove:移动(重排)某条队列输入(参数 taskId, fromIndex, toIndex)。 */
    private void rpcTaskQueueMove(RpcContext ctx) {
        String taskId = ctx.strParam("taskId");
        int fromIndex = ctx.params().path("fromIndex").asInt(-1);
        int toIndex = ctx.params().path("toIndex").asInt(-1);
        mutateQueue(ctx, taskId, QueueOp.MOVE, fromIndex, fromIndex, toIndex);
    }

    /** 队列修改操作(remove 用 index;move 用 fromIndex/toIndex)。 */
    private enum QueueOp { REMOVE, MOVE }

    /**
     * queueRemove / queueMove 共用骨架(定位 + 分热/冷执行):
     * 运行中热任务直接改内存 InputQueue(removeAt/move,越界抛 IOOBE → BAD_PARAMS)
     * 并 touch + publishQueue(task.updated 携带 pendingInputs 快照,前端镜像实时);
     * 终态任务改磁盘悬空队列 queue.jsonl(整读改写,失败 INTERNAL)并广播 task.updated
     * (deepCopy 后再 set pendingInputs,严禁原地改共享 summary)。
     * 参数缺失(BadParamsException)/越界 BAD_PARAMS;不存在 NOT_FOUND。
     */
    private void mutateQueue(RpcContext ctx, String taskId, QueueOp op, int index, int fromIndex, int toIndex) {
        TaskEntry t = tasks.get(taskId);
        if (t != null) {
            if (!t.status.terminal()) {
                // 热任务:直接在内存队列上操作(越界抛 IndexOutOfBoundsException)
                try {
                    if (op == QueueOp.REMOVE) {
                        t.inputQueue.removeAt(index);
                    } else {
                        if (fromIndex == toIndex) {
                            ctx.ok(Json.obj().put("taskId", taskId).put("ok", true)); // 无操作
                            return;
                        }
                        t.inputQueue.move(fromIndex, toIndex);
                    }
                } catch (IndexOutOfBoundsException e) {
                    ctx.err(Rpc.ERR_BAD_PARAMS, "队列索引越界");
                    return;
                }
                t.touch();
                publishQueue(t);
                ctx.ok(Json.obj().put("taskId", taskId).put("ok", true));
                return;
            }
            synchronized (t) {
            } // 等 finish 驱逐完成(flush 后任务必在 diskTasks),随后走冷路径
        }
        // 冷路径:终态任务的磁盘悬空队列(queue.jsonl)整读改写
        TaskStore.StoredTask st = diskTasks.get(taskId);
        if (st == null) {
            ctx.err(Rpc.ERR_NOT_FOUND, "任务不存在");
            return;
        }
        Path dir = st.dir();
        List<UserInput> list = new ArrayList<>(store.readQueue(dir));
        if (op == QueueOp.REMOVE) {
            if (index < 0 || index >= list.size()) {
                ctx.err(Rpc.ERR_BAD_PARAMS, "队列索引越界");
                return;
            }
            list.remove(index);
        } else {
            if (fromIndex == toIndex) {
                ctx.ok(Json.obj().put("taskId", taskId).put("ok", true)); // 无操作
                return;
            }
            if (fromIndex < 0 || fromIndex >= list.size() || toIndex < 0 || toIndex >= list.size()) {
                ctx.err(Rpc.ERR_BAD_PARAMS, "队列索引越界");
                return;
            }
            UserInput item = list.remove(fromIndex);
            list.add(toIndex, item);
        }
        try {
            store.writeQueue(dir, list);
        } catch (java.io.IOException e) {
            ctx.err(Rpc.ERR_INTERNAL, "队列写入失败");
            return;
        }
        ObjectNode summary = st.summary().deepCopy(); // 共享引用,严禁原地修改
        ArrayNode arr = Json.arr();
        for (UserInput item : list) {
            arr.add(item.text());
        }
        summary.set("pendingInputs", arr);
        pool.pubAllTasks(Events.TASK_UPDATED, null, summary, null);
        ctx.ok(Json.obj().put("taskId", taskId).put("ok", true));
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
        pool.pubAllTasks(Events.TASK_DELETED, null,
                Json.obj().put("taskId", taskId), null);
        return DeleteResult.OK;
    }

    /**
     * workspaces.remove 级联:删除挂靠指定工作区根的全部任务数据
     * (meta.json/jsonl 等系统落盘,位于 data/tasks/&lt;taskId&gt;/;绝不动工作区目录本身)。
     * 运行中任务跳过(与 task.delete 语义一致),返回实际删除数。
     */
    public int deleteByWorkspace(String workspaceRoot) {
        int deleted = 0;
        // 冷任务(磁盘索引;含已驱逐的终态任务)
        for (TaskStore.StoredTask st : store.scan()) {
            if (!workspaceRoot.equals(st.summary().path("workspace").asString(""))) {
                continue;
            }
            if (deleteTask(st.taskId()) == DeleteResult.OK) {
                deleted++;
            }
        }
        // 热任务(运行中驻留内存;运行中由 deleteTask 拒绝,终态兜底删除)
        for (TaskEntry t : tasks.values()) {
            if (!workspaceRoot.equals(t.workspaceRoot)) {
                continue;
            }
            if (deleteTask(t.taskId) == DeleteResult.OK) {
                deleted++;
            }
        }
        if (deleted > 0) {
            log.info("workspaces.remove 级联删除 {} 个任务(工作区 {})", deleted, workspaceRoot);
        }
        return deleted;
    }

    /**
     * workspaces.resolveMissing(纠正路径)迁移:把挂靠旧工作区根的任务 meta.workspace
     * 改到新根,保证任务仍归属移动后的工作区、后续运行沙箱挂载新目录。运行中任务直接改
     * 内存字段并回写 meta;磁盘终态任务原地改 meta.json 后替换内存索引镜像。返回迁移数。
     */
    public int redirectWorkspace(String oldRoot, String newRoot) {
        int moved = 0;
        for (TaskStore.StoredTask st : store.scan()) {
            if (!oldRoot.equals(st.summary().path("workspace").asString(""))) {
                continue;
            }
            ObjectNode copy = st.summary().deepCopy();
            copy.put("workspace", newRoot);
            try {
                TaskStore.writeMeta(st.dir(), copy);
            } catch (java.io.IOException e) {
                log.warn("任务 workspace 迁移写盘失败 task={}", st.taskId(), e);
                continue;
            }
            diskTasks.put(st.taskId(), new TaskStore.StoredTask(st.taskId(), st.dir(), copy));
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

    private void rpcConfigGet(RpcContext ctx) {
        ArrayNode arr = Json.arr();
        for (ModelConfig c : configs.list()) {
            ModelConfig safe = new ModelConfig(c.configId(), c.provider(), c.baseUrl(),
                    c.model(), c.apiKey() == null || c.apiKey().isEmpty() ? null : "******",
                    c.params(), c.isDefault(), c.members());
            arr.add(Json.toJson(safe));
        }
        ctx.ok(Json.obj().set("models", arr));
    }

    // ---- 终态任务再运行(冷启动;无"续跑"概念,对 agent 就是一次普通运行)----

    /**
     * 轻认领在消息线程(原子),重活进虚拟线程:
     * diskTasks.remove 认领(与 delete/并发 rerun 互斥,输家直接返回);
     * 热终态任务先等 finish 驱逐完成(空 synchronized 块)再走冷路径。
     */
    private void rerunTask(HubLink conn, String taskId, String text, String rawContent) {
        TaskEntry hot = tasks.get(taskId);
        if (hot != null) {
            if (!hot.status.terminal()) {
                // finish 尚未开始(竞态窗口极小):直接入队
                hot.inputQueue.offer(text, rawContent);
                hot.touch();
                return;
            }
            synchronized (hot) {
            } // 等 finish 驱逐(flush 后 tasks.remove)
            if (tasks.get(taskId) != null) {
                return; // 已被并发 rerun 重建,交给它
            }
        }
        TaskStore.StoredTask st = diskTasks.remove(taskId);
        if (st == null) {
            log.warn("再运行认领失败(任务不存在或已被并发操作): {}", taskId);
            return;
        }
        vt.submit(() -> {
            try {
                startRerun(st, UserInput.of(text, rawContent), null);
            } catch (Throwable e) {
                log.error("再运行失败 task={}: {}", taskId, RootCause.summary(e));
                log.debug("再运行失败 task={} 完整堆栈", taskId, e);
                diskTasks.putIfAbsent(taskId, st); // 放回索引,保留可重试
            }
        });
    }

    private void startRerun(TaskStore.StoredTask st, String text) throws java.io.IOException {
        startRerun(st, UserInput.of(text), null);
    }

    /**
     * 冷启动续跑(载入磁盘历史,一次普通运行)。
     * @param overrideConfigId 输入箱切换模型时透传的 configId;为空则沿用任务最后运行的
     *                         configId(配置已删回退默认),与历史续跑语义一致。
     */
    private void startRerun(TaskStore.StoredTask st, UserInput initialInput, String overrideConfigId) throws java.io.IOException {
        JsonNode meta = st.summary();
        String taskId = st.taskId();
        String mainAgentId = meta.path("mainAgentId").asString("");
        if (mainAgentId.isEmpty() || !Files.isDirectory(st.dir())) {
            log.warn("任务不可再运行(旧格式或目录缺失): {}", taskId);
            diskTasks.putIfAbsent(taskId, st);
            return;
        }
        // 模型:输入箱切换时以 overrideConfigId 优先;否则沿用任务最后运行的 configId
        // (配置已删回退默认)。override 非法时回退任务原始 configId,再不行回退默认。
        ResolvedConfig cfg;
        String desiredConfigId = (overrideConfigId != null && !overrideConfigId.isEmpty())
                ? overrideConfigId
                : meta.path("configId").asString(null);
        try {
            cfg = configs.resolve(desiredConfigId);
        } catch (NotFoundException e) {
            try {
                cfg = configs.resolve(meta.path("configId").asString(null));
            } catch (NotFoundException e2) {
                cfg = configs.resolve(null);
            }
        }
        TaskEntry t = new TaskEntry(taskId,
                meta.path("title").asString("继续对话"), cfg.snapshot(), cfg.apiKey(),
                meta.path("workspace").asString(null), mainAgentId,
                props.getLimits().getMaxEventsPerTask());
        t.createdAt(meta.path("createdAt").asLong(0));
        t.aiReview = meta.path("aiReview").asBoolean(false);      // AI 审议任务级开关(plan-unattended-ai-auth 步骤3)
        t.unattended = meta.path("unattended").asBoolean(false);  // 无人值守任务级开关(plan-unattended-ai-auth 步骤3)
        t.networkBlocked = meta.path("networkBlocked").asBoolean(false); // 禁网开关任务级(/禁用网络)
        t.powershellEnabled = meta.path("powershellEnabled").asBoolean(false); // 启用 powershell 开关任务级(/启用powershell)
        t.seedUsageMeta(meta.path("usage")); // 恢复最近一轮上下文用量(续跑后列表/电池数据不丢)
        restoreAgentLedger(t, meta); // 恢复子 agent 台账(冷启动后 list_agents/wait_agents 正常)
        // slash 任务级 token 回读(仅 slash 层存储、业务方不读;随 meta.json 落盘,冷启动续跑恢复)。
        // 在 store.track 之前完成 add,确保首落盘 meta 含 slashTaskTokens。
        JsonNode tt = meta.path("slashTaskTokens");
        if (tt.isArray()) {
            for (JsonNode e : tt) {
                if (e.isTextual()) {
                    t.addSlashTaskToken(e.asText());
                }
            }
        }
        t.log.seed(store.seqLastOf(st.dir())); // 新事件从 meta.seqLast 水位续号(含瞬态占位水位,磁盘不再写占位行)
        wireUsageBroadcast(t);
        wireAgentPersist(t);
        if (tasks.putIfAbsent(taskId, t) != null) {
            diskTasks.putIfAbsent(taskId, st);
            return; // 并发兜底(不应发生:认领已互斥)
        }
        // 再运行通知:唤醒该任务的定向推送器立即对账换挂新日志——纯 1s 对账在极快续跑
        // (两次对账间完成并驱逐)时会漏推整轮,即「终态任务再运行后前端看不到新输出」的根因。
        for (TaskResumeListener l : resumeListeners) {
            try {
                l.onTaskResumed(taskId);
            } catch (RuntimeException e) {
                log.debug("再运行通知失败 task={}", taskId, e);
            }
        }
        // 恢复悬空队列(上轮终态落盘的 queue.jsonl):先跑本轮新输入,自然完成后逐条 poll 消费;
        // 必须在 runFuture 提交前 offer 完毕,避免 runTask 线程先 poll 到空队列而错过。
        for (UserInput q : store.readQueue(st.dir())) {
            t.inputQueue.offer(q.text(), q.rawContent());
        }
        // 再运行启动:恢复悬空队列后即开始;实时增量由上方通知唤醒的定向推送器换挂推送,历史/补齐由前端拉取
        try {
            store.track(taskId, t.log, t::summaryJson);
        } catch (java.io.IOException e) {
            log.error("再运行落盘启动失败 task={}(继续内存运行)", taskId, e);
        }
        // slash 任务级 token 建后回调:终态任务 meta 已含 slashTaskTokens,再运行冷启动需对每个
        // 已存在 token 补调业务 onSelect(taskId)——注册方据此用 taskId 把业务标记重新写入内存并落盘
        // (如 AI 审议/无人值守/网络开关),保证「已有任务在终态时 apply bottom token,再运行后业务标记生效」。
        notifySlashCallbacks(t, taskId);
        // 模型切换 trace:用户从输入框切到其它模型后续跑,在主线显式标注本轮所用模型,
        // 否则用户看不到"本轮用了哪个模型"(旧任务冻结模型被覆盖,且 worker 已按 override 解析新模型)。
        final ModelSnapshot effectiveSnapshot = cfg.snapshot();
        String storedConfigId = meta.path("configId").asString(null);
        if (overrideConfigId != null && !overrideConfigId.isEmpty()
                && (storedConfigId == null || !storedConfigId.equals(overrideConfigId))) {
            emitQuietly(() -> t.events.modelSwitch(effectiveSnapshot, storedConfigId));
        }
        int now = active.incrementAndGet(); // 再运行放行不查上限(用户单发驱动);越限记日志
        if (now > props.getLimits().getMaxConcurrentTasks()) {
            log.warn("并发任务越限(再运行放行): {} / {}", now, props.getLimits().getMaxConcurrentTasks());
        }
        List<Message> prior = ConversationLoader.load(store, st.dir(), mainAgentId); // 冷启动:磁盘重建上下文
        t.runFuture = vt.submit(() -> runTask(t, initialInput, prior));
    }

    // ---- 任务主流程 ----

    private void runTask(TaskEntry t, UserInput initialInput, List<Message> priorConversation) {
        AgentEntity main = null;
        try {
            t.startedAt = System.currentTimeMillis();
            setStatus(t, TaskStatus.RUNNING);
            t.events.agentStatus(t.mainAgentId, "running"); // 主 agent 开跑(agent 列表状态机)
            main = buildMainAgent(t, priorConversation);
            t.main = main; // 主 agent 引用(运行期状态供持久化/诊断)
            consumeInput(t, main, initialInput);
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("cancelled");
                }
                // 文件改动收集与收口由 FileChangeAdvisor 承担(每轮 run 前建收集器、流完成时填充
                // light/full 槽);RoundIndexAdvisor 在流完成时把摘要/全文随轮落盘,
                // MeasureDurationAdvisor 再回填耗时——经 doOnComplete 嵌套顺序保证「文件变更先、耗时后」。
                runner.run(main);
                UserInput next = t.inputQueue.poll();
                if (next == null) {
                    // 队列插入兜底:插入事件在收尾轮模型调用期间(advisor 的 before 已过)到达时,
                    // 本轮 advisor 未能注入;这里把残留插入消费为普通用户输入,保证不丢
                    // (该输入仍会在下一轮进入模型上下文)。
                    next = main.pendingDialogInserts.poll();
                    if (next == null) {
                        break;
                    }
                    consumeInput(t, main, next);
                    continue;
                }
                consumeInput(t, main, next);
                publishQueue(t); // 消费一条少一条(与 user.message 同拍广播)
            }
            subs.awaitAllBeforeFinish(t); // 收口:自动等待全部子 agent(§5.6)
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("cancelled");
            }
            finish(t, TaskStatus.DONE, null);
        } catch (InterruptedException e) {
            // 先收口再恢复中断标记:finish 内 flush / updateMeta 需要文件 I/O,
            // 若带着中断标记进入会立即抛 ClosedByInterruptException,导致终态收口不完整。
            t.stopRequested = true; // 与 rpcTaskCancel 同语义:停止后不再启动新子 agent
            subs.stopAll(t);
            asks.cancelTask(t.taskId, "user");
            emitQuietly(() -> t.events.cancelled("user"));
            finish(t, TaskStatus.CANCELLED, null);
            Thread.currentThread().interrupt();
        } catch (EventLog.LogOverflowException loe) {
            subs.stopAll(t);
            asks.cancelTask(t.taskId, "worker");
            finish(t, TaskStatus.FAILED, "LOG_OVERFLOW: " + loe.getMessage()); // 日志已满,无法再写事件
        } catch (Throwable e) {
            // 收口:ERROR 打一行根因摘要(BaseAdvisor 多层 "Stream processing failed" 包装
            // 把真实错误埋在最里层,完整堆栈降 DEBUG,避免数百行 reactor 帧刷屏)。
            log.error("任务 {} 失败: {}", t.taskId, RootCause.summary(e));
            log.debug("任务 {} 失败完整堆栈", t.taskId, e);
            subs.stopAll(t);
            asks.cancelTask(t.taskId, "worker");
            String msg = RootCause.summary(e);
            emitQuietly(() -> t.events.error(t.mainAgentId, msg));
            finish(t, TaskStatus.FAILED, msg);
        }
    }

    /** 消费一条用户输入:授权本轮失效 + 记 user.message + 开轮落盘 + 入会话内存。 */
    private void consumeInput(TaskEntry t, AgentEntity main, UserInput input) {
        String text = input.text();
        String rawContent = input.rawContent();
        gate.beginRun(t.taskId); // 新一条用户输入:本轮(run)授权失效(任务级不受影响)
        // user.message 落盘后以它的 seq 为轮起点开轮:最后一行未闭合则沿用(中间输入/续跑不开新轮);
        // 已闭合/无行则追加一条 endSeq="" 的未闭合轮。中断/取消/失败不再于终态补写,轮行随开轮即持久化。
        long seq = t.events.userMessage(text, rawContent);
        // 开轮落盘带完整 user.message payload(懒加载骨架起点;与 userMessage 事件 payload 同源):
        // text 供展示/AI 摘要,rawContent 供前端回放还原胶囊。
        ObjectNode userPayload = Json.obj().put("text", text);
        if (rawContent != null && !rawContent.isEmpty()) {
            userPayload.put("rawContent", rawContent);
        }
        if (roundIndexStore.openRoundAtStart(store, t.taskId, seq, text, userPayload)) {
            // 真的新开一轮(非中间输入/续跑沿用)才推 round.opened;瞬态不落盘。
            t.events.roundOpened(seq, text);
        }
        main.conversation.add(new UserMessage(text));
        t.touch();
    }

    /** 主 agent:agentId = 任务 mainAgentId(再运行沿用);priorConversation 为冷启动载入的历史。 */
    private AgentEntity buildMainAgent(TaskEntry t, List<Message> priorConversation) {
        // 渐进式披露:内置 skill 知识包由 BuiltInSkills 启动时物化到系统技能目录(§5.10),
        // 不在任务侧重复物化;AI 按需按绝对路径 read_file 读取(系统技能目录只读放行)。
        ResolvedConfig cfg = resolveAgentConfig(t);
        List<ToolCallback> tools = new ArrayList<>();
        for (ToolCallback c : ToolCallbacks.from(new AskUserTool(asks, props, t, t.mainAgentId))) {
            tools.add(c);
        }
        for (ToolCallback c : ToolCallbacks.from(new SubAgentTools(subs, t))) {
            tools.add(c);
        }
        // 文件工具(file,移植自 novel_agent-n;工作区外访问经 PermissionGate 授权)
        for (ToolCallback c : ToolCallbacks.from(new FileTools(fs, t, t.mainAgentId))) {
            tools.add(c);
        }
        // 真实 OS 进程命令执行器(非工具):授权检查 + OsSandbox 降权隔离(Windows 套 Job Object + Restricted Token)
        // rg 二进制所在目录随 bash/powershell 子进程注入命令 PATH(缺失时传 null 不注入)
        java.nio.file.Path rg = rgbin.path();
        CommandExecutor exec = new CommandExecutor(sandbox, t, gate, t.mainAgentId,
                rg != null ? rg.getParent() : null);
        // 平台化命令执行工具:按沙箱后端选方言——wsl-bwrap(命令进 WSL 发行版,bash)与
        // Linux/macOS 注册 bash;windows-mic 回退后端注册 powershell(描述动态注入系统默认编码提示)
        if (isWindows() && !sandbox.registerBashTool()) {
            tools.add(new PowerShellTool(exec).toolCallback());
        } else {
            for (ToolCallback c : ToolCallbacks.from(new BashTool(exec))) {
                tools.add(c);
            }
            // 任务级「启用 powershell」(仅 WSL+Linux 后端有该斜杠条目):bash 之外追加
            // PowerShellTool(发行版内 pwsh 执行),让 AI 同时拥有 powershell 与 bash;
            // windows-mic 后端无此开关,PowerShellTool 已在上方独占注册,不会重复。
            if (t.powershellEnabled) {
                tools.add(new PowerShellTool(exec, sandbox.isWslBackend()).toolCallback());
            }
        }
        // M5:fs/git 模型工具在此追加;tool.result 事件由 AgentRunner 统一发射
        // 模型装配:普通模型 → OpenAiChatModel;provider=model-pool → ModelPoolChatModel(自动容灾)。
        // Agent 请求 options 基底:普通 = 自身快照,池 = 首成员(主模型)快照(上下文压缩等 advisor 据此读参数)。
        ChatModelFactory.AgentModel am = modelFactory.buildAgentModel(cfg, t.mainAgentId, t.events, null);
        AgentEntity main = new AgentEntity(t, t.mainAgentId, AgentEntity.Kind.MAIN, "主 agent",
                am.chatModel(), am.options(), tools);
        main.conversation.addAll(priorConversation);
        return main;
    }

    /**
     * 任务运行配置:普通模型用冻结快照(t.snapshot/t.apiKey);池配置(configId 指向 provider=model-pool)
     * 重新经 ConfigStore 解析以获得池成员列表——冻结快照不含成员信息,必须回查
     * (运行期 worker.models 启动即物化、无热更新,与创建时解析一致)。
     */
    private ResolvedConfig resolveAgentConfig(TaskEntry t) {
        if (ConfigStore.POOL_PROVIDER.equals(t.snapshot.provider())) {
            return configs.resolve(t.snapshot.configId());
        }
        return new ResolvedConfig(t.snapshot, t.apiKey);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * 终态收口(全程持任务监视器,与再运行/删除的空 synchronized(t){} 互斥):
     * flush → updateMeta → 写磁盘索引 → untrack 双件套 → tasks.remove(两参原子)
     * → TaskEntry 连同 EventLog 丢弃(销毁,不占内存)。磁盘是唯一真相源。
     */
    private void finish(TaskEntry t, TaskStatus status, String error) {
        if (t.status.terminal()) {
            return;
        }
        synchronized (t) {
            if (t.status.terminal()) {
                return;
            }
            t.endedAt = System.currentTimeMillis();
            t.error = error;
            t.status = status;
            emitQuietly(() -> t.events.agentStatus(t.mainAgentId, agentStatusOf(status))); // 主 agent 终态(落盘)
            pool.pubAllTasks(Events.TASK_UPDATED, null, t.runtimeSummaryJson(), null);
            active.decrementAndGet();
            try {
                store.flush(t.taskId); // 全量落盘(无 trim:磁盘永久保留)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // 轮次索引不在此补写:未闭合尾轮已由开轮路径(consumeInput→openRoundAtStart)持久化为
            // endSeq="" 行,已闭合轮由 RoundIndexAdvisor 增量闭合;终态无需对账/补写(不做自愈)。
            // 终态「悬空保留」:未消费队列落盘 queue.jsonl(冷启动续跑时恢复逐条消费);
            // 队列已空则清掉可能残留的旧文件。写盘失败只记日志,不让 finish 崩溃。
            List<UserInput> pending = t.inputQueue.snapshotItems();
            Path qdir = store.dirOf(t.taskId);
            try {
                if (pending.isEmpty()) {
                    store.deleteQueue(qdir);
                } else {
                    store.writeQueue(qdir, pending);
                }
            } catch (java.io.IOException e) {
                log.warn("终态队列落盘失败 task={}(悬空队列丢弃)", t.taskId, e);
            }
            store.updateMeta(t.taskId);
            diskTasks.put(t.taskId, new TaskStore.StoredTask(t.taskId,
                    store.dirOf(t.taskId), t.summaryJson()));
            store.untrack(t.taskId);
            gate.untrack(t.taskId); // 授权内存驱逐(任务级已在 grants.json,再运行 lazy 重载)
            tasks.remove(t.taskId, t); // 两参原子:认领者(并发 rerun/delete)以此判断输赢
        }
    }

    private void setStatus(TaskEntry t, TaskStatus s) {
        synchronized (t) {
            if (t.status == s) {
                return;
            }
            t.status = s;
        }
        pool.pubAllTasks(Events.TASK_UPDATED, null, t.runtimeSummaryJson(), null);
    }

    /** 队列变化广播:task.updated 携带最新 pendingInputs 快照(§3.3,运行时态不落盘)。 */
    private void publishQueue(TaskEntry t) {
        pool.pubAllTasks(Events.TASK_UPDATED, null, t.runtimeSummaryJson(), null);
    }

    /**
     * 注入任务级 usage 实时广播:WorkerToolEventAdvisor 在主 agent 每轮实测 usage 后触发,
     * 广播 task.updated(携带最新 usage 快照,前端列表电池实时刷新)。终态后不再触发。
     * fire-and-forget:广播失败不影响任务线程。
     */
    private void wireUsageBroadcast(TaskEntry t) {
        t.onUsageBroadcast = () -> {
            if (t.status.terminal()) {
                return;
            }
            try {
                pool.pubAllTasks(Events.TASK_UPDATED, null, t.runtimeSummaryJson(), null);
            } catch (RuntimeException e) {
                log.debug("任务用量广播失败 task={}", t.taskId, e);
            }
        };
    }

    /**
     * 注入 agent 台账持久化钩子:SubAgentManager 在子 agent 创建/终态收口时触发,
     * 把最新 agent 元数据写盘(崩溃后冷启动可恢复台账)。终态后不再触发(finish 统一落盘)。
     * fire-and-forget:失败不影响任务线程。
     */
    private void wireAgentPersist(TaskEntry t) {
        t.persistHook = () -> {
            if (t.status.terminal()) {
                return;
            }
            try {
                store.updateMeta(t.taskId);
            } catch (RuntimeException e) {
                log.debug("agent 台账持久化失败 task={}", t.taskId, e);
            }
        };
    }

    /**
     * 定时持久化运行中任务的 agent 台账(每 30s,见 init):agent 元数据随 meta.json 落盘,
     * worker 崩溃/重启后可恢复,冷启动后 list_agents/wait_agents 仍能看到历史子 agent。
     */
    private void persistAgentLedgers() {
        for (TaskEntry t : tasks.values()) {
            if (t.status.terminal()) {
                continue; // 终态由 finish 统一落盘
            }
            if (t.agentLedger.isEmpty()) {
                continue; // 无子 agent,无需写
            }
            try {
                store.updateMeta(t.taskId);
            } catch (RuntimeException e) {
                log.debug("agent 台账定时持久化失败 task={}", t.taskId, e);
            }
        }
    }

    /**
     * 冷启动续跑:从 meta.json 的 agents 数组恢复子 agent 台账。
     * 重启后内存无活实体,运行中/waiting-user 的 agent 统一视为 stopped(worker 重启中断),
     * 保持 list_agents/wait_agents 契约的终态语义。
     */
    private void restoreAgentLedger(TaskEntry t, JsonNode meta) {
        JsonNode agents = meta.path("agents");
        if (!agents.isArray()) {
            return;
        }
        for (JsonNode a : agents) {
            if (!a.isObject()) {
                continue;
            }
            String id = a.path("agentId").asString("");
            if (id.isEmpty()) {
                continue;
            }
            ObjectNode copy = ((ObjectNode) a).deepCopy(); // 共享引用,严禁原地修改
            String st = copy.path("status").asString("");
            if ("running".equals(st) || "waiting-user".equals(st)) {
                copy.put("status", "stopped");
            }
            t.agentLedger.put(id, copy);
        }
    }

    // ---- PendingAsks.StatusHook:waiting-user ⇄ running ----

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
        agentMetaScheduler.shutdownNow();
        vt.shutdownNow();
        for (TaskEntry t : tasks.values()) {
            if (!t.status.terminal()) {
                t.stopRequested = true;
                asks.cancelTask(t.taskId, "worker");
                subs.stopAll(t);
                Future<?> f = t.runFuture;
                if (f != null) {
                    f.cancel(true);
                }
                finish(t, TaskStatus.FAILED, "worker 停机");
            }
        }
    }

    private void emitQuietly(Runnable emitter) {
        try {
            emitter.run();
        } catch (RuntimeException e) {
            log.debug("终态事件写入失败(日志可能已满)", e);
        }
    }

    /** 任务终态 → 主 agent 状态(agent.status)。 */
    private static String agentStatusOf(TaskStatus s) {
        return switch (s) {
            case DONE -> "done";
            case FAILED -> "failed";
            case CANCELLED -> "stopped";
            default -> "running";
        };
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
     * 广播 task.updated 到全部连接:运行中任务用内存 runtimeSummaryJson(带 pendingInputs 快照),
     * 磁盘终态任务用磁盘 summary(共享引用,只读广播);任务都不存在则不广播。
     */
    public void publishTaskUpdated(String taskId) {
        TaskEntry t = tasks.get(taskId);
        if (t != null) {
            pool.pubAllTasks(Events.TASK_UPDATED, null, t.runtimeSummaryJson(), null);
            return;
        }
        TaskStore.StoredTask st = diskTasks.get(taskId);
        if (st != null) {
            pool.pubAllTasks(Events.TASK_UPDATED, null, st.summary(), null);
        }
    }

    /**
     * slash 任务级 token 快照(仅 slash 层存储、业务方不读):运行中读内存槽;
     * 磁盘读 summary 的 slashTaskTokens 数组(meta 缺失/非数组→空,仅收集 textual 项,
     * 与 TaskEntry.slashTaskTokens 语义一致);任务不存在返回空列表。
     */
    public List<String> slashTaskTokens(String taskId) {
        TaskEntry t = tasks.get(taskId);
        if (t != null) {
            return t.slashTaskTokens();
        }
        TaskStore.StoredTask st = diskTasks.get(taskId);
        if (st == null || st.summary() == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        JsonNode arr = st.summary().path("slashTaskTokens");
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                if (n.isTextual()) {
                    out.add(n.asText());
                }
            }
        }
        return out;
    }

    /**
     * slash 任务级 token 存储是否可寻(任务存在:运行中或磁盘索引可寻)
     * —— 供 slash.cancel 等后续步骤区分「任务不存在」与「token 不在」。
     */
    public boolean slashTaskTokenExists(String taskId) {
        return tasks.containsKey(taskId) || diskTasks.containsKey(taskId);
    }

    public int activeCount() {
        return active.get();
    }
}
