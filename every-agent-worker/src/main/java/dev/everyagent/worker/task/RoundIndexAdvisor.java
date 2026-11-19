package dev.everyagent.worker.task;

import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.worker.agent.AgentEntity;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;

/**
 * 轮次索引 advisor(架构 §5.2 + 红线:一个 advisor 只负责一个功能)。
 *
 * <p>职责:每次 {@code runner.run}(= 一条用户输入的完整模型流,含工具循环递归)
 * 正常完成时,把本轮「已闭合轮」增量补写进任务目录的 rounds.jsonl(每行一轮:
 * startSeq/endSeq/用户输入/AI 最终回复/子 agent 区间,见 {@link RoundIndex}),
 * 并顺带把本次新闭合的轮推 round.closed(瞬态,只走 stream 频道不落盘)。
 * 实际扫描与落盘逻辑全部在 {@link RoundIndexStore#persistClosedRounds}(幂等、
 * 异常自吞,返回新闭合轮列表),推送在 {@link RoundIndexStore#publishClosed};
 * 本类只是 doOnComplete 薄壳,不改动任何流语义。
 *
 * <p>时机:本 advisor 位于整条主 agent advisor 链的最外层(order = HIGHEST_PRECEDENCE + 10),
 * 其 {@code doOnComplete} 在内层整条流(含 ToolCallingAdvisor 递归工具循环、每轮权威 message
 * 事件)全部完成之后、向下游转发 onComplete 之前触发——此时本轮最终回复的 message 事件已在
 * 内存 EventLog 中,扫描窗口即可闭合该轮。取消/异常不触发 doOnComplete;未闭合尾轮的
 * endSeq="" 行已在开轮路径({@code consumeInput → openRoundAtStart})持久化。
 *
 * <p>本 advisor 的闭合是「尽力而为的路径之一」而非唯一保证:漏掉(取消/异常/时序)时由开轮前
 * 对账(下次用户输入)与任务终态收口对账补齐——两者都以事件扫描为权威、幂等。
 * rounds.jsonl 缺失时另由 {@code task.rounds} 首次惰性全量生成兜底。
 *
 * <p>设计纪律:per-run 物化(每 run 新建实例,状态随实例隔离),多任务并发安全;
 * 落盘失败被 {@link RoundIndexStore} 吞掉,绝不阻断流的 doOnComplete 向 AgentRunner 传播。
 * 所有 agent 均挂载本 advisor。
 */
public class RoundIndexAdvisor implements StreamAdvisor {

    private final AgentEntity a;
    private final RoundIndexStore rounds;
    private final Path dataDir;

    public RoundIndexAdvisor(AgentEntity a, RoundIndexStore rounds, Path dataDir) {
        this.a = a;
        this.rounds = rounds;
        this.dataDir = dataDir;
    }

    @Override
    public String getName() {
        return "Round Index Advisor";
    }

    @Override
    public int getOrder() {
        // 主链最外层、system-info advisor(+50)外层:
        // doOnComplete 晚于全部内层 advisor(最终回复 message 事件已入日志)即可。
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
            StreamAdvisorChain streamAdvisorChain) {
        return streamAdvisorChain.nextStream(chatClientRequest)
                .doOnComplete(this::persistRounds);
    }

    /**
     * 一轮用户任务流完成:增量补写已闭合轮,并对本次新闭合的轮推 round.closed。
     * 耗时由 {@link RoundIndexStore} 取「最终回复事件时间 − 磁盘 startedAt」计算并随闭合行内联
     * (保证推送时耗时已在磁盘,消除「前端收到通知即拉快照、却拉在耗时写入之前」的竞态,§7.15.1)。
     * 异常自吞,不阻断 onComplete。
     */
    private void persistRounds() {
        TaskRuntime t = (TaskRuntime) a.execution();
        List<RoundIndex.Round> closed =
                rounds.persistClosedRounds(dataDir, t.log(), t.taskId(), t.mainAgentId());
        // round.closed 与 rounds.jsonl 闭合行同源;瞬态不落盘,仅推 stream 频道。
        rounds.publishClosed(t.taskId(), t.events(), closed);
    }
}