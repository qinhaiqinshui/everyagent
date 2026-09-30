package dev.everyagent.worker.agent;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.event.Usage;
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
 * 单个 agent(主或子)的运行时:模型客户端、工具集、会话内存(可被压缩重写的载体,§5.8)。
 *
 * <p>解耦后(agent 层):不再引用 TaskEntry，持有从上层传入的 {@link EventEmitter}
 * (构造时包装：填 agentId → 委托上游)和 {@link #properties}（上层黑盒数据，agent 层核心不读）。
 * {@link #chatClient} 由 {@link AgentBuilder} 装配注入。
 */
public final class AgentEntity implements Agent {

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

    /** 上层黑盒数据，agent 层核心(AgentRunner)完全不读；task 层 advisor 从中取 TaskEntry 等。 */
    public final Map<String, Object> properties;

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
    private final AtomicBoolean terminalClaimed = new AtomicBoolean(false);

    /** 创建时刻(list_agents/wait_agents 契约字段 createdAt)。 */
    public final long createdAt;
    public volatile String status = "running";

    /**
     * agent 层包装 emitter:在 emit 时把 EmitEvent.agentId 填入本 agent 的 agentId
     * (低层产生方留 null → 上游兜底 mainAgentId)。步骤 6-8 的调用方通过
     * 此 emitter 发射事件,无需手动填 agentId。
     */
    public final EventEmitter emitter;

    /** 最近活动快照(list_agents/wait_agents 契约;WorkerToolEventAdvisor 每轮合并写)。 */
    private final AtomicReference<AgentActivity> activity =
            new AtomicReference<>(new AgentActivity(null, null, null, null, null));

    public AgentEntity(String agentId, String title, ChatModel chatModel,
            OpenAiChatOptions options, List<ToolCallback> tools,
            EventEmitter upstreamEmitter, Map<String, Object> properties) {
        this.agentId = agentId;
        this.title = title;
        this.chatModel = chatModel;
        this.options = options;
        this.tools = tools;
        this.properties = properties;
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
    public long createdAt() {
        return createdAt;
    }

    @Override
    public Map<String, Object> properties() {
        return properties;
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
        AgentActivity a = activity.get();
        if (a != null && a.error() != null) {
            activity.set(new AgentActivity(a.reasoning(), a.content(), null, a.createdAt(), a.updatedAt()));
        }
    }

    @Override
    public boolean claimTerminal(String status) {
        if (terminalClaimed.compareAndSet(false, true)) {
            this.status = status;
            this.finished = true;
            return true;
        }
        return false;
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
