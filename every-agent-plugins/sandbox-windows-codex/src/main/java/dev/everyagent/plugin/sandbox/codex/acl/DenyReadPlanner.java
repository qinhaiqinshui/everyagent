package dev.everyagent.plugin.sandbox.codex.acl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * deny-read ACL 计划器——对齐 codex {@code deny_read_acl.rs}。
 *
 * <ul>
 *   <li>{@link #plan}：每条路径同时保留<b>词法路径 + canonical 路径</b>
 *       （canonicalize 容忍 NotFound 跳过），以 {@link #lexicalPathKey} 去重。
 *       双写理由（codex 注释）：词法路径覆盖用户配置的拼法、允许稍后物化尚不存在的精确
 *       deny；canonical 路径额外覆盖 reparse-point 目标，防沙箱经解析后位置读到同一对象。
 *       plan 末尾对文件系统根（绝对且无父）立即拒绝——别名/重解析路径不得把机器整体锁死
 *       （与 {@link DenyAcePrimitives#addDenyReadAce} 的句柄级校验构成双重拦截）。</li>
 *   <li>{@link #apply}：缺失路径先物化为<b>目录</b>（防沙箱在可写父目录下先创建该路径
 *       再读取的 TOCTOU 竞态）→ 逐条挂 deny-read ACE；任一条失败即对<b>本次调用新增的</b>
 *       ACE 逐条 revoke 回滚后抛错——一次性运行不留半套状态。</li>
 * </ul>
 *
 * <p>glob 展开（对齐 {@code deny_read_resolver.rs} 的精确路径直通 + glob 快照）见
 * {@link DenyReadGlobs}。跨平台纯逻辑（ACE 经 {@link AclOperations} 注入）。
 */
public final class DenyReadPlanner {

    private DenyReadPlanner() {
    }

    /**
     * 构建 deny-read ACE 的确切目标集（词法 + canonical 双路径，去重，拒绝根）。
     */
    public static List<Path> plan(List<Path> paths) throws IOException {
        List<Path> planned = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Path path : paths) {
            pushPlanned(planned, seen, path);
            Path canonical;
            try {
                canonical = path.toRealPath();
            } catch (java.io.IOException e) {
                if (e instanceof java.nio.file.NoSuchFileException) {
                    continue; // 缺失路径只保留词法形态，稍后物化
                }
                throw new IOException("canonicalize deny-read path " + path + " failed: " + e, e);
            }
            pushPlanned(planned, seen, canonical);
        }
        for (Path path : planned) {
            if (path.isAbsolute() && path.getParent() == null) {
                throw new IOException("refusing to apply a deny-read ACE to filesystem root "
                        + path);
            }
        }
        return planned;
    }

    private static void pushPlanned(List<Path> planned, Set<String> seen, Path path) {
        if (seen.add(lexicalPathKey(path))) {
            planned.add(path);
        }
    }

    /** 词法键：'\'→'/'、去尾 '/'、小写——对齐 deny_read_acl.rs::lexical_path_key。 */
    public static String lexicalPathKey(Path path) {
        String key = path.toString().replace('\\', '/');
        while (key.endsWith("/") && key.length() > 1) {
            key = key.substring(0, key.length() - 1);
        }
        return key.toLowerCase(Locale.ROOT);
    }

    /**
     * 施加 deny-read ACE——物化缺失路径为目录、失败回滚本次新增。
     *
     * @param principalSid deny 主体 SID（elevated 流程为沙箱组 SID）
     * @return 全部计划路径（含此前已存在而本次幂等跳过的）
     */
    public static List<Path> apply(List<Path> paths, String principalSid, AclOperations ops)
            throws IOException {
        List<Path> planned = plan(paths);
        List<Path> applied = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Path> addedInThisCall = new ArrayList<>();
        for (Path path : planned) {
            boolean added;
            try {
                if (!Files.exists(path)) {
                    Files.createDirectories(path); // 物化为目录（TOCTOU 防御）
                }
                added = ops.addDenyReadAce(path, principalSid);
            } catch (IOException | RuntimeException e) {
                for (Path addedPath : addedInThisCall) {
                    try {
                        ops.revokeAce(addedPath, principalSid);
                    } catch (IOException | RuntimeException suppressed) {
                        e.addSuppressed(suppressed); // 回滚尽力而为
                    }
                }
                throw e instanceof IOException io ? io : new IOException(
                        "apply deny-read ACE to " + path + " failed: " + e, e);
            }
            if (added) {
                addedInThisCall.add(path);
            }
            if (seen.add(lexicalPathKey(path))) {
                applied.add(path);
            }
        }
        return applied;
    }
}
