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
import org.springframework.ai.chat.messages.AssistantMessage;
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
 *
 * <p><b>子 agent 是主体级(跨轮)资产</b>:{@code ctx.agents()} 是 per-run 注册表
 * (主体收口即销毁),子 agent 的可见性不随单轮结束消失——工具读侧(list/wait/stop)
 * 按「本轮 live 实体 &gt; 进程内 carry-over 会话快照 &gt; 磁盘台账 agents.json」三层合并
 * (非在场实体的 running/waiting-user 一律归一 stopped,与 AgentLedger 冷启动归一同规则);
 * {@code run_agent} 传历史 agentId 时经 carry-over 重建实体续跑(会话完整延续)。
 * worker 重启后 carry-over 丢失,同 agentId 续跑=全新会话(子 agent 会话不做磁盘冷启动
 * 重建,ConversationLoader 只重建主 agent)。
 *
 * <p><b>会话交替不变量</b>:子 agent 会话跨轮复用(同 agentId 的 run_agent 续跑),
 * 而 agent 执行链不回写会话内存(worker 的 {@code WorkerToolEventAdvisor} 只发
 * message 事件、{@code AgentRunner} 只把会话副本交给 ChatClient),故复用方必须自己
 * 把上一轮最终回答补回会话——每轮收口由 {@link #runSub} 的 finally 调用
 * {@link #appendFinalAnswerTurn} 补 assistant 轮,保证「append 新 user 之前 assistant
 * 已在场」。缺了这一步,子 agent 续跑时会看到两条连续 user,重复回答上一轮指令
 * (主 agent 侧同一条不变量由 task 层 {@code ConversationLoader.catchUpRuntime} 维持)。
 */
public class SubAgentManager {

    /** wait_agents 未显式传 timeoutMs 时的默认等待上限(毫秒),防长时间挂起主 agent。 */
    private static final long DEFAULT_WAIT_TIMEOUT_MS = 30_000;

    /**
     * 主体收口等待全部子 agent 的超时(毫秒)。架构 §7.14:安全超时默认 5 min——
     * 主 agent 未调 wait_agents 就收口时,子 agent 仍被等待到自然完成(典型子任务
     * 分钟级),只有超过安全上限才级联停。
     */
    private static final long AWAIT_ALL_TIMEOUT_MS = 300_000;

    /** 取消竞态定稿等待上限(毫秒):future 已取消但子线程尚未写终态时的有界等待。 */
    private static final long SETTLE_NUDGE_MS = 2_000;

    /**
     * 上一轮未产出最终回答(被 stop / 异常中断)时回写的占位 assistant 轮:
     * 保持会话内 user/assistant 严格交替(与 ConversationLoader 的中断占位同语义)。
     */
    private static final String NO_FINAL_ANSWER_PLACEHOLDER = "[上一轮被中断,无最终回答]";

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
        /**
         * 跨轮 carry-over:上一轮子 agent 的会话快照(agentId → 快照,插入序=收口序)。
         * {@code ctx.agents()} 是 per-run 注册表,收口即销毁;本表由 {@link SubAgentManager}
         * (进程级单例)持有,供下一轮 list/wait/stop 可见与 run_agent 跨轮续跑重建。
         * 容量有界({@link #CARRY_LIMIT}),超限逐出最旧;worker 重启即丢(磁盘兜底见
         * {@link #diskLedger})。
         */
        final java.util.LinkedHashMap<String, CarriedSub> carried = new java.util.LinkedHashMap<>();
    }

    /** carry-over 容量上限(超出按收口序逐出最旧;防长任务/多任务下会话快照无界累积)。 */
    private static final int CARRY_LIMIT = 12;

    /** 跨轮子 agent 快照:终态状态 + 活动快照 + 完整会话(含最终回答轮)。 */
    private static final class CarriedSub {
        final String agentId;
        final String title;
        final long createdAt;
        final String status;
        final AgentActivity activity;
        final List<org.springframework.ai.chat.messages.Message> conversation;

        CarriedSub(String agentId, String title, long createdAt, String status,
                AgentActivity activity,
                List<org.springframework.ai.chat.messages.Message> conversation) {
            this.agentId = agentId;
            this.title = title;
            this.createdAt = createdAt;
            this.status = status;
            this.activity = activity;
            this.conversation = conversation;
        }
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
     * <p>跨轮:实体不在 {@code ctx.agents()}(per-run 注册表)但 carry-over 快照在场 →
     * 重建实体(会话种子=快照,续跑);仅磁盘台账在场(worker 重启后)→ 同 id 全新会话。
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
                // 续跑:原会话历史(含上一轮最终回答,由 runSub 收口时回写)+ 新指令
                sub.conversation().add(new UserMessage(input));
                // 复用路径不经过 build():agent.started 由 AgentStatusAdvisor 在本轮
                // run() 的流入口自动发射(与新建路径同一发射点),此处不再手动补发。
            } else if (agentId != null && !agentId.isEmpty()) {
                // 指定 agentId 但本轮实体不在场:上一轮的跨轮续跑,或重启后的同 id 重建
                CarriedSub carried = st.carried.get(agentId);
                if (carried != null) {
                    sub = rebuildCarried(ctx, carried, input);
                    log.info("[sub] 跨轮续跑重建 agentId={} subjectId={} 会话轮数={} 原status={}",
                            agentId, ctx.subjectId(), carried.conversation.size(), carried.status);
                } else {
                    sub = buildSubAgent(ctx, id, title == null || title.isEmpty() ? "子任务" : title, input);
                    if (diskLedger(ctx).containsKey(agentId)) {
                        log.info("[sub] agentId={} 仅磁盘台账在场(worker 重启或 carry-over 逐出),同 id 全新会话 subjectId={}",
                                agentId, ctx.subjectId());
                    }
                }
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
     * 跨轮续跑重建:用 carry-over 快照作会话种子新建实体(新 emitter/新 ChatClient,
     * 绑定本轮 ExecContext——直接复用旧实体会把事件发进已销毁的上一轮日志)。
     * 快照会话含原 system 轮与上一轮最终回答轮,故不再另设 systemPrompt,只追加新指令。
     */
    private Agent rebuildCarried(ExecContext ctx, CarriedSub carried, String input) {
        List<ToolCallback> toRemove = new ArrayList<>(List.of(ToolCallbacks.from(new SubAgentToolNames())));
        return ctx.agentFactory().create(carried.agentId)
                .title(carried.title == null || carried.title.isEmpty() ? "子任务" : carried.title)
                .tools(toRemove, AgentBuilder.ModifyMode.REMOVE)
                .creator("subagent")
                .conversation(carried.conversation)
                .userInput(input)
                .build();
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
            // 会话配对回写:子 agent 会话跨轮复用(同 agentId 续跑),而 agent 执行链不回写
            // 会话内存(WorkerToolEventAdvisor 只发 message 事件、AgentRunner 只把会话副本
            // 交给 ChatClient)。本类是复用方,必须自己把本轮最终回答作为 assistant 轮补回
            // 会话——否则下一轮 run_agent 续跑时子 agent 看到的是「两条连续 user」,会重复
            // 回答上一轮指令(与主 agent 侧 ConversationLoader.catchUpRuntime 同一不变量)。
            // 异常/中断路径同样回写(空则记占位),保证 user/assistant 严格交替。
            appendFinalAnswerTurn(sub);
            sub.finished(true);
            // 跨轮 carry-over:快照(终态 + 活动 + 完整会话)留给下一轮的 list/wait/stop
            // 与 run_agent 跨轮续跑(ctx.agents() 是 per-run 注册表,收口即销毁)。
            carryOver(ctx, id, sub);
            log.debug("[sub] runSub 收口退出 id={} subStatus={} finished=true thread={}",
                    id, sub.status(), Thread.currentThread().getName());
        }
    }

    /** 记录跨轮快照(容量有界,超限按收口序逐出最旧;同 id 重收口则刷新到最新)。 */
    private void carryOver(ExecContext ctx, String id, Agent sub) {
        TaskSubState st = state(ctx);
        synchronized (st) {
            st.carried.remove(id);
            st.carried.put(id, new CarriedSub(id, sub.title(), sub.createdAt(), sub.status(),
                    sub.activity(), List.copyOf(sub.conversation())));
            while (st.carried.size() > CARRY_LIMIT) {
                String eldest = st.carried.keySet().iterator().next();
                st.carried.remove(eldest);
                log.debug("[sub] carry-over 超限逐出 subjectId={} agentId={}", ctx.subjectId(), eldest);
            }
        }
    }

    /**
     * 把本轮最终回答以 assistant 轮补回子 agent 会话(空/异常中断则记占位),
     * 供下一次同 agentId 续跑时模型看到完整的 (user → assistant) 配对。
     */
    private static void appendFinalAnswerTurn(Agent sub) {
        String text = sub.lastText();
        sub.conversation().add(AssistantMessage.builder()
                .content(text == null || text.isBlank() ? NO_FINAL_ANSWER_PLACEHOLDER : text)
                .build());
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
     * <p>跨轮可见:合并「本轮 live 实体 &gt; 进程内 carry-over &gt; 磁盘台账 agents.json」
     * 三层——上一轮(乃至 worker 重启前)的子 agent 均在列表内,不随单轮收口消失。
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
        r.set("agents", agentsJsonMerged(ctx, st));
        return Json.write(r);
    }

    /**
     * 遍历合并视图返回 subagent 创建的 agent 摘要数组:
     * 磁盘台账(上一轮落定的终态基线,worker 重启后唯一来源)→ carry-over 快照覆盖
     * (进程内,比磁盘新鲜)→ 本轮 live 实体覆盖(status/latestActivity 最新,createdAt
     * /title 沿用基线——跨轮重建实体的 createdAt 是重建时刻,不能覆盖台账的原始创建时间)。
     * 按顶级 creator=subagent 过滤(旧条目无顶级 creator 时回退 metadata.creator,
     * 两者皆空视为旧格式子 agent 保留,§7.20.1),只展示本插件创建的子 agent。
     */
    private ArrayNode agentsJsonMerged(ExecContext ctx, TaskSubState st) {
        java.util.LinkedHashMap<String, ObjectNode> merged = new java.util.LinkedHashMap<>();
        // 1. 磁盘台账基线(终态;非在场实体的 running/waiting-user 归一 stopped——
        //    与 AgentLedger 冷启动归一同规则,防止陈旧快照把死 agent 报成 running)
        for (Map.Entry<String, ObjectNode> e : diskLedger(ctx).entrySet()) {
            ObjectNode n = e.getValue().deepCopy();
            n.put("status", normalizeHistorical(e.getKey(), n.path("status").asString("stopped"), st, ctx));
            merged.put(e.getKey(), n);
        }
        // 2. 进程内 carry-over 覆盖(收口即记,天然终态且比 30s 磁盘快照新鲜)
        for (CarriedSub c : st.carried.values()) {
            merged.put(c.agentId, carriedJson(c));
        }
        // 3. 本轮 live 实体覆盖
        for (AgentContext s : ctx.agents().values()) {
            // 过滤:只展示 subagent 创建的 agent
            if (!"subagent".equals(s.creator())) continue;
            ObjectNode live = summaryJson(ctx, s);
            ObjectNode base = merged.get(s.agentId());
            merged.put(s.agentId(), base == null ? live : overlayLive(base, live));
        }
        java.util.List<ObjectNode> ordered = new java.util.ArrayList<>(merged.values());
        ordered.sort(java.util.Comparator.comparingLong(a -> a.path("createdAt").asLong(0)));
        ArrayNode agents = Json.arr();
        ordered.forEach(agents::add);
        return agents;
    }

    /** 历史条目状态归一:agentId 在本轮在场(实体或运行中 future)→ 原样;否则 running/waiting-user → stopped。 */
    private static String normalizeHistorical(String agentId, String status, TaskSubState st, ExecContext ctx) {
        boolean present = ctx.agents().containsKey(agentId) || st.subFutures.containsKey(agentId);
        if (present) {
            return status;
        }
        return "running".equals(status) || "waiting-user".equals(status) ? "stopped" : status;
    }

    /** carry-over 快照 → 工具契约摘要(终态已由收口写定,防御性再归一一次)。 */
    private static ObjectNode carriedJson(CarriedSub c) {
        ObjectNode n = Json.obj();
        n.put("agentId", c.agentId);
        if (c.title != null && !c.title.isEmpty()) {
            n.put("title", c.title);
        }
        n.put("createdAt", c.createdAt);
        String s = c.status;
        n.put("status", "running".equals(s) || "waiting-user".equals(s) ? "stopped" : s);
        AgentActivity act = c.activity;
        ObjectNode la = Json.obj();
        if (act != null) {
            if (act.reasoning() != null) la.put("reasoning", act.reasoning());
            if (act.content() != null) la.put("content", act.content());
            if (act.error() != null) la.put("error", act.error());
            if (act.createdAt() != null) la.put("createdAt", act.createdAt());
            if (act.updatedAt() != null) la.put("updatedAt", act.updatedAt());
        }
        n.set("latestActivity", la);
        return n;
    }

    /** live 摘要覆盖历史基线:状态/活动取 live(最新),createdAt/title 沿用基线(原始资产)。 */
    private static ObjectNode overlayLive(ObjectNode base, ObjectNode live) {
        ObjectNode n = base.deepCopy();
        if (base.path("createdAt").asLong(0) <= 0) {
            n.put("createdAt", live.path("createdAt").asLong(0));
        }
        String baseTitle = base.path("title").asString("");
        if (baseTitle.isEmpty()) {
            String liveTitle = live.path("title").asString("");
            if (!liveTitle.isEmpty()) {
                n.put("title", liveTitle);
            }
        }
        String liveStatus = live.path("status").asString("");
        if (!liveStatus.isEmpty()) {
            n.put("status", liveStatus);
        }
        n.set("latestActivity", live.path("latestActivity").deepCopy());
        return n;
    }

    /**
     * 读主体 dataDir 下的磁盘台账 agents.json(worker AgentLedger 落盘,§7.14/§7.20.1;
     * 30s 定时快照 + 收口终态快照两路径)。只保留 subagent 条目,按 agentId 索引。
     * 文件缺失/形状异常/读取失败返回空表(台账非本插件所有,读侧容错不抛)。
     */
    private Map<String, ObjectNode> diskLedger(ExecContext ctx) {
        java.nio.file.Path dir = ctx.dataDir();
        if (dir == null) {
            return Map.of();
        }
        java.nio.file.Path f = dir.resolve("agents.json");
        if (!java.nio.file.Files.isRegularFile(f)) {
            return Map.of();
        }
        try {
            String text = java.nio.file.Files.readString(f);
            tools.jackson.databind.JsonNode root = Json.parse(text);
            tools.jackson.databind.JsonNode arr = root == null ? null : root.path("agents");
            if (arr == null || !arr.isArray()) {
                return Map.of();
            }
            java.util.LinkedHashMap<String, ObjectNode> out = new java.util.LinkedHashMap<>();
            for (tools.jackson.databind.JsonNode a : arr) {
                if (!a.isObject()) continue;
                ObjectNode o = (ObjectNode) a;
                String id = o.path("agentId").asString("");
                if (id.isEmpty() || !subagentEntry(o)) continue;
                out.put(id, o);
            }
            return out;
        } catch (java.io.IOException | RuntimeException e) {
            log.debug("[sub] 磁盘台账读取失败(忽略) dir={}", dir, e);
            return Map.of();
        }
    }

    /** 台账条目是否 subagent 创建:顶级 creator,旧条目回退 metadata.creator;两者皆空视为旧格式子 agent(§7.20.1)。 */
    private static boolean subagentEntry(ObjectNode entry) {
        String c = entry.path("creator").asString("");
        if (c.isEmpty()) {
            c = entry.path("metadata").path("creator").asString("");
        }
        return c.isEmpty() || "subagent".equals(c);
    }

    /** 历史摘要查询(carry-over 优先,磁盘台账兜底;均无返回 null)——wait/stop 的跨轮入口。 */
    private ObjectNode historicalSummary(TaskSubState st, ExecContext ctx, String agentId) {
        CarriedSub c = st.carried.get(agentId);
        if (c != null) {
            return carriedJson(c);
        }
        ObjectNode disk = diskLedger(ctx).get(agentId);
        if (disk != null) {
            ObjectNode n = disk.deepCopy();
            n.put("status", normalizeHistorical(agentId, n.path("status").asString("stopped"), st, ctx));
            return n;
        }
        return null;
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
                // 跨轮历史 agent(上一轮收口的子 agent):终态即落定,直接返回最新摘要。
                // f 仍在的异常形态(实体缺失)不走历史摘要,落入下方凭空 id 分支。
                ObjectNode hist = f == null ? historicalSummary(st, ctx, agentId) : null;
                if (hist != null) {
                    r.put("waitStatus", "completed");
                    r.set("agent", hist);
                    return Json.write(r);
                }
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
                // 已落定(完成/停止/失败):直接返回最新摘要。
                // 竞态封口:实体终态先于运行体 finally(回写 assistant 轮 + carry-over 快照)置位,
                // 此时 future 尚未 done——必须等它退场再返回,否则调用方紧接的 run_agent 续跑
                // 会撞上「已在运行」守卫,或读到未回写完成的会话。有界等(终态已定,退场是瞬时的)。
                if (f != null && !f.isDone()) {
                    try {
                        f.get(timeout, TimeUnit.MILLISECONDS);
                    } catch (java.util.concurrent.ExecutionException
                            | java.util.concurrent.CancellationException
                            | java.util.concurrent.TimeoutException ignored) {
                        // 终态已由实体声明;future 异常形态不影响摘要取数
                    }
                }
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
        r.set("agents", agentsJsonMerged(ctx, st));
        return Json.write(r);
    }

    public String stop(ExecContext ctx, String agentId) {
        TaskSubState st = state(ctx);
        AgentContext sub = ctx.agents().get(agentId);
        Future<?> f = st.subFutures.get(agentId);
        if (f == null) {
            // 跨轮历史 agent:已是终态(收口时落定),无需也无法停止
            ObjectNode hist = historicalSummary(st, ctx, agentId);
            if (hist != null) {
                return "子 agent 已结束(" + hist.path("status").asString("stopped") + "),无需停止: " + agentId;
            }
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
