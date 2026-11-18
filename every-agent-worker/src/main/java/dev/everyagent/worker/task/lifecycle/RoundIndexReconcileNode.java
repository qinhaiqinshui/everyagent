package dev.everyagent.worker.task.lifecycle;

import dev.everyagent.plugin.api.task.TaskLifecycleContext;
import dev.everyagent.worker.task.RoundIndex;
import dev.everyagent.worker.task.RoundIndexStore;

import java.util.List;

/**
 * 终态轮次对账节点(order=700,临界段内纯上行):任务收口时把「事件上已闭合、索引仍 open」的
 * 轮补闭合,并推 {@code round.closed}(瞬态)。
 *
 * <p>为什么需要:闭合行的落盘挂在模型流 doOnComplete 上,取消/异常不触发,时序上也可能漏扫;
 * 漏做的结果会一直留在磁盘——`task.rounds` 把一轮本已给出最终回复的轮当成「未闭合」,前端按
 * 终态未闭合尾轮渲染(常开视图、无折叠条、无耗时),而实际它早已结束。任务终态是天然的最终
 * 一致校准点:此处以事件扫描为权威补一次闭合(幂等、异常自吞)。
 *
 * <p>位置约束:上行执行序 = order 降序,故 order=700 落在 log.flush(750)之后、
 * status.persist(650)/persistence.untrack(500)之前——事件已 flush(对账窗口的磁盘侧完整),
 * 推流通道仍在线(round.closed 能到前端)。仍真未闭合的尾轮(中断/失败/取消,无最终回复)不动:
 * 它就该以未闭合形态呈现(常开视图),语义由前端按「终态未闭合尾轮」渲染。
 */
public final class RoundIndexReconcileNode extends UpstreamNode {

    private final RoundIndexStore roundIndexStore;

    public RoundIndexReconcileNode(RoundIndexStore roundIndexStore) {
        this.roundIndexStore = roundIndexStore;
    }

    @Override
    public String id() { return "rounds.reconcile"; }

    @Override
    public float order() { return 700; }

    @Override
    protected Object up(TaskLifecycleContext ctx, Object result) {
        var t = ((TaskLifecycleContextImpl) ctx).taskEntryImpl();
        List<RoundIndex.Round> closed = roundIndexStore.persistClosedRounds(
                t.dataDir(), t.log(), t.taskId, t.mainAgentId);
        roundIndexStore.publishClosed(t.taskId, t.events, closed);
        return result;
    }
}