package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.worker.proto.TaskDtos.Usage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单个 agent(主或子)的运行时:模型客户端、工具集、会话内存(可被压缩重写的载体,§5.8)。
 */
public final class AgentEntity {

    public enum Kind {
        MAIN, SUB
    }

    public final TaskEntry task;
    public final String agentId;
    public final Kind kind;
    public final String title;
    public final ChatModel chatModel;
    /** 请求参数完整快照:每轮 prompt 在其 mutate() 上追加工具集(2.0.1 不合并默认 options)。 */
    public final OpenAiChatOptions options;
    public final List<ToolCallback> tools;

    /** 仅由本 agent 的执行线程读写(任务线程 / 子 agent 线程)。 */
    public final List<Message> conversation = new ArrayList<>();


    private final AtomicReference<Usage> usage = new AtomicReference<>(Usage.zero());
    /** 最近一轮实测 usage(WorkerToolEventAdvisor 每轮 usage 事件时写;任务级 usageSummary 的供体)。 */
    private volatile Usage lastRound = Usage.zero();
    /** 最近一轮所用模型名(与 lastRound 同源;空 = 未知)。 */
    private volatile String lastModel = "";
    /**
     * 最近一轮上下文用量快照(台账 context 字段的供体,随 agents.json 独立落盘):
     * 最近一轮 usage + 上下文窗口上限 + 模型名,recordLastRound 在 usage 事件时一并写入。
     */
    private volatile Usage lastContextUsage;
    private volatile Long lastContextWindow;
    private volatile String lastContextModel;
    public volatile String lastText = "";
    public volatile boolean finished;
    /**
     * 终态唯一声明:completed/stopped/error 只能有一个赢家写入。
     * 用于 stop_agent / 任务取消级联与子线程收口之间的竞态——stop 侧可以立即把实体置为
     * stopped 并发事件,子线程收口侧稍后到达时据此跳过,避免重复发 agent.done/error/agent.status。
     */
    private final AtomicBoolean terminalClaimed = new AtomicBoolean(false);

    /** 创建时刻(list_agents/wait_agents 契约字段 createdAt)。 */
    public final long createdAt;
    /**
     * 工具契约运行状态(§5.6):completed/stopped/error 为终态,running 为运行中。
     * 与 UI 事件态(agentStatus 的 done/failed/...)分属两套词汇,互不干扰。
     */
    public volatile String status = "running";
    /**
     * agent 层包装 emitter:在 emit 时把 EmitEvent.agentId 填入本 agent 的 agentId
     * (低层产生方留 null → TaskEvents 兜底 mainAgentId)。步骤 6-8 的调用方通过
     * 此 emitter 发射事件,无需手动填 agentId。
     */
    public final EventEmitter agentEmitter;
    /** 最近活动快照(list_agents/wait_agents 契约;WorkerToolEventAdvisor 每轮合并写)。 */
    private final AtomicReference<AgentActivity> activity =
            new AtomicReference<>(new AgentActivity(null, null, null, null, null));

    public AgentEntity(TaskEntry task, String agentId, Kind kind, String title, ChatModel chatModel,
            OpenAiChatOptions options, List<ToolCallback> tools) {
        this.task = task;
        this.agentId = agentId;
        this.kind = kind;
        this.title = title;
        this.chatModel = chatModel;
        this.options = options;
        this.tools = tools;
        this.agentEmitter = e -> {
            EmitEvent filled = (e.agentId() == null || e.agentId().isEmpty())
                    ? e.withAgentId(this.agentId) : e;
            return task.events.emit(filled);
        };
        this.createdAt = System.currentTimeMillis();
    }

    public Usage usage() {
        return usage.get();
    }

    public Usage lastRound() {
        return lastRound;
    }

    public String lastModel() {
        return lastModel;
    }

    /**
     * 记录最近一轮实测用量与上下文快照(usage 事件发射时同步写):
     * round → lastRound(任务级 usageSummary 与 ContextCompressionAdvisor 的供体)、
     * total → 累计 usage(台账 usage 字段的供体),并保存上下文窗口/模型名(台账 context 字段的供体);
     * 保持原语义:null/空/零值字段不覆盖。
     */
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

    public AgentActivity activity() {
        return activity.get();
    }

    /**
     * 元数据摘要(agents.json 台账数组项 + 冷启动恢复台账):agentId/kind/title/createdAt/
     * status/usage/latestActivity/lastText/context。契约字段与 SubAgentManager.summaryJson 同形,
     * 额外携带 usage/lastText/kind/context 供持久化与诊断(usage 为累计值,context 为最近一轮上下文快照)。
     */
    public ObjectNode toSummary() {
        ObjectNode n = Json.obj();
        n.put("agentId", agentId);
        n.put("kind", kind.name());
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
        // 最近一轮上下文用量快照(仅当有数据时写入;与累计 usage 字段并存,语义不同)
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

    /** 合并写入活动快照(null 字段保留原值;留首 createdAt,updatedAt 刷新)。
     *  仅本 agent 的执行线程顺序写,读侧(list_agents/wait_agents)靠 volatile 引用无锁可见。 */
    public void updateActivity(String reasoning, String content, String error) {
        long now = System.currentTimeMillis();
        activity.updateAndGet(old -> new AgentActivity(
                reasoning != null ? reasoning : old.reasoning(),
                content != null ? content : old.content(),
                error != null ? error : old.error(),
                old.createdAt() != null ? old.createdAt() : now,
                now));
    }

    /** 同 id 续跑前重置运行态(status/finished 回运行中,清除上一轮 error 快照)。 */
    public void resetForRerun() {
        status = "running";
        finished = false;
        terminalClaimed.set(false); // 续跑后允许重新声明终态
        AgentActivity a = activity.get();
        if (a != null && a.error() != null) {
            activity.set(new AgentActivity(a.reasoning(), a.content(), null, a.createdAt(), a.updatedAt()));
        }
    }

    /**
     * 声明终态(status=completed/stopped/error)。只有第一次调用能成功;
     * stop_agent / 任务取消级联与子线程收口并发时,后到者返回 false 并跳过事件发射。
     */
    public boolean claimTerminal(String status) {
        if (terminalClaimed.compareAndSet(false, true)) {
            this.status = status;
            this.finished = true;
            return true;
        }
        return false;
    }

    /** 累计 usage 引用(供 WorkerToolEventAdvisor 读取运行期最新累计值,不拷贝)。 */
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
