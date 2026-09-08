package dev.everyagent.worker.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

/**
 * 挂起提问登记表(架构 §5.6 / G4):
 * ask 阻塞在虚拟线程上等 future;挂起期间每 30s 重发 ask.state;
 * 超时/回答/取消均走 ask.resolved;同 taskId 全部 ask 结束 → 任务回到 running。
 */
@Component
public class PendingAsks {

    public record AskAnswer(String status, String text) {
    }

    /**
     * 单个选择题(ask_user 多问题形态)。id 由 worker 在工具侧分配(与 askId 关联),
     * 前端据此把答案回传配对;options 为选项文案,「其他」由前端渲染时自动追加,不入此列表。
     */
    public record AskQuestion(String id, String prompt, java.util.List<String> options) {
    }

    /** 任务状态联动:有挂起 → waiting-user;全部解决 → running。 */
    public interface StatusHook {
        void askPendingChanged(String taskId, boolean nowPending);
    }

    private static final Logger log = LoggerFactory.getLogger(PendingAsks.class);

    private static final class Ask {
        final String askId;
        final String taskId;
        final String agentId;
        final String kind;
        final java.util.List<AskQuestion> questions;
        final TaskEvents events;
        final CompletableFuture<AskAnswer> future = new CompletableFuture<>();
        volatile ScheduledFuture<?> refresher;
        volatile ScheduledFuture<?> timeout;

        Ask(String askId, String taskId, String agentId, String kind,
                java.util.List<AskQuestion> questions, TaskEvents events) {
            this.askId = askId;
            this.taskId = taskId;
            this.agentId = agentId;
            this.kind = kind;
            this.questions = questions;
            this.events = events;
        }
    }

    private final Map<String, Ask> asks = new ConcurrentHashMap<>();
    private final Map<String, Integer> pendingByTask = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ask-refresher");
                t.setDaemon(true);
                return t;
            });
    private volatile StatusHook hook;

    public void setHook(StatusHook hook) {
        this.hook = hook;
    }

    /** 阻塞等待用户回答(运行在 agent 的工具调用线程=任务虚拟线程上,挂起廉价)。 */
    public AskAnswer ask(TaskEvents events, String taskId, String agentId, String kind,
            java.util.List<AskQuestion> questions, long timeoutMs)
            throws InterruptedException {
        String askId = dev.everyagent.worker.proto.ShortIds.askId();
        // 题目 id 统一由真实 askId 派生(askId_i):调用方无法预知 askId,传入的 id 仅占位,
        // 前端据此与 ask.create 载荷稳定配对回传。
        java.util.List<AskQuestion> withIds = new java.util.ArrayList<>(questions.size());
        for (int i = 0; i < questions.size(); i++) {
            AskQuestion q = questions.get(i);
            withIds.add(new AskQuestion(askId + "_" + i, q.prompt(), q.options()));
        }
        Ask ask = new Ask(askId, taskId, agentId, kind, withIds, events);
        asks.put(askId, ask);
        events.askCreate(askId, kind, withIds, timeoutMs, agentId);
        events.agentStatus(agentId, "waiting-user"); // 该 agent 挂起等待用户(主/子统一)
        pendingChanged(taskId, +1);
        ask.refresher = scheduler.scheduleAtFixedRate(() -> {
            try {
                events.askState(askId, kind, "pending", withIds, agentId);
            } catch (RuntimeException e) {
                log.warn("ask.state 刷新失败", e);
            }
        }, 30, 30, TimeUnit.SECONDS);
        ask.timeout = scheduler.schedule(() -> {
            if (ask.future.complete(new AskAnswer("timeout", null))) {
                events.askResolved(askId, "timeout", "timeout", agentId);
                events.agentStatus(agentId, "running"); // 超时恢复执行(工具返回模型继续)
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        try {
            return ask.future.get(); // 可中断:任务取消时被打断
        } catch (java.util.concurrent.ExecutionException e) {
            // future 只会 complete 正常值,此分支不可达
            throw new IllegalStateException(e);
        } finally {
            ask.refresher.cancel(false);
            ask.timeout.cancel(false);
            asks.remove(askId);
            pendingChanged(taskId, -1);
        }
    }

    /** 前端 ask.reply → resolve;首个结果生效,幂等。 */
    public boolean resolve(String askId, String answer, String by) {
        Ask a = asks.get(askId);
        if (a == null) {
            return false;
        }
        boolean first = a.future.complete(new AskAnswer("answered", answer));
        if (first) {
            a.events.askResolved(askId, by, "answered", a.agentId);
            a.events.agentStatus(a.agentId, "running"); // 回答到达,该 agent 恢复执行
        }
        return first;
    }

    /** 任务取消:全部挂起 ask 立即作废(工具线程随即被中断)。 */
    public void cancelTask(String taskId, String by) {
        for (Ask a : asks.values()) {
            if (a.taskId.equals(taskId) && a.future.complete(new AskAnswer("cancelled", null))) {
                a.events.askResolved(a.askId, by, "cancelled", a.agentId);
            }
        }
    }

    public boolean hasPending(String taskId) {
        return pendingByTask.getOrDefault(taskId, 0) > 0;
    }

    /** 指定 agent 是否有挂起 ask(list_agents/wait_agents 的 waiting-user 判定,主/子统一)。 */
    public boolean hasPendingFor(String taskId, String agentId) {
        for (Ask a : asks.values()) {
            if (a.taskId.equals(taskId) && a.agentId.equals(agentId)) {
                return true;
            }
        }
        return false;
    }

    private void pendingChanged(String taskId, int delta) {
        int now = pendingByTask.merge(taskId, delta, Integer::sum);
        if (now <= 0) {
            pendingByTask.remove(taskId, 0);
        }
        StatusHook h = hook;
        if (h != null) {
            h.askPendingChanged(taskId, now > 0);
        }
    }
}
