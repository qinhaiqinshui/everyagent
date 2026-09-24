package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.worker.task.AgentEntity;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskStore;
import dev.everyagent.worker.task.UserInput;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 任务生命周期上下文实现。
 * <p>public 但仅在 worker 模块内部使用（非 plugin-api），外部插件只见 {@link TaskLifecycleContext} 窄接口。
 * 内置节点通过此类访问完整 {@link TaskEntry} 和回调。
 */
public class TaskLifecycleContextImpl implements TaskLifecycleContext {

    private final TaskEntry taskEntry;
    private UserInput initialInput;
    private List<Message> priorConversation;

    // 回调：由 TaskManager 在创建上下文时设置
    private Function<List<Message>, AgentEntity> mainAgentBuilder;
    private BiConsumer<AgentEntity, UserInput> inputConsumer;
    private Runnable concurrencyReleaser;
    private Consumer<TaskStore.StoredTask> diskIndexer;
    private Runnable registryRemover;
    /** 流源挂接回调：PersistenceTrackNode 下行段调用（streamSources.attach）。 */
    private Runnable streamSourceAttacher;
    /** 流源摘除回调：PersistenceUntrackNode 上行段调用（streamSources.detach）。 */
    private Runnable streamSourceDetacher;

    public TaskLifecycleContextImpl(TaskEntry taskEntry) {
        this.taskEntry = taskEntry;
    }

    // ---- 设置器（由 TaskManager 调用）----

    public void initialInput(UserInput input) { this.initialInput = input; }
    public void priorConversation(List<Message> conv) { this.priorConversation = conv; }
    public void mainAgentBuilder(Function<List<Message>, AgentEntity> fn) { this.mainAgentBuilder = fn; }
    public void inputConsumer(BiConsumer<AgentEntity, UserInput> fn) { this.inputConsumer = fn; }
    public void concurrencyReleaser(Runnable r) { this.concurrencyReleaser = r; }
    public void diskIndexer(Consumer<TaskStore.StoredTask> c) { this.diskIndexer = c; }
    public void registryRemover(Runnable r) { this.registryRemover = r; }
    public void streamSourceAttacher(Runnable r) { this.streamSourceAttacher = r; }
    public void streamSourceDetacher(Runnable r) { this.streamSourceDetacher = r; }

    // ---- 访问器（节点用，worker 模块内部）----

    public TaskEntry taskEntry() { return taskEntry; }
    public UserInput initialInput() { return initialInput; }
    public List<Message> priorConversation() { return priorConversation; }
    public Function<List<Message>, AgentEntity> mainAgentBuilder() { return mainAgentBuilder; }
    public BiConsumer<AgentEntity, UserInput> inputConsumer() { return inputConsumer; }
    public Runnable concurrencyReleaser() { return concurrencyReleaser; }
    public Consumer<TaskStore.StoredTask> diskIndexer() { return diskIndexer; }
    public Runnable registryRemover() { return registryRemover; }
    public Runnable streamSourceAttacher() { return streamSourceAttacher; }
    public Runnable streamSourceDetacher() { return streamSourceDetacher; }

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
    @Override public void onPersistHook(Runnable hook) { taskEntry.persistHook = hook; }
    @Override public void agentStatus(String agentId, String status) { taskEntry.events.agentStatus(agentId, status); }
}
