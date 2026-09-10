package dev.everyagent.worker.task;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * 轮次索引 advisor(架构 §5.2 + 红线:一个 advisor 只负责一个功能;仅主 agent 挂载)。
 *
 * <p>职责:每次 {@code runner.run}(= 一条用户输入的完整模型流,含工具循环递归)
 * 正常完成时,把本轮「已闭合轮」增量补写进任务目录的 rounds.jsonl(每行一轮:
 * startSeq/endSeq/用户输入/AI 最终回复/子 agent 区间,见 {@link RoundIndex}),
 * 并顺带把本次新闭合的轮推 round.closed(瞬态,只走 stream 频道不落盘)。
 * 实际扫描与落盘逻辑全部在 {@link RoundIndexStore#persistClosedRounds}(幂等、
 * 异常自吞,返回新闭合轮列表);本类只是 doOnComplete 薄壳,不改动任何流语义。
 *
 * <p>时机(与 {@link MeasureDurationAdvisor} 同款 doOnComplete 推理):本 advisor 位于
 * {@code MeasureDurationAdvisor}(最外层,HIGHEST_PRECEDENCE)的内层、其余 advisor 的
 * 外层(order = HIGHEST_PRECEDENCE + 10),其 {@code doOnComplete} 在内层整条流(含
 * ToolCallingAdvisor 递归工具循环、每轮权威 message 事件)全部完成之后、向下游转发
 * onComplete 之前触发——此时本轮最终回复的 message 事件已在内存 EventLog 中,
 * 扫描窗口即可闭合该轮。取消/异常不触发 doOnComplete;未闭合尾轮的 endSeq="" 行已在
 * 开轮路径({@code consumeInput → openRoundAtStart})持久化,终态不做补写/对账;
 * rounds.jsonl 缺失时另由 {@code task.rounds} 首次惰性全量生成兜底。
 *
 * <p>设计纪律:per-run 物化(每 run 新建实例,状态随实例隔离),多任务并发安全;
 * 落盘失败被 {@link RoundIndexStore} 吞掉,绝不阻断流的 doOnComplete 向 AgentRunner 传播。
 * 子 agent 不挂本 advisor({@code AgentClientFactory.forSub});防御性再校验 kind。
 */
public class RoundIndexAdvisor implements StreamAdvisor {

    private final AgentEntity a;
    private final TaskStore store;
    private final RoundIndexStore rounds;

    public RoundIndexAdvisor(AgentEntity a, TaskStore store, RoundIndexStore rounds) {
        this.a = a;
        this.store = store;
        this.rounds = rounds;
    }

    @Override
    public String getName() {
        return "Round Index Advisor";
    }

    @Override
    public int getOrder() {
        // MeasureDurationAdvisor(HIGHEST_PRECEDENCE)内层、SystemInfoAdvisor(+50)外层:
        // doOnComplete 晚于全部内层 advisor(最终回复 message 事件已入日志)即可。
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
            StreamAdvisorChain streamAdvisorChain) {
        return streamAdvisorChain.nextStream(chatClientRequest)
                .doOnComplete(this::persistRounds);
    }

    /** 一轮用户任务流完成:增量补写已闭合轮(耗时随行内联),并对本次新闭合的轮推 round.closed(异常自吞,不阻断 onComplete)。 */
    private void persistRounds() {
        if (a.kind != AgentEntity.Kind.MAIN) {
            return; // 防御:仅主 agent(工厂只给主链挂载)
        }
        // 取出本轮文件变更槽并清空(FileChangeAdvisor 收口填充;无变更时两槽均为 null)
        JsonNode light = a.task.fileChangesLight;
        JsonNode full = a.task.fileChangesFull;
        a.task.fileChangesLight = null;
        a.task.fileChangesFull = null;
        // 本轮端到端耗时:MeasureDurationAdvisor 组装时打点(同 run 实例,此刻必已写入);
        // 随闭合行同一次落盘内联,保证下方 round.closed 推送时耗时已在磁盘(消除「前端收到
        // 通知即拉快照、却拉在耗时回填之前」的竞态,见 §7.15.1)。
        long startedAt = a.task.roundDurationStart;
        long elapsed = startedAt > 0 ? System.currentTimeMillis() - startedAt : 0L;
        List<RoundIndex.Round> closed =
                rounds.persistClosedRounds(store, a.task.log, a.task.taskId, a.task.mainAgentId,
                        light, full, elapsed);
        for (RoundIndex.Round r : closed) {
            if (r.endSeq() != null) {
                // round.closed 与 rounds.jsonl 闭合行同源;瞬态不落盘,仅推 stream 频道。
                a.task.events.roundClosed(r.startSeq(), r.endSeq(), r.finalReply());
            }
        }
    }
}
