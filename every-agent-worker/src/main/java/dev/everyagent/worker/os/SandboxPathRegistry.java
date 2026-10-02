package dev.everyagent.worker.os;

import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.SandboxBackend.Access;
import dev.everyagent.plugin.api.spi.SandboxBackend.MountRequest;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 路径翻译中间人 —— 管理 {宿主路径 → 沙箱内路径} 映射表。
 *
 * <p>核心组件通过此注册表做路径翻译，消费方只调核心不感知沙箱。
 * 核心调 {@link SandboxBackend#mount} 拿到映射关系后自己查表翻译。
 *
 * <p><b>两段式：意图登记 + 惰性物化</b>（架构 §7.10）：
 * <ul>
 *   <li>{@link #register} / {@link #sync} / {@link #unregister} 只登记「挂载意图」
 *       （按 owner 分组的幂等集合），<b>零 IO</b>——挂载在后端是 {@code wsl.exe} 级开销，
 *       且登记发生时（启动期）生效后端可能尚未定论；</li>
 *   <li>真正的 {@link SandboxBackend#mount} 推迟到<b>首次翻译查询</b>时批量执行，
 *       物化代次键 = (生效后端 id, 意图版本)：两者任一变化即整表重建，故后端切换
 *       （如 direct → wsl-ubuntu）不会残留恒等映射，插件热插拔亦自愈。</li>
 * </ul>
 *
 * <p>登记方按 owner 分组、谁的路径谁登记：{@code workspaces}（{@code WorkspaceManager}：
 * 在册工作区根 + 各工作区外部授权根）、{@code skills}（{@code BuiltInSkills}：系统技能目录根）。
 * 任务级单次授权（grant）不进此表——命令侧挂载清单由各沙箱插件每次执行自行推导，
 * 经此表扩挂会让 run 档授权沉淀成跨任务可读根（§7.10）。
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

    /** 宿主为 Windows 时路径大小写不敏感（前缀比较须折叠大小写）。 */
    private static final boolean CASE_INSENSITIVE =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final SandboxBackend sandbox;

    /** 挂载意图（权威源）：owner → (归一键 → 请求)。 */
    private final Map<String, Map<String, MountRequest>> intents = new ConcurrentHashMap<>();

    /** 意图版本：任何意图增删自增（物化失效判据之一）。 */
    private final AtomicLong intentVersion = new AtomicLong();

    private final Object mountLock = new Object();

    /** 上次物化时的生效后端 id（与 {@link #mountedIntentVersion} 共同构成物化代次）。 */
    private volatile String mountedBackendId;

    /** 上次物化时的意图版本。 */
    private volatile long mountedIntentVersion = -1;

    /** 物化产物（不可变、按宿主键长度倒序，便于最长前缀优先命中）。 */
    private volatile List<Entry> views = List.of();

    /** 单条映射：归一宿主键 + 原始宿主路径 + 沙箱内视图。 */
    private record Entry(String hostKey, Path hostPath, String sandboxPath) {
    }

    public SandboxPathRegistry(SandboxBackend sandbox) {
        this.sandbox = sandbox;
    }

    // ── 意图登记（零 IO） ─────────────────────────────────────

    /** 登记单个宿主路径（默认 owner、读写）。 */
    public void register(Path hostPath) {
        register(OWNER_DEFAULT, hostPath, Access.READ_WRITE);
    }

    /** 登记单个宿主路径（默认 owner）。 */
    public void register(Path hostPath, Access access) {
        register(OWNER_DEFAULT, List.of(new MountRequest(hostPath, access)));
    }

    /** 批量登记（默认 owner，追加语义）。 */
    public void register(List<MountRequest> requests) {
        register(OWNER_DEFAULT, requests);
    }

    /** 登记单个宿主路径到指定 owner。 */
    public void register(String owner, Path hostPath, Access access) {
        register(owner, List.of(new MountRequest(hostPath, access)));
    }

    /** 批量登记到指定 owner（追加语义；幂等，重复登记不产生第二次挂载）。 */
    public void register(String owner, List<MountRequest> requests) {
        if (owner == null || requests == null || requests.isEmpty()) {
            return;
        }
        Map<String, MountRequest> bucket =
                intents.computeIfAbsent(owner, k -> new ConcurrentHashMap<>());
        boolean changed = false;
        for (MountRequest req : requests) {
            if (req == null || req.hostPath() == null) {
                continue;
            }
            String key = cmp(req.hostPath().toString());
            MountRequest prev = bucket.get(key);
            // 更宽语义覆盖更窄语义（读写覆盖只读），反向不降级
            if (prev != null && prev.access() == Access.READ_WRITE
                    && req.access() == Access.READ_ONLY) {
                continue;
            }
            if (!sameMapping(prev, req)) {
                bucket.put(key, req);
                changed = true;
            }
        }
        if (changed) {
            intentVersion.incrementAndGet();
        }
    }

    /**
     * 覆盖式对齐某 owner 的全部意图（集合会变的登记方用，如工作区注册表变更）：
     * 新集合之外的旧意图自动撤销。
     */
    public void sync(String owner, List<MountRequest> requests) {
        Map<String, MountRequest> next = new ConcurrentHashMap<>();
        registerInto(next, requests);
        Map<String, MountRequest> prev = intents.put(owner, next);
        if (prev == null || !sameSet(prev, next)) {
            intentVersion.incrementAndGet();
        }
    }

    /** 撤销某 owner 下单条意图。 */
    public void unregister(String owner, Path hostPath) {
        Map<String, MountRequest> bucket = intents.get(owner);
        if (bucket != null && bucket.remove(cmp(hostPath.toString())) != null) {
            intentVersion.incrementAndGet();
        }
    }

    /** 撤销某 owner 的全部意图。 */
    public void unregisterOwner(String owner) {
        if (intents.remove(owner) != null) {
            intentVersion.incrementAndGet();
        }
    }

    /**
     * 工作区被删除时通知沙箱清理 + 从意图表与映射表移除该根。
     * 未登记的根同样安全（no-op）。
     */
    public void onWorkspaceRemoved(Path root) {
        boolean had = false;
        for (Map<String, MountRequest> bucket : intents.values()) {
            had |= bucket.remove(cmp(root.toString())) != null;
        }
        if (had) {
            intentVersion.incrementAndGet();
        }
        try {
            sandbox.onWorkspaceRemoved(root);
        } catch (RuntimeException e) {
            log.warn("[path-registry] onWorkspaceRemoved 失败: {}", e.getMessage());
        }
    }

    // ── 翻译 ─────────────────────────────────────────────────

    /**
     * 宿主路径 → AI 可见路径；命中登记根（或其子路径）时按最长前缀推导，
     * 无映射则原样返回（注册表外 / DIRECT 场景）。
     */
    public String toSandboxPath(Path hostPath) {
        if (hostPath == null) {
            return null;
        }
        String raw = hostPath.toString();
        ensureMaterialized();
        Entry best = longestHostMatch(cmp(raw));
        return best == null ? raw : best.sandboxPath() + tailOf(raw, best.hostKey());
    }

    /**
     * AI 视角路径 → 宿主路径；命中登记视图（或其子路径）时按最长前缀反推，
     * 无映射返回 null（注册表外路径，调用方原样交给 Java NIO 自然报错）。
     */
    public String toHostPath(String sandboxPath) {
        if (sandboxPath == null || sandboxPath.isBlank()) {
            return null;
        }
        ensureMaterialized();
        String key = cmp(sandboxPath);
        Entry best = null;
        String bestViewKey = null;
        for (Entry e : views) {
            String view = cmp(e.sandboxPath());
            if ((key.equals(view) || key.startsWith(view + "/"))
                    && (bestViewKey == null || view.length() > bestViewKey.length())) {
                best = e;
                bestViewKey = view;
            }
        }
        return best == null ? null : joinHost(best.hostPath().toString(),
                tailOf(sandboxPath, bestViewKey));
    }

    // ── 物化 ─────────────────────────────────────────────────

    /**
     * 按「生效后端 id + 意图版本」惰性物化：任一与上次物化不符，就把全部意图一次
     * 批量交给 {@link SandboxBackend#mount} 并整表重建。
     *
     * <p>mount 失败（后端不可用/超时）只 WARN 并留空表——翻译退化为原路径，
     * 与 DIRECT 语义一致，不阻断任务线程。
     */
    private void ensureMaterialized() {
        String backendId = sandbox.id();
        long version = intentVersion.get();
        if (backendId.equals(mountedBackendId) && version == mountedIntentVersion) {
            return;
        }
        synchronized (mountLock) {
            if (backendId.equals(mountedBackendId) && version == mountedIntentVersion) {
                return;
            }
            // 跨 owner 去重:同一路径被多个 owner 登记时只挂一次,读写语义覆盖只读
            Map<String, MountRequest> merged = new LinkedHashMap<>();
            for (Map<String, MountRequest> bucket : intents.values()) {
                for (MountRequest req : bucket.values()) {
                    String key = cmp(req.hostPath().toString());
                    MountRequest prev = merged.get(key);
                    if (prev != null && prev.access() == Access.READ_WRITE
                            && req.access() == Access.READ_ONLY) {
                        continue;
                    }
                    merged.put(key, req);
                }
            }
            List<MountRequest> all = new ArrayList<>(merged.values());
            Map<Path, String> mounted = new LinkedHashMap<>();
            if (!all.isEmpty()) {
                try {
                    mounted.putAll(sandbox.mount(all));
                } catch (RuntimeException e) {
                    log.warn("[path-registry] mount 失败,路径翻译退化为原路径: {}", e.toString());
                }
            }
            List<Entry> built = new ArrayList<>(mounted.size());
            for (Map.Entry<Path, String> m : mounted.entrySet()) {
                if (m.getKey() == null || m.getValue() == null) {
                    continue;
                }
                built.add(new Entry(cmp(m.getKey().toString()), m.getKey(), m.getValue()));
            }
            built.sort(Comparator.comparingInt((Entry e) -> e.hostKey().length()).reversed());
            views = List.copyOf(built);
            mountedBackendId = backendId;
            mountedIntentVersion = version;
            if (!built.isEmpty()) {
                log.info("[path-registry] 映射表物化 backend={} 意图={} 映射={} {}",
                        backendId, all.size(), built.size(),
                        built.stream().map(e -> e.hostPath() + "→" + e.sandboxPath()).toList());
            }
        }
    }

    /** 最长宿主前缀匹配（键已归一）。 */
    private Entry longestHostMatch(String hostKey) {
        Entry best = null;
        int bestLen = -1;
        for (Entry e : views) {
            if (hostKey.equals(e.hostKey()) || hostKey.startsWith(e.hostKey() + "/")) {
                if (e.hostKey().length() > bestLen) {
                    bestLen = e.hostKey().length();
                    best = e;
                }
            }
        }
        return best;
    }

    // ── 归一/拼接工具 ─────────────────────────────────────────

    private static void registerInto(Map<String, MountRequest> target, List<MountRequest> requests) {
        for (MountRequest req : requests == null ? List.<MountRequest>of() : requests) {
            if (req == null || req.hostPath() == null) {
                continue;
            }
            String key = cmp(req.hostPath().toString());
            MountRequest prev = target.get(key);
            if (prev != null && prev.access() == Access.READ_WRITE
                    && req.access() == Access.READ_ONLY) {
                continue;
            }
            target.put(key, req);
        }
    }

    /** 分隔符归一 + 去尾斜杠（保留原始大小写）。 */
    private static String norm(String p) {
        String s = p.replace('\\', '/');
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 比较键：{@link #norm} + Windows 下折叠大小写（长度与 norm 一致，可按长度切分）。 */
    private static String cmp(String p) {
        String s = norm(p);
        return CASE_INSENSITIVE ? s.toLowerCase(Locale.ROOT) : s;
    }

    /** raw 相对已归一根键的尾部（以 '/' 开头；相等时为空串）。 */
    private static String tailOf(String raw, String rootKey) {
        String n = norm(raw);
        if (n.length() <= rootKey.length()) {
            return "";
        }
        String tail = n.substring(rootKey.length());
        return tail.startsWith("/") ? tail : "";
    }

    /** 宿主根 + '/' 形态尾部 → 宿主路径字符串（宿主分隔符、已规范化）。 */
    private static String joinHost(String hostRoot, String tail) {
        if (tail == null || tail.isEmpty()) {
            return Path.of(hostRoot).normalize().toString();
        }
        String rel = tail.replace('/', File.separatorChar);
        if (rel.startsWith(File.separator)) {
            rel = rel.substring(1); // 防止 resolve 把前导分隔符当绝对路径(Windows 会丢盘符)
        }
        return Path.of(hostRoot).resolve(rel).normalize().toString();
    }

    private static boolean sameMapping(MountRequest a, MountRequest b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.access() == b.access()
                && cmp(a.hostPath().toString()).equals(cmp(b.hostPath().toString()));
    }

    private static boolean sameSet(Map<String, MountRequest> a, Map<String, MountRequest> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (Map.Entry<String, MountRequest> e : a.entrySet()) {
            MountRequest other = b.get(e.getKey());
            if (other == null || other.access() != e.getValue().access()) {
                return false;
            }
        }
        return true;
    }
}

