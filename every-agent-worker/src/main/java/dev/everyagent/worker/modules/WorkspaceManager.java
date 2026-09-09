package dev.everyagent.worker.modules;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.AtomicFiles;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.worker.rpc.BadParamsException;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.rpc.RpcContext;
import dev.everyagent.worker.rpc.SandboxViolationException;
import dev.everyagent.worker.task.TaskManager;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作区注册表(架构 §5.9/D15):多工作区并行,fs/git/task.run 按调用显式指定工作区。
 * 工作区因任务而注册(task.run 新建时写入 <data>/workspaces.json;init 预注册默认工作区)。
 * 工作区只是任务属性(meta.workspace),不是存储维度——任务统一存系统目录
 * data/tasks/<taskId>/(工作区是用户数据目录,不存任务数据)。
 * 校验规则不变:绝对路径、不得是系统目录本身或其祖先、realpath 规范化。
 *
 * <p>启动自检:init 时校验 workspaces.json 已注册工作区,目录缺失(用户移动/删除工作区后
 * 重启)的记录进 {@code missing}——注册表快照对该条目标记 missing,前端据此弹窗让用户
 * 选择「删除工作区(连带任务数据)」或「纠正路径(选择移动后的新目录)」,经
 * {@code workspaces.resolveMissing} RPC 落定(架构 §5.9)。未处理的缺失工作区禁止 resolve
 * (任务/fs/git 无法在其上运行,避免沙箱挂载失败或新建空目录掩盖数据丢失)。
 */
@Component
public class WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);

    /** 工作区根:normalize 后的绝对路径 + realpath(沙箱校验用)。 */
    public record Root(Path path, Path realPath) {
    }

    /** 注册表条目(workspaces.json 单项:{root, addedAt},无存储维度)。 */
    public record Registered(String root, long addedAt) {
    }

    private final WorkerProperties props;
    private final RpcDispatcher dispatcher;
    private final HubPool pool;
    /**
     * TaskManager 依赖本类(TaskManager 构造器注入 WorkspaceManager),若本类构造器直接注入
     * TaskManager 会构成构造器循环。用 ObjectProvider 懒解析,仅 workspaces.remove 级联删除时取用。
     */
    private final ObjectProvider<TaskManager> taskManagers;

    private Path systemDir;
    private Path defaultRoot;
    /** normalize 路径字符串 → 已 prepare 的 Root(校验结果缓存)。 */
    private final Map<String, Root> cache = new ConcurrentHashMap<>();
    /** 注册表:normalize 路径字符串 → 条目。 */
    private final Map<String, Registered> registry = new ConcurrentHashMap<>();
    /**
     * 启动自检发现的「目录缺失」工作区(root 规范键;架构 §5.9)。只含从 workspaces.json
     * 载入且目录不存在的条目;init 刚注册的默认工作区(全新安装尚未创建)不在此列。
     * 未落定前 resolve 拒绝该工作区;经 workspaces.resolveMissing 删除/纠正后移除。
     */
    private final Set<String> missing = ConcurrentHashMap.newKeySet();

    public WorkspaceManager(WorkerProperties props, RpcDispatcher dispatcher, HubPool pool,
            ObjectProvider<TaskManager> taskManagers) {
        this.props = props;
        this.dispatcher = dispatcher;
        this.pool = pool;
        this.taskManagers = taskManagers;
        dispatcher.register(RpcMethods.WORKSPACES_LIST, this::rpcList);
        dispatcher.register(RpcMethods.WORKSPACES_ADD, this::rpcAdd);
        dispatcher.register(RpcMethods.WORKSPACES_REMOVE, this::rpcRemove);
        dispatcher.register(RpcMethods.WORKSPACES_RESOLVE_MISSING, this::rpcResolveMissing);
    }

    @PostConstruct
    synchronized void init() throws IOException {
        systemDir = props.resolveHomeDir();
        defaultRoot = readDefaultOverride().orElseGet(props::resolveInitialWorkspace);
        Files.createDirectories(systemDir);
        Files.createDirectories(props.resolveDataDir());
        loadRegistry();
        // 自检只针对「本次从 workspaces.json 载入」的条目;随后再注册默认工作区,
        // 保证全新安装(默认目录尚未创建)不会被误判为「移动后丢失」。
        validateRegistry();
        register(defaultRoot); // 默认工作区始终在册(注册即广播;此刻 hub 未连则安静跳过)
        log.info("工作区注册表 {} 项(默认 {}),系统目录 {},缺失待处理 {} 项",
                registry.size(), defaultRoot, systemDir, missing.size());
    }

    /**
     * 解析工作区(校验 + prepare + 缓存;不写注册表)——fs 与 git 的 RPC 用:
     * 浏览任意合法目录不改变注册表,工作区因任务而注册。
     */
    public Root resolve(String raw) throws IOException {
        if (raw == null || raw.isBlank()) {
            throw new BadParamsException("缺少参数 workspace(工作区绝对路径)");
        }
        Path in = Path.of(raw.trim());
        if (!in.isAbsolute()) {
            throw new BadParamsException("工作区必须是 worker 所在机器的绝对路径: " + raw);
        }
        Path norm = in.toAbsolutePath().normalize();
        if (missing.contains(norm.toString())) {
            throw new BadParamsException(
                    "工作区目录不存在(可能已被移动或删除),请先选择「纠正路径」或「删除工作区」: " + raw);
        }
        Root cached = cache.get(norm.toString());
        if (cached != null) {
            return cached;
        }
        Root prepared = prepare(norm); // 越界(含系统目录)抛 SandboxViolationException
        cache.put(norm.toString(), prepared);
        return prepared;
    }

    /** 解析并注册(task.run 新建用):新工作区时写 workspaces.json。 */
    public synchronized Root resolveAndRegister(String raw) throws IOException {
        Root root = resolve(raw);
        register(root.path());
        return root;
    }

    /** 注册表快照(按注册时间升序)。 */
    public List<Registered> list() {
        List<Registered> out = new ArrayList<>(registry.values());
        out.sort(Comparator.comparingLong(Registered::addedAt));
        return out;
    }

    public Path systemDir() {
        return systemDir;
    }

    public Path defaultRoot() {
        return defaultRoot;
    }

    // ---- RPC ----

    private void rpcList(RpcContext ctx) {
        ctx.ok(snapshot());
    }

    /** 前端资源管理器入口:注册新工作区(校验规则与 task.run 新建相同)。 */
    private void rpcAdd(RpcContext ctx) throws IOException {
        Root root = resolveAndRegister(ctx.strParam("path"));
        // 返回完整快照(前端 apply 依赖 defaultRoot) + worker 规范化后的新增根。
        ctx.ok(snapshot().put("addedRoot", root.path().toString()));
    }

    /**
     * 移除注册(不删工作区目录本身——用户真实数据不动;但挂靠该工作区的任务数据一并删除,
     * 任务数据落系统目录 data/tasks/&lt;taskId&gt;/ 不属用户目录)。默认工作区不可移除。
     */
    private synchronized void rpcRemove(RpcContext ctx) throws IOException {
        String raw = ctx.strParam("root");
        String key = Path.of(raw).toAbsolutePath().normalize().toString();
        if (key.equals(defaultRoot.toString())) {
            throw new BadParamsException("默认工作区不可移除: " + key);
        }
        if (registry.remove(key) == null) {
            throw new BadParamsException("工作区未注册: " + key);
        }
        missing.remove(key);
        cache.remove(key);
        persistRegistry();
        broadcastRegistry();
        // 级联删除该工作区下的任务数据(任务落盘 data/tasks/<taskId>/,与用户目录无关)。
        TaskManager taskManager = taskManagers.getIfAvailable();
        if (taskManager != null) {
            taskManager.deleteByWorkspace(key);
        }
        ctx.ok(snapshot());
    }

    /**
     * 启动自检发现的缺失工作区落定(架构 §5.9):前端弹窗后回传用户选择。
     * - action=delete:移除注册并级联删除挂靠该工作区的任务数据;默认工作区不可删除。
     * - action=redirect:把注册表条目纠正到用户选定的新目录(newRoot 必填,须真实存在),
     *   并迁移挂靠该工作区的任务 meta.workspace;若为默认工作区,同时持久化新的默认根,
     *   避免下次重启又按配置把旧(已失效)路径重新注册回来。
     */
    private synchronized void rpcResolveMissing(RpcContext ctx) throws IOException {
        String raw = ctx.strParam("root");
        String key = Path.of(raw).toAbsolutePath().normalize().toString();
        if (!registry.containsKey(key)) {
            throw new BadParamsException("工作区未注册: " + key);
        }
        String action = ctx.strParam("action");
        switch (action) {
            case "delete" -> resolveMissingDelete(ctx, key);
            case "redirect" -> resolveMissingRedirect(ctx, key);
            default -> throw new BadParamsException("action 仅支持 delete/redirect: " + action);
        }
    }

    private void resolveMissingDelete(RpcContext ctx, String key) throws IOException {
        if (key.equals(defaultRoot.toString())) {
            throw new BadParamsException("默认工作区不可删除,请选择「纠正路径」: " + key);
        }
        registry.remove(key);
        missing.remove(key);
        cache.remove(key);
        persistRegistry();
        broadcastRegistry();
        TaskManager taskManager = taskManagers.getIfAvailable();
        if (taskManager != null) {
            taskManager.deleteByWorkspace(key);
        }
        ctx.ok(snapshot());
    }

    private void resolveMissingRedirect(RpcContext ctx, String key) throws IOException {
        String newRaw = ctx.strParam("newRoot");
        Path newPath = Path.of(newRaw).toAbsolutePath().normalize();
        if (!Files.isDirectory(newPath)) {
            throw new BadParamsException("新目录不存在或不是目录: " + newRaw);
        }
        Root newRoot = prepare(newPath); // 校验不越界(含系统目录即拒)
        Registered existing = registry.remove(key);
        missing.remove(key);
        cache.remove(key);
        String newKey = newRoot.path().toString();
        long addedAt = existing == null ? System.currentTimeMillis() : existing.addedAt();
        registry.put(newKey, new Registered(newKey, addedAt));
        if (key.equals(defaultRoot.toString())) {
            writeDefaultOverride(newKey);
            defaultRoot = newRoot.path();
        }
        persistRegistry();
        broadcastRegistry();
        TaskManager taskManager = taskManagers.getIfAvailable();
        if (taskManager != null) {
            taskManager.redirectWorkspace(key, newKey);
        }
        ctx.ok(snapshot());
    }

    // ---- 内部 ----

    /** 校验并落定工作区根:创建缺失目录,realpath 规范化;含系统目录即拒。 */
    private Root prepare(Path in) throws IOException {
        Files.createDirectories(in);
        Path real = in.toRealPath();
        Path realSys = systemDir.toRealPath();
        if (real.equals(realSys) || realSys.startsWith(real)) {
            throw new SandboxViolationException(
                    "工作区不能是系统目录本身或其祖先(否则模型配置会落入沙箱): " + in);
        }
        return new Root(in, real);
    }

    private void register(Path root) throws IOException {
        String key = root.toString();
        Registered existing = registry.get(key);
        if (existing != null) {
            return;
        }
        registry.put(key, new Registered(key, System.currentTimeMillis()));
        persistRegistry();
        broadcastRegistry(); // task.create 注册新工作区时,前端资源管理器即时感知
    }

    /** workspaces.list 应答与 workspaces.changed 广播共用的注册表快照。 */
    private ObjectNode snapshot() {
        ArrayNode arr = Json.arr();
        for (Registered r : list()) {
            String key = Path.of(r.root()).toAbsolutePath().normalize().toString();
            ObjectNode o = Json.obj()
                    .put("root", r.root())
                    .put("addedAt", r.addedAt());
            if (missing.contains(key)) {
                o.put("missing", true);
            }
            arr.add(o);
        }
        return Json.obj().put("defaultRoot", defaultRoot.toString()).set("workspaces", arr);
    }

    /** 注册表变化广播(各连接的 evt 频道);全断时安静跳过,前端重连后经 list 校准。 */
    private void broadcastRegistry() {
        try {
            pool.broadcastEvt("workspaces.changed", snapshot());
        } catch (RuntimeException e) {
            log.debug("workspaces.changed 广播失败(hub 未连接?): {}", e.getMessage());
        }
    }

    private void loadRegistry() {
        Path f = props.resolveDataDir().resolve("workspaces.json");
        if (!Files.isRegularFile(f)) {
            return;
        }
        try {
            JsonNode arr = Json.parse(Files.readString(f));
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    String root = n.path("root").asString("");
                    if (root.isEmpty()) {
                        continue;
                    }
                    registry.put(Path.of(root).toAbsolutePath().normalize().toString(),
                            new Registered(root, n.path("addedTs").asLong(System.currentTimeMillis())));
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("workspaces.json 读取失败,忽略注册表", e);
        }
    }

    /** 启动自检:仅校验本次从 workspaces.json 载入的条目,目录缺失即标记待处理。 */
    private void validateRegistry() {
        for (Registered r : registry.values()) {
            Path p = Path.of(r.root()).toAbsolutePath().normalize();
            if (!Files.isDirectory(p)) {
                missing.add(p.toString());
                log.warn("工作区目录不存在(可能已被移动/删除),等待用户处理: {}", r.root());
            }
        }
    }

    /** 默认工作区覆盖(纠正路径后持久化,避免下次重启按配置注册回旧路径)。 */
    private Path defaultOverrideFile() {
        return props.resolveDataDir().resolve("workspace-default.json");
    }

    private Optional<Path> readDefaultOverride() {
        Path f = defaultOverrideFile();
        if (!Files.isRegularFile(f)) {
            return Optional.empty();
        }
        try {
            String root = Json.parse(Files.readString(f)).path("root").asString("");
            if (!root.isEmpty()) {
                return Optional.of(Path.of(root).toAbsolutePath().normalize());
            }
        } catch (IOException | RuntimeException e) {
            log.warn("默认工作区覆盖读取失败,回退配置", e);
        }
        return Optional.empty();
    }

    private void writeDefaultOverride(String root) throws IOException {
        Path f = defaultOverrideFile();
        Path tmp = f.resolveSibling("workspace-default.json.tmp");
        Files.writeString(tmp, Json.write(Json.obj().put("root", root)));
        AtomicFiles.replace(tmp, f);
    }

    /** 原子写注册表(临时文件 + ATOMIC_MOVE)。 */
    private void persistRegistry() throws IOException {
        Path f = props.resolveDataDir().resolve("workspaces.json");
        Path tmp = f.resolveSibling("workspaces.json.tmp");
        List<Registered> sorted = list();
        ArrayNode arr = Json.arr();
        for (Registered r : sorted) {
            arr.add(Json.obj().put("root", r.root()).put("addedTs", r.addedAt()));
        }
        Files.writeString(tmp, Json.write(arr));
        AtomicFiles.replace(tmp, f); // 原子替换(失败已清理 tmp 后抛出,不残留垃圾)
    }
}
