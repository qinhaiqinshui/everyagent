package dev.everyagent.plugin.subagent;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.agent.Agent;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentActivity;
import dev.everyagent.plugin.api.agent.AgentBuilder;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.util.RootCause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 子 agent 管理(架构 §5.6;设计 §8.2 全面中性化):
 * 子 agent 独立会话(不继承父上下文)、工具集不含 agent 工具(结构上禁递归);
 * 级联停止;主体收口前自动等待全部子 agent。
 *
 * <p><b>执行域能力,零服务依赖</b>:构造参数归零,全部取数经 {@link ExecContext}
 * 槽位(agentFactory / emitter / agents / interaction / subjectId)——没有 task
 * 只有 workflow 时同样可用。本类只负责编排(启动/等待/停止)。
 *
 * <p><b>监视器内部化</b>:run/stopAll 互斥不再借用任务对象监视器,改锁 Manager
 * 自有的 per-subject 状态对象 {@link TaskSubState}——对执行主体对象的锁依赖消失。
 */
public class SubAgentManager {

    /** wait_agents 未显式传 timeoutMs 时的默认等待上限(毫秒),防长时间挂起主 agent。 */
    private static final long DEFAULT_WAIT_TIMEOUT_MS = 30_000;

    /** 主体收口等待全部子 agent 的超时(毫秒)。 */
    private static final long AWAIT_ALL_TIMEOUT_MS = 30_000;

    /** 取消竞态定稿等待上限(毫秒):future 已取消但子线程尚未写终态时的有界等待。 */
    private static final long SETTLE_NUDGE_MS = 2_000;

    /** 子 agent 最大并发数(固定值,替代原 props.getLimits().getMaxConcurrentSubs())。 */
    private static final int MAX_CONCURRENT_SUBS = 10;

    /** 子 agent 系统提示词(从已删除的旧装配工厂迁入)。 */
    public static final String SUB_SYSTEM_PROMPT =
            "你是任务中派生的子 agent。专注完成交给你的单一目标,善用工具,给出简明的最终结论。";

    private static final Logger log = LoggerFactory.getLogger(SubAgentManager.class);

    /** per-subject 子 agent 状态(subFutures + stopRequested,Manager 自有;主体对象不持有)。 */
    private final Map<String, TaskSubState> taskStates = new ConcurrentHashMap<>();

    /** 子 agent 运行线程池(虚拟线程 per-task)。 */
    private final java.util.concurrent.ExecutorService vt = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    /** per-subject 状态:子 agent futures + 停止标志(也是 run/stopAll 的互斥监视器)。 */
    private static final class TaskSubState {
        final Map<String, Future<?>> subFutures = new ConcurrentHashMap<>();
        volatile boolean stopRequested;
    }

    private TaskSubState state(ExecContext ctx) {
        return taskStates.computeIfAbsent(ctx.subjectId(), k -> new TaskSubState());
    }

    /**
     * 每轮执行前重置 per-subject 状态:清除上一轮的 stopRequested 标志,
     * 并清理已完成的子 agent futures(防止跨轮无限增长)。
     * <p>由 SubAgentSpawnedAwaitNode 下行段调用,确保新一轮执行时
     * run_agent 不会因上一轮 stopAll 设置的 stopRequested 而被误拒。
     * <p>下行阶段执行主体可能尚未创建,因此接受 subjectId 而非 ExecContext。
     * 下行阶段不会有并发的 run/stopAll(上一轮已收口、本轮 agent 尚未启动),无需 synchronized。
     */
    public void resetForRun(String subjectId) {
        TaskSubState st = taskStates.get(subjectId);
        if (st == null) {
            log.info("[sub] resetForRun:无历史状态(首次运行) subjectId={}", subjectId);
            return; // 首次运行,无历史状态需重置
        }
        log.info("[sub] resetForRun:清除 stopRequested={} 清理已完成 futures subjectId={}",
                st.stopRequested, subjectId);
        st.stopRequested = false;
        // 清理上一轮已完成的子 agent futures(未完成的保留:极端情况下
        // 上一轮的子 agent 可能尚未收口,不应在此丢弃)
        st.subFutures.entrySet().removeIf(e -> e.getValue().isDone());
    }

    /**
     * run_agent 工具入口:返回给主 agent 的文本。
     * 同 agentId 仅在"运行中"时拒绝;已落定(完成/停止/失败)→ 复用原实体续跑
     * (原会话追加新指令,不重做已完成部分,§5.6/agent-dispatch 技能)。
     */
    public String run(ExecContext ctx, String input, String title, String agentId)
            throws InterruptedException {
        TaskSubState st = state(ctx);
        log.info("[sub] run_agent 进入 subjectId={} agentId={} stopRequested={} liveCount={}",
                ctx.subjectId(), agentId, st.stopRequested,
                st.subFutures.values().stream().filter(f -> !f.isDone()).count());
        // 注意:不在同步块外做 stopRequested 硬拒。
        // stopRequested 由 stopAll 设置,stopAll 与 run 共用 synchronized(st) 互斥。
        // 若 run 拿到锁,stopAll 一定不在运行——此时 stopRequested=true 只能是上一轮
        // 生命周期 stopAll 遗留的陈旧标志(见 SubAgentSpawnedAwaitNode 上行段)。
        // 陈旧标志在同步块内自愈清除,不再拦截 run_agent。
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
        boolean reuse = agentId != null && !agentId.isEmpty() && ctx.agents().containsKey(agentId);
        String id = agentId == null || agentId.isEmpty() ? dev.everyagent.plugin.api.proto.ShortIds.next("sub") : agentId;
        Agent sub;

        // 注册/启动放在 TaskSubState 监视器内,与 stopAll 互斥(监视器内部化,§8.2):
        // 要么 stopAll 先拿到锁(主体停止,run 被阻塞),要么这里先注册完,
        // stopAll 随后一定能 cancel 到该 future 并立即收口。
        synchronized (st) {
            if (st.stopRequested) {
                // 同步块内看到 stopRequested=true:stopAll 不可能在并发运行(我们持锁),
                // 此标志来自上一轮生命周期的 stopAll(上行段)。清除并继续启动子 agent。
                log.info("[sub] 清除陈旧 stopRequested 标志 subjectId={} agentId={}", ctx.subjectId(), id);
                st.stopRequested = false;
            }
            if (reuse) {
                sub = (Agent) ctx.agents().get(id);
                sub.resetForRerun();
                sub.conversation().add(new UserMessage(input)); // 续跑:原会话历史 + 新指令
                // 复用路径不经过 build():agent.started 由 AgentStatusAdvisor 在本轮
                // run() 的流入口自动发射(与新建路径同一发射点),此处不再手动补发。
            } else {
                sub = buildSubAgent(ctx, id, title == null || title.isEmpty() ? "子任务" : title, input);
            }

            // FutureTask 先入册再执行:waitFor/stop/run 守卫看到的永远是当前运行,
            // 不存在 submit 与 put 之间被查询/完成的窗口。
            // 注:agents().put 和 agent.started 事件由 build() 自动完成;
            // 此处只管理 SubAgentManager 自己的 future。
            java.util.concurrent.FutureTask<Void> ft = new java.util.concurrent.FutureTask<>(() -> {
                runSub(ctx, id, sub);
                return null;
            });
            st.subFutures.put(id, ft);
            vt.execute(ft);
            log.debug("[sub] 启动子 agent id={} subjectId={} reuse={} thread={}",
                    id, ctx.subjectId(), reuse, Thread.currentThread().getName());
        }

        return "子 agent 已启动(异步): " + id;
    }

    /**
     * 用绑定工厂装配子 agent:{@code ctx.agentFactory().create(agentId)} 单参创建
     * (绑定默认 configId = {@code ctx.snapshot().configId()},emitter 固定主体事件口);
     * 工具集用 REMOVE 模式移除派发工具(run_agent/list_agents/wait_agents/stop_agent)
     * 和 ask_user(结构上禁递归 + 提问只能由主 agent 发起)。
     */
    private Agent buildSubAgent(ExecContext ctx, String agentId, String title, String input) {
        // REMOVE 模式移除派发工具和 ask_user
        List<ToolCallback> toRemove = new ArrayList<>(List.of(ToolCallbacks.from(new SubAgentToolNames())));

        return ctx.agentFactory().create(agentId)
                .title(title)
                .tools(toRemove, AgentBuilder.ModifyMode.REMOVE)
                .systemPrompt(SUB_SYSTEM_PROMPT)
                .userInput(input)
                .creator("subagent")
                .build();
    }

    /**
     * 子 agent 运行体(vt 线程)。
     *
     * <p><b>本方法一发事件都不发</b>:{@code agent.started}/{@code agent.status}/
     * {@code agent.done}/{@code error} 全由子 agent 自己 advisor 链上的
     * {@code AgentStatusAdvisor} 按 per-run 生命周期发射(§7.20.1)。此处仅剩两件事:
     * <ul>
     *   <li>{@link Agent#claimTerminal} 作**兜底**——运行体没进入流生命周期就收口的路径
     *       (FutureTask 被 cancel 后从未启动、或 run() 在订阅前就抛)不会有 advisor 终态回调,
     *       缺这一兜底前端就永远显示 running;advisor 已声明过时 CAS 返回 false,
     *       绝不重复发事件;</li>
     *   <li>finally 置 finished(wait_agents 的定稿等待依赖它)。</li>
     * </ul>
     */
    private void runSub(ExecContext ctx, String id, Agent sub) {
        log.debug("[sub] runSub 进入 id={} subjectId={} thread={} interruptFlag={}",
                id, ctx.subjectId(), Thread.currentThread().getName(), Thread.currentThread().isInterrupted());
        try {
            sub.run();
            // 正常完成:advisor 的 doOnComplete 已发 agent.status{done} + agent.done;
            // 未被 stop 侧抢先声明时此处 CAS 成功也不重复发(claimTerminal 幂等)。
            boolean claimed = sub.claimTerminal("completed");
            log.debug("[sub] 正常完成 id={} advisor 外补声明={} 现status={} thread={}",
                    id, claimed, sub.status(), Thread.currentThread().getName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("[sub] 捕获 InterruptedException id={} thread={}", id, Thread.currentThread().getName());
            // stop_agent / 级联取消:流被 dispose 时 advisor doOnCancel 已发 stopped;
            // 未启动过(future 在 runSub 前被 cancel)或 dispose 竞态漏信号时由此兜底。
            markStopped(sub);
        } catch (Throwable t) {
            // 根因摘要:BaseAdvisor 包装会把真实错误埋在最里层(见 RootCause)。
            log.warn("子 agent {} 异常: {}", id, RootCause.summary(t));
            log.debug("子 agent {} 异常完整堆栈", id, t);
            // advisor 的 doOnError 已发 failed + error;订阅前就抛(装配/模型客户端缺失)时由此兜底。
            sub.claimTerminal("error", RootCause.summary(t));
        } finally {
            sub.finished(true);
            log.debug("[sub] runSub 收口退出 id={} subStatus={} finished=true thread={}",
                    id, sub.status(), Thread.currentThread().getName());
        }
    }

    /**
     * 把子 agent 收口为 stopped(CAS 幂等;advisor 已声明过则什么都不发生)。
     * stop_agent / 主体取消级联调用;已 completed/error 收口的实体不覆盖。
     */
    private static void markStopped(Agent sub) {
        boolean claimed = sub.claimTerminal("stopped", "已取消");
        log.debug("[sub] markStopped id={} 本次声明={} 现status={} thread={}",
                sub.agentId(), claimed, sub.status(), Thread.currentThread().getName());
    }

    /**
     * list_agents 工具入口:返回 {@code {agents:[摘要]}}(对标 old list_agent 契约)。
     * 摘要 = {agentId,title,createdAt,status,latestActivity}:status 终态为
     * completed/stopped/error,running 运行中,waiting-user 挂起等用户回答;
     * latestActivity 为最近一次 AI 返回的快照,不回灌完整历史。
     */
    public String list(ExecContext ctx) throws InterruptedException {
        TaskSubState st = state(ctx);
        // 取消竞态收口:future 已取消但子线程尚未写终态的,有界等定稿,避免把已停止的报成 running
        for (AgentContext s : ctx.agents().values()) {
            if (!s.finished()) {
                Future<?> f = st.subFutures.get(s.agentId());
                if (f != null && f.isDone()) {
                    awaitSettle(s);
                }
            }
        }
        ObjectNode r = Json.obj();
        r.set("agents", agentsJsonMerged(ctx));
        return Json.write(r);
    }

    /**
     * 遍历 ctx.agents() 返回 subagent 创建的 agent 摘要数组。
     * 按顶级 creator=subagent 过滤,只展示本插件创建的子 agent。
     */
    private ArrayNode agentsJsonMerged(ExecContext ctx) {
        java.util.LinkedHashMap<String, ObjectNode> merged = new java.util.LinkedHashMap<>();
        for (AgentContext s : ctx.agents().values()) {
            // 过滤:只展示 subagent 创建的 agent
            if (!"subagent".equals(s.creator())) continue;
            merged.put(s.agentId(), summaryJson(ctx, s));
        }
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
    public String waitFor(ExecContext ctx, String agentId, Long timeoutMs) throws InterruptedException {
        TaskSubState st = state(ctx);
        long timeout = timeoutMs != null && timeoutMs > 0 ? timeoutMs : DEFAULT_WAIT_TIMEOUT_MS;
        if (agentId != null && !agentId.isEmpty()) {
            ObjectNode r = Json.obj();
            r.put("mode", "single");
            AgentContext sub = ctx.agents().get(agentId);
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
            if (f == null || isTerminal(sub.status())) {
                // 已落定(完成/停止/失败):直接返回最新摘要
                r.put("waitStatus", "completed");
                r.set("agent", summaryJson(ctx, sub));
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
            r.set("agent", summaryJson(ctx, sub));
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
        r.set("agents", agentsJsonMerged(ctx));
        return Json.write(r);
    }

    public String stop(ExecContext ctx, String agentId) {
        TaskSubState st = state(ctx);
        AgentContext sub = ctx.agents().get(agentId);
        Future<?> f = st.subFutures.get(agentId);
        if (f == null) {
            return "子 agent 不存在: " + agentId;
        }
        if (sub != null && isTerminal(sub.status())) {
            return "子 agent 已结束(" + sub.status() + "),无需停止: " + agentId;
        }
        boolean cancelled = f.cancel(true);
        log.debug("[sub] stop 请求 id={} cancel(true)={} futureDone={} thread={}",
                agentId, cancelled, f.isDone(), Thread.currentThread().getName());
        // 不能只 cancel future:FutureTask 尚未开始执行(cancel 只置 CANCELLED 不跑 runSub)、
        // 或子线程未能立刻响应中断时,前端会一直看到 running。这里同步声明终态——
        // advisor 的 doOnCancel 已声明过时 CAS 失败,不重复发事件(§7.20.1)。
        if (sub != null) {
            markStopped((Agent) sub);
        }
        return "已请求停止: " + agentId;
    }

    /** 子 agent 摘要(list_agents/wait_agents 共用契约,对标 old AgentSummary):字段 null 省略。 */
    private ObjectNode summaryJson(ExecContext ctx, AgentContext s) {
        ObjectNode n = Json.obj();
        n.put("agentId", s.agentId());
        if (s.title() != null) {
            n.put("title", s.title());
        }
        n.put("createdAt", s.createdAt());
        n.put("status", effectiveStatus(ctx, s));
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
    private String effectiveStatus(ExecContext ctx, AgentContext s) {
        if ("running".equals(s.status())
                && ctx.interaction().hasPendingFor(ctx.subjectId(), s.agentId())) {
            return "waiting-user";
        }
        return s.status();
    }

    /** 工具契约终态判定。 */
    private static boolean isTerminal(String status) {
        return "completed".equals(status) || "stopped".equals(status) || "error".equals(status);
    }

    /**
     * 有界等待子 agent 收口定稿(finished 置位)。
     * 取消竞态下 future.get() 会先于子线程写终态返回,不等待会把已停止的报成 running。
     */
    private static void awaitSettle(AgentContext sub) throws InterruptedException {
        if (sub.finished()) {
            return;
        }
        long deadline = System.currentTimeMillis() + SETTLE_NUDGE_MS;
        while (!sub.finished() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    /** 主体收口前调用:等待全部子 agent(超时则级联停止,§5.6)。 */
    public void awaitAllBeforeFinish(ExecContext ctx) {
        try {
            waitFor(ctx, null, AWAIT_ALL_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        stopAll(ctx);
    }

    public void stopAll(ExecContext ctx) {
        TaskSubState st = state(ctx);
        synchronized (st) {
            st.stopRequested = true; // 统一入口置位:任何全停路径都不允许再启动新子 agent
            log.info("[sub] stopAll 设置 stopRequested=true subjectId={} 子数={} thread={}",
                    ctx.subjectId(), st.subFutures.size(), Thread.currentThread().getName());
            for (Future<?> f : st.subFutures.values()) {
                boolean c = f.cancel(true);
                log.debug("[sub] stopAll cancel id={} cancel(true)={} done={} thread={}",
                        "?", c, f.isDone(), Thread.currentThread().getName());
            }
            // 见 stop():cancel 不保证 runSub 会执行收口(未启动/未及时响应中断的 future 永远停在 running),
            // 这里对全部未终态实体同步声明 stopped,确保主体取消/失败/停机路径下前端状态能收口。
            // advisor 的 doOnCancel 已抢先声明过的实体 CAS 失败,不会重复发事件(§7.20.1)。
            for (AgentContext sub : ctx.agents().values()) {
                markStopped((Agent) sub);
            }
        }
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        vt.shutdownNow();
    }
}
