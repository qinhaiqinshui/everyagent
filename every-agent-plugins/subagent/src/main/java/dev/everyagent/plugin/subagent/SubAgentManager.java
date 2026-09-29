package dev.everyagent.plugin.subagent;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.worker.agent.AgentBuilder;
import dev.everyagent.worker.agent.AgentEntity;
import dev.everyagent.worker.agent.AgentRunner;
import dev.everyagent.worker.modules.ConfigStore;
import dev.everyagent.worker.modules.ConfigStore.ResolvedConfig;
import dev.everyagent.worker.proto.SnowflakeId;
import dev.everyagent.worker.task.AgentActivity;
import dev.everyagent.worker.task.ChatModelFactory;
import dev.everyagent.worker.interaction.InteractionServiceImpl;
import dev.everyagent.worker.task.RootCause;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.tools.AskUserTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 子 agent 管理(架构 §5.6):
 * 子 agent 独立会话(不继承父上下文)、工具集不含 agent 工具(结构上禁递归);
 * 级联停止;任务收口前自动等待全部子 agent。
 * 装配用 AgentBuilder(agent 层);本类只负责编排(启动/等待/停止/台账)。
 *
 * <p>重构后:TaskEntry 不再持有 subs/subFutures/stopRequested,本类内部维护 per-task 状态。
 */
@Component
public class SubAgentManager {

    /** wait_agents 未显式传 timeoutMs 时的默认等待上限(毫秒),防长时间挂起主 agent。 */
    private static final long DEFAULT_WAIT_TIMEOUT_MS = 30_000;

    /** 任务收口等待全部子 agent 的超时(毫秒)。 */
    private static final long AWAIT_ALL_TIMEOUT_MS = 30_000;

    /** 取消竞态定稿等待上限(毫秒):future 已取消但子线程尚未写终态时的有界等待。 */
    private static final long SETTLE_NUDGE_MS = 2_000;

    /** 子 agent 最大并发数(固定值,替代原 props.getLimits().getMaxConcurrentSubs())。 */
    private static final int MAX_CONCURRENT_SUBS = 10;

    /** 子 agent 系统提示词(从已删除的旧装配工厂迁入)。 */
    public static final String SUB_SYSTEM_PROMPT =
            "你是任务中派生的子 agent。专注完成交给你的单一目标,善用工具,给出简明的最终结论。";

    private static final Logger log = LoggerFactory.getLogger(SubAgentManager.class);

    private final AgentRunner runner;
    private final AgentBuilder agentBuilder;
    private final ConfigStore configStore;
    private final ChatModelFactory modelFactory;
    private final InteractionServiceImpl asks;
    private final java.util.concurrent.ExecutorService vt = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    /** per-task 子 agent 状态(subFutures + stopRequested,TaskEntry 不再持有这些)。 */
    private final Map<String, TaskSubState> taskStates = new ConcurrentHashMap<>();

    /** per-task 状态:子 agent futures + 停止标志。 */
    private static final class TaskSubState {
        final Map<String, Future<?>> subFutures = new ConcurrentHashMap<>();
        volatile boolean stopRequested;
    }

    private TaskSubState state(TaskEntry task) {
        return taskStates.computeIfAbsent(task.taskId, k -> new TaskSubState());
    }

    public SubAgentManager(AgentRunner runner, AgentBuilder agentBuilder,
            ConfigStore configStore, ChatModelFactory modelFactory, InteractionServiceImpl asks) {
        this.runner = runner;
        this.agentBuilder = agentBuilder;
        this.configStore = configStore;
        this.modelFactory = modelFactory;
        this.asks = asks;
    }

    /**
     * run_agent 工具入口:返回给主 agent 的文本。
     * 同 agentId 仅在"运行中"时拒绝;已落定(完成/停止/失败)→ 复用原实体续跑
     * (原会话追加新指令,不重做已完成部分,§5.6/agent-dispatch 技能)。
     */
    public String run(TaskEntry task, String input, String title, String agentId)
            throws InterruptedException {
        TaskSubState st = state(task);
        if (st.stopRequested) {
            return "任务已停止,未启动子 agent";
        }
        if (agentId != null && !agentId.isEmpty()) {
            Future<?> live = st.subFutures.get(agentId);
            if (live != null && !live.isDone()) {
                return "子 agent 已在运行: " + agentId + "(请先 wait_agents 等待或 stop_agent 停止)";
            }
        }
        long liveCount = st.subFutures.values().stream().filter(f -> !f.isDone()).count();
        if (liveCount >= MAX_CONCURRENT_SUBS) {
            return "[无法启动] 本任务运行中的子 agent 已达 " + liveCount + "/" + MAX_CONCURRENT_SUBS
                    + "(上限),请先用 wait_agents 等待现有子 agent 完成。"
                    + "注:前端「正在排队(在飞 N / 排队 M)」是模型 API 级限流(跨任务统计模型请求数),与此处子 agent 并发上限(单任务)是两套独立计数,数值不对应。";
        }
        boolean reuse = agentId != null && !agentId.isEmpty() && task.agents.containsKey(agentId);
        String id = agentId == null || agentId.isEmpty() ? dev.everyagent.worker.proto.ShortIds.next("sub") : agentId;
        AgentEntity sub;

        // 注册/启动放在 task 监视器内,与 stopAll 互斥:
        // 要么 stopAll 先拿到锁(任务停止,这里看到 stopRequested 后放弃启动),
        // 要么这里先注册完,stopAll 随后一定能 cancel 到该 future 并立即收口。
        synchronized (task) {
            if (st.stopRequested) {
                return "任务已停止,未启动子 agent";
            }
            if (reuse) {
                sub = task.agents.get(id);
                sub.resetForRerun();
                sub.conversation.add(new UserMessage(input)); // 续跑:原会话历史 + 新指令
            } else {
                sub = buildSubAgent(task, id, title == null || title.isEmpty() ? "子任务" : title, input);
            }

            // FutureTask 先入册再执行:waitFor/stop/run 守卫看到的永远是当前运行,
            // 不存在 submit 与 put 之间被查询/完成的窗口。注册必须先于 running 事件,
            // 这样前端一旦看到「运行中」,stop 就一定能在 subFutures 里找到并取消它。
            java.util.concurrent.FutureTask<Void> ft = new java.util.concurrent.FutureTask<>(() -> {
                runSub(task, id, sub);
                return null;
            });
            task.agents.put(id, sub);
            st.subFutures.put(id, ft);
            {
                ObjectNode startedData = Json.obj();
                startedData.put("agentId", id);
                if (sub.title != null) {
                    startedData.put("title", sub.title);
                }
                startedData.put("input", input);
                task.events.emit(EmitEvent.of(SnowflakeId.next(), "agent.started", id,
                        null, null, null, null, startedData, EmitEvent.Mode.REPLACE));
                task.events.emit(EmitEvent.of(SnowflakeId.next(), "agent.status", id,
                        null, null, null, "running", null, EmitEvent.Mode.REPLACE));
            }
            vt.execute(ft);
            log.debug("[sub] 启动子 agent id={} taskId={} reuse={} thread={}",
                    id, task.taskId, reuse, Thread.currentThread().getName());
        }

        return "子 agent 已启动(异步): " + id;
    }

    /**
     * 用 AgentBuilder 装配子 agent:模型经 ChatModelFactory 解析,工具集用 REMOVE 模式
     * 移除派发工具(run_agent/list_agents/wait_agents/stop_agent)和 ask_user(结构上禁递归 +
     * 提问只能由主 agent 发起)。
     */
    private AgentEntity buildSubAgent(TaskEntry task, String agentId, String title, String input) {
        ResolvedConfig cfg = configStore.resolve(task.snapshot.configId());
        ChatModelFactory.AgentModel am = modelFactory.buildAgentModel(cfg, agentId, task.events, null);

        java.util.Map<String, Object> props = new java.util.HashMap<>();
        props.put("taskEntry", task);
        props.put("taskId", task.taskId);
        props.put("workspaceRoot", task.workspaceRoot);
        props.put("configId", task.snapshot.configId());

        // REMOVE 模式移除派发工具和 ask_user
        List<ToolCallback> toRemove = new ArrayList<>();
        toRemove.addAll(List.of(ToolCallbacks.from(new SubAgentTools(this, Map.of()))));
        toRemove.addAll(List.of(ToolCallbacks.from(new AskUserTool(null, null, null, ""))));

        return agentBuilder.create(agentId, am.chatModel(), am.options(), task.events, props)
                .title(title)
                .tools(toRemove, AgentBuilder.ModifyMode.REMOVE)
                .systemPrompt(SUB_SYSTEM_PROMPT)
                .userInput(input)
                .build();
    }

    /** 子 agent 运行体(vt 线程):任何收口路径都写工具契约终态 + error 快照,finally 置 finished。 */
    private void runSub(TaskEntry task, String id, AgentEntity sub) {
        log.debug("[sub] runSub 进入 id={} taskId={} thread={} interruptFlag={}",
                id, task.taskId, Thread.currentThread().getName(), Thread.currentThread().isInterrupted());
        try {
            runner.run(sub);
            // 正常完成:只有未被 stop 侧抢先置为 stopped 时才发 done(终态唯一声明)。
            if (sub.claimTerminal("completed")) {
                log.debug("[sub] 正常完成 claimTerminal(completed)=true id={} thread={}",
                        id, Thread.currentThread().getName());
                ObjectNode doneData = Json.obj();
                doneData.put("agentId", id);
                doneData.set("usage", Json.toJson(sub.usage()));
                task.events.emit(EmitEvent.of(SnowflakeId.next(), "agent.done", id,
                        null, null, sub.lastText, null, doneData, EmitEvent.Mode.REPLACE));
                task.events.emit(EmitEvent.of(SnowflakeId.next(), "agent.status", id,
                        null, null, null, "done", null, EmitEvent.Mode.REPLACE));
            } else {
                log.debug("[sub] 正常完成但 claimTerminal=false(已被停止侧抢先) id={} subStatus={} thread={}",
                        id, sub.status, Thread.currentThread().getName());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("[sub] 捕获 InterruptedException id={} thread={}", id, Thread.currentThread().getName());
            emitStopped(task, sub); // stop_agent / 级联取消
        } catch (Throwable t) {
            // 根因摘要:BaseAdvisor 包装会把真实错误埋在最里层(见 RootCause)。
            log.warn("子 agent {} 异常: {}", id, RootCause.summary(t));
            log.debug("子 agent {} 异常完整堆栈", id, t);
            if (sub.claimTerminal("error")) {
                String msg = RootCause.summary(t);
                sub.updateActivity(null, null, msg);
                task.events.emit(EmitEvent.of(SnowflakeId.next(), "error", id,
                        null, null, msg, null, null, EmitEvent.Mode.REPLACE));
                task.events.emit(EmitEvent.of(SnowflakeId.next(), "agent.status", id,
                        null, null, null, "failed", null, EmitEvent.Mode.REPLACE));
            }
        } finally {
            sub.finished = true;
            log.debug("[sub] runSub 收口退出 id={} subStatus={} finished=true thread={}",
                    id, sub.status, Thread.currentThread().getName());
        }
    }

    /**
     * 把子 agent 立即置为 stopped 并发终态事件(幂等)。
     * stop_agent / 任务取消级联调用;如果子线程已抢先收口(completed/error),此处不覆盖。
     */
    private void emitStopped(TaskEntry task, AgentEntity sub) {
        boolean claimed = sub.claimTerminal("stopped");
        log.debug("[sub] emitStopped id={} claimed={} 现status={} thread={}",
                sub.agentId, claimed, sub.status, Thread.currentThread().getName());
        if (!claimed) {
            return;
        }
        sub.updateActivity(null, null, "已取消");
        task.events.emit(EmitEvent.of(SnowflakeId.next(), "error", sub.agentId,
                null, null, "已取消", null, null, EmitEvent.Mode.REPLACE));
        task.events.emit(EmitEvent.of(SnowflakeId.next(), "agent.status", sub.agentId,
                null, null, null, "stopped", null, EmitEvent.Mode.REPLACE));
    }

    /**
     * list_agents 工具入口:返回 {@code {agents:[摘要]}}(对标 old list_agent 契约)。
     * 摘要 = {agentId,title,createdAt,status,latestActivity}:status 终态为
     * completed/stopped/error,running 运行中,waiting-user 挂起等用户回答;
     * latestActivity 为最近一次 AI 返回的快照,不回灌完整历史。
     */
    public String list(TaskEntry task) throws InterruptedException {
        TaskSubState st = state(task);
        // 取消竞态收口:future 已取消但子线程尚未写终态的,有界等定稿,避免把已停止的报成 running
        for (AgentEntity s : task.agents.values()) {
            if (!s.finished) {
                Future<?> f = st.subFutures.get(s.agentId);
                if (f != null && f.isDone()) {
                    awaitSettle(s);
                }
            }
        }
        ObjectNode r = Json.obj();
        r.set("agents", agentsJson(task));
        return Json.write(r);
    }

    /** 活实体的 agents 摘要数组:遍历 task.agents.values(),按 createdAt 稳定排序。 */
    private ArrayNode agentsJson(TaskEntry task) {
        java.util.LinkedHashMap<String, ObjectNode> merged = new java.util.LinkedHashMap<>();
        for (AgentEntity s : task.agents.values()) {
            merged.put(s.agentId, summaryJson(task, s));
        }
        // 按 createdAt 稳定排序。
        java.util.List<ObjectNode> ordered = new java.util.ArrayList<>(merged.values());
        ordered.sort(java.util.Comparator.comparingLong(a -> a.path("createdAt").asLong(0)));
        ArrayNode agents = Json.arr();
        ordered.forEach(agents::add);
        return agents;
    }

    /**
     * wait_agents 工具入口(对标 old wait_agent 契约):
     * 传 agentId → {@code {mode:"single",waitStatus,agent}}(等待单个,
     * 超时 waitStatus="timeout" 且仍返回最新摘要供判断进度);
     * 不传 → {@code {mode:"list",waitStatus,agents}}(共享 deadline 等待全部未落定)。
     */
    public String waitFor(TaskEntry task, String agentId, Long timeoutMs) throws InterruptedException {
        TaskSubState st = state(task);
        long timeout = timeoutMs != null && timeoutMs > 0 ? timeoutMs : DEFAULT_WAIT_TIMEOUT_MS;
        if (agentId != null && !agentId.isEmpty()) {
            ObjectNode r = Json.obj();
            r.put("mode", "single");
            AgentEntity sub = task.agents.get(agentId);
            Future<?> f = st.subFutures.get(agentId);
            if (sub == null) {
                // agentId 不存在(凭空 id):最小摘要 + 说明,waitStatus=timeout
                log.warn("wait_agents 未找到 agentId,返回最小摘要: {}", agentId);
                ObjectNode a = Json.obj();
                a.put("agentId", agentId);
                a.put("createdAt", System.currentTimeMillis());
                a.put("status", "running");
                ObjectNode la = Json.obj();
                la.put("error", "agentId 不存在(agentId 应来自 list_agents)");
                a.set("latestActivity", la);
                r.put("waitStatus", "timeout");
                r.set("agent", a);
                return Json.write(r);
            }
            if (f == null || isTerminal(sub.status)) {
                // 已落定(完成/停止/失败):直接返回最新摘要
                r.put("waitStatus", "completed");
                r.set("agent", summaryJson(task, sub));
                return Json.write(r);
            }
            boolean settled = true;
            try {
                f.get(timeout, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                settled = false;
            } catch (java.util.concurrent.ExecutionException
                    | java.util.concurrent.CancellationException ignored) {
                // 异常/stop_agent 取消由子线程收口为终态摘要,此处视为已落定
            }
            // 取消竞态:cancel 使 get() 立即返回,子线程可能尚未写终态——有界等定稿再取摘要
            awaitSettle(sub);
            r.put("waitStatus", settled ? "completed" : "timeout");
            r.set("agent", summaryJson(task, sub));
            return Json.write(r);
        }
        boolean allSettled = true;
        long deadline = System.currentTimeMillis() + timeout;
        for (Future<?> f : st.subFutures.values()) {
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                allSettled = false;
                break;
            }
            if (f.isDone()) {
                continue; // 已落定(含被取消)不占等待预算
            }
            try {
                f.get(remain, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                allSettled = false;
                break;
            } catch (java.util.concurrent.ExecutionException
                    | java.util.concurrent.CancellationException ignored) {
                // 异常已在子线程记录为该子 agent 的 error 事件
            }
        }
        ObjectNode r = Json.obj();
        r.put("mode", "list");
        r.put("waitStatus", allSettled ? "completed" : "timeout");
        r.set("agents", agentsJson(task));
        return Json.write(r);
    }

    public String stop(TaskEntry task, String agentId) {
        TaskSubState st = state(task);
        AgentEntity sub = task.agents.get(agentId);
        Future<?> f = st.subFutures.get(agentId);
        if (f == null) {
            return "子 agent 不存在: " + agentId;
        }
        if (sub != null && isTerminal(sub.status)) {
            return "子 agent 已结束(" + sub.status + "),无需停止: " + agentId;
        }
        boolean cancelled = f.cancel(true);
        log.debug("[sub] stop 请求 id={} cancel(true)={} futureDone={} thread={}",
                agentId, cancelled, f.isDone(), Thread.currentThread().getName());
        // 不能只 cancel future:FutureTask 尚未开始执行(cancel 只置 CANCELLED 不跑 runSub)、
        // 或子线程未能立刻响应中断时,前端会一直看到 running。这里同步声明终态并发事件。
        if (sub != null) {
            emitStopped(task, sub);
        }
        return "已请求停止: " + agentId;
    }

    /** 子 agent 摘要(list_agents/wait_agents 共用契约,对标 old AgentSummary):字段 null 省略。 */
    private ObjectNode summaryJson(TaskEntry task, AgentEntity s) {
        ObjectNode n = Json.obj();
        n.put("agentId", s.agentId);
        if (s.title != null) {
            n.put("title", s.title);
        }
        n.put("createdAt", s.createdAt);
        n.put("status", effectiveStatus(task, s));
        AgentActivity act = s.activity();
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
        n.set("latestActivity", la);
        return n;
    }

    /** 工具契约有效状态:运行中且挂起 ask → waiting-user(与 UI 事件态同词),否则实体状态。 */
    private String effectiveStatus(TaskEntry task, AgentEntity s) {
        if ("running".equals(s.status) && asks.hasPendingFor(task.taskId, s.agentId)) {
            return "waiting-user";
        }
        return s.status;
    }

    /** 工具契约终态判定。 */
    private static boolean isTerminal(String status) {
        return "completed".equals(status) || "stopped".equals(status) || "error".equals(status);
    }

    /**
     * 有界等待子 agent 收口定稿(finished 置位)。
     * 取消竞态下 future.get() 会先于子线程写终态返回,不等待会把已停止的报成 running。
     */
    private static void awaitSettle(AgentEntity sub) throws InterruptedException {
        if (sub.finished) {
            return;
        }
        long deadline = System.currentTimeMillis() + SETTLE_NUDGE_MS;
        while (!sub.finished && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    /** 任务收口前调用:等待全部子 agent(超时则级联停止,§5.6)。 */
    public void awaitAllBeforeFinish(TaskEntry task) {
        try {
            waitFor(task, null, AWAIT_ALL_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        stopAll(task);
    }

    public void stopAll(TaskEntry task) {
        TaskSubState st = state(task);
        synchronized (task) {
            st.stopRequested = true; // 统一入口置位:任何全停路径都不允许再启动新子 agent
            log.debug("[sub] stopAll 进入 taskId={} 子数={} thread={}",
                    task.taskId, st.subFutures.size(), Thread.currentThread().getName());
            for (Future<?> f : st.subFutures.values()) {
                boolean c = f.cancel(true);
                log.debug("[sub] stopAll cancel id={} cancel(true)={} done={} thread={}",
                        "?", c, f.isDone(), Thread.currentThread().getName());
            }
            // 见 stop():cancel 不保证 runSub 会执行收口(未启动/未及时响应中断的 future 永远停在 running),
            // 这里对全部未终态实体同步声明 stopped,确保任务取消/失败/停机路径下前端状态能收口。
            for (AgentEntity sub : task.agents.values()) {
                emitStopped(task, sub);
            }
        }
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        vt.shutdownNow();
    }
}
