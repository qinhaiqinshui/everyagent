package dev.everyagent.plugin.workflow.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.exception.BadParamsException;
import dev.everyagent.plugin.api.proto.ShortIds;
import dev.everyagent.plugin.api.proto.SnowflakeId;
import dev.everyagent.plugin.api.spi.WorkspaceManager;
import dev.everyagent.plugin.api.util.AtomicFiles;
import dev.everyagent.plugin.workflow.model.AgentEventLine;
import dev.everyagent.plugin.workflow.model.Definition;
import dev.everyagent.plugin.workflow.model.EventLine;
import dev.everyagent.plugin.workflow.model.InstanceMeta;
import dev.everyagent.plugin.workflow.model.InstanceStatus;
import dev.everyagent.plugin.workflow.model.RunRecord;
import dev.everyagent.plugin.workflow.validate.DefinitionValidator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 工作流磁盘存储 —— 目录布局（与 tasks/ 平级的工作区隔离，架构 §7.15）：
 *
 * <pre>
 * &lt;systemDir&gt;/workspaces/&lt;workspaceId&gt;/workflows/
 * ├─ definitions/&lt;wf_id&gt;/definition.json        # 定义（原子重写；目录形态为未来扩展留位）
 * └─ instances/&lt;wi_id&gt;/
 *     ├─ meta.json                              # 实例元数据+定义快照（状态迁移点原子重写）
 *     ├─ events.jsonl                           # 实例事件日志（单 writer 串行追加）
 *     ├─ runs/&lt;0007_wn_b7&gt;.json                 # 每次节点运行一文件（原子写，并行无冲突）
 *     └─ agents/&lt;name&gt;.jsonl                    # 命名 agent 会话日志（追加写永不重写）
 * </pre>
 *
 * <p>写策略与并发安全（计划「存储设计 §3」）：
 * definition/meta 用 {@link AtomicFiles#writeText} 原子重写；events.jsonl 实例内一把小锁
 * 串行追加；runs 每运行一文件；agents/*.jsonl 由本类提供纯追加 API，串行化由上层
 * （引擎的同 agent fair 锁）保证。列表只读 definition.json/meta.json，<b>不触 events.jsonl</b>。
 *
 * <p>内存索引：activate 时全扫建索引，之后增量维护（defId/instanceId 全局唯一键）；
 * workspaceId→root 注册表解析经 {@link WorkspaceManager}（redirect 安全），
 * 列表据此支持「工作区缺失」标记（数据保留，工作区以同 id 重新注册后自动回归）。
 */
public final class WorkflowStore {

    private static final Logger log = LoggerFactory.getLogger(WorkflowStore.class);

    /** worker 重启中断实例的失败文案（activate 扫描标 failed 用）。 */
    public static final String INTERRUPT_MESSAGE = "worker 重启中断";

    /** 每实例内存尾部队量（instance.events 长轮询用；磁盘 jsonl 才是完整事实源）。 */
    private static final int TAIL_MAX = 256;

    private final WorkspaceManager workspaces;
    private final Path systemDir;

    /** defId → 定义（全局唯一；id 由 ShortIds 分配并查重）。 */
    private final Map<String, Definition> defIndex = new ConcurrentHashMap<>();
    /** instanceId → 实例元数据。 */
    private final Map<String, InstanceMeta> instanceIndex = new ConcurrentHashMap<>();
    /** 实例事件串行锁（wsId/wiId → lock）；events.jsonl 单 writer 保证。 */
    private final ConcurrentHashMap<String, Object> eventLocks = new ConcurrentHashMap<>();
    /** 实例事件内存尾部（长轮询快路径）。 */
    private final ConcurrentHashMap<String, Deque<EventLine>> eventTails = new ConcurrentHashMap<>();
    /** 已注册工作区 id 快照（「工作区缺失」标记用）。 */
    private volatile Set<String> registeredWorkspaceIds = Set.of();

    public WorkflowStore(WorkspaceManager workspaces) {
        this.workspaces = workspaces;
        this.systemDir = workspaces.systemDir();
    }

    /** activate 扫描结果。 */
    public record ScanReport(int workspaces, int definitions, int instances, int interrupted) {
    }

    // ════════════════════════════ 路径 ════════════════════════════

    private Path workspacesRoot() {
        return systemDir.resolve("workspaces");
    }

    private Path workflowsRoot(String wsId) {
        return workspacesRoot().resolve(requireSafeSegment(wsId, "workspaceId")).resolve("workflows");
    }

    private Path definitionsRoot(String wsId) {
        return workflowsRoot(wsId).resolve("definitions");
    }

    private Path instancesRoot(String wsId) {
        return workflowsRoot(wsId).resolve("instances");
    }

    private Path defFile(String wsId, String defId) {
        return definitionsRoot(wsId).resolve(requireSafeSegment(defId, "definition id")).resolve("definition.json");
    }

    private Path instanceDir(String wsId, String wiId) {
        return instancesRoot(wsId).resolve(requireSafeSegment(wiId, "instance id"));
    }

    private Path metaFile(String wsId, String wiId) {
        return instanceDir(wsId, wiId).resolve("meta.json");
    }

    private Path eventsFile(String wsId, String wiId) {
        return instanceDir(wsId, wiId).resolve("events.jsonl");
    }

    private Path runsDir(String wsId, String wiId) {
        return instanceDir(wsId, wiId).resolve("runs");
    }

    private Path agentsDir(String wsId, String wiId) {
        return instanceDir(wsId, wiId).resolve("agents");
    }

    private Path runFile(String wsId, String wiId, int runNo, String nodeId) {
        return runsDir(wsId, wiId)
                .resolve(String.format("%04d_%s.json", runNo, requireSafeSegment(nodeId, "nodeId")));
    }

    private static String requireSafeSegment(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            throw new BadParamsException(what + " 不能为空");
        }
        String s = raw.trim();
        if (s.equals(".") || s.equals("..")) {
            throw new BadParamsException(what + " 非法: " + raw);
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|' || c < 0x20 || c == 0x7f) {
                throw new BadParamsException(what + " 含路径非法字符: " + raw);
            }
        }
        if (s.endsWith(".")) {
            throw new BadParamsException(what + " 不能以点结尾: " + raw);
        }
        return s;
    }

    private static String key(String wsId, String wiId) {
        return wsId + "/" + wiId;
    }

    // ════════════════════════════ 激活扫描 ════════════════════════════

    /**
     * activate 扫描：全扫各工作区 workflows/ 建内存索引；非终态实例（created/running/
     * waiting-user）标 failed("{@value #INTERRUPT_MESSAGE}")——写回 meta.json 并追加
     * wf.status 事件（v1 无断点续跑，同任务语义）。损坏的 definition.json/meta.json
     * 跳过并告警（不崩溃，保留磁盘待人工处理）。
     */
    public ScanReport scanOnActivate() {
        int wsCount = 0;
        int defCount = 0;
        int instCount = 0;
        refreshWorkspaceRegistry();
        Path root = workspacesRoot();
        if (!Files.isDirectory(root)) {
            return new ScanReport(0, 0, 0, 0);
        }
        List<Path> wsDirs;
        try (Stream<Path> s = Files.list(root)) {
            wsDirs = s.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            log.warn("[workflow] 扫描工作区目录失败: {}", root, e);
            return new ScanReport(0, 0, 0, 0);
        }
        int interrupted = 0;
        for (Path wsDir : wsDirs) {
            if (!Files.isDirectory(wsDir.resolve("workflows"))) {
                continue;
            }
            wsCount++;
            String wsId = wsDir.getFileName().toString();
            defCount += scanDefinitions(wsId);
            instCount += scanInstances(wsId);
            interrupted += interruptNonTerminal(wsId);
        }
        return new ScanReport(wsCount, defCount, instCount, interrupted);
    }

    private int scanDefinitions(String wsId) {
        Path dir = definitionsRoot(wsId);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int n = 0;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path defDir : s.filter(Files::isDirectory).sorted().toList()) {
                Path file = defDir.resolve("definition.json");
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    Definition def = Json.MAPPER.readValue(file.toFile(), Definition.class);
                    if (def == null || def.id() == null || def.id().isBlank()) {
                        log.warn("[workflow] 定义缺 id,跳过: {}", file);
                        continue;
                    }
                    Definition prev = defIndex.put(def.id(), def);
                    if (prev != null && !wsId.equals(prev.workspaceId())) {
                        log.warn("[workflow] 定义 id {} 在多个工作区出现({}/{})——保留后扫到者,请人工核查",
                                def.id(), prev.workspaceId(), wsId);
                    }
                    n++;
                } catch (RuntimeException e) {
                    log.warn("[workflow] 定义文件损坏,跳过: {} ({})", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[workflow] 扫描定义目录失败: {}", dir, e);
        }
        return n;
    }

    private int scanInstances(String wsId) {
        Path dir = instancesRoot(wsId);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int n = 0;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path wiDir : s.filter(Files::isDirectory).sorted().toList()) {
                Path file = wiDir.resolve("meta.json");
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    InstanceMeta meta = Json.MAPPER.readValue(file.toFile(), InstanceMeta.class);
                    if (meta == null || meta.id() == null || meta.id().isBlank()) {
                        log.warn("[workflow] 实例 meta 缺 id,跳过: {}", file);
                        continue;
                    }
                    instanceIndex.put(meta.id(), meta);
                    n++;
                } catch (RuntimeException e) {
                    log.warn("[workflow] 实例 meta 损坏,跳过: {} ({})", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[workflow] 扫描实例目录失败: {}", dir, e);
        }
        return n;
    }

    /** 非终态实例标 failed("worker 重启中断")：写回 meta.json + 追加 wf.status 事件。 */
    private int interruptNonTerminal(String wsId) {
        int n = 0;
        for (InstanceMeta meta : instanceIndex.values()) {
            if (!wsId.equals(meta.workspaceId())) {
                continue;
            }
            if (meta.status() != null && meta.status().terminal()) {
                continue;
            }
            long now = System.currentTimeMillis();
            Long duration = meta.startedAt() == null ? null : now - meta.startedAt();
            InstanceMeta failed = new InstanceMeta(
                    InstanceMeta.SCHEMA_VERSION, meta.id(), meta.definitionId(), meta.definitionName(),
                    meta.workspaceId(), meta.definitionSnapshot(), meta.variables(),
                    InstanceStatus.FAILED, meta.createdAt(), meta.startedAt(), now, duration,
                    new InstanceMeta.Error(INTERRUPT_MESSAGE, null, null),
                    null, null, meta.runCounter() == null ? 0 : meta.runCounter());
            saveInstanceMeta(failed);
            ObjectNode payload = Json.obj().put("status", "failed")
                    .set("error", Json.obj().put("message", INTERRUPT_MESSAGE));
            appendEvent(wsId, meta.id(), "wf.status", payload);
            n++;
            log.info("[workflow] 实例 {} 因 worker 重启中断,标 failed", meta.id());
        }
        return n;
    }

    // ════════════════════════════ 工作区注册表 ════════════════════════════

    /**
     * 刷新已注册工作区 id 快照（「工作区缺失」标记用）。
     * plugin-api 的 WorkspaceManager 只暴露 root→id 方向，故经
     * {@code pruneStaleAndListMountRoots()} 取存活根再逐一 idOfRoot。
     */
    public void refreshWorkspaceRegistry() {
        try {
            Set<String> ids = new HashSet<>();
            for (Path root : workspaces.pruneStaleAndListMountRoots()) {
                String id = workspaces.idOfRoot(root.toString());
                if (id != null) {
                    ids.add(id);
                }
            }
            registeredWorkspaceIds = Set.copyOf(ids);
        } catch (RuntimeException e) {
            log.warn("[workflow] 刷新工作区注册表快照失败,沿用旧快照", e);
        }
    }

    /** 工作区是否仍在注册表（false=「工作区缺失」，数据保留、列表打标）。 */
    public boolean isWorkspaceRegistered(String wsId) {
        return registeredWorkspaceIds.contains(wsId);
    }

    // ════════════════════════════ 定义 ════════════════════════════

    /**
     * 保存定义（upsert）：全量校验（失败抛 {@link BadParamsException}，消息含逐项错误）
     * → normalize（agent 引用合并/缺省补齐）→ id 分配（draft.id 空则新赋 wf id 并查重）
     * → 原子重写 definition.json → 更新索引。定义的 workspaceId 以本参数为准（写入方裁定）。
     */
    public Definition saveDefinition(String workspaceId, Definition draft) {
        requireSafeSegment(workspaceId, "workspaceId");
        List<String> errors = DefinitionValidator.validate(draft);
        if (!errors.isEmpty()) {
            throw new BadParamsException(String.join("; ", errors));
        }
        Definition normalized = DefinitionValidator.normalize(draft);
        String id = (normalized.id() == null || normalized.id().isBlank())
                ? uniqueDefinitionId(workspaceId)
                : requireSafeSegment(normalized.id(), "definition id");
        Definition existing = defIndex.get(id);
        if (existing != null && !workspaceId.equals(existing.workspaceId())) {
            throw new BadParamsException("定义 id 已存在于其他工作区(" + existing.workspaceId() + "): " + id);
        }
        long now = System.currentTimeMillis();
        Definition saved = new Definition(
                Definition.SCHEMA_VERSION, id, normalized.name(), normalized.description(), workspaceId,
                existing != null && existing.createdAt() != null ? existing.createdAt() : now, now,
                normalized.variables(), normalized.onError(), normalized.supervisor(),
                normalized.agents(), normalized.nodes(), normalized.edges());
        try {
            Files.createDirectories(defFile(workspaceId, id).getParent());
            AtomicFiles.writeText(defFile(workspaceId, id), Json.write(saved));
        } catch (IOException e) {
            throw new UncheckedIOException("写 definition.json 失败: " + id, e);
        }
        defIndex.put(id, saved);
        return saved;
    }

    private String uniqueDefinitionId(String wsId) {
        while (true) {
            String id = ShortIds.next("wf");
            if (!defIndex.containsKey(id) && !Files.exists(defFile(wsId, id))) {
                return id;
            }
        }
    }

    /** 全局按 id 查定义（instance.get 等不带 workspace 的路径用）。 */
    public Optional<Definition> findDefinition(String defId) {
        return Optional.ofNullable(defIndex.get(defId));
    }

    /** 按工作区 + id 查定义（工作区边界校验：挂靠不符=空）。 */
    public Optional<Definition> getDefinition(String workspaceId, String defId) {
        return findDefinition(defId).filter(d -> workspaceId.equals(d.workspaceId()));
    }

    /** 全部定义，按 updatedAt 倒序（缺省=创建序兜底）。 */
    public List<Definition> listDefinitions() {
        return listDefinitions(null);
    }

    /** 定义列表（workspaceId 空=全部工作区；按 updatedAt 倒序）。仅读索引（activate 已建）。 */
    public List<Definition> listDefinitions(String workspaceId) {
        refreshWorkspaceRegistry();
        return defIndex.values().stream()
                .filter(d -> workspaceId == null || workspaceId.equals(d.workspaceId()))
                .sorted(Comparator
                        .comparingLong((Definition d) -> d.updatedAt() == null ? 0L : d.updatedAt()).reversed()
                        .thenComparing(Definition::id))
                .toList();
    }

    /** 删除定义（只删 definitions/ 下目录；历史实例持快照不受影响）。false=不存在或挂靠不符。 */
    public boolean deleteDefinition(String workspaceId, String defId) {
        Optional<Definition> existing = getDefinition(workspaceId, defId);
        if (existing.isEmpty()) {
            return false;
        }
        deleteRecursively(defFile(workspaceId, defId).getParent());
        defIndex.remove(defId);
        return true;
    }

    /**
     * 导入定义：<b>重赋顶层 wf id</b>（防跨机/跨区冲突）+ 落入指定工作区；
     * 节点/边/agent 名保留（仅在定义内唯一）；校验同 save（失败抛 BadParamsException）。
     */
    public Definition importDefinition(String workspaceId, Definition incoming) {
        Definition stripped = withId(incoming, null);
        return saveDefinition(workspaceId, stripped);
    }

    /** 导出定义：definition.json 原样文本（前端转文件下载）。 */
    public Optional<String> exportDefinition(String workspaceId, String defId) {
        Path file = defFile(workspaceId, defId);
        if (!getDefinition(workspaceId, defId).isPresent() || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("读 definition.json 失败: " + defId, e);
        }
    }

    private static Definition withId(Definition d, String id) {
        return new Definition(d.schemaVersion(), id, d.name(), d.description(), d.workspaceId(),
                d.createdAt(), d.updatedAt(), d.variables(), d.onError(), d.supervisor(),
                d.agents(), d.nodes(), d.edges());
    }

    // ════════════════════════════ 实例 ════════════════════════════

    /**
     * 创建实例（一次运行 = 一个实例，自包含可独立回放）：深拷贝定义快照、
     * 分配 wi id、status=created、落 meta.json、追加 wf.created 事件。
     */
    public InstanceMeta createInstance(Definition def, Map<String, String> variables) {
        if (def == null || def.workspaceId() == null || def.workspaceId().isBlank()) {
            throw new BadParamsException("创建实例需要挂靠工作区的定义(workspaceId 缺失)");
        }
        String wsId = def.workspaceId();
        String wiId = uniqueInstanceId(wsId);
        Definition snapshot = deepCopy(def);
        Map<String, String> vars = variables == null ? Map.of() : Map.copyOf(variables);
        InstanceMeta meta = new InstanceMeta(
                InstanceMeta.SCHEMA_VERSION, wiId, def.id(), def.name(), wsId, snapshot, vars,
                InstanceStatus.CREATED, System.currentTimeMillis(), null, null, null,
                null, null, null, 0);
        try {
            Files.createDirectories(instanceDir(wsId, wiId));
            Files.createDirectories(runsDir(wsId, wiId));
            Files.createDirectories(agentsDir(wsId, wiId));
            AtomicFiles.writeText(metaFile(wsId, wiId), Json.write(meta));
        } catch (IOException e) {
            throw new UncheckedIOException("写实例 meta.json 失败: " + wiId, e);
        }
        instanceIndex.put(wiId, meta);
        appendEvent(wsId, wiId, "wf.created", Json.obj()
                .put("instanceId", wiId)
                .put("definitionId", def.id() == null ? "" : def.id())
                .put("name", def.name() == null ? "" : def.name())
                .set("variables", Json.toJson(vars)));
        return meta;
    }

    private String uniqueInstanceId(String wsId) {
        while (true) {
            String id = ShortIds.next("wi");
            if (!instanceIndex.containsKey(id) && !Files.exists(instanceDir(wsId, id))) {
                return id;
            }
        }
    }

    /** 深拷贝（JSON 往返；记录浅持的 List/Map 一并复制，快照与定义解耦）。 */
    private static Definition deepCopy(Definition def) {
        return Json.convert(Json.toJson(def), Definition.class);
    }

    /** 全局按 instanceId 查实例（workflow.instance.* RPC 不带 workspace 参数）。 */
    public Optional<InstanceMeta> findInstance(String instanceId) {
        return Optional.ofNullable(instanceIndex.get(instanceId));
    }

    /** 按工作区 + instanceId 查实例（边界校验：挂靠不符=空）。 */
    public Optional<InstanceMeta> getInstance(String workspaceId, String instanceId) {
        return findInstance(instanceId).filter(m -> workspaceId.equals(m.workspaceId()));
    }

    /** 写回实例元数据（状态迁移点原子重写 meta.json + 索引维护）。 */
    public InstanceMeta saveInstanceMeta(InstanceMeta meta) {
        if (meta == null || meta.id() == null || meta.id().isBlank() || meta.workspaceId() == null) {
            throw new BadParamsException("实例 meta 缺 id/workspaceId");
        }
        try {
            Files.createDirectories(instanceDir(meta.workspaceId(), meta.id()));
            AtomicFiles.writeText(metaFile(meta.workspaceId(), meta.id()), Json.write(meta));
        } catch (IOException e) {
            throw new UncheckedIOException("写实例 meta.json 失败: " + meta.id(), e);
        }
        instanceIndex.put(meta.id(), meta);
        return meta;
    }

    /** 全部实例，createdAt 倒序。 */
    public List<InstanceMeta> listInstances() {
        return listInstances(null, null);
    }

    /** 实例列表（workspaceId/definitionId 空=不过滤；createdAt 倒序）。仅读 meta 索引，不触 events.jsonl。 */
    public List<InstanceMeta> listInstances(String workspaceId, String definitionId) {
        refreshWorkspaceRegistry();
        return instanceIndex.values().stream()
                .filter(m -> workspaceId == null || workspaceId.equals(m.workspaceId()))
                .filter(m -> definitionId == null || definitionId.equals(m.definitionId()))
                .sorted(Comparator
                        .comparingLong((InstanceMeta m) -> m.createdAt() == null ? 0L : m.createdAt()).reversed()
                        .thenComparing(InstanceMeta::id))
                .toList();
    }

    /**
     * 删除实例（删整个 &lt;wi_id&gt;/ 目录）。store 层仅拒 RUNNING/waiting-user
     * （引擎持有内存态，运行中的强拒绝由引擎/RPC 层兜底）。
     */
    public boolean deleteInstance(String workspaceId, String instanceId) {
        Optional<InstanceMeta> existing = getInstance(workspaceId, instanceId);
        if (existing.isEmpty()) {
            return false;
        }
        InstanceStatus status = existing.get().status();
        if (status == InstanceStatus.RUNNING || status == InstanceStatus.WAITING_USER) {
            throw new BadParamsException("实例运行中/挂起等待用户,拒绝删除: " + instanceId);
        }
        deleteRecursively(instanceDir(workspaceId, instanceId));
        instanceIndex.remove(instanceId);
        String k = key(workspaceId, instanceId);
        eventLocks.remove(k);
        eventTails.remove(k);
        return true;
    }

    // ════════════════════════════ runs ════════════════════════════

    /** 写节点运行记录（runs/&lt;000N_节点&gt;.json 原子写；并行节点各写各文件）。 */
    public void saveRunRecord(String workspaceId, String instanceId, RunRecord rec) {
        if (rec == null || rec.runNo() == null || rec.nodeId() == null) {
            throw new BadParamsException("RunRecord 缺 runNo/nodeId");
        }
        try {
            Files.createDirectories(runsDir(workspaceId, instanceId));
            AtomicFiles.writeText(runFile(workspaceId, instanceId, rec.runNo(), rec.nodeId()), Json.write(rec));
        } catch (IOException e) {
            throw new UncheckedIOException("写 run 记录失败: " + rec.nodeId(), e);
        }
    }

    /** 读全部节点运行记录（按 runNo 升序；坏 JSON 跳过告警）。 */
    public List<RunRecord> listRunRecords(String workspaceId, String instanceId) {
        Path dir = runsDir(workspaceId, instanceId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<RunRecord> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                try {
                    out.add(Json.MAPPER.readValue(f.toFile(), RunRecord.class));
                } catch (RuntimeException e) {
                    log.warn("[workflow] run 记录损坏,跳过: {} ({})", f, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[workflow] 读 runs 目录失败: {}", dir, e);
            return List.of();
        }
        out.sort(Comparator.comparing(r -> r.runNo() == null ? Integer.MAX_VALUE : r.runNo()));
        return out;
    }

    // ════════════════════════════ events.jsonl ════════════════════════════

    /**
     * 追加实例事件（单 writer 串行：实例内一把小锁；seq=SnowflakeId.next() 字符串化，
     * <b>取号在锁内</b>——events.jsonl 的文件序与 seq 序一致，afterSeq 游标无回读死角）。
     * 同时维护内存尾部（{@link #tailEvents}）。
     */
    public EventLine appendEvent(String workspaceId, String instanceId, String event, JsonNode payload) {
        String k = key(workspaceId, instanceId);
        Object lock = eventLocks.computeIfAbsent(k, x -> new Object());
        synchronized (lock) {
            EventLine line = new EventLine(Long.toString(SnowflakeId.next()),
                    System.currentTimeMillis(), event, payload);
            String text = Json.write(line) + "\n";
            try {
                Files.createDirectories(instanceDir(workspaceId, instanceId));
                Files.writeString(eventsFile(workspaceId, instanceId), text,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException("追加 events.jsonl 失败: " + instanceId, e);
            }
            Deque<EventLine> tail = eventTails.computeIfAbsent(k, x -> new ArrayDeque<>());
            tail.addLast(line);
            while (tail.size() > TAIL_MAX) {
                tail.pollFirst();
            }
            return line;
        }
    }

    /** 便捷重载：payload 直接给 Map。 */
    public EventLine appendEvent(String workspaceId, String instanceId, String event, Map<String, Object> payload) {
        return appendEvent(workspaceId, instanceId, event, Json.toJson(payload));
    }

    /**
     * 读实例事件（seq &gt; afterSeq，升序；limit&gt;0 时截断）。坏 JSON 行跳过告警
     * （磁盘前缀一致性由追加写保证；坏行=外部损坏，容错不崩溃）。
     */
    public List<EventLine> readEvents(String workspaceId, String instanceId, long afterSeq, int limit) {
        Path file = eventsFile(workspaceId, instanceId);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<EventLine> out = new ArrayList<>();
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty()) {
                    continue;
                }
                EventLine ev;
                try {
                    ev = Json.MAPPER.readValue(line, EventLine.class);
                } catch (RuntimeException e) {
                    log.warn("[workflow] events.jsonl 坏行跳过: {} ({})", file, e.getMessage());
                    continue;
                }
                long seq = ev.seqValue();
                if (seq > afterSeq) {
                    out.add(ev);
                    if (limit > 0 && out.size() >= limit) {
                        break;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("[workflow] 读 events.jsonl 失败: {}", file, e);
        }
        return out;
    }

    /** 内存尾部队列快照（长轮询快路径；完整事实源在磁盘 jsonl）。 */
    public List<EventLine> tailEvents(String workspaceId, String instanceId, int max) {
        Deque<EventLine> tail = eventTails.get(key(workspaceId, instanceId));
        if (tail == null || tail.isEmpty()) {
            return List.of();
        }
        List<EventLine> snapshot = new ArrayList<>(tail);
        return max > 0 && snapshot.size() > max
                ? snapshot.subList(snapshot.size() - max, snapshot.size())
                : snapshot;
    }

    // ════════════════════════════ agents/*.jsonl ════════════════════════════

    /**
     * 追加命名 agent 会话事件（agents/&lt;name&gt;.jsonl，追加写永不重写）。
     * <b>串行化由上层保证</b>（引擎同 agent fair 锁，锁粒度=整个 run）；本方法只提供
     * 纯追加（CREATE+APPEND），不建内存态。
     */
    public AgentEventLine appendAgentEvent(String workspaceId, String instanceId, String agentName,
            String event, String agentId, JsonNode payload) {
        AgentEventLine line = new AgentEventLine(Long.toString(SnowflakeId.next()),
                System.currentTimeMillis(), event, agentId, payload);
        appendAgentEventLine(workspaceId, instanceId, agentName, line);
        return line;
    }

    /** 追加已构造好的 agent 事件行（测试/引擎直接落行用）。 */
    public void appendAgentEventLine(String workspaceId, String instanceId, String agentName, AgentEventLine line) {
        Path file = agentsDir(workspaceId, instanceId)
                .resolve(requireSafeSegment(agentName, "agent 名") + ".jsonl");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, Json.write(line) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("追加 agent 会话日志失败: " + agentName, e);
        }
    }

    /** 读命名 agent 会话事件（坏行跳过告警）。 */
    public List<AgentEventLine> readAgentEvents(String workspaceId, String instanceId, String agentName) {
        Path file = agentsDir(workspaceId, instanceId)
                .resolve(requireSafeSegment(agentName, "agent 名") + ".jsonl");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<AgentEventLine> out = new ArrayList<>();
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    out.add(Json.MAPPER.readValue(line, AgentEventLine.class));
                } catch (RuntimeException e) {
                    log.warn("[workflow] agent jsonl 坏行跳过: {} ({})", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[workflow] 读 agent 会话日志失败: {}", file, e);
        }
        return out;
    }

    // ════════════════════════════ 工具 ════════════════════════════

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("[workflow] 删除失败(残留 {}): {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("[workflow] 遍历删除失败: {}", dir, e);
        }
    }
}
