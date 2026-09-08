package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
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

    /**
     * 任务队列「插入到当前对话」缓冲(本轮有效,不跨 run 共享):用户点击队列项「插入」按钮时,
     * TaskManager 把正文 offer 到主 agent 实体的此队列;{@link DialogInsertAdvisor} 在工具循环
     * 把工具结果交回 AI 时的下行阶段(before) drain 并以 role=user 消息随工具结果一并提交给模型,
     * 同时发射 {@code user.message} 事件。队列本体挂在主 agent 实体上——用户停止/任务终态时
     * AgentEntity 随本轮 run 销毁,积压的插入用户消息随之作废,不会带进下一轮/再运行。
     * 线程安全:入队来自 hub 消息线程,出队来自 agent 工具循环线程(main。子 agent 不接收,恒空)。
     */
    public final java.util.concurrent.ConcurrentLinkedQueue<UserInput> pendingDialogInserts =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private final AtomicReference<Usage> usage = new AtomicReference<>(Usage.zero());
    /** 最近一轮实测 usage(WorkerToolEventAdvisor 每轮 usage 事件时写;任务级 usageSummary 的供体)。 */
    private volatile Usage lastRound = Usage.zero();
    /** 最近一轮所用模型名(与 lastRound 同源;空 = 未知)。 */
    private volatile String lastModel = "";
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

    /** 记录最近一轮实测用量(usage 事件发射时同步写,任务级 usageSummary 据此持久化)。 */
    public void recordLastRound(Usage round, String model) {
        if (round != null) {
            lastRound = round;
        }
        if (model != null && !model.isEmpty()) {
            lastModel = model;
        }
    }

    public AgentActivity activity() {
        return activity.get();
    }

    /**
     * 元数据摘要(meta.json agents 数组项 + 冷启动恢复台账):agentId/kind/title/createdAt/
     * status/usage/latestActivity/lastText。契约字段与 SubAgentManager.summaryJson 同形,
     * 额外携带 usage/lastText/kind 供持久化与诊断。
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
