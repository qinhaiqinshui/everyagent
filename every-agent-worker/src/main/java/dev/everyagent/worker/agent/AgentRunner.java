package dev.everyagent.worker.agent;

import dev.everyagent.plugin.api.exception.ModelCallException;
import dev.everyagent.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * agent 运行入口（agent 层）：薄化——工具循环与轮次聚合全交 {@code ToolCallingAdvisor}，
 * 本类只负责"订阅流式 + 中断/dispose + 终态标记"，不再手写 while 循环/聚合。
 *
 * <p>解耦后：不依赖 {@code AgentClientFactory}，直接使用 {@link AgentEntity#chatClient}
 * （由 {@link AgentBuilder} 装配注入）。per-run 上下文经 prompt options 的框架
 * {@code ToolContext} 下行(见 {@link #run},不使用 ThreadLocal)。
 *
 * <p>纪律(AGENTS.md §13):agent 执行必须走 ChatClient + Advisor 生态,禁止手搓工具循环。
 */
@Component
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    /**
     * 无活动看门狗共享调度器(单守护线程):检查本身只读两个 volatile 长整并回写时间戳,
     * 极轻量,全 worker 并发任务共用一条即可;任务粒度看门狗经 scheduleWithFixedDelay
     * 挂载、run() 收口时 cancel,不泄漏。
     */
    private static final ScheduledExecutorService INACTIVITY_WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "agent-inactivity-watchdog");
                t.setDaemon(true);
                return t;
            });

    /** 看门狗检查周期(ms)。 */
    private static final long WATCHDOG_INTERVAL_MS = 30_000;

    /** 诊断重复间隔(ms):同一任务无活动期间每 5 分钟重打一条,避免刷屏又保持续场提示可见。 */
    private static final long DIAG_REPEAT_MS = 300_000;

    private final WorkerProperties props;

    public AgentRunner(WorkerProperties props) {
        this.props = props;
    }

    /**
     * 跑完一个 agent(阻塞至最终回答)。会话内存由调用方预置(system + 历史 + 首条 user)。
     * 工具循环由 ChatClient 的 advisor 链递归完成;本方法订阅流式响应,支持线程中断取消。
     *
     * <p><b>无活动诊断</b>(worker.agent-inactivity-timeout-ms,默认 15 分钟,<b>纯诊断不打断</b>):
     * 整条 advisor 递归链(模型流 → 轮聚合 → 工具批 → 下一轮)任一环把续体弄丢时,表现为
     * 「零事件、零线程为其工作、任务永久 running」的静默挂起(2026-11 t_145g:第 19 轮
     * message+usage 落盘后工具批永不调度)。模型流段已有 FirstChunkWatchdogAdvisor 罩住,
     * 其余段位暂无根因结论——故只打 WARN 诊断日志(实体状态 + jcmd 现场抓取指引),
     * 故意<b>不打断</b>:续体丢失类缺陷的现场一旦被兜底销毁,根因即无从查起。
     * waiting-user 态(挂 ask 等用户)豁免顺延计时(ask-timeout-ms 自行兜底)。
     */
    public void run(AgentEntity a) throws InterruptedException {
        // 2.0.1:prompt options 原样透传并强转 OpenAiChatOptions(且不与默认 options 合并),
            OpenAiChatOptions.Builder options = a.options.mutate();
            if (!a.tools.isEmpty()) {
                options.toolCallbacks(a.tools.stream()
                        .map(SchemaStrippedToolCallback::new)
                        .toArray(ToolCallback[]::new));
            }
            // per-run 四件套经框架 ToolContext map 下行(架构 §7.14.3):共享单例
            // PerCallToolCallingManager 的切口由此取出 agentId(链节点 MAIN/SUB_AGENT 归属判定,
            // file-change 一阶段记账消费)/agent 级 emitter(agentId 包装,链上事件自动带归属
            // agentId)/ExecContext/ToolOutcomeLog 构造 per-call 链 ctx;框架 buildToolContext
            // 原样透传,工具回调只按自己的键取值不受影响。
            options.toolContext(PerCallToolCallingManager.RUN_CONTEXT_KEY,
                    new PerCallToolCallingManager.RunContext(a.agentId, a.emitter, a.execution, a.toolOutcomes));
            Prompt prompt = new Prompt(new ArrayList<>(a.conversation), options.build());

            CountDownLatch done = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<Throwable> error =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Disposable[] holder = new Disposable[1];
            a.touchActivity(); // 活动起点(订阅到首个事件之间的空档由看门狗预算覆盖)
            final long inactivityMs = props.getAgentInactivityTimeoutMs();
            // 检查周期随阈值自适应(1s~30s):大阈值省调度,小阈值(测试/紧配置)保采样密度,
            // 保证触发延迟至多约为阈值的 1/3 + 1s。
            final long intervalMs = Math.min(WATCHDOG_INTERVAL_MS, Math.max(1_000, inactivityMs / 3));
            // 诊断去重:调度器单线程,无并发,直接时间戳判重(首次 + 每 5 分钟重打一条)
            final long[] lastDiagMs = {0L};
            final ScheduledFuture<?> watch = inactivityMs > 0
                    ? INACTIVITY_WATCHDOG.scheduleWithFixedDelay(() -> {
                        try {
                            if (done.getCount() == 0) {
                                return; // 已收口,等 cancel 兜底
                            }
                            if (a.isWaitingUser()) {
                                a.touchActivity(); // 挂 ask 等用户:豁免并顺延计时
                                return;
                            }
                            long idleMs = System.currentTimeMillis() - a.lastActivityMs();
                            long sinceDiagMs = lastDiagMs[0] == 0 ? Long.MAX_VALUE
                                    : System.currentTimeMillis() - lastDiagMs[0];
                            if (idleMs < inactivityMs || sinceDiagMs < DIAG_REPEAT_MS) {
                                return;
                            }
                            lastDiagMs[0] = System.currentTimeMillis();
                            // 纯诊断,不打断:续体丢失类缺陷的现场一旦被兜底销毁,根因即无从查起
                            //(2026-11 t_145g:第 19 轮 message+usage 落盘后工具批永不调度,
                            // 全进程零线程为其工作——保留挂起现场,等线程转储定位丢在哪一环)。
                            Disposable d = holder[0];
                            log.warn("[diag] 任务 {} agent {} 已 {}s 无任何事件(阈值 {}s):"
                                    + "响应式链疑似续体丢失(工具批未调度/流未推进)。现场快照:"
                                    + "runState={} status={} finished={} subscription={}({}) latchOpen={}"
                                    + " —— boundedElastic 池状态(queued>0 且 IDLE = 任务已入队但从未被执行,唤醒丢失铁证):{}"
                                    + " —— 请立即抓取虚拟线程转储辅助定位: "
                                    + "jcmd <pid> Thread.dump_to_file -format=json /tmp/vt.json "
                                    + "(本诊断仅打点不打断,任务保持原状)",
                                    a.execution().subjectId(), a.agentId(),
                                    idleMs / 1000, inactivityMs / 1000,
                                    a.runState(), a.status(), a.finished,
                                    d == null ? "null" : "present", d != null && d.isDisposed(),
                                    done.getCount() > 0,
                                    boundedElasticPoolDump());
                        }
                        catch (RuntimeException e) {
                            // 周期任务抛异常会被调度器静默取消,必须吞掉保住后续检查
                            log.debug("[diag] 检查异常(忽略继续)", e);
                        }
                    }, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
                    : null;
            log.debug("[run] 订阅模型流 agentId={} vtThread={}",
                    a.agentId, Thread.currentThread().getName());
            try {
                holder[0] = a.chatClient.prompt(prompt).stream().chatClientResponse()
                    .doOnSubscribe(s -> log.info("[flux] onSubscribe agentId={} subject={} thread={}",
                            a.agentId, a.execution().subjectId(), Thread.currentThread().getName()))
                    .doOnComplete(() -> log.info("[flux] onComplete agentId={} subject={} thread={}",
                            a.agentId, a.execution().subjectId(), Thread.currentThread().getName()))
                    .doOnError(e -> log.warn("[flux] onError agentId={} subject={} thread={} err={}",
                            a.agentId, a.execution().subjectId(), Thread.currentThread().getName(), e.toString()))
                    .doOnCancel(() -> log.warn("[flux] onCancel agentId={} subject={} thread={}",
                            a.agentId, a.execution().subjectId(), Thread.currentThread().getName()))
                    .doFinally(sig -> log.info("[flux] doFinally agentId={} subject={} signal={} thread={}",
                            a.agentId, a.execution().subjectId(), sig, Thread.currentThread().getName()))
                    .subscribe(
                            r -> {
                                // 响应经 advisor 链内部处理(工具循环递归);事件已在 WorkerToolEventAdvisor 发。
                            },
                            e -> {
                                error.compareAndSet(null, e);
                                done.countDown();
                                log.warn("[flux] subscriber onError→latch agentId={} err={}", a.agentId, e.toString());
                            },
                            () -> {
                                done.countDown();
                                log.info("[flux] subscriber onComplete→latch agentId={}", a.agentId);
                            });

                while (!done.await(100, TimeUnit.MILLISECONDS)) {
                    if (Thread.currentThread().isInterrupted()) {
                        log.debug("[cancel] 轮询检测到中断标记 agentId={} holderNull={} thread={}",
                                a.agentId, holder[0] == null, Thread.currentThread().getName());
                        throw new InterruptedException("流式输出被取消");
                    }
                }
            } catch (InterruptedException awaitEx) {
                if (holder[0] != null) {
                    holder[0].dispose();
                    log.debug("[cancel] 已 dispose reactive 订阅链 agentId={} thread={}",
                            a.agentId, Thread.currentThread().getName());
                }
                throw awaitEx;
            } finally {
                if (watch != null) {
                    watch.cancel(false);
                }
            }
            log.debug("[run] 模型流自然结束 agentId={} thread={}", a.agentId, Thread.currentThread().getName());
            Throwable t = error.get();
            if (t != null) {
                if (t instanceof InterruptedException ie) {
                    throw ie;
                }
                if (t instanceof LoopRepeatException lre) {
                    throw lre;
                }
                Throwable cause = t.getCause();
                if (cause instanceof InterruptedException ie) {
                    throw ie;
                }
                if (cause instanceof LoopRepeatException lre) {
                    throw lre;
                }
                ContextOverflow.logIfOverflow(a, t);
                throw new ModelCallException("模型调用失败: " + t.getMessage(), t);
            }
            a.finished = true;
    }

    /**
     * boundedElastic 池状态转储(诊断专用,反射读 Scannable.inners):每个 BoundedState 一行,
     * 含 backing(被 pick 次数)/idleSince/executor 状态(BoundedScheduledExecutorService.toString
     * 自带 ACTIVE|IDLE + queued=Q/completed=C)。<b>queued&gt;0 且 IDLE = 任务已入队但单工作线程
     * 未取走</b>(唤醒丢失/队列损坏的直接铁证);backing 恒正且长期不归零 = worker 未 dispose
     * (嵌套续体泄漏形态,2 核机 20 上限打满后靠 round-robin 复用)。反射失败返回占位串不抛错。
     */
    private static String boundedElasticPoolDump() {
        try {
            Scheduler sched = Schedulers.boundedElastic();
            Object real = sched;
            // Schedulers.boundedElastic() 返回 CachedScheduler 包装,字段 cached 才是本体
            for (java.lang.reflect.Field f : sched.getClass().getDeclaredFields()) {
                if ("cached".equals(f.getName())) {
                    f.setAccessible(true);
                    real = f.get(sched);
                    break;
                }
            }
            java.lang.reflect.Method inners = real.getClass().getMethod("inners");
            inners.setAccessible(true);
            List<Object> states = new ArrayList<>();
            ((Stream<?>) inners.invoke(real)).filter(Objects::nonNull).forEach(states::add);
            StringBuilder sb = new StringBuilder("\n");
            for (Object s : states) {
                sb.append("    ").append(s).append('\n');
            }
            return sb + "    (states=" + states.size() + ")";
        }
        catch (Throwable t) {
            return "(池转储失败: " + t + ")";
        }
    }
}
