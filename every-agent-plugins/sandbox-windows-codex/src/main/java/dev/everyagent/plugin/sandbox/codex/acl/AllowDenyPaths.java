package dev.everyagent.plugin.sandbox.codex.acl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * allow/deny 路径模型——对齐 codex {@code allow.rs::AllowDenyPaths} +
 * {@code compute_allow_paths_for_permissions}。
 *
 * <p>输入：写根（含只读子路径模型——codex 侧是 {@code .git/.codex/.agents/.aws}，
 * every-agent 对应 {@code .git/.everyagent/agents.md} 等，由调用方按配置传入，
 * 本类只做计算）；输出：allow（写根，须存在）/deny（只读子路径，须存在）两集合。
 *
 * <p>映射规则（对齐 allow.rs）：每个 root 经 canonicalize（失败保留原拼法）且
 * {@code exists()} 才进 allow；{@code read_only_subpaths} 原样（不做 canonicalize）
 * 且存在才进 deny。TEMP/TMP 展开对齐 {@code windows_temp_env_roots}：
 * 仅当策略含可写 Tmpdir 项时（调用方 {@code includeTempEnvRoots=true}），
 * 取 env_map 的 TEMP/TMP（缺省回退进程环境），仅绝对路径，去重。
 *
 * <p>纯逻辑、跨平台可测（java.nio 语义在 Linux/macOS 上一致）。
 */
public record AllowDenyPaths(Set<Path> allow, Set<Path> deny) {

    /** 写根条目：根 + 其下的只读子路径（carveout）。 */
    public record WritableRoot(Path root, List<Path> readOnlySubpaths) {
        public WritableRoot {
            readOnlySubpaths = List.copyOf(readOnlySubpaths);
        }
    }

    public AllowDenyPaths {
        allow = Set.copyOf(allow);
        deny = Set.copyOf(deny);
    }

    /**
     * 计算 allow/deny 两集合。
     *
     * @param writableRoots       写根列表（workspace 根 + externalRoots 等）
     * @param includeTempEnvRoots 策略是否含可写 Tmpdir 项（TEMP/TMP 是否计入 allow）
     * @param env                 命令环境（TEMP/TMP 取值；缺键回退 {@link System#getenv})
     */
    public static AllowDenyPaths compute(List<WritableRoot> writableRoots,
            boolean includeTempEnvRoots, Map<String, String> env) {
        Map<String, String> envMap = env == null ? Map.of() : env;
        Set<Path> allow = new LinkedHashSet<>();
        Set<Path> deny = new LinkedHashSet<>();
        Map<Path, WritableRoot> roots = new LinkedHashMap<>();
        for (WritableRoot writableRoot : writableRoots) {
            roots.put(writableRoot.root(), writableRoot);
        }
        if (includeTempEnvRoots) {
            for (String key : new String[] { "TEMP", "TMP" }) {
                String value = envMap.containsKey(key) ? envMap.get(key) : System.getenv(key);
                if (value == null || value.isBlank()) {
                    continue;
                }
                Path temp = Path.of(value);
                if (temp.isAbsolute()) {
                    roots.putIfAbsent(temp, new WritableRoot(temp, List.of()));
                }
            }
        }
        for (WritableRoot writableRoot : roots.values()) {
            // canonicalize 失败保留原拼法（对齐 allow.rs unwrap_or(root)）
            Path canonical = canonicalize(writableRoot.root());
            if (Files.exists(canonical)) {
                allow.add(canonical);
            }
            for (Path subpath : writableRoot.readOnlySubpaths()) {
                if (Files.exists(subpath)) {
                    deny.add(subpath);
                }
            }
        }
        return new AllowDenyPaths(allow, deny);
    }

    /** dunce::canonicalize 语义：toRealPath（失败保留原值，不抛错）。 */
    static Path canonicalize(Path path) {
        try {
            return path.toRealPath();
        } catch (java.io.IOException e) {
            return path;
        }
    }
}
