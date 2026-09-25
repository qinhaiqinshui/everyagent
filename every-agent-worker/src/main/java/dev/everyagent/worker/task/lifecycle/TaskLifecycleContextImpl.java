package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.worker.task.AgentEntity;
import dev.everyagent.worker.task.RoundIndexStore;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskStore;
import dev.everyagent.worker.task.UserInput;
import dev.everyagent.worker.tools.PermissionGate;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 任务生命周期上下文实现。
 * <p>public 但仅在 worker 模块内部使用（非 plugin-api），外部插件只见 {@link TaskLifecycleContext} 窄接口。
 * 内置节点通过此类访问完整 {@link TaskEntry} 和回调。
 */
public class TaskLifecycleContextImpl implements TaskLifecycleContext {

    private final TaskEntry taskEntry;
    /** 任务级协作者（consumeInput 从 TaskManager 迁入，授权门/轮索引/存储随任务上下文走）。 */
    private final PermissionGate gate;
    private final RoundIndexStore roundIndexStore;
    private final TaskStore store;
    private UserInput initialInput;
    private List<Message> priorConversation;
    /** 再运行来源 meta（冷启动续跑时非空；rerun.restore/model.switch.trace 据此恢复与标注）。 */
    private JsonNode rerunMeta;
    /** 再运行时输入箱切换模型透传的 configId（model.switch.trace 判异用）。 */
    private String overrideConfigId;
    /** 再运行 seq 水位（meta.seqLast，含瞬态占位水位；track 前由 startRerun 读盘注入）。 */
    private long rerunSeqLast;

    // 回调：由 TaskManager 在创建上下文时设置
    private Function<List<Message>, AgentEntity> mainAgentBuilder;
    private Runnable concurrencyReleaser;
    private Consumer<TaskStore.StoredTask> diskIndexer;
    private Runnable registryRemover;

    public TaskLifecycleContextImpl(TaskEntry taskEntry, PermissionGate gate,
            RoundIndexStore roundIndexStore, TaskStore store) {
        this.taskEntry = taskEntry;
        this.gate = gate;
        this.roundIndexStore = roundIndexStore;
        this.store = store;
    }

    // ---- 设置器（由 TaskManager 调用）----

    public void initialInput(UserInput input) { this.initialInput = input; }
    public void priorConversation(List<Message> conv) { this.priorConversation = conv; }
    public void rerunMeta(JsonNode meta) { this.rerunMeta = meta; }
    public void overrideConfigId(String configId) { this.overrideConfigId = configId; }
    public void rerunSeqLast(long seqLast) { this.rerunSeqLast = seqLast; }
    public void mainAgentBuilder(Function<List<Message>, AgentEntity> fn) { this.mainAgentBuilder = fn; }
    public void concurrencyReleaser(Runnable r) { this.concurrencyReleaser = r; }
    public void diskIndexer(Consumer<TaskStore.StoredTask> c) { this.diskIndexer = c; }
    public void registryRemover(Runnable r) { this.registryRemover = r; }

    // ---- 访问器（节点用，worker 模块内部）----

    public TaskEntry taskEntry() { return taskEntry; }
    public UserInput initialInput() { return initialInput; }
    public List<Message> priorConversation() { return priorConversation; }
    public JsonNode rerunMeta() { return rerunMeta; }
    public String overrideConfigId() { return overrideConfigId; }
    public long rerunSeqLast() { return rerunSeqLast; }
    public Function<List<Message>, AgentEntity> mainAgentBuilder() { return mainAgentBuilder; }
    public Runnable concurrencyReleaser() { return concurrencyReleaser; }
    public Consumer<TaskStore.StoredTask> diskIndexer() { return diskIndexer; }
    public Runnable registryRemover() { return registryRemover; }

    // ---- 输入消费（首条输入 MainAgentNode 与内核轮次循环共用）----

    /** 消费一条用户输入:授权本轮失效 + 记 user.message + 开轮落盘 + 入会话内存。（从 TaskManager 迁入） */
    public void consumeInput(AgentEntity main, UserInput input) {
        String text = input.text();
        String rawContent = input.rawContent();
        gate.beginRun(taskEntry.taskId); // 新一条用户输入:本轮(run)授权失效(任务级不受影响)
        // user.message 落盘后以它的 seq 为轮起点开轮:最后一行未闭合则沿用(中间输入/续跑不开新轮);
        // 已闭合/无行则追加一条 endSeq="" 的未闭合轮。中断/取消/失败不再于终态补写,轮行随开轮即持久化。
        long seq = taskEntry.events.userMessage(text, rawContent);
        // 开轮落盘带完整 user.message payload(懒加载骨架起点;与 userMessage 事件 payload 同源):
        // text 供展示/AI 摘要,rawContent 供前端回放还原胶囊。
        ObjectNode userPayload = Json.obj().put("text", text);
        if (rawContent != null && !rawContent.isEmpty()) {
            userPayload.put("rawContent", rawContent);
        }
        if (roundIndexStore.openRoundAtStart(store, taskEntry.taskId, seq, text, userPayload)) {
            // 真的新开一轮(非中间输入/续跑沿用)才推 round.opened;瞬态不落盘。
            taskEntry.events.roundOpened(seq, text);
        }
        main.conversation.add(new UserMessage(text));
        taskEntry.touch();
    }

    // ---- TaskLifecycleContext 接口实现 ----

    @Override public String taskId() { return taskEntry.taskId; }
    @Override public String title() { return taskEntry.title; }
    @Override public String workspaceRoot() { return taskEntry.workspaceRoot; }
    @Override public String workspaceId() { return taskEntry.workspaceId; }
    @Override public String mainAgentId() { return taskEntry.mainAgentId; }
    @Override public String status() { return taskEntry.status.wire(); }
    @Override public Object taskLock() { return taskEntry; }
    @Override public Map<String, Boolean> taskFlags() { return taskEntry.taskFlags; }
    @Override public long startedAt() { return taskEntry.startedAt != null ? taskEntry.startedAt : 0; }
    @Override public void startedAt(long ms) { taskEntry.startedAt = ms; }
    @Override public void onUsageBroadcast(Runnable hook) { taskEntry.onUsageBroadcast = hook; }
    @Override public void agentStatus(String agentId, String status) { taskEntry.events.agentStatus(agentId, status); }
}
