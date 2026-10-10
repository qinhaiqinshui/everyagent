package dev.everyagent.worker.agent;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.plugin.api.event.Usage;
import dev.everyagent.plugin.api.proto.SnowflakeId;
import dev.everyagent.plugin.api.agent.Agent;
import dev.everyagent.plugin.api.agent.AgentActivity;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单个 agent(主或子)的运行时:模型客户端、工具集、会话内存(可被压缩重写的载体,§5.8)、
 * **per-run 生命周期状态机与 agent.* 事件的唯一发射口**。
 *
 * <p>S2 起持有 {@link #execution}(本 agent 所属执行上下文,构造注入);S4 起
 * advisor 链全面经 {@code execution()} 类型化槽位取数,原过渡黑盒 map properties
 * 已删除。agent 级 {@link #emitter} 构造时包装上游 emitter(填 agentId →
 * 委托 execution.emitter())。{@link #chatClient} 由 {@link AgentBuilder} 装配注入。
 *
 * <p><b>状态机纪律</b>(§7.20.1):{@code agent.started} / {@code agent.status} /
 * {@code agent.done} / agent 级 {@code error} 四类事件在本类一处发射,触发源在别处——
 * {@code AgentStatusAdvisor} 把流生命周期信号(订阅/完成/错误/取消)翻译成
 * {@link #beginRun()} / {@link #claimTerminal(String, String)},交互层把 ask 生命周期
 * 翻译成 {@link #markWaitingUser()} / {@link #markAskResolved()}。task 层与插件一律不再
 * 手搓 agent.* 的 EmitEvent;插件只在「运行体从未启动」的兜底路径上调用
 * {@link #claimTerminal}(CAS 保证与 advisor 的终态不重复)。
 */
public final class AgentEntity implements Agent {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AgentEntity.class);

    // ── per-run 微观状态机取值(与对外 wire 状态是两个维度,互不映射混淆) ──
    /** 未运行 / 新一轮开始前。 */
    static final String RUN_IDLE = "idle";
    /** 本轮 run() 正在跑模型流。 */
    static final String RUN_RUNNING = "running";
    /** 本轮 run() 挂在 ask 上等用户回答。 */
    static final String RUN_WAITING_USER = "waiting-user";
    /** 本轮 run() 已声明终态(下一轮 run() 会重置回 idle)。 */
    static final String RUN_TERMINAL = "terminal";

    /** 宏观状态(wire 值 agent.status 用):本轮正常收口。 */
    static final String MACRO_COMPLETED = "completed";
    /** 宏观状态:本轮被取消/停止。 */
    static final String MACRO_STOPPED = "stopped";
    /** 宏观状态:本轮异常失败。 */
    static final String MACRO_ERROR = "error";

    public final String agentId;
    public final String title;
    public final ChatModel chatModel;
    /** 请求参数完整快照:每轮 prompt 在其 mutate() 上追加工具集(2.0.1 不合并默认 options)。 */
    public final OpenAiChatOptions options;
    public final List<ToolCallback> tools;

    /** 仅由本 agent 的执行线程读写(任务线程 / 子 agent 线程)。 */
    public final List<Message> conversation = new ArrayList<>();

    /** 由 AgentBuilder 装配注入(build 后设置)。 */
    public ChatClient chatClient;

    /** 本 agent 所属的执行上下文（task 或未来 workflow；S2 起构造注入，S4 起为 advisor 取数主干）。 */
    public final ExecContext execution;

    /**
     * agent 创建者标识(task / subagent / ai-review；由创建方经 AgentBuilder.creator 设定)。
     * 随 agent.started 事件持久化进台账顶级 creator 字段。
     */
    public final String creator;

    /** agent 元数据（创建时注入的其他元数据；随 agent.started 事件持久化到台账 metadata）。 */
    public final Map<String, Object> agentMetadata;

    private final AtomicReference<Usage> usage = new AtomicReference<>(Usage.zero());
    /** 最近一轮实测 usage(WorkerToolEventAdvisor 每轮 usage 事件时写;任务级 usageSummary 的供体)。 */
    private volatile Usage lastRound = Usage.zero();
    /** 最近一轮所用模型名(与 lastRound 同源;空 = 未知)。 */
    private volatile String lastModel = "";
    private volatile Usage lastContextUsage;
    private volatile Long lastContextWindow;
    private volatile String lastContextModel;
    public volatile String lastText = "";
    public volatile boolean finished;
    /** 本轮终态声明 CAS(每轮 beginRun/resetForRerun 复位,保证一轮之内终态事件只发一次)。 */
    private final AtomicBoolean terminalClaimed = new AtomicBoolean(false);
    /** per-run 微观状态机(idle → running ⇄ waiting-user → terminal;每轮 beginRun 复位)。 */
    private final AtomicReference<String> runState = new AtomicReference<>(RUN_IDLE);

    /** 创建时刻(list_agents/wait_agents 契约字段 createdAt)。 */
    public final long createdAt;
    public volatile String status = "running";

    /**
     * agent 层包装 emitter:在 emit 时把 EmitEvent.agentId 填入本 agent 的 agentId
     * (低层产生方留 null → 上游兜底 mainAgentId)。per-run 生命周期事件全部走此 emitter。
     */
    public final EventEmitter emitter;

    /** 最近活动快照(list_agents/wait_agents 契约;WorkerToolEventAdvisor 每轮合并写)。 */
    private final AtomicReference<AgentActivity> activity =
            new AtomicReference<>(new AgentActivity(null, null, null, null, null));

    public AgentEntity(String agentId, String title, ChatModel chatModel,
            OpenAiChatOptions options, List<ToolCallback> tools,
            EventEmitter upstreamEmitter, ExecContext execution,
            String creator,
            Map<String, Object> agentMetadata) {
        this.agentId = agentId;
        this.title = title;
        this.chatModel = chatModel;
        this.options = options;
        this.tools = tools;
        this.execution = execution;
        this.creator = creator;
        this.agentMetadata = agentMetadata != null ? Map.copyOf(agentMetadata) : Map.of();
        // 包装：添加本层信息（agentId），委托上游 emitter
        this.emitter = e -> {
            EmitEvent filled = (e.agentId() == null || e.agentId().isEmpty())
                    ? e.withAgentId(this.agentId) : e;
            return upstreamEmitter.emit(filled);
        };
        this.createdAt = System.currentTimeMillis();
    }

    // ── AgentContext / Agent 接口实现 ──

    /** AgentRunner 引用(由 AgentBuilder.build() 注入),供 {@link #run()} 委托调用。 */
    private AgentRunner runner;

    /** 注入 AgentRunner(包级可见,AgentBuilder.build() 调用)。 */
    void runner(AgentRunner runner) {
        this.runner = runner;
    }

    @Override
    public String agentId() {
        return agentId;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public String creator() {
        return creator;
    }

    @Override
    public Map<String, Object> agentMetadata() {
        return agentMetadata;
    }

    @Override
    public long createdAt() {
        return createdAt;
    }

    @Override
    public ExecContext execution() {
        return execution;
    }

    @Override
    public EventEmitter emitter() {
        return emitter;
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public boolean finished() {
        return finished;
    }

    @Override
    public void finished(boolean finished) {
        this.finished = finished;
    }

    @Override
    public String lastText() {
        return lastText;
    }

    @Override
    public List<Message> conversation() {
        return conversation;
    }

    @Override
    public void run() throws InterruptedException {
        if (runner == null) {
            throw new IllegalStateException("AgentRunner 未注入,无法执行 run()");
        }
        runner.run(this);
    }

    // ── 以下为 worker 内部方法(非 AgentContext 契约) ──

    @Override
    public ChatModel chatModel() {
        return chatModel;
    }

    @Override
    public String currentModel() {
        return options == null ? null : options.getModel();
    }

    @Override
    public Usage usage() {
        return usage.get();
    }

    @Override
    public Usage lastRound() {
        return lastRound;
    }

    @Override
    public String lastModel() {
        return lastModel;
    }

    public void recordLastRound(Usage round, Usage total, Long contextWindowTokens, String model) {
        if (round != null) {
            lastRound = round;
            lastContextUsage = round;
        }
        if (total != null && (total.inputTokens() != 0 || total.outputTokens() != 0)) {
            usage.set(total);
        }
        if (contextWindowTokens != null && contextWindowTokens > 0) {
            lastContextWindow = contextWindowTokens;
        }
        if (model != null && !model.isEmpty()) {
            lastModel = model;
            lastContextModel = model;
        }
    }

    @Override
    public AgentActivity activity() {
        return activity.get();
    }

    /**
     * 元数据摘要(agents.json 台账数组项 + 冷启动恢复台账)。
     */
    public ObjectNode toSummary() {
        ObjectNode n = Json.obj();
        n.put("agentId", agentId);
        if (title != null && !title.isEmpty()) {
            n.put("title", title);
        }
        if (creator != null && !creator.isEmpty()) {
            n.put("creator", creator);
        }
        n.put("createdAt", createdAt);
        n.put("status", status);
        AgentActivity act = activity.get();
        ObjectNode la = Json.obj();
        if (act.reasoning() != null) {
            la.put("reasoning", act.reasoning());
        }
        if (act.content() != null) {
            la.put("content", act.content());
        }
        if (act.error() != null) {
            la.put("error", act.error());
        }
        if (act.createdAt() != null) {
            la.put("createdAt", act.createdAt());
        }
        if (act.updatedAt() != null) {
            la.put("updatedAt", act.updatedAt());
        }
        if (!la.isEmpty()) {
            n.set("latestActivity", la);
        }
        Usage u = usage.get();
        if (u != null && (u.inputTokens() != 0 || u.outputTokens() != 0 || u.totalTokens() != 0)) {
            n.set("usage", Json.toJson(u));
        }
        Usage ctx = lastContextUsage;
        if (ctx != null && (ctx.inputTokens() != 0 || ctx.outputTokens() != 0)) {
            ObjectNode c = Json.obj();
            c.put("inputTokens", ctx.inputTokens());
            if (lastContextWindow != null && lastContextWindow > 0) {
                c.put("contextWindowTokens", lastContextWindow);
            }
            if (lastContextModel != null && !lastContextModel.isEmpty()) {
                c.put("model", lastContextModel);
            }
            n.set("context", c);
        }
        if (lastText != null && !lastText.isEmpty()) {
            n.put("lastText", lastText);
        }
        return n;
    }

    @Override
    public void updateActivity(String reasoning, String content, String error) {
        long now = System.currentTimeMillis();
        activity.updateAndGet(old -> new AgentActivity(
                reasoning != null ? reasoning : old.reasoning(),
                content != null ? content : old.content(),
                error != null ? error : old.error(),
                old.createdAt() != null ? old.createdAt() : now,
                now));
    }

    @Override
    public void resetForRerun() {
        status = "running";
        finished = false;
        terminalClaimed.set(false);
        runState.set(RUN_IDLE);
        AgentActivity a = activity.get();
        if (a != null && a.error() != null) {
            activity.set(new AgentActivity(a.reasoning(), a.content(), null, a.createdAt(), a.updatedAt()));
        }
    }

    // ── per-run 生命周期状态机 + agent.* 事件唯一发射口(契约见 AgentContext) ──

    @Override
    public void beginRun() {
        // 上一轮已终态 → 本轮是新一轮 run()(队列取下一条输入 / 复用续跑 / 冷启动再运行):
        // 微观状态与终态声明一起复位,一个周期一次出生。
        if (RUN_TERMINAL.equals(runState.get())) {
            terminalClaimed.set(false);
            runState.set(RUN_IDLE);
        }
        if (!runState.compareAndSet(RUN_IDLE, RUN_RUNNING)) {
            return; // 本轮已在跑(同一 run 内重复进入),不重发出生事件
        }
        status = "running";
        finished = false;
        emitStarted();
        emitAgentStatus("running");
    }

    @Override
    public boolean markWaitingUser() {
        if (!runState.compareAndSet(RUN_RUNNING, RUN_WAITING_USER)) {
            return false; // 本轮不在运行中(未开跑/已终态),不翻转
        }
        emitAgentStatus("waiting-user");
        return true;
    }

    @Override
    public boolean markAskResolved() {
        if (terminalClaimed.get()) {
            return false; // 本轮已收口:ask 被取消不该把已终态 agent 报回 running
        }
        if (!runState.compareAndSet(RUN_WAITING_USER, RUN_RUNNING)) {
            return false;
        }
        emitAgentStatus("running");
        return true;
    }

    @Override
    public boolean claimTerminal(String status) {
        return claimTerminal(status, null);
    }

    @Override
    public boolean claimTerminal(String status, String message) {
        if (!terminalClaimed.compareAndSet(false, true)) {
            return false;
        }
        this.status = status;
        this.finished = true;
        runState.set(RUN_TERMINAL);
        String msg = message == null || message.isEmpty() ? null : message;
        if (msg != null) {
            updateActivity(null, null, msg);
        }
        // 发射顺序固定 error? → agent.done → agent.status{终态}:
        // 台账的 agent.done 分支无条件写 status=completed,终态 status 必须后发才不被改判。
        // 带附言的终态(error 的根因 / stopped 的「已取消」)都发 error 事件——
        // 前端按 error 收口流式锚点并落一条红块,子 agent 被 stop_agent 停掉时
        // 「已取消」就是它这一轮的收口原因(与重构前 SubAgentManager.emitStopped 同形)。
        if (msg != null && !MACRO_COMPLETED.equals(status)) {
            emitError(msg);
        }
        emitAgentDone();
        emitAgentStatus(wireStatusOf(status));
        return true;
    }

    /** 宏观终态状态 → agent.status 事件 wire 值。 */
    private static String wireStatusOf(String macro) {
        return switch (macro) {
            case MACRO_COMPLETED -> "done";
            case MACRO_ERROR -> "failed";
            default -> "stopped";
        };
    }

    /** 发 agent.started(agentId/creator/metadata 在 data,title 走通用展示字段;持久 REPLACE)。 */
    private void emitStarted() {
        ObjectNode startedData = Json.obj();
        startedData.put("agentId", agentId);
        if (creator != null && !creator.isEmpty()) {
            startedData.put("creator", creator);
        }
        if (!agentMetadata.isEmpty()) {
            startedData.set("metadata", Json.toJson(agentMetadata));
        }
        safeEmit("agent.started", () -> emitter.emit(EmitEvent.of(
                SnowflakeId.next(), Events.AGENT_STARTED, agentId,
                title == null || title.isEmpty() ? null : title,
                null, null, null, startedData, EmitEvent.Mode.REPLACE)));
    }

    /** 发 agent.status(主/子统一;持久 REPLACE)。 */
    private void emitAgentStatus(String status) {
        safeEmit(Events.AGENT_STATUS, () -> emitter.emit(EmitEvent.of(
                SnowflakeId.next(), Events.AGENT_STATUS, agentId,
                null, null, null, status, null, EmitEvent.Mode.REPLACE)));
    }

    /** 发 agent.done(携带累计 usage + 最近一轮正文;持久 REPLACE)。 */
    private void emitAgentDone() {
        ObjectNode doneData = Json.obj();
        doneData.put("agentId", agentId);
        doneData.set("usage", Json.toJson(usage()));
        safeEmit(Events.AGENT_DONE, () -> emitter.emit(EmitEvent.of(
                SnowflakeId.next(), Events.AGENT_DONE, agentId,
                null, null, lastText, null, doneData, EmitEvent.Mode.REPLACE)));
    }

    /** 发 agent 级 error(本轮失败原因;持久 REPLACE)。 */
    private void emitError(String msg) {
        safeEmit(Events.ERROR, () -> emitter.emit(EmitEvent.of(
                SnowflakeId.next(), Events.ERROR, agentId,
                null, null, msg, null, null, EmitEvent.Mode.REPLACE)));
    }

    /**
     * 发射生命周期事件——**异常一律吞掉只记日志**。
     * 本方法可能在 Reactor 的 doOnComplete/doOnError/doOnCancel 回调栈里执行:回调抛异常
     * 会被 Reactor 转成 error 信号下传,把「已经正常跑完的一轮」改判成失败;
     * 而事件日志侧的失败(EventLog 内存护栏 LogOverflow / 日志已随主体收口销毁)不是
     * agent 的错。状态机的 CAS 已在发射前完成,吞掉发射失败不会破坏终态唯一性。
     */
    private void safeEmit(String kind, Runnable emit) {
        try {
            emit.run();
        } catch (RuntimeException e) {
            log.debug("agent 生命周期事件发射失败(忽略,不影响本轮收口) kind={} agentId={}",
                    kind, agentId, e);
        }
    }

    public AtomicReference<Usage> usageRef() {
        return usage;
    }

    public void addUsage(org.springframework.ai.chat.metadata.Usage u) {
        if (u == null) {
            return;
        }
        long in = u.getPromptTokens() == null ? 0 : u.getPromptTokens();
        long out = u.getCompletionTokens() == null ? 0 : u.getCompletionTokens();
        long total = u.getTotalTokens() == null ? in + out : u.getTotalTokens();
        if (in == 0 && out == 0) {
            return;
        }
        usage.updateAndGet(old -> old.plus(new Usage(in, out, total)));
    }
}
