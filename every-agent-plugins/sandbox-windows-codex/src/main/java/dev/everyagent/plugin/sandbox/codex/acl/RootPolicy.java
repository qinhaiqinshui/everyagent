package dev.everyagent.plugin.sandbox.codex.acl;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一次会话的根集合模型——write 根（每根带 capability SID）、deny-write 路径、
 * deny-read 路径、read 根，外加敏感过滤（对齐 codex {@code setup.rs::
 * filter_sensitive_write_roots} 的思想）。
 *
 * <p>write 根<b>不得</b>是 codexHome 自身或其下 {@code .sandbox}/
 * {@code .sandbox-bin}/{@code .sandbox-secrets} 状态目录（及其子路径）——
 * 这些位置承载沙箱控制/状态与 helper 二进制，必须防篡改；codexHome 下
 * 其余兄弟路径（如临时目录）放行。canonical 键比较（对齐
 * {@code canonical_path_key}：toRealPath 容忍缺失 → '/' 化 → 小写），
 * Windows 大小写/分隔符变体归一后仍能命中。
 *
 * <p>纯逻辑、跨平台可测；SPI 适配层（Backend.mount 登记）与本模型对齐。
 */
public record RootPolicy(List<WriteRoot> writeRoots, List<Path> denyWritePaths,
        List<Path> denyReadPaths, List<Path> readRoots) {

    /** 写根条目：根路径 + 该根的 capability SID（S-1-5-21-…）。 */
    public record WriteRoot(Path root, String capSid) {
        public WriteRoot {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(capSid, "capSid");
        }
    }

    public RootPolicy {
        writeRoots = List.copyOf(writeRoots);
        denyWritePaths = List.copyOf(denyWritePaths);
        denyReadPaths = List.copyOf(denyReadPaths);
        readRoots = List.copyOf(readRoots);
    }

    /** 状态目录名（对齐 codex sandbox_dir/sandbox_bin_dir/sandbox_secrets_dir）。 */
    public static final String SANDBOX_DIR = ".sandbox";
    /** helper/runner 物化目录名。 */
    public static final String SANDBOX_BIN_DIR = ".sandbox-bin";
    /** 账户凭据目录名（组 DENY）。 */
    public static final String SANDBOX_SECRETS_DIR = ".sandbox-secrets";

    /**
     * 敏感路径判定：codexHome 本身 / 三个状态目录本身或其下。
     */
    public static boolean isSensitivePath(Path path, Path codexHome) {
        String key = WorkspaceProtect.canonicalKey(path);
        if (key.equals(WorkspaceProtect.canonicalKey(codexHome))) {
            return true;
        }
        for (String dir : new String[] { SANDBOX_DIR, SANDBOX_BIN_DIR, SANDBOX_SECRETS_DIR }) {
            String dirKey = WorkspaceProtect.canonicalKey(codexHome.resolve(dir));
            if (key.equals(dirKey) || key.startsWith(dirKey + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 剥离敏感 write 根（对齐 filter_sensitive_write_roots 的 retain 语义）+
     * canonical 键去重（对齐 payload 组装时 HashSet 去重）。
     *
     * @param codexHome 沙箱持久根（敏感状态目录的父）
     */
    public RootPolicy sanitized(Path codexHome) {
        Map<String, WriteRoot> kept = new LinkedHashMap<>();
        for (WriteRoot writeRoot : writeRoots) {
            if (isSensitivePath(writeRoot.root(), codexHome)) {
                continue;
            }
            kept.putIfAbsent(WorkspaceProtect.canonicalKey(writeRoot.root()), writeRoot);
        }
        return new RootPolicy(new ArrayList<>(kept.values()), denyWritePaths, denyReadPaths,
                readRoots);
    }

    /** 校验（fail-closed）：敏感 write 根必须先经 {@link #sanitized} 剥离。 */
    public RootPolicy requireNoSensitiveWriteRoots(Path codexHome) {
        for (WriteRoot writeRoot : writeRoots) {
            if (isSensitivePath(writeRoot.root(), codexHome)) {
                throw new IllegalStateException("write root " + writeRoot.root()
                        + " overlaps sandbox state under " + codexHome + "; refusing");
            }
        }
        return this;
    }
}
