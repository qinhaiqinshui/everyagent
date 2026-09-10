package dev.everyagent.worker.tools.permission;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.AtomicFiles;
import dev.everyagent.worker.authreview.AiAuthReviewer;
import dev.everyagent.worker.authreview.ReviewDecision;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.task.AgentCancelledException;
import dev.everyagent.worker.task.PendingAsks;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskStore;
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
 * 维护 per-task 授权状态(run/task 两档、inFlight 去重、grants.json 持久化),
 * 并提供「授权决议链」——同一请求沿 AI 审议 → 无人值守 → 人工弹窗 三条独立环节
 * 顺序判定,任一环节给出 ALLOW/DENY 即收口,全部无法处理(SKIP)才落到下一环节;
 * 拒绝/超时抛 {@link PermissionDeniedException} 回灌模型(agent 循环不中断)。
 *
 * <p>主/子 agent 按 taskId 共享授权;并发同 grantKey 只弹一张卡(inFlight future 去重,
 * 后来者 join 共享结论)。授权两档:run(内存,下一条用户输入清)/ task(grants.json,随任务删除)。
 */
@Component
public class GrantRegistry {

    /** 授权弹窗三选项文案(前端按文案/稳定 token 均可回传,见 parseScope)。 */
    static final List<String> AUTHORIZE_OPTIONS = List.of("本轮运行内允许", "本任务全程允许", "拒绝");

    private static final Logger log = LoggerFactory.getLogger(GrantRegistry.class);

    private final PendingAsks asks;
    private final WorkerProperties props;
    private final WorkspaceManager workspaces;
    private final TaskStore store;
    /** AI 安全审议器;判空兜底:未注入时回退人工弹窗。 */
    private final AiAuthReviewer aiReviewer;

    private final Map<String, TaskGrants> byTask = new ConcurrentHashMap<>();

    public GrantRegistry(PendingAsks asks, WorkerProperties props, WorkspaceManager workspaces,
            TaskStore store, AiAuthReviewer aiReviewer) {
        this.asks = asks;
        this.props = props;
        this.workspaces = workspaces;
        this.store = store;
        this.aiReviewer = aiReviewer;
    }

    // ---- 生命周期 ---- 

    /** 新一条用户输入到达:本轮(run)授权即失效(任务级不受影响)。 */
    public void beginRun(String taskId) {
        TaskGrants g = byTask.get(taskId);
        if (g != null) {
            g.runGrants.clear();
            g.runRoots.clear();
            g.runExecRoots.clear();
        }
    }

    /** 任务终态:内存驱逐(任务级授权已在磁盘,再运行时 lazy 重载)。 */
    public void untrack(String taskId) {
        byTask.remove(taskId);
    }

    /** 已授权外部根(realpath + 词法形态),供 Sandbox 附加放行。 */
    public List<Path> extraRoots(String taskId) {
        TaskGrants g = byTask.get(taskId);
        if (g == null) {
            return List.of();
        }
        List<Path> out = new ArrayList<>(g.taskRoots.size() + g.runRoots.size());
        out.addAll(g.taskRoots);
        out.addAll(g.runRoots);
        return out;
    }

    /** 已授权的命令 EXEC 根(realpath),供命令执行器做 Windows Low 完整性标注(§13.6)。 */
    public List<Path> execRoots(String taskId) {
        TaskGrants g = byTask.get(taskId);
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
    public List<Path> execRootsSandboxed(TaskEntry t) {
        Path wsLex = null;
        Path wsReal = null;
        try {
            WorkspaceManager.Root ws = workspaces.resolve(t.workspaceRoot);
            wsLex = ws.path();
            wsReal = ws.realPath();
        } catch (IOException e) {
            log.warn("[gate] 工作区解析失败,EXEC 根过滤退化为仅文件系统根判定 task={}", t.taskId, e);
        }
        List<Path> out = new ArrayList<>();
        for (Path root : execRoots(t.taskId)) {
            if (OverBroadRootCheck.isOverBroadRoot(root, wsLex, wsReal)) {
                log.warn("[gate] L2 拒收过度宽泛 EXEC 根(不进沙箱/标注/ACL)task={} root={}",
                        t.taskId, root);
                continue;
            }
            out.add(root);
        }
        for (Path root : workspaces.externalRootsOf(t.workspaceRoot)) {
            if (out.contains(root)) {
                continue; // 与已授权 EXEC 根重叠:去重
            }
            if (OverBroadRootCheck.isOverBroadRoot(root, wsLex, wsReal)) {
                log.warn("[gate] L2 拒收过度宽泛外部授权根(不进沙箱/标注/ACL)task={} root={}",
                        t.taskId, root);
                continue;
            }
            out.add(root);
        }
        return out;
    }

    // ---- 授权决议链(责任链一环:AI 审议 → 无人值守 → 人工弹窗) ----

    /**
     * 授权决议入口:grant 已存在直接放行;否则沿决议链分派(阻塞虚拟线程):
     * {@code aiReview=false}(或审议器未注入)→ 人工弹窗;{@code aiReview=true} →
     * AiAuthReviewer 审议短路(不弹窗)。拒绝/超时抛 {@link PermissionDeniedException}。
     * rootsOnGrant 为该授权随附的 Sandbox 附加根;execRootsOnGrant 为命令 EXEC 授权随附的
     * Low 完整性标注根。
     */
    public void authorize(TaskEntry t, String agentId, String grantKey, String prompt,
            List<Path> rootsOnGrant, List<Path> execRootsOnGrant) {
        TaskGrants g = grantsOf(t.taskId);
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
            GrantScope scope = resolveScope(t, agentId, prompt, grantKey);
            future.complete(scope);
            record(t, g, grantKey, scope, prompt, rootsOnGrant, execRootsOnGrant);
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
     * 授权决议链(责任链一环):三条独立环节顺序生效、互不相关——
     * ① AI 安全审议(只看任务级 {@code t.aiReview}):ALLOW → 自动授权(RUN 档)、DENY →
     * 拒绝、ESCALATE(不确定)→ 不直接拒绝、落到下一环节弹窗人工授权;审议失败且
     * review-deny-on-error=false(fallback=true)→ 同样落到下一环节(绝不因审议失败放行)。
     * ② 无人值守拦截(只看任务级 {@code t.unattended}):无人值守开启时无人工可弹,直接拒绝;
     * 未开 → ③ 人工弹窗 askUser。本方法只出「授权范围」结论;future.complete 与 record
     * 由 {@link #authorize} owner 路径统一执行,后来者 join 共享同一结论。
     */
    private GrantScope resolveScope(TaskEntry t, String agentId, String prompt, String grantKey) {
        // 环节 1:AI 安全审议(独立功能,只看 t.aiReview;未开/无审议器则跳过)
        if (usesAiReview(t)) {
            ReviewDecision d;
            try {
                d = aiReviewer.review(t, grantKey, prompt);
            } catch (RuntimeException e) {
                // 审议组件本身崩溃(不应发生):绝不静默放行,按 deny-on-error 语义对待
                log.warn("AI 审议异常 task={}(按 deny-on-error 处理)", t.taskId, e);
                d = props.getPermissions().isReviewDenyOnError()
                        ? ReviewDecision.deny("error: " + e)
                        : ReviewDecision.fallback("error: " + e);
            }
            if (!d.fallback()) {
                log.info("AI 审议结论 task={} grantKey={} verdict={} confidence={} reason={}",
                        t.taskId, grantKey, d.verdict(), d.confidence(), d.reason());
                switch (d.verdict()) {
                    case ALLOW -> {
                        return GrantScope.RUN; // 审议放行,按 RUN 档自动授权(不弹窗)
                    }
                    case DENY -> {
                        throw denyException(); // 审议拒绝:错误文本回灌模型,与人工拒绝一致
                    }
                    case ESCALATE -> {
                        // 不确定:不直接拒绝,落到下一环节弹窗人工授权(不在此拦截)
                    }
                }
            }
            // fallback=true(审议失败且 deny-on-error=false)或 ESCALATE:继续向下
        }
        // 环节 2:无人值守拦截(独立功能,只看 t.unattended)
        if (t.unattended) {
            throw denyException(); // 无人值守:无人工可弹,授权请求直接拒绝
        }
        // 环节 3:正常人工弹窗
        return askUser(t, agentId, prompt);
    }

    /** aiReview 开关开启且审议器在位才走 AI 审议;否则(含未注入兜底)维持人工弹窗。 */
    private boolean usesAiReview(TaskEntry t) {
        return t.aiReview && aiReviewer != null;
    }

    /** 发起 authorization ask 并解析答案(阻塞;timeout/cancelled 视为拒绝)。 */
    private GrantScope askUser(TaskEntry t, String agentId, String prompt) {
        // 题目 id 由 PendingAsks.ask 以真实 askId 派生,占位即可
        List<PendingAsks.AskQuestion> questions = List.of(
                new PendingAsks.AskQuestion("", prompt, AUTHORIZE_OPTIONS));
        PendingAsks.AskAnswer ans;
        try {
            ans = asks.ask(t.events, t.taskId, agentId, "authorization", questions,
                    props.getPermissions().getAuthTimeoutMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentCancelledException("task cancelled");
        }
        if (!"answered".equals(ans.status())) {
            throw denyException();
        }
        return parseScope(ans.text());
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

    /** 按档位记录授权(DENY 抛 PermissionDeniedException)。 */
    private void record(TaskEntry t, TaskGrants g, String grantKey, GrantScope scope, String prompt,
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
                persistTaskGrants(t, g);
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

    private TaskGrants grantsOf(String taskId) {
        TaskGrants g = byTask.computeIfAbsent(taskId, k -> new TaskGrants());
        if (!g.diskLoaded) {
            synchronized (g) {
                if (!g.diskLoaded) {
                    loadDiskGrants(taskId, g);
                    g.diskLoaded = true;
                }
            }
        }
        return g;
    }

    /** 任务级授权 lazy 载入(首次过 gate 时;startRerun 冷启动后自然恢复)。 */
    private void loadDiskGrants(String taskId, TaskGrants g) {
        try {
            Path f = store.dirOf(taskId).resolve("grants.json");
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
            log.warn("任务级授权读取失败 task={}(按无授权处理)", taskId, e);
        }
    }

    /** 任务级授权落盘(tmp + ATOMIC_MOVE;仅用户点「本任务」时发生,频率极低)。 */
    private void persistTaskGrants(TaskEntry t, TaskGrants g) {
        try {
            Path dir = store.dirOf(t.taskId);
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
            log.warn("任务级授权落盘失败 task={}(继续内存生效)", t.taskId, e);
        }
    }
}