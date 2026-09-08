package dev.everyagent.worker.task;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * 本轮用户任务耗时 advisor(架构 §5.2 + 红线:一个 advisor 只负责一个功能)。
 *
 * <p>仿 node 侧 {@code measureDuration} 节点:在 {@link #before} 打点,包裹整个
 * {@code agent.execute}(含 {@link WorkerToolEventAdvisor} 驱动的递归工具循环),
 * 收口时把本轮耗时经 {@link RoundIndexStore#recordDuration} 回填进 rounds.jsonl
 * 的最后一轮(不再发 {@code task.trace} 的 {@code task_duration} 事件)。
 *
 * <p>与 node 侧口径一致:仅主 agent 生效——子 agent 不挂本 advisor(见
 * {@code AgentClientFactory.forMain}),避免嵌套 agent 重复计时与噪声;计时只反映主 agent
 * 入口到其自身收口(子 agent 在其内部递归,已并入主线程耗时),与 node 侧「本轮用户任务」语义对齐。
 *
 * <p>放置顺序:作为最外层 advisor({@link Ordered#HIGHEST_PRECEDENCE}),早于
 * {@link SkillAdvisor} 与 {@link WorkerToolEventAdvisor},保证它包裹的是整条链(含工具循环),
 * 测到的即「本轮从开始运行到收口完成」的真实耗时。不参与 prompt / 工具循环逻辑,纯计时副作用。
 *
 * <p><b>回填时机(重要)</b>:流式({@link #adviseStream})是 worker 唯一路径——本类覆盖
 * 默认 {@code BaseAdvisor.adviseStream},在整条流 {@code doOnComplete} 时回填耗时。
 * 原因:Spring AI 2.0.x 的 {@code ToolCallingAdvisor} 把「每轮聚合 + 工具递归」放在
 * 首个响应流 <b>after 之外</b>的 {@code concatWith(handleToolCallRecursion)} 里,终片 chunk
 * 先于其 {@code doAfterStream→message()} 事件流出;若用默认 after,耗时会插到最后一轮权威
 * {@code message} 之前。选 {@code doOnComplete} 而非 {@code doFinally}:Reactor 的
 * {@code doFinally} 在<b>下游 onComplete 之后</b>执行,会与 {@code TaskManager.finish()}
 * 竞态;{@code doOnComplete} 在向下游转发 onComplete <b>之前</b>执行,顺序与线程都安全。
 * <b>回填顺序(关键)</b>:Reactor 的 {@code doOnComplete} 由最内层先触发——内层
 * {@link RoundIndexAdvisor}(order=HIGHEST_PRECEDENCE+10)先增量落盘 rounds.jsonl,
 * 本 advisor(最外层)随后把耗时回填进最后一条已闭合轮。取消/异常不触发 onComplete,
 * 故不回填耗时(startedAt 随 per-run 实例丢弃)。
 * 非流式 {@code call} 走默认 {@code adviseCall}:nextCall 阻塞到整条链(含工具循环)收口,
 * 末尾 message 已落盘,故 {@link #after} 时序天然正确(该路径不经 runTask 循环),保留直接回填。
 *
 * <p>设计纪律:本类与 {@link WorkerToolEventAdvisor} 同构——per-run 物化(持 {@link AgentEntity}
 * 引用,只读其不可变字段、仅经由 {@link RoundIndexStore} 落盘),多任务并发复用同一
 * {@link org.springframework.ai.model.tool.ToolCallingManager} 单例时线程安全。
 */
public class MeasureDurationAdvisor implements BaseAdvisor {

    /** 计时目标 agent(仅主 agent 会注入本 advisor)。 */
    private final AgentEntity a;
    /** 轮次索引落盘组件(耗时回填 rounds.jsonl 用)。 */
    private final TaskStore store;
    private final RoundIndexStore rounds;

    /** before 打点时间戳(仅本 advisor 实例持有,per-run 物化,线程内单测安全)。 */
    private long startedAt = 0;

    public MeasureDurationAdvisor(AgentEntity a, TaskStore store, RoundIndexStore rounds) {
        this.a = a;
        this.store = store;
        this.rounds = rounds;
    }

    @Override
    public String getName() {
        return "Measure Duration Advisor";
    }

    @Override
    public int getOrder() {
        // 最外圈:早于 SkillAdvisor(HIGHEST_PRECEDENCE + 100)与事件 advisor,
        // 确保包裹整条链(含工具循环)测真实端到端耗时。
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        startedAt = System.currentTimeMillis();
        return chatClientRequest;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        // 仅非流式 call 路径走到这里:BaseAdvisor.adviseCall 在 nextCall(阻塞整条链,含工具循环)
        // 返回后调用,末尾 message/usage 已落盘,耗时 trace 必然排在它们之后。
        if (startedAt == 0) {
            return chatClientResponse;
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        startedAt = 0; // 防重入/重复计时
        // 回填进 rounds.jsonl 最后一轮(不再发 task_duration trace)。
        rounds.recordDuration(store, a.task.taskId, elapsed);
        return chatClientResponse;
    }

    /**
     * 流式(worker 唯一路径)计时:覆盖默认 BaseAdvisor.adviseStream,绕开「finish-reason
     * 终片 chunk 触发 after」的过早打点(见类注释),改为整条流 {@code doOnComplete} 回填——
     * 它发生在 ToolCallingAdvisor 递归循环(含每轮 message / 最终 message / usage)全部完成后,
     * 且在下游订阅方({@code AgentRunner} 的 done::countDown)收到 onComplete 之前执行:
     * 既不与任务收口(finish/flush)竞态,又保证耗时回填到刚落盘的那一轮。
     * 顺序:内层 {@link RoundIndexAdvisor} 的 doOnComplete 先触发(先落盘 rounds.jsonl),
     * 本 advisor(最外层)的 doOnComplete 后触发(后回填 durationMs)。
     * 取消/异常不触发 onComplete,故不回填耗时(startedAt 随 per-run 实例丢弃)。
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
            StreamAdvisorChain streamAdvisorChain) {
        startedAt = System.currentTimeMillis();
        return streamAdvisorChain.nextStream(chatClientRequest)
                .doOnComplete(() -> {
                    if (startedAt == 0) {
                        return;
                    }
                    long elapsed = System.currentTimeMillis() - startedAt;
                    startedAt = 0; // 防重入/重复计时
                    // 回填进 rounds.jsonl 最后一轮(不再发 task_duration trace)。
                    // 顺序由 doOnComplete 嵌套保证:内层 RoundIndexAdvisor 已先增量落盘该轮,
                    // 本 doOnComplete 在外层后触发 → 耗时恰回填到刚落盘的那一轮。
                    rounds.recordDuration(store, a.task.taskId, elapsed);
                });
    }
}
