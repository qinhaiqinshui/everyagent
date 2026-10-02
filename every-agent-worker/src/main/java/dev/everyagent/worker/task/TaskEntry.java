package dev.everyagent.worker.task;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.event.EventLogReader;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.worker.agent.AgentEntity;
import dev.everyagent.worker.agent.AgentFactoryImpl;
import dev.everyagent.worker.interaction.SubjectBoundInteractionService;
import dev.everyagent.worker.proto.TaskDtos.TaskStatus;
import dev.everyagent.worker.proto.TaskDtos.TaskSummary;
import dev.everyagent.plugin.api.event.Usage;
import dev.everyagent.worker.proto.TaskDtos.UsageSummary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 任务运行时(架构 §5.8):快照 + 事件日志 + 输入队列 + agent 集合。
 * 运行完成即销毁(finish 里 untrack + tasks.remove),磁盘是唯一真相源;
 * 再运行 = 同 taskId 新建本对象(冷启动,mainAgentId 沿用 → 同一 jsonl 文件续写)。
 *
 * <p>S2 起 implements {@link TaskRuntime}(extends ExecContext):预绑定端口
 * agentFactory()/interaction() 见下方懒加载实现;S3 授权链收编 ExecContext 后,
 * 原 permission {@code TaskInfo} 过渡实现已退役删除。
 */
public final class TaskEntry implements TaskRuntime {

    public final String taskId;
    public final String title;
    public final ModelConfig snapshot;
    /**
     * 工作区根(meta.workspace;挂靠关系,任务数据存 workspaces/&lt;workspaceId&gt;/tasks 不随之迁移)。
     * 非 final:workspaces.resolveMissing 纠正路径时整体改挂到新目录(见
     * {@link dev.everyagent.worker.task.TaskManager#redirectWorkspace})。
     */
    public String workspaceRoot;
    /**
     * 任务挂靠工作区的稳定 id(meta.workspaceId;磁盘存储维度,任务数据目录
     * workspaces/&lt;workspaceId&gt;/tasks/&lt;taskId&gt;/ 的定位键)。
     * 创建时定死,纠正路径/再运行均不变(root 改变不影响 id)。
     */
    public final String workspaceId;
    /** 主 agent 稳定 Id:任务生命周期内不变,即 &lt;mainAgentId&gt;.jsonl 文件名(Spring AI conversationId)。 */
    public final String mainAgentId;
    public final EventLog log;
    public final TaskEvents events;

    /**
     * 最近一轮主 agent 实测 usage(上下文窗口占用口径,随 meta.json 持久化;无则 null)。
     * 由 WorkerToolEventAdvisor 在每次主 agent 模型调用末帧写入,子 agent 用量忽略
     * (与前端聊天页电池口径一致:主 agent 最近一轮 inputTokens / contextWindowTokens)。
     */
    private volatile Usage lastUsage;
    /** 最近一次携带的上下文窗口上限(模型/任务快照配置)。 */
    private volatile Long contextWindowTokens;
    /** 最近一轮所用模型名(usage 事件携带)。 */
    private volatile String usageModel = "";
    /**
     * 每轮主 agent usage 后触发:TaskManager 注入的 task.updated 实时广播(终态后不再触发)。
     * 弱引用语义:广播失败不阻塞任务线程。
     */
    public volatile Runnable onUsageBroadcast;

    /** @see dev.everyagent.plugin.api.task.TaskRuntime#onUsageBroadcastCallback() */
    @Override
    public Runnable onUsageBroadcastCallback() {
        return onUsageBroadcast;
    }

    /** 创建时间:新任务 = 当前时刻;再运行沿用 meta 原值(createdAt 不随续写重置)。 */
    public volatile long createdAt = System.currentTimeMillis();
    public volatile TaskStatus status = TaskStatus.CREATED;
    public volatile Long startedAt;
    public volatile Long endedAt;
    public volatile String error;

    /** 终态后的收尾工作是否已执行(finish 全程持本对象监视器,与再运行互斥)。 */
    public volatile boolean finalized;

    /**
     * 通用任务级持久化数据（替代原 taskFlags，Map<String, Boolean>）。
     * 插件用字符串 key 存取任意类型值（如 "ai-review"→Boolean、"unattended"→Boolean），
     * 核心不感知具体 key/value 类型。
     * 随 {@link #summaryJson()} 落盘 meta.json、再运行仍保持。旧格式自动迁移（见 TaskManager）。
     */
    public final java.util.Map<String, Object> metadata = new java.util.concurrent.ConcurrentHashMap<>();

    // ---- TaskRuntime 域中性槽位实现 ----

    @Override
    public String taskId() {
        return taskId;
    }

    @Override
    public String status() {
        return status.wire();
    }

    @Override
    public boolean terminal() {
        return status.terminal();
    }

    @Override
    public Map<String, Object> metadata() {
        return metadata;
    }

    /** 任务数据目录路径（由 TaskStore 定位，创建时注入）。 */
    private volatile Path taskDir;

    /** 注入任务数据目录路径（TaskEntryCreateNode 创建后调用）。 */
    public void taskDir(Path dir) {
        this.taskDir = dir;
    }

    @Override
    public Path taskDir() {
        return taskDir;
    }

    // ---- ExecContext 槽位显式实现(原 TaskRuntime default 桥接显式化,语义不变;§4.3) ----

    /** 执行主体 ID:任务域即 taskId。 */
    @Override
    public String subjectId() {
        return taskId;
    }

    /** 任务级事件口:桥接 {@link #events()}。 */
    @Override
    public EventEmitter emitter() {
        return events();
    }

    /** 数据目录:桥接 {@link #taskDir()}。 */
    @Override
    public Path dataDir() {
        return taskDir();
    }

    // ---- ExecContext 预绑定端口(agentFactory / interaction,§4.1;懒加载 + 裸依赖注入) ----

    /** 绑定 Agent 工厂的裸依赖(TaskManager → TaskEntryCreateNode 组装时注入)。 */
    private volatile AgentFactoryImpl agentFactoryImpl;
    /** 绑定交互口的裸依赖(TaskManager → TaskEntryCreateNode 组装时注入)。 */
    private volatile InteractionService interactionService;
    /** 懒加载缓存:agentFactoryImpl.bind(this)(TaskBoundAgentFactory,worker.agent 包内类型)。 */
    private volatile AgentFactory boundAgentFactory;
    /** 懒加载缓存:new SubjectBoundInteractionService(interactionService, taskId)。 */
    private volatile InteractionService boundInteraction;

    /**
     * 注入预绑定端口的裸依赖(TaskEntryCreateNode 创建本对象后调用,与 taskDir 注入同风格)。
     * 未注入时 agentFactory()/interaction() 返回 null(单测直构场景)。
     */
    public void bindExecPorts(AgentFactoryImpl agentFactory, InteractionService interaction) {
        this.agentFactoryImpl = agentFactory;
        this.interactionService = interaction;
    }

    /** 已绑定本任务的 Agent 工厂(TaskBoundAgentFactory 静态代理;唯一获取口,§4.5)。 */
    @Override
    public AgentFactory agentFactory() {
        AgentFactory bound = boundAgentFactory;
        if (bound != null) {
            return bound;
        }
        AgentFactoryImpl impl = agentFactoryImpl;
        if (impl == null) {
            return null;
        }
        bound = impl.bind(this);
        boundAgentFactory = bound;
        return bound;
    }

    /** 已绑定本任务的交互口(SubjectBoundInteractionService:ask 自动填 taskId,§4.1)。 */
    @Override
    public InteractionService interaction() {
        InteractionService bound = boundInteraction;
        if (bound != null) {
            return bound;
        }
        InteractionService raw = interactionService;
        if (raw == null) {
            return null;
        }
        bound = new SubjectBoundInteractionService(raw, taskId);
        boundInteraction = bound;
        return bound;
    }

    // ---- AgentContext 接口已删除，以下为 TaskEntry 自身方法 ----

    /** 工作区根路径。 */
    public String workspaceRoot() {
        return workspaceRoot;
    }

    // ---- TaskRuntime 接口方法 ----

    @Override
    public String workspaceId() {
        return workspaceId;
    }

    @Override
    public String mainAgentId() {
        return mainAgentId;
    }

    @Override
    public ModelConfig snapshot() {
        return snapshot;
    }

    @Override
    public EventEmitter events() {
        return events;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, AgentContext> agents() {
        return (Map<String, AgentContext>) (Map) agents;
    }

    @Override
    public AgentContext main() {
        return main;
    }

    @Override
    public EventLogReader log() {
        return log;
    }

    @Override
    public long startedAt() {
        Long v = startedAt;
        return v != null ? v : 0;
    }

    @Override
    public long endedAt() {
        Long v = endedAt;
        return v != null ? v : 0;
    }

    @Override
    public void touch() {
        lastActivityMs.set(System.currentTimeMillis());
    }

    // summaryJson() 已存在，自然满足 TaskRuntime.summaryJson() 接口方法

    @Override
    public void truncateLogAfter(long targetSeq) {
        log.truncateAfter(targetSeq);
    }

    public final Map<String, AgentEntity> agents = new ConcurrentHashMap<>();
    public volatile java.util.concurrent.Future<?> runFuture;

    private final java.util.concurrent.atomic.AtomicLong lastActivityMs;

    public TaskEntry(String taskId, String title,
            ModelConfig snapshot, String workspaceRoot, String workspaceId, String mainAgentId,
            long maxEvents) {
        this.taskId = taskId;
        this.title = title;
        this.snapshot = snapshot;
        this.workspaceRoot = workspaceRoot;
        this.workspaceId = workspaceId;
        this.mainAgentId = mainAgentId;
        this.lastActivityMs = new java.util.concurrent.atomic.AtomicLong(createdAt);
        this.log = new EventLog(maxEvents);
        this.events = new TaskEvents(log, mainAgentId);
    }

    /** 再运行时保留原创建时间。 */
    public void createdAt(long ms) {
        if (ms > 0) {
            createdAt = ms;
        }
    }

    public long lastActivityMs() {
        return lastActivityMs.get();
    }

    /** 主 + 子 agent 的 usage 合计。 */
    public Usage totalUsage() {
        Usage total = Usage.zero();
        if (main != null) {
            total = total.plus(main.usage());
        }
        for (AgentEntity a : agents.values()) {
            total = total.plus(a.usage());
        }
        return total;
    }

    /** 主 agent 当前实体(runTask 建好后置;运行期 agent 状态/用量供持久化与恢复)。 */
    public volatile AgentEntity main;

    /**
     * 记录最近一轮主 agent 实测 usage(上下文窗口占用口径)。
     * 由 WorkerToolEventAdvisor 在主 agent 模型调用末帧调用;子 agent 用量忽略。
     */
    @Override
    public void recordUsage(Usage round, Long ctxWindow, String model) {
        if (round == null) {
            return;
        }
        lastUsage = round;
        if (ctxWindow != null && ctxWindow > 0) {
            contextWindowTokens = ctxWindow;
        }
        if (model != null && !model.isEmpty()) {
            usageModel = model;
        }
    }

    /** 最近一轮上下文用量快照(无数据返回 null,summaryJson 据此省略 usage 字段)。 */
    public UsageSummary usageSummary() {
        Usage u = lastUsage;
        if (u == null || (u.inputTokens() <= 0 && u.outputTokens() <= 0 && u.totalTokens() <= 0)) {
            return null;
        }
        return new UsageSummary(u.inputTokens(), u.outputTokens(), u.totalTokens(),
                contextWindowTokens, usageModel.isEmpty() ? null : usageModel);
    }

    /** 再运行冷启动时从 meta.usage 恢复最近一轮用量(续跑后列表/电池数据不丢)。 */
    public void seedUsageMeta(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return;
        }
        long in = usage.path("inputTokens").asLong(0);
        long out = usage.path("outputTokens").asLong(0);
        long total = usage.path("totalTokens").asLong(0);
        if (in > 0 || out > 0 || total > 0) {
            lastUsage = new Usage(in, out, total > 0 ? total : in + out);
        }
        long ctx = usage.path("contextWindowTokens").asLong(0);
        if (ctx > 0) {
            contextWindowTokens = ctx;
        }
        String m = usage.path("model").asString("");
        if (!m.isEmpty()) {
            usageModel = m;
        }
    }

    public ObjectNode summaryJson() {
        long first = log.firstSeq();
        long last = log.lastSeq();
        TaskSummary s = new TaskSummary(taskId, title, status.wire(), createdAt,
                startedAt, endedAt,
                last > 0 ? first : null,
                last > 0 ? last : null,
                null, null, error, workspaceRoot, mainAgentId,
                snapshot.configId(),
                usageSummary());
        ObjectNode n = (ObjectNode) Json.toJson(s);
        // 稳定工作区 id(磁盘存储维度;TaskSummary record 保持不动,wire/meta 上额外携带)。
        n.put("workspaceId", workspaceId);
        // 任务级持久化数据随 meta 落盘:metadata Map 序列化(缺失 = 空 map,再运行据此保持)。
        if (!metadata.isEmpty()) {
            var metaObj = n.putObject("metadata");
            metadata.forEach((k, v) -> metaObj.set(k, Json.toJson(v)));
        }
        return n;
    }

    /**
     * wire 专用组装:summary（无 pendingInputs —— 队列概念已插件化，核心不持有）。
     * 队列插件经 task.updated 广播时自行在 payload 中补充 pendingInputs。
     */
    public ObjectNode runtimeSummaryJson() {
        return summaryJson();
    }

    /** 当前挂起的所有 agent 实体列表。 */
    public List<AgentEntity> agentList() {
        return List.copyOf(agents.values());
    }
}
