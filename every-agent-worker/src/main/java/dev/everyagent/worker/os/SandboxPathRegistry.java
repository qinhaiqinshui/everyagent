package dev.everyagent.worker.os;

import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.SandboxBackend.Access;
import dev.everyagent.plugin.api.spi.SandboxBackend.PathGrant;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 「谁想要哪些沙箱可访问路径」的账本 + 向沙箱下发的薄适配（架构 §7.8 / §7.10）。
 *
 * <p><b>职责</b>（重构后收窄为两件事）：
 * <ol>
 *   <li><b>聚合账本</b>：按 owner 分组登记「期望可访问的宿主路径」（幂等集合），
 *       跨 owner 去重、读写语义覆盖只读；</li>
 *   <li><b>下发适配</b>：账本变化时把<b>并集差量</b>交给
 *       {@link SandboxBackend#grant} / {@link SandboxBackend#revoke}——
 *       沙箱侧只见到路径，<b>不知道</b>这些路径来自工作区还是技能目录。</li>
 * </ol>
 *
 * <p><b>路径翻译不再由本类实现</b>：{@link #toSandboxPath}/{@link #toHostPath} 直接转发
 * 沙箱的纯查询 {@link SandboxBackend#toSandbox}/{@link SandboxBackend#toHost}——
 * 翻译是沙箱世界的属性（如 wsl 的挂载点），默认恒等。本类因此不再持有映射表。
 *
 * <p><b>下发时机</b>：记账事件（register/sync/unregister/onWorkspaceRemoved）与首次翻译
 * 查询都会触发 {@link #reconcile()}。后者保证<b>启动期时序</b>安全：插件注册晚于本组件
 * 初始化（§7.10 时序红线），故早期下发可能落到「尚无生效后端」，随后首次使用时按
 * 后端 id 变化整批重放。
 */
@Component
public class SandboxPathRegistry {

    /** 默认 owner（兼容无 owner 的单点登记）。 */
    public static final String OWNER_DEFAULT = "default";

    /** 工作区根 + 外部授权根（{@code WorkspaceManager} 覆盖式维护）。 */
    public static final String OWNER_WORKSPACES = "workspaces";

    /** 系统技能目录根（{@code BuiltInSkills} 维护）。 */
    public static final String OWNER_SKILLS = "skills";

    private static final Logger log = LoggerFactory.getLogger(SandboxPathRegistry.class);

    /** 宿主为 Windows 时路径大小写不敏感（归一键须折叠大小写）。 */
    private static final boolean CASE_INSENSITIVE =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final SandboxBackend sandbox;

    /** 账本（权威源）：owner → (归一键 → 期望授权)。 */
    private final Map<String, Map<String, PathGrant>> ledger = new ConcurrentHashMap<>();

    private final Object pushLock = new Object();

    /** 已成功下发的并集（归一键 → 授权）。 */
    private volatile Map<String, PathGrant> pushed = Map.of();

    /** 上次下发时的生效后端 id（变化即整批重放；null = 尚未下发过）。 */
    private volatile String pushedBackendId;

    public SandboxPathRegistry(SandboxBackend sandbox) {
        this.sandbox = sandbox;
    }

    // ── 记账（零 IO；变化后无感地下发） ─────────────────────────

    /** 登记单个宿主路径（默认 owner、读写）。 */
    public void register(Path hostPath) {
        register(OWNER_DEFAULT, hostPath, Access.READ_WRITE);
    }

    /** 登记单个宿主路径（默认 owner）。 */
    public void register(Path hostPath, Access access) {
        register(OWNER_DEFAULT, List.of(new PathGrant(hostPath, access)));
    }

    /** 批量登记（默认 owner，追加语义）。 */
    public void register(List<PathGrant> grants) {
        register(OWNER_DEFAULT, grants);
    }

    /** 登记单个宿主路径到指定 owner。 */
    public void register(String owner, Path hostPath, Access access) {
        register(owner, List.of(new PathGrant(hostPath, access)));
    }

    /** 批量登记到指定 owner（追加语义；幂等，重复登记不产生第二次下发）。 */
    public void register(String owner, List<PathGrant> grants) {
        if (owner == null || grants == null || grants.isEmpty()) {
            return;
        }
        Map<String, PathGrant> bucket = ledger.computeIfAbsent(owner, k -> new ConcurrentHashMap<>());
        boolean changed = false;
        for (PathGrant g : grants) {
            if (g == null || g.hostPath() == null) {
                continue;
            }
            if (putWidest(bucket, g)) {
                changed = true;
            }
        }
        if (changed) {
            reconcile();
        }
    }

    /**
     * 覆盖式对齐某 owner 的全部期望集合（集合会变的登记方用，如工作区注册表变更）：
     * 新集合之外的旧路径自动撤销。
     */
    public void sync(String owner, List<PathGrant> grants) {
        Map<String, PathGrant> next = new ConcurrentHashMap<>();
        for (PathGrant g : grants == null ? List.<PathGrant>of() : grants) {
            if (g != null && g.hostPath() != null) {
                putWidest(next, g);
            }
        }
        Map<String, PathGrant> prev = ledger.put(owner, next);
        if (prev == null || !sameSet(prev, next)) {
            reconcile();
        }
    }

    /** 撤销某 owner 下单条期望。 */
    public void unregister(String owner, Path hostPath) {
        Map<String, PathGrant> bucket = ledger.get(owner);
        if (bucket != null && bucket.remove(key(hostPath)) != null) {
            reconcile();
        }
    }

    /** 撤销某 owner 的全部期望。 */
    public void unregisterOwner(String owner) {
        if (ledger.remove(owner) != null) {
            reconcile();
        }
    }

    /**
     * 工作区被删除时从账本移除该根；若再无任何 owner 期望它，则触发回收。
     * 未登记的根同样安全（no-op）。
     */
    public void onWorkspaceRemoved(Path root) {
        String k = key(root);
        boolean had = false;
        for (Map<String, PathGrant> bucket : ledger.values()) {
            had |= bucket.remove(k) != null;
        }
        if (had) {
            reconcile();
        }
    }

    // ── 翻译（转发沙箱纯查询） ─────────────────────────────────

    /**
     * 宿主路径 → AI 可见路径；由沙箱决定（如 wsl 后端映射到挂载点，DIRECT 恒等）。
     * 后端抛异常时退化为原路径，不阻断工具线程。
     */
    public String toSandboxPath(Path hostPath) {
        if (hostPath == null) {
            return null;
        }
        ensurePushed();
        try {
            String view = sandbox.toSandbox(hostPath);
            return view == null ? hostPath.toString() : view;
        } catch (RuntimeException e) {
            log.warn("[path-registry] 沙箱路径翻译失败,退化为原路径: {}", e.toString());
            return hostPath.toString();
        }
    }

    /**
     * AI 视角路径 → 宿主路径；由沙箱反推，无映射返回 null
     * （调用方原样交给 Java NIO 自然报错）。
     */
    public String toHostPath(String sandboxPath) {
        if (sandboxPath == null || sandboxPath.isBlank()) {
            return null;
        }
        ensurePushed();
        try {
            Path host = sandbox.toHost(sandboxPath);
            return host == null ? null : host.toString();
        } catch (RuntimeException e) {
            log.warn("[path-registry] 沙箱路径反解失败: {}", e.toString());
            return null;
        }
    }

    // ── 下发 ─────────────────────────────────────────────────

    /** 首次翻译查询时的兜底下发（覆盖「账本早已登记、但下发时后端尚未就绪」的时序）。 */
    private void ensurePushed() {
        if (backendId().equals(pushedBackendId)) {
            return;
        }
        reconcile();
    }

    /**
     * 把账本并集与「已下发集合」对账：只发差量（新增 / 权限提升 → {@code grant}，
     * 已无人期望 → {@code revoke}）；后端 id 变化则整批重放。
     *
     * <p>下发失败只 WARN 并保留旧快照，下次记账 / 翻译时自然重试——
     * 不阻断任务线程（§7.10）。
     */
    private void reconcile() {
        String backendId = backendId();
        Map<String, PathGrant> merged = merge();
        synchronized (pushLock) {
            boolean backendChanged = !backendId.equals(pushedBackendId);
            Map<String, PathGrant> prev = backendChanged ? Map.of() : pushed;

            List<PathGrant> toGrant = new ArrayList<>();
            for (Map.Entry<String, PathGrant> e : merged.entrySet()) {
                PathGrant old = prev.get(e.getKey());
                if (old == null || old.access() != e.getValue().access()) {
                    toGrant.add(e.getValue());
                }
            }
            List<Path> toRevoke = new ArrayList<>();
            for (Map.Entry<String, PathGrant> e : prev.entrySet()) {
                if (!merged.containsKey(e.getKey())) {
                    toRevoke.add(e.getValue().hostPath());
                }
            }
            if (toGrant.isEmpty() && toRevoke.isEmpty()) {
                pushedBackendId = backendId;
                pushed = merged;
                return;
            }
            try {
                if (!toRevoke.isEmpty()) {
                    sandbox.revoke(toRevoke);
                }
                if (!toGrant.isEmpty()) {
                    sandbox.grant(toGrant);
                }
            } catch (RuntimeException e) {
                log.warn("[path-registry] 沙箱授权下发失败,保留旧快照待重试: {}", e.toString());
                return;
            }
            pushed = merged;
            pushedBackendId = backendId;
            log.debug("[path-registry] 已下发 backend={} 授权根={} 本次 grant={} revoke={}",
                    backendId, merged.size(), toGrant.size(), toRevoke.size());
        }
    }

    /** 跨 owner 合并去重：同路径取最宽语义（读写覆盖只读）。 */
    private Map<String, PathGrant> merge() {
        Map<String, PathGrant> merged = new LinkedHashMap<>();
        for (Map<String, PathGrant> bucket : ledger.values()) {
            for (Map.Entry<String, PathGrant> e : bucket.entrySet()) {
                PathGrant prev = merged.get(e.getKey());
                if (prev == null || (prev.access() == Access.READ_ONLY
                        && e.getValue().access() == Access.READ_WRITE)) {
                    merged.put(e.getKey(), e.getValue());
                }
            }
        }
        return merged;
    }

    /** 生效后端 id；探测失败退化为 "direct"（与 DIRECT 默认语义一致）。 */
    private String backendId() {
        try {
            String id = sandbox.id();
            return id == null ? "direct" : id;
        } catch (RuntimeException e) {
            return "direct";
        }
    }

    // ── 归一/比较工具 ─────────────────────────────────────────

    /** 写入并返回是否产生变化（读写语义覆盖只读，反向不降级）。 */
    private static boolean putWidest(Map<String, PathGrant> bucket, PathGrant g) {
        String k = key(g.hostPath());
        PathGrant prev = bucket.get(k);
        if (prev == null) {
            bucket.put(k, g);
            return true;
        }
        if (prev.access() == g.access()) {
            return false;
        }
        if (prev.access() == Access.READ_ONLY && g.access() == Access.READ_WRITE) {
            bucket.put(k, g);
            return true;
        }
        return false;
    }

    /** 分隔符归一 + 去尾斜杠（保留原始大小写）。 */
    private static String norm(String p) {
        String s = p.replace('\\', '/');
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 比较键：{@link #norm} + Windows 下折叠大小写。 */
    private static String key(Path p) {
        String s = norm(p.toString());
        return CASE_INSENSITIVE ? s.toLowerCase(Locale.ROOT) : s;
    }

    private static boolean sameSet(Map<String, PathGrant> a, Map<String, PathGrant> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (Map.Entry<String, PathGrant> e : a.entrySet()) {
            PathGrant other = b.get(e.getKey());
            if (other == null || other.access() != e.getValue().access()) {
                return false;
            }
        }
        return true;
    }
}