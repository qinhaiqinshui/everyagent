package dev.everyagent.worker.tools.permission;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.permission.AuthorizationHandler;
import dev.everyagent.plugin.api.util.AtomicFiles;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.plugin.registry.AuthorizationHandlerRegistry;
import dev.everyagent.plugin.api.exception.AgentCancelledException;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationRequest;
import dev.everyagent.worker.tools.PermissionDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 授权状态 + 授权决议链宿主(架构 §5.5,自 PermissionGate 拆分):
 * 维护 per-task 授权状态(run/task 两档、inFlight 去重、grants.json 持久化),授权决议
 * 沿 AuthorizationHandler 责任链遍历(按 order 排序),任一环节给出 ALLOW/DENY 即收口,
 * 全部 PASS 则直接放行;拒绝/超时抛 {@link PermissionDeniedException} 回灌模型
 * (agent 循环不中断)。
 *
 * <p>主/子 agent 按 taskId 共享授权;并发同 grantKey 只弹一张卡(inFlight future 去重,
 * 后来者 join 共享结论)。授权两档:run(内存,下一条用户输入清)/ task(grants.json,随任务删除)。
 *
 * <p>核心不感知任何具体 handler 节点(如 AI 审议、无人值守等),只遍历 handler 列表。
 * handler 的注册与排序由 Spring 自动收集 + {@link AuthorizationHandler#order()} 完成。
 */
@Component
public class GrantRegistry {

    private static final Logger log = LoggerFactory.getLogger(GrantRegistry.class);

    private final InteractionService asks;
    private final WorkerProperties props;
    private final WorkspaceManager workspaces;
    /** 授权决议链节点注册表(按 order 排序);零节点 → 直接放行。 */
    private final AuthorizationHandlerRegistry authHandlerRegistry;

    private final Map<String, TaskGrants> byTask = new ConcurrentHashMap<>();

    public GrantRegistry(InteractionService asks, WorkerProperties props, WorkspaceManager workspaces,
            AuthorizationHandlerRegistry authHandlerRegistry) {
        this.asks = asks;
        this.props = props;
        this.workspaces = workspaces;
        this.authHandlerRegistry = authHandlerRegistry;
    }

    // ---- 生命周期 ---- 

    /** 新一条用户输入到达:本轮(run)授权即失效(任务级不受影响)。subjectId=执行主体 ID(今天=taskId)。 */
    public void beginRun(String subjectId) {
        TaskGrants g = byTask.get(subjectId);
        if (g != null) {
            g.runGrants.clear();
            g.runRoots.clear();
            g.runExecRoots.clear();
        }
    }

    /** 主体终态:内存驱逐(任务级授权已在磁盘,再运行时 lazy 重载)。subjectId=执行主体 ID(今天=taskId)。 */
    public void untrack(String subjectId) {
        byTask.remove(subjectId);
    }

    /** 已授权外部根(realpath + 词法形态),供 Sandbox 附加放行。subjectId=执行主体 ID(今天=taskId)。 */
    public List<Path> extraRoots(String subjectId) {
        TaskGrants g = byTask.get(subjectId);
        if (g == null) {
            return List.of();
        }
        List<Path> out = new ArrayList<>(g.taskRoots.size() + g.runRoots.size());
        out.addAll(g.taskRoots);
        out.addAll(g.runRoots);
        return out;
    }

    /**
     * 全部在途主体的已授权外部根并集(任务级 + 本轮级),供前端只读 RPC 沙箱
     * ({@code FsService.readSandbox})放行「AI 本任务经授权读过的文件」——用户点开
     * 工具调用里的工作区外路径 chip 时与 read_file 同源可读;主体驱逐(gate.evict,
     * 任务收口)后自然失效,授权生命周期跟随任务。前端写沙箱不并入(不因此获得越界写)。
     */
    public List<Path> allExtraRoots() {
        if (byTask.isEmpty()) {
            return List.of();
        }
        List<Path> out = new ArrayList<>();
        for (TaskGrants g : byTask.values()) {
            out.addAll(g.taskRoots);
            out.addAll(g.runRoots);
        }
        return out;
    }

    /** 已授权的命令 EXEC 根(realpath),供命令执行器做 Windows Low 完整性标注(§13.6)。subjectId=执行主体 ID(今天=taskId)。 */
    public List<Path> execRoots(String subjectId) {
        TaskGrants g = byTask.get(subjectId);
        if (g == null) {
            return List.of();
        }
        List<Path> out = new ArrayList<>(g.taskExecRoots.size() + g.runExecRoots.size());
        out.addAll(g.taskExecRoots);
        out.addAll(g.runExecRoots);
        return out;
    }

    /**
     * EXEC 根的<b>安全过滤视图</b>(§13.3 修复 B L2,消费层防御纵深):按
     * {@link OverBroadRootCheck#isOverBroadRoot} 过滤后返回,过度宽泛根(盘根/工作区祖先)
     * 一律拒收——含历史 grants.json 载入的旧宽根(修复前落盘的 {@code C:\} 等)。
     * 命令执行器两后端共用:windows-mic 侧不进 Low 标注/ACL,wsl-bwrap 侧不进 --bind
     * 白名单(否则一个 C:\ 会把整个 /mnt/c 以读写挂进沙箱,读隔离被击穿)。
     *
     * <p>外部授权根并入(§7.17):该工作区的 externalRoots(用户显式选择=已授权,
     * 完全读写)同样进入本视图——注册时已过宽根滤过,这里按同一谓词再滤一遍(纵深),
     * 与已授权 EXEC 根去重后拼接。
     */
    public List<Path> execRootsSandboxed(String workspaceRoot, String subjectId) {
        Path wsLex = null;
        Path wsReal = null;
        try {
            WorkspaceManager.Root ws = workspaces.resolve(workspaceRoot);
            wsLex = ws.path();
            wsReal = ws.realPath();
        } catch (IOException e) {
            log.warn("[gate] 工作区解析失败,EXEC 根过滤退化为仅文件系统根判定 subject={}", subjectId, e);
        }
        List<Path> out = new ArrayList<>();
        for (Path root : execRoots(subjectId)) {
            if (OverBroadRootCheck.isOverBroadRoot(root, wsLex, wsReal)) {
                log.warn("[gate] L2 拒收过度宽泛 EXEC 根(不进沙箱/标注/ACL)subject={} root={}",
                        subjectId, root);
                continue;
            }
            out.add(root);
        }
        for (Path root : workspaces.externalRootsOf(workspaceRoot)) {
            if (out.contains(root)) {
                continue; // 与已授权 EXEC 根重叠:去重
            }
            if (OverBroadRootCheck.isOverBroadRoot(root, wsLex, wsReal)) {
                log.warn("[gate] L2 拒收过度宽泛外部授权根(不进沙箱/标注/ACL)subject={} root={}",
                        subjectId, root);
                continue;
            }
            out.add(root);
        }
        return out;
    }

    // ---- 授权决议链(AuthorizationHandler 责任链遍历) ----

    /**
     * 授权决议入口:grant 已存在直接放行;否则沿决议链遍历 authHandlers(阻塞虚拟线程):
     * 第一个 applies 的 handler 返回 ALLOW → 自动授权(RUN 档);DENY → 抛
     * {@link PermissionDeniedException};全部 PASS 或零节点 → 直接放行(RUN 档)。
     * rootsOnGrant 为该授权随附的 Sandbox 附加根;execRootsOnGrant 为命令 EXEC 授权随附的
     * Low 完整性标注根。
     */
    public void authorize(AuthorizationRequest req, List<Path> rootsOnGrant, List<Path> execRootsOnGrant) {
        ExecContext ctx = req.context();
        String subjectId = ctx.subjectId(); // 授权状态分区键(今天=taskId,未来=workflowId)
        Path dataDir = ctx.dataDir();       // grants.json 落盘目录(ExecContext 数据目录槽位)
        String grantKey = req.grantKey();
        TaskGrants g = grantsOf(subjectId, dataDir);
        if (g.hasGrant(grantKey)) {
            return;
        }
        CompletableFuture<GrantScope> future;
        boolean owner;
        synchronized (g) {
            if (g.hasGrant(grantKey)) {
                return; // 并发窗口内刚被授权
            }
            future = g.inFlight.get(grantKey);
            if (future == null) {
                future = new CompletableFuture<>();
                g.inFlight.put(grantKey, future);
                owner = true;
            } else {
                owner = false; // 已有同 key 弹窗挂起:共享其结果,不再弹第二张卡
            }
        }
        if (!owner) {
            if (join(future) == GrantScope.DENY) {
                throw denyException();
            }
            return; // 授权者已记录,后来者直接放行
        }
        try {
            GrantScope scope = resolveScope(req);
            future.complete(scope);
            record(dataDir, g, grantKey, scope, rootsOnGrant, execRootsOnGrant);
        } catch (Throwable e) {
            future.complete(GrantScope.DENY); // 分派异常结束(审议/弹窗):后来者按拒绝处理
            throw e;
        } finally {
            g.inFlight.remove(grantKey);
        }
    }

    private GrantScope join(CompletableFuture<GrantScope> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentCancelledException("task cancelled");
        } catch (java.util.concurrent.ExecutionException e) {
            return GrantScope.DENY; // 不可达(future 只 complete 正常值),安全缺省
        }
    }

    /**
     * 授权决议链(责任链遍历):通过 {@link AuthorizationChainExecutor} 折叠 authHandlers
     * (按 order 排序)为嵌套链;任一节点返回 ALLOW → 放行(RUN 档);DENY → 抛
     * {@link PermissionDeniedException};全部 PASS 或零节点 → 链尾兜底放行(RUN 档)。
     * 核心不感知任何具体节点(AI 审议、无人值守、人工弹窗等均由各 handler 自行判断)。
     * future.complete 与 record 由 {@link #authorize} owner 路径统一执行,后来者 join 共享同一结论。
     */
    private GrantScope resolveScope(AuthorizationRequest req) {
        AuthorizationHandler.AuthorizationDecision d = new AuthorizationChainExecutor()
                .run(authHandlerRegistry.sorted(), req);
        return switch (d.type()) {
            case ALLOW, PASS -> GrantScope.RUN;
            case DENY -> throw denyException();
        };
    }

    /** 宽容解析:稳定 token 优先,退文案关键词;未识别按拒绝(安全缺省,兼容任意回传文本)。 */
    public static GrantScope parseScope(String answer) {
        String a = answer == null ? "" : answer.trim().toLowerCase();
        if (a.equals("run") || a.contains("本轮")) {
            return GrantScope.RUN;
        }
        if (a.equals("task") || a.contains("本任务")) {
            return GrantScope.TASK;
        }
        return GrantScope.DENY;
    }

    /** 按档位记录授权(DENY 抛 PermissionDeniedException)。dataDir=grants.json 落盘目录(ExecContext.dataDir)。 */
    private void record(Path dataDir, TaskGrants g, String grantKey, GrantScope scope,
            List<Path> rootsOnGrant, List<Path> execRootsOnGrant) {
        switch (scope) {
            case RUN -> {
                g.runGrants.add(grantKey);
                g.runRoots.addAll(rootsOnGrant);
                g.runExecRoots.addAll(execRootsOnGrant);
            }
            case TASK -> {
                g.taskGrants.add(grantKey);
                g.taskRoots.addAll(rootsOnGrant);
                g.taskExecRoots.addAll(execRootsOnGrant);
                persistGrants(dataDir, g);
            }
            case DENY -> throw denyException();
        }
    }

    /** 拒绝/超时统一以简洁「拒绝」回灌模型(不带授权请求内容,模型据此自行调整方案)。 */
    private static PermissionDeniedException denyException() {
        return new PermissionDeniedException("拒绝");
    }

    // ---- per-task 授权状态与持久化 ----

    /** per-task 授权状态(主/子 agent 共享;run 档内存,task 档内存+磁盘镜像)。 */
    private static final class TaskGrants {
        final Set<String> runGrants = ConcurrentHashMap.newKeySet();
        final Set<String> taskGrants = ConcurrentHashMap.newKeySet();
        /** run/task 档各自的外部根(Sandbox 附加放行用)。 */
        final Set<Path> runRoots = ConcurrentHashMap.newKeySet();
        final Set<Path> taskRoots = ConcurrentHashMap.newKeySet();
        /** run/task 档各自的命令 EXEC 根(Windows Low 完整性标注用,§13.6)。 */
        final Set<Path> runExecRoots = ConcurrentHashMap.newKeySet();
        final Set<Path> taskExecRoots = ConcurrentHashMap.newKeySet();
        final Map<String, CompletableFuture<GrantScope>> inFlight = new ConcurrentHashMap<>();
        volatile boolean diskLoaded;

        boolean hasGrant(String key) {
            return runGrants.contains(key) || taskGrants.contains(key);
        }
    }

    private TaskGrants grantsOf(String subjectId, Path dataDir) {
        TaskGrants g = byTask.computeIfAbsent(subjectId, k -> new TaskGrants());
        if (!g.diskLoaded) {
            synchronized (g) {
                if (!g.diskLoaded) {
                    loadDiskGrants(subjectId, dataDir, g);
                    g.diskLoaded = true;
                }
            }
        }
        return g;
    }

    /** 任务级授权 lazy 载入(首次过 gate 时;startRerun 冷启动后自然恢复)。dataDir 来自 ExecContext 槽位。 */
    private void loadDiskGrants(String subjectId, Path dataDir, TaskGrants g) {
        if (dataDir == null) {
            return; // 上下文未携带数据目录(单测直构场景):按无磁盘授权处理
        }
        try {
            Path f = dataDir.resolve("grants.json");
            if (!Files.isRegularFile(f)) {
                return;
            }
            JsonNode root = Json.parse(Files.readString(f));
            for (JsonNode k : root.path("taskGrants")) {
                String key = k.asString("");
                if (!key.isEmpty()) {
                    g.taskGrants.add(key);
                }
            }
            for (JsonNode p : root.path("extraRoots")) {
                String s = p.asString("");
                if (!s.isEmpty()) {
                    try {
                        g.taskRoots.add(Path.of(s));
                    } catch (RuntimeException ignore) {
                        // 跨平台路径形态不兼容等:忽略该根(不影响 grant key 判定)
                    }
                }
            }
            for (JsonNode p : root.path("execRoots")) {
                String s = p.asString("");
                if (!s.isEmpty()) {
                    try {
                        g.taskExecRoots.add(Path.of(s));
                    } catch (RuntimeException ignore) {
                        // 同上:形态不兼容忽略
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("任务级授权读取失败 subject={}(按无授权处理)", subjectId, e);
        }
    }

    /** 任务级授权落盘(tmp + ATOMIC_MOVE;仅用户点「本任务」时发生,频率极低)。dataDir 来自 ExecContext 槽位。 */
    private void persistGrants(Path dataDir, TaskGrants g) {
        if (dataDir == null) {
            log.warn("任务级授权落盘失败(上下文未携带数据目录,继续内存生效)");
            return;
        }
        try {
            Path dir = dataDir;
            Files.createDirectories(dir);
            ObjectNode root = Json.obj().put("version", 1);
            ArrayNode grants = root.putArray("taskGrants");
            g.taskGrants.forEach(grants::add);
            ArrayNode roots = root.putArray("extraRoots");
            for (Path p : g.taskRoots) {
                roots.add(p.toString());
            }
            ArrayNode execRoots = root.putArray("execRoots");
            for (Path p : g.taskExecRoots) {
                execRoots.add(p.toString());
            }
            Path f = dir.resolve("grants.json");
            Path tmp = dir.resolve("grants.json.tmp");
            Files.writeString(tmp, Json.write(root));
            AtomicFiles.replace(tmp, f); // 原子替换(失败已清理 tmp 后抛出,不残留垃圾)
        } catch (IOException e) {
            log.warn("任务级授权落盘失败 dir={}(继续内存生效)", dataDir, e);
        }
    }
}