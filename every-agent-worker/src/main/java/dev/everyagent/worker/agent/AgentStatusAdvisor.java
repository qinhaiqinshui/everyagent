package dev.everyagent.worker.agent;

import dev.everyagent.plugin.api.util.RootCause;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

/**
 * agent 生命周期 advisor（架构 §7.20.1；红线：一个 advisor 只负责一个功能）。
 *
 * <p>唯一职责 = 把 **ChatClient 流的生命周期信号**翻译成 per-run 的 agent 生命周期事件：
 * <table border="1">
 *   <caption>信号 → 状态机动作</caption>
 *   <tr><th>信号</th><th>动作（全部经 {@link AgentEntity} 的 CAS 状态机发射）</th></tr>
 *   <tr><td>{@code adviseStream} 入口</td><td>{@link AgentEntity#beginRun()} —
 *       {@code agent.started} + {@code agent.status{running}}</td></tr>
 *   <tr><td>{@code doOnComplete}</td><td>{@code claimTerminal(completed)} —
 *       {@code agent.done} + {@code agent.status{done}}</td></tr>
 *   <tr><td>{@code doOnError}</td><td>{@code claimTerminal(error, 根因)} —
 *       {@code error} + {@code agent.done} + {@code agent.status{failed}}</td></tr>
 *   <tr><td>{@code doOnCancel}</td><td>{@code claimTerminal(stopped, "已取消")} —
 *       {@code agent.done} + {@code agent.status{stopped}}</td></tr>
 * </table>
 *
 * <p><b>为什么实现 {@link StreamAdvisor} 而不继承 {@code ToolCallingAdvisor}</b>：本 advisor
 * 不需要挂钩工具循环，只需要「整轮一次」的流生命周期信号。而 {@code ToolCallingAdvisor} 的
 * 递归（{@code internalStream → chain.copy(this).nextStream}）只重新调用**比自己更内层**的
 * advisor——所以挂在其外侧的本 advisor，{@code adviseStream} 每次 {@code AgentRunner.run()}
 * 恰好进入一次，{@code doOnComplete} 即整轮收口；若同样继承 ToolCallingAdvisor 嵌成两个工具
 * 循环，反而要担双循环/双聚合的风险（且 order 一旦落到工具循环内侧就会变成每轮触发一次）。
 * 与 {@link dev.everyagent.worker.task.RoundIndexAdvisor} 同范式（per-run 薄壳 + doOnComplete）。
 *
 * <p><b>order = HIGHEST_PRECEDENCE + 5</b>（全链最外层，RoundIndexAdvisor +10 之外）：
 * 完成信号自内向外传播，于是发射次序天然为
 * {@code message}/{@code usage}（WorkerToolEventAdvisor，+300）→
 * rounds 闭合落盘（RoundIndexAdvisor，+10）→ {@code agent.done}/{@code agent.status{done}}（本类）。
 * 前端正是以主 agent 的 {@code agent.status} 终态为「拉 rounds 快照」的触发点，
 * 故 done 必须晚于 rounds 落盘——最外层保证这一点。
 *
 * <p><b>幂等与泄漏</b>：终态唯一性由 {@link AgentEntity#claimTerminal} 的 per-run CAS 保证，
 * 三个终态回调互斥且与插件侧兜底（运行体从未启动时的 {@code claimTerminal}）互斥；
 * 发射不再用 {@code execution().terminal()} 做 leak-guard——任务终态标记早于流的
 * cancel/error 信号到达，拦掉就会丢掉本轮终态事件（主 agent 被取消时正是这个场景），
 * 而 per-run CAS 已足以保证「一轮之内至多一个终态」。
 */
public class AgentStatusAdvisor implements StreamAdvisor {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AgentStatusAdvisor.class);

    private final AgentEntity a;

    public AgentStatusAdvisor(AgentEntity a) {
        this.a = a;
    }

    @Override
    public String getName() {
        return "Agent Status Advisor";
    }

    @Override
    public int getOrder() {
        // 整条 advisor 链最外层：出生事件早于任何内层 advisor 的轮次事件，
        // 终态事件晚于 RoundIndexAdvisor 的 rounds 闭合落盘。
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
            StreamAdvisorChain streamAdvisorChain) {
        a.beginRun();
        return streamAdvisorChain.nextStream(chatClientRequest)
                .doOnComplete(this::onComplete)
                .doOnError(this::onError)
                .doOnCancel(this::onCancel);
    }

    private void onComplete() {
        if (a.claimTerminal(AgentEntity.MACRO_COMPLETED, null)) {
            log.debug("[agent-status] 本轮正常收口 agentId={} thread={}",
                    a.agentId, Thread.currentThread().getName());
        }
    }

    private void onError(Throwable e) {
        // 根因摘要：BaseAdvisor 包装会把真实错误埋在最里层（见 RootCause）。
        String msg = RootCause.summary(e);
        if (a.claimTerminal(AgentEntity.MACRO_ERROR, msg)) {
            log.debug("[agent-status] 本轮失败收口 agentId={} err={}", a.agentId, msg);
        }
    }

    private void onCancel() {
        if (a.claimTerminal(AgentEntity.MACRO_STOPPED, "已取消")) {
            log.debug("[agent-status] 本轮取消收口 agentId={} thread={}",
                    a.agentId, Thread.currentThread().getName());
        }
    }
}
