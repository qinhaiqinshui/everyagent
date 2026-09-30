package dev.everyagent.worker.interaction;

import dev.everyagent.plugin.api.interaction.AskOption;
import dev.everyagent.plugin.api.interaction.AskQuestion;
import dev.everyagent.plugin.api.interaction.AskResult;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.proto.SnowflakeId;
import dev.everyagent.plugin.api.event.EventPayloads;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskEvents;
import dev.everyagent.worker.task.TaskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 用户交互服务实现(架构 §5.6 / G4):
 * ask 阻塞在虚拟线程上等 future;挂起期间每 30s 重发 ask.state;
 * 超时/回答/取消均走 ask.resolved;同 taskId 全部 ask 结束 → 任务回到 running。
 *
 * <p>由原 {@code PendingAsks} 迁移而来,删除 kind 参数,options 从 string[] 升级为
 * {@link AskOption} 对象数组。
 */
@Component
public class InteractionServiceImpl implements InteractionService {

    /** 任务状态联动:有挂起 → waiting-user;全部解决 → running。 */
    public interface StatusHook {
        void askPendingChanged(String taskId, boolean nowPending);
    }

    private static final Logger log = LoggerFactory.getLogger(InteractionServiceImpl.class);

    private static final class Ask {
        final String askId;
        final String taskId;
        final String agentId;
        final List<AskQuestion> questions;
        final TaskEvents events;
        final CompletableFuture<AskResult> future = new CompletableFuture<>();
        volatile ScheduledFuture<?> refresher;
        volatile ScheduledFuture<?> timeout;

        Ask(String askId, String taskId, String agentId,
                List<AskQuestion> questions, TaskEvents events) {
            this.askId = askId;
            this.taskId = taskId;
            this.agentId = agentId;
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

    /** @Lazy 断环:TaskManager 注入本类,本类反向查 TaskEntry.events。 */
    private final TaskManager taskManager;

    public InteractionServiceImpl(@Lazy TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    public void setHook(StatusHook hook) {
        this.hook = hook;
    }

    @Override
    public AskResult ask(List<AskQuestion> questions, long timeoutMs, Map<String, String> context)
            throws InterruptedException {
        String taskId = context != null ? context.getOrDefault("taskId", "") : "";
        String agentId = context != null ? context.getOrDefault("agentId", "") : "";
        TaskEntry entry = taskManager.get(taskId);
        TaskEvents events = entry != null ? entry.events : null;
        if (events == null) {
            return new AskResult("cancelled", null);
        }
        String askId = dev.everyagent.plugin.api.proto.ShortIds.askId();
        // 题目 id 统一由真实 askId 派生(askId_i):调用方无法预知 askId,传入的 id 仅占位,
        // 前端据此与 ask.create 载荷稳定配对回传。
        List<AskQuestion> withIds = new java.util.ArrayList<>(questions.size());
        for (int i = 0; i < questions.size(); i++) {
            AskQuestion q = questions.get(i);
            withIds.add(new AskQuestion(askId + "_" + i, q.prompt(), q.options()));
        }
        Ask ask = new Ask(askId, taskId, agentId, withIds, events);
        asks.put(askId, ask);
        ObjectNode askData = Json.obj();
        askData.put("askId", askId);
        askData.set("questions", EventPayloads.questionsToJson(withIds));
        if (timeoutMs > 0) {
            askData.put("timeoutMs", timeoutMs);
        }
        events.emit(EmitEvent.of(SnowflakeId.next(), "ask.create", agentId,
                null, null, null, null, askData, EmitEvent.Mode.REPLACE));
        events.emit(EmitEvent.of(SnowflakeId.next(), "agent.status", agentId,
                null, null, null, "waiting-user", null, EmitEvent.Mode.REPLACE));
        pendingChanged(taskId, +1);
        ask.refresher = scheduler.scheduleAtFixedRate(() -> {
            try {
                ObjectNode stateData = Json.obj();
                stateData.put("askId", askId);
                stateData.put("status", "pending");
                stateData.set("questions", EventPayloads.questionsToJson(withIds));
                events.emit(EmitEvent.of(SnowflakeId.next(), "ask.state", agentId,
                        null, null, null, null, stateData, EmitEvent.Mode.REPLACE));
            } catch (RuntimeException e) {
                log.warn("ask.state 刷新失败", e);
            }
        }, 30, 30, TimeUnit.SECONDS);
        ask.timeout = scheduler.schedule(() -> {
            if (ask.future.complete(new AskResult("timeout", null))) {
                ObjectNode resolvedData = Json.obj();
                resolvedData.put("askId", askId);
                resolvedData.put("by", "timeout");
                resolvedData.put("status", "timeout");
                events.emit(EmitEvent.of(SnowflakeId.next(), "ask.resolved", agentId,
                        null, null, null, null, resolvedData, EmitEvent.Mode.REPLACE));
                events.emit(EmitEvent.of(SnowflakeId.next(), "agent.status", agentId,
                        null, null, null, "running", null, EmitEvent.Mode.REPLACE));
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

    @Override
    public void askAsync(List<AskQuestion> questions, long timeoutMs, Map<String, String> context,
            Consumer<AskResult> callback) {
        Thread.startVirtualThread(() -> {
            try {
                AskResult result = ask(questions, timeoutMs, context);
                callback.accept(result);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                callback.accept(new AskResult("cancelled", null));
            }
        });
    }

    /** 前端 ask.reply → resolve;首个结果生效,幂等。 */
    public boolean resolve(String askId, String answer, String by) {
        Ask a = asks.get(askId);
        if (a == null) {
            return false;
        }
        boolean first = a.future.complete(new AskResult("answered", answer));
        if (first) {
            ObjectNode resolvedData = Json.obj();
            resolvedData.put("askId", askId);
            resolvedData.put("by", by);
            resolvedData.put("status", "answered");
            a.events.emit(EmitEvent.of(SnowflakeId.next(), "ask.resolved", a.agentId,
                    null, null, null, null, resolvedData, EmitEvent.Mode.REPLACE));
            a.events.emit(EmitEvent.of(SnowflakeId.next(), "agent.status", a.agentId,
                    null, null, null, "running", null, EmitEvent.Mode.REPLACE));
        }
        return first;
    }

    /** 任务取消:全部挂起 ask 立即作废(工具线程随即被中断)。 */
    public void cancelTask(String taskId, String by) {
        for (Ask a : asks.values()) {
            if (a.taskId.equals(taskId) && a.future.complete(new AskResult("cancelled", null))) {
                ObjectNode resolvedData = Json.obj();
                resolvedData.put("askId", a.askId);
                resolvedData.put("by", by);
                resolvedData.put("status", "cancelled");
                a.events.emit(EmitEvent.of(SnowflakeId.next(), "ask.resolved", a.agentId,
                        null, null, null, null, resolvedData, EmitEvent.Mode.REPLACE));
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
