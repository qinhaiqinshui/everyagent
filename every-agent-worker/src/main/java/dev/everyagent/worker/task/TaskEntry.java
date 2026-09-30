package dev.everyagent.worker.task;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.event.EventLogReader;
import dev.everyagent.plugin.api.permission.TaskInfo;
import dev.everyagent.plugin.api.task.FileChangesCollector;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.worker.agent.AgentEntity;
import dev.everyagent.worker.proto.TaskDtos.TaskStatus;
import dev.everyagent.worker.proto.TaskDtos.TaskSummary;
import dev.everyagent.plugin.api.event.Usage;
import dev.everyagent.worker.proto.TaskDtos.UsageSummary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
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

    /**
     * 禁网开关(任务级):开启后本任务后续所有命令禁止访问网络(覆盖 worker 级
     * {@code sandbox.allow-network=true} 默认放行),随 {@link #summaryJson()} 落盘
     * meta.json、再运行仍保持。由 {@code NetworkSlashProvider}(/禁用网络)的 onSelect/onCancel
     * 置位复位并落盘。默认 false = 继承全局默认(放行),用户选 /禁用网络 显式关闭。
     */
    public volatile boolean networkBlocked;

    /**
     * 启用 powershell 开关(任务级):开启后主/子 agent 工具集在 bash 之外<b>追加</b>
     * {@code powershell} 工具(WSL 后端经发行版内 pwsh 执行),让 AI 同时拥有 powershell
     * 与 bash 两个命令工具;随 {@link #summaryJson()} 落盘 meta.json、再运行仍保持。
     * 由 {@code PowerShellEnableSlashProvider}(/允许AI访问电脑)的 onSelect/onCancel
     * 置位复位并落盘;buildMainAgent/buildAgent 每次运行构建工具集时实时读本字段
     * (选中/取消从下一轮或再运行起生效)。
     * 仅 WSL+Linux 沙箱后端注册该斜杠条目;windows-mic(Windows+ACL)后端命令工具本就
     * 是 PowerShellTool,不注册,本开关在该后端无意义(默认 false)。
     */
    public volatile boolean powershellEnabled;

    /**
     * slash 任务级 token 槽:自包含 opaque token 串数组,仅 slash 层存储、业务方不读。
     * 新任务由 task.run 的 taskTokens 入参写入,随 meta.json 的 slashTaskTokens 落盘,
     * 冷启动续跑(startRerun)回读恢复。线程安全(CopyOnWriteArrayList),快照读。
     */
    private final java.util.concurrent.CopyOnWriteArrayList<String> slashTaskTokens =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 追加一条 slash 任务级 opaque token(判空、去重:重复或空/null 忽略)。 */
    public void addSlashTaskToken(String opaque) {
        if (opaque == null || opaque.isEmpty()) {
            return;
        }
        if (!slashTaskTokens.contains(opaque)) {
            slashTaskTokens.add(opaque);
        }
    }

    /** 移除一条 slash 任务级 opaque token。 */
    public void removeSlashTaskToken(String opaque) {
        if (opaque == null) {
            return;
        }
        slashTaskTokens.remove(opaque);
    }

    /** slash 任务级 token 快照(不可变;供 summaryJson 序列化与建后回调遍历)。 */
    public List<String> slashTaskTokens() {
        return List.copyOf(slashTaskTokens);
    }


    // ---- TaskInfo 接口实现 ----

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
    public FileChangesCollector fileChanges() {
        return fileChanges;
    }

    @Override
    public void fileChanges(FileChangesCollector collector) {
        fileChanges = collector;
    }

    @Override
    public JsonNode fileChangesLight() {
        return fileChangesLight;
    }

    @Override
    public void fileChangesLight(JsonNode light) {
        fileChangesLight = light;
    }

    @Override
    public JsonNode fileChangesFull() {
        return fileChangesFull;
    }

    @Override
    public void fileChangesFull(JsonNode full) {
        fileChangesFull = full;
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

    public final Map<String, AgentEntity> agents = new ConcurrentHashMap<>();
    public volatile java.util.concurrent.Future<?> runFuture;
    /**
     * 当前回合文件改动收集器:FileChangeAdvisor 在主 agent 首次 adviseStream 时新建、
     * 工具循环最后一轮收口后置空;主/子 agent 的工具调用均经 FileChangeAdvisor 记录到本槽
     * (子 agent 在主 agent run 内部递归执行,其保存同样归入当前回合)。运行期状态,不落盘。
     */
    public volatile FileChangesCollector fileChanges;

    /**
     * 本轮文件改动轻量摘要(FileChangeAdvisor 收口时写,RoundIndexAdvisor 消费后清空):
     * 仅 filePath/fileName/changeType/saveCount 的数组,随 rounds.jsonl 每轮行内联落盘。
     */
    public volatile JsonNode fileChangesLight;

    /**
     * 本轮文件改动全文(FileChangeAdvisor 收口时写,RoundIndexAdvisor 消费后清空):
     * 形状为 { changes:[{filePath,fileName,changeType,beforeContent,afterContent,saveCount}] },
     * 由 RoundIndexStore 写到 {@code file-changes/<roundId>.json}。
     */
    public volatile JsonNode fileChangesFull;

    private final AtomicLong lastActivityMs = new AtomicLong(createdAt);

    public TaskEntry(String taskId, String title,
            ModelConfig snapshot, String workspaceRoot, String workspaceId, String mainAgentId,
            long maxEvents) {
        this.taskId = taskId;
        this.title = title;
        this.snapshot = snapshot;
        this.workspaceRoot = workspaceRoot;
        this.workspaceId = workspaceId;
        this.mainAgentId = mainAgentId;
        this.log = new EventLog(maxEvents);
        this.events = new TaskEvents(log, mainAgentId);
    }

    public void touch() {
        lastActivityMs.set(System.currentTimeMillis());
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
        if (networkBlocked) {
            n.put("networkBlocked", true);
        }
        if (powershellEnabled) {
            n.put("powershellEnabled", true);
        }
        // slash 任务级 token(仅 slash 层存储、业务方不读;随 meta 落盘,冷启动续跑回读)。
        List<String> slashTokens = slashTaskTokens();
        if (!slashTokens.isEmpty()) {
            ArrayNode tokArr = Json.arr();
            for (String tok : slashTokens) {
                tokArr.add(tok);
            }
            n.set("slashTaskTokens", tokArr);
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
