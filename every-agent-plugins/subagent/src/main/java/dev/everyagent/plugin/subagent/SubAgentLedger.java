package dev.everyagent.plugin.subagent;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.EventLogReader;
import dev.everyagent.plugin.api.event.EventRecord;
import dev.everyagent.plugin.api.task.TaskStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 子 agent 台账事件投影（Phase 4：从 TaskManager 迁入插件域）。
 *
 * <p>订阅任务 EventLog 的 agent.started / agent.done / agent.status / usage / message / error 事件，维护 per-task 内存台账
 * （agentId → ObjectNode 摘要）。台账随 agents.json 独立落盘：
 * <ul>
 *   <li>30s 定时快照（运行中任务）</li>
 *   <li>任务收口时经 ledger.persist 生命周期节点写终态快照</li>
 *   <li>冷启动恢复时读 agents.json（running/waiting-user → stopped 归一）</li>
 * </ul>
 *
 * <p>读侧（task.agents RPC / list_agents 工具）：live 任务读内存台账，
 * 冷任务读磁盘 agents.json。
 */
public class SubAgentLedger {

    private static final Logger log = LoggerFactory.getLogger(SubAgentLedger.class);

    private static final long PERSIST_INTERVAL_MS = 30_000;

    private final TaskStoreService store;
    /** per-task 内存台账：taskId → (agentId → 摘要 ObjectNode) */
    private final Map<String, Map<String, ObjectNode>> taskLedgers = new ConcurrentHashMap<>();
    /** per-task EventLog 游标：taskId → 最后处理的 EventLog 位置（按记录数） */
    private final Map<String, Integer> taskCursors = new ConcurrentHashMap<>();
    /** per-task EventLogReader.Listener 引用（用于 untrack 时移除） */
    private final Map<String, EventLogReader.Listener> taskListeners = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("subagent-ledger-timer").unstarted(r));

    public SubAgentLedger(TaskStoreService store) {
        this.store = store;
        scheduler.scheduleAtFixedRate(this::persistAll, PERSIST_INTERVAL_MS, PERSIST_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    // ── 任务生命周期回调 ──

    /**
     * 任务 track 时调用：注册 EventLogReader.Listener，开始订阅事件维护台账。
     * 同时从磁盘恢复已有台账（冷启动续跑场景）。
     */
    public void onTrack(String taskId, EventLogReader log, JsonNode meta) {
        // 冷启动恢复：优先 agents.json，回退 meta.agents
        Map<String, ObjectNode> ledger = taskLedgers.computeIfAbsent(taskId, k -> new ConcurrentHashMap<>());
        Path dir = store.dirOf(taskId);
        List<ObjectNode> disk = store.readAgents(dir);
        if (disk != null) {
            for (ObjectNode a : disk) {
                String id = a.path("agentId").asString("");
                if (id.isEmpty()) continue;
                ObjectNode copy = a.deepCopy();
                // running/waiting-user → stopped（冷启动归一）
                String st = copy.path("status").asString("");
                if ("running".equals(st) || "waiting-user".equals(st)) {
                    copy.put("status", "stopped");
                }
                ledger.put(id, copy);
            }
        } else if (meta != null) {
            // 旧任务兼容：从 meta.agents 回退
            JsonNode agents = meta.path("agents");
            if (agents.isArray()) {
                for (JsonNode a : agents) {
                    if (a.isObject()) {
                        ObjectNode copy = ((ObjectNode) a).deepCopy();
                        String id = copy.path("agentId").asString("");
                        if (!id.isEmpty()) {
                            String st = copy.path("status").asString("");
                            if ("running".equals(st) || "waiting-user".equals(st)) {
                                copy.put("status", "stopped");
                            }
                            ledger.put(id, copy);
                        }
                    }
                }
            }
        }

        // 注册 EventLogReader.Listener
        EventLogReader.Listener listener = new EventLogReader.Listener() {
            @Override
            public void onAppend() {
                processNewEvents(taskId, log);
            }
        };
        log.addListener(listener);
        taskListeners.put(taskId, listener);
    }

    /**
     * 任务 untrack 时调用：移除 EventLogReader.Listener，清理内存台账。
     * 终态快照已由 ledger.persist 节点写入磁盘。
     */
    public void onUntrack(String taskId, EventLogReader log) {
        EventLogReader.Listener listener = taskListeners.remove(taskId);
        if (listener != null) {
            log.removeListener(listener);
        }
        taskCursors.remove(taskId);
        taskLedgers.remove(taskId);
    }

    // ── 事件处理 ──

    /**
     * 处理 EventLog 中新增的事件，过滤 agent.started / agent.done / agent.status / usage / message / error 更新台账。
     */
    private void processNewEvents(String taskId, EventLogReader log) {
        Map<String, ObjectNode> ledger = taskLedgers.get(taskId);
        if (ledger == null) return;

        int cursor = taskCursors.getOrDefault(taskId, 0);
        List<EventRecord> newRecords = log.readFrom(cursor, 200);
        if (newRecords.isEmpty()) return;

        for (EventRecord r : newRecords) {
            processEvent(taskId, ledger, r);
        }
        taskCursors.put(taskId, cursor + newRecords.size());
    }

    /**
     * 处理单个事件，更新台账。
     */
    private void processEvent(String taskId, Map<String, ObjectNode> ledger, EventRecord r) {
        String event = r.event();
        String agentId = r.agentId();
        if (agentId == null || agentId.isEmpty()) return;

        JsonNode payload = r.payload();
        if (payload == null) return;

        switch (event) {
            case "agent.started" -> {
                String title = payload.path("title").asString("");
                long createdAt = r.ts();
                ObjectNode entry = Json.obj();
                entry.put("agentId", agentId);
                if (!title.isEmpty()) entry.put("title", title);
                entry.put("createdAt", createdAt);
                entry.put("status", "running");
                entry.putObject("latestActivity");
                ledger.put(agentId, entry);
            }
            case "agent.done" -> {
                ObjectNode entry = ledger.get(agentId);
                if (entry != null) {
                    entry.put("status", "completed");
                    JsonNode result = payload.path("result");
                    if (result.isTextual() && !result.asText().isEmpty()) {
                        ObjectNode la = entry.has("latestActivity") && entry.get("latestActivity").isObject()
                                ? (ObjectNode) entry.get("latestActivity") : entry.putObject("latestActivity");
                        la.put("content", result.asText());
                        la.put("updatedAt", r.ts());
                    }
                }
            }
            case "agent.status" -> {
                ObjectNode entry = ledger.get(agentId);
                if (entry != null) {
                    String status = payload.path("status").asString("");
                    if (!status.isEmpty()) {
                        // 映射事件状态到台账状态
                        String ledgerStatus = switch (status) {
                            case "running" -> "running";
                            case "done" -> "completed";
                            case "stopped" -> "stopped";
                            case "failed" -> "error";
                            default -> status;
                        };
                        entry.put("status", ledgerStatus);
                    }
                }
            }
            case "error" -> {
                ObjectNode entry = ledger.get(agentId);
                if (entry != null) {
                    String msg = payload.path("message").asString("");
                    ObjectNode la = entry.has("latestActivity") && entry.get("latestActivity").isObject()
                            ? (ObjectNode) entry.get("latestActivity") : entry.putObject("latestActivity");
                    la.put("error", msg);
                    la.put("updatedAt", r.ts());
                    // 如果当前是 running 状态，改为 error
                    if ("running".equals(entry.path("status").asString(""))) {
                        entry.put("status", "error");
                    }
                }
            }
            case "message" -> {
                ObjectNode entry = ledger.get(agentId);
                if (entry != null) {
                    ObjectNode la = entry.has("latestActivity") && entry.get("latestActivity").isObject()
                            ? (ObjectNode) entry.get("latestActivity") : entry.putObject("latestActivity");
                    JsonNode thinking = payload.path("thinking");
                    if (thinking.isTextual() && !thinking.asText().isEmpty()) {
                        la.put("reasoning", thinking.asText());
                    }
                    JsonNode text = payload.path("text");
                    if (text.isTextual() && !text.asText().isEmpty()) {
                        la.put("content", text.asText());
                    }
                    la.put("updatedAt", r.ts());
                }
            }
            case "usage" -> {
                ObjectNode entry = ledger.get(agentId);
                if (entry != null) {
                    JsonNode usage = payload.path("total");
                    if (usage.isObject()) {
                        entry.set("usage", usage.deepCopy());
                    }
                }
            }
            default -> { /* 忽略其他事件 */ }
        }
    }

    // ── 持久化 ──

    /**
     * 30s 定时持久化运行中任务的台账。
     */
    private void persistAll() {
        for (Map.Entry<String, Map<String, ObjectNode>> e : taskLedgers.entrySet()) {
            String taskId = e.getKey();
            Map<String, ObjectNode> ledger = e.getValue();
            if (ledger.isEmpty()) continue;
            try {
                store.writeAgents(taskId, new ArrayList<>(ledger.values()));
            } catch (RuntimeException ex) {
                log.debug("台账定时持久化失败 task={}", taskId, ex);
            }
        }
    }

    /**
     * 任务收口时写终态快照（由 ledger.persist 生命周期节点调用）。
     */
    public void persistFinal(String taskId) {
        Map<String, ObjectNode> ledger = taskLedgers.get(taskId);
        if (ledger == null) return;
        try {
            store.writeAgents(taskId, new ArrayList<>(ledger.values()));
        } catch (RuntimeException ex) {
            log.warn("台账终态持久化失败 task={}", taskId, ex);
        }
    }

    // ── 读取 ──

    /**
     * 获取 live 任务的台账摘要数组（用于 task.agents RPC）。
     * 返回 null 表示该任务不在内存中（冷任务）。
     */
    public List<ObjectNode> getLiveAgents(String taskId) {
        Map<String, ObjectNode> ledger = taskLedgers.get(taskId);
        if (ledger == null) return null;
        List<ObjectNode> ordered = new ArrayList<>(ledger.values());
        ordered.sort(java.util.Comparator.comparingLong(a -> a.path("createdAt").asLong(0)));
        return ordered;
    }

    /**
     * 获取单个 live agent 的台账摘要（用于 wait_agents 工具历史查询）。
     */
    public ObjectNode getLiveAgent(String taskId, String agentId) {
        Map<String, ObjectNode> ledger = taskLedgers.get(taskId);
        if (ledger == null) return null;
        return ledger.get(agentId);
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
