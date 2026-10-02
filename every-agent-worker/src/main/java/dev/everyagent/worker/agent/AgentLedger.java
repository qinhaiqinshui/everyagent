package dev.everyagent.worker.agent;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.EventLogReader;
import dev.everyagent.plugin.api.event.EventRecord;
import dev.everyagent.plugin.api.util.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Agent 台账事件投影（worker core 层，收编自 subagent 插件 SubAgentLedger）。
 *
 * <p>订阅主体 EventLog 的 agent.started / agent.done / agent.status / usage / message / error 事件，
 * 维护 per-subject 内存台账（agentId → ObjectNode 摘要）。台账随 agents.json 独立落盘
 * （住在主体 dataDir 下，经 {@link AtomicFiles} 自行读写）：
 * <ul>
 *   <li>30s 定时快照（运行中主体）</li>
 *   <li>主体收口时写终态快照（{@link #persistFinal(String)}）</li>
 *   <li>冷启动恢复时读 agents.json（running/waiting-user → stopped 归一）</li>
 * </ul>
 *
 * <p>读侧（task.agents RPC / list_agents 工具）：live 主体读内存台账，
 * 冷主体读磁盘 agents.json（{@link #readAgents(Path)}）。
 */
@Component
public class AgentLedger {

    private static final Logger log = LoggerFactory.getLogger(AgentLedger.class);

    private static final long PERSIST_INTERVAL_MS = 30_000;

    /** per-subject 内存台账：subjectId → (agentId → 摘要 ObjectNode)。 */
    private final Map<String, Map<String, ObjectNode>> taskLedgers = new ConcurrentHashMap<>();
    /** per-subject 数据目录（track 时登记，persistAll 落盘定位用）。 */
    private final Map<String, Path> taskDirs = new ConcurrentHashMap<>();
    /** per-subject EventLog 游标：subjectId → 最后处理的 EventLog 位置（按记录数）。 */
    private final Map<String, Integer> taskCursors = new ConcurrentHashMap<>();
    /** per-subject EventLogReader.Listener 引用（用于 untrack 时移除）。 */
    private final Map<String, EventLogReader.Listener> taskListeners = new ConcurrentHashMap<>();
    /** per-subject EventLogReader 引用（用于 untrack 时移除监听器）。 */
    private final Map<String, EventLogReader> taskLogs = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofVirtual().name("agent-ledger-timer").unstarted(r));

    public AgentLedger() {
        scheduler.scheduleAtFixedRate(this::persistAll, PERSIST_INTERVAL_MS, PERSIST_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    // ── 主体生命周期回调 ──

    /**
     * 主体 track 时调用：登记数据目录、注册 EventLogReader.Listener，开始订阅事件维护台账。
     * 同时从磁盘恢复已有台账（冷启动续跑场景）。
     */
    public void onTrack(String subjectId, Path dataDir, EventLogReader eventLog, JsonNode meta) {
        if (dataDir != null) {
            taskDirs.put(subjectId, dataDir);
        }
        taskLogs.put(subjectId, eventLog);
        // 冷启动恢复：优先 agents.json，回退 meta.agents
        Map<String, ObjectNode> ledger = taskLedgers.computeIfAbsent(subjectId, k -> new ConcurrentHashMap<>());
        List<ObjectNode> disk = dataDir != null ? readAgents(dataDir) : null;
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
                processNewEvents(subjectId, eventLog);
            }
        };
        eventLog.addListener(listener);
        taskListeners.put(subjectId, listener);
    }

    /**
     * 主体 untrack 时调用：移除 EventLogReader.Listener，清理内存台账。
     * 终态快照已由 persistFinal 写入磁盘。
     */
    public void onUntrack(String subjectId) {
        EventLogReader.Listener listener = taskListeners.remove(subjectId);
        EventLogReader eventLog = taskLogs.remove(subjectId);
        if (listener != null && eventLog != null) {
            eventLog.removeListener(listener);
        }
        taskCursors.remove(subjectId);
        taskLedgers.remove(subjectId);
        taskDirs.remove(subjectId);
    }

    // ── 事件处理 ──

    /**
     * 处理 EventLog 中新增的事件，过滤 agent.started / agent.done / agent.status / usage / message / error 更新台账。
     */
    private void processNewEvents(String subjectId, EventLogReader eventLog) {
        Map<String, ObjectNode> ledger = taskLedgers.get(subjectId);
        if (ledger == null) return;

        int cursor = taskCursors.getOrDefault(subjectId, 0);
        List<EventRecord> newRecords = eventLog.readFrom(cursor, 200);
        if (newRecords.isEmpty()) return;

        for (EventRecord r : newRecords) {
            processEvent(ledger, r);
        }
        taskCursors.put(subjectId, cursor + newRecords.size());
    }

    /**
     * 处理单个事件，更新台账。
     */
    private void processEvent(Map<String, ObjectNode> ledger, EventRecord r) {
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
                // metadata 投影（agent.started 事件 payload 携带的 metadata 字段）
                JsonNode metadata = payload.path("metadata");
                if (metadata.isObject()) {
                    entry.set("metadata", metadata.deepCopy());
                }
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
     * 30s 定时持久化运行中主体的台账。
     */
    private void persistAll() {
        for (Map.Entry<String, Map<String, ObjectNode>> e : taskLedgers.entrySet()) {
            String subjectId = e.getKey();
            Map<String, ObjectNode> ledger = e.getValue();
            if (ledger.isEmpty()) continue;
            Path dir = taskDirs.get(subjectId);
            if (dir == null) continue;
            try {
                writeAgents(dir, ledger.values());
            } catch (RuntimeException ex) {
                log.debug("台账定时持久化失败 subject={}", subjectId, ex);
            }
        }
    }

    /**
     * 主体收口时写终态快照。
     */
    public void persistFinal(String subjectId) {
        Map<String, ObjectNode> ledger = taskLedgers.get(subjectId);
        if (ledger == null) return;
        Path dir = taskDirs.get(subjectId);
        if (dir == null) return;
        try {
            writeAgents(dir, ledger.values());
        } catch (RuntimeException ex) {
            log.warn("台账终态持久化失败 subject={}", subjectId, ex);
        }
    }

    // ── 读取 ──

    /**
     * 获取 live 主体的台账摘要数组（用于 task.agents RPC）。
     * 返回 null 表示该主体不在内存中（冷主体）。
     */
    public List<ObjectNode> getLiveAgents(String subjectId) {
        Map<String, ObjectNode> ledger = taskLedgers.get(subjectId);
        if (ledger == null) return null;
        List<ObjectNode> ordered = new ArrayList<>(ledger.values());
        ordered.sort(java.util.Comparator.comparingLong(a -> a.path("createdAt").asLong(0)));
        return ordered;
    }

    /**
     * 获取单个 live agent 的台账摘要（用于 wait_agents 工具历史查询）。
     */
    public ObjectNode getLiveAgent(String subjectId, String agentId) {
        Map<String, ObjectNode> ledger = taskLedgers.get(subjectId);
        if (ledger == null) return null;
        return ledger.get(agentId);
    }

    // ── agents.json 读写 ──

    /**
     * 读主体数据目录下 agents.json（形状 {@code {"agents":[...]}}）。
     * 文件不存在/损坏/形状不符返回 null（null = 调用方回退旧格式 meta.agents）。
     */
    public List<ObjectNode> readAgents(Path dir) {
        Path f = dir.resolve("agents.json");
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            JsonNode agents = Json.parse(Files.readString(f)).path("agents");
            if (!agents.isArray()) {
                log.debug("agents.json 形状异常(无 agents 数组): {}", f);
                return null;
            }
            List<ObjectNode> out = new ArrayList<>();
            for (JsonNode a : agents) {
                if (a.isObject()) {
                    out.add((ObjectNode) a);
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            log.debug("agents.json 读取失败 {}", f, e);
            return null;
        }
    }

    /**
     * 原子写 agents.json（临时文件 + {@link AtomicFiles#replace}，同 writeMeta 惯例）。
     * 空台账时删除已存在的 agents.json（避免遗留脏数据；无文件则 no-op，不写空数组占位）。
     * 失败仅 warn 不抛（台账非真相源，下一轮 persist/30s 定时会重写）。
     */
    public void writeAgents(Path dir, Collection<ObjectNode> agents) {
        try {
            Path f = dir.resolve("agents.json");
            if (agents == null || agents.isEmpty()) {
                Files.deleteIfExists(f);
                return;
            }
            ObjectNode root = Json.obj();
            ArrayNode arr = Json.arr();
            agents.forEach(arr::add);
            root.set("agents", arr);
            Path tmp = dir.resolve("agents.json.tmp");
            Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
            AtomicFiles.replace(tmp, f); // 原子替换
        } catch (IOException | RuntimeException e) {
            log.warn("agents.json 写入失败 dir={}", dir, e);
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
