package dev.everyagent.plugin.sandbox.codex.acl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 宿主侧 ACL 施加编排——对齐 codex {@code setup_provisioning.rs::run_setup_full}
 * 的 ACL 段（账户/网络/目录锁定由其余步骤承担）。
 *
 * <p>施加顺序（对齐 run_setup_full）：
 * <ol>
 *   <li><b>deny-read 同步</b>（{@link DenyReadState#sync}，组 SID 主体）——必须在
 *       沙箱命令启动前完成，失败即中止（codex：{@code .context("apply deny-read ACLs")}）；</li>
 *   <li><b>write 根授权</b>：逐根去重、缺失跳过；每根组 SID + root cap SID 双主体、
 *       WRITE_ALLOW_MASK、SET_ACCESS、CI|OI（{@code ensure_allow_write_aces}）；
 *       刷新判定失败按需刷新处理（对齐 path_write_aces_need_refresh 容错）；</li>
 *   <li><b>deny-write carveout</b>：缺失路径先物化为目录（防沙箱抢先创建绕过）、
 *       按重叠 cap 选择（根包含路径或路径包含根；无命中回退全部活动根）、
 *       逐 SID {@code add_deny_write_ace}；</li>
 *   <li><b>read 根组授权</b>：内建主体（Everyone/Users/Authenticated Users）已持
 *       完整 RX 则跳过，组已持则跳过，否则组 RX allow（SET_ACCESS、OI|CI）。
 *       （codex 把读授权放后台 ReadAclsOnly helper 渐进收敛；此处同步执行——
 *       首期规模小，语义等价、时序更保守。）</li>
 * </ol>
 *
 * <p>错误语义对齐：deny-read 失败 → 抛出（fail-closed）；deny-write 物化失败 → 抛出；
 * write 授权 / deny ACE / 读授权的单点失败 → 记录后继续（codex Full 模式
 * refresh_errors 尽力而为，preflight 在每次执行前还有二次校验兜底）。
 */
public final class ProvisioningAcl {

    /** 内建主体：Everyone / Users / Authenticated Users（读根已持 RX 自检用）。 */
    static final List<String> BUILTIN_RX_SIDS =
            List.of("S-1-1-0", "S-1-5-32-545", "S-1-5-11");

    private final AclOperations ops;

    /** 生产构造：Windows 原语实现。 */
    public ProvisioningAcl() {
        this(WindowsAclOperations.INSTANCE);
    }

    /** 测试构造：注入假 {@link AclOperations}（跨平台单测）。 */
    ProvisioningAcl(AclOperations ops) {
        this.ops = ops;
    }

    /** 施加一次会话的 ACL 授权域（入参见 {@link ProvisioningRequest}）。 */
    public void applyProvisioning(ProvisioningRequest req) throws IOException {
        List<String> errors = new ArrayList<>();

        // 1) deny-read 同步（先于一切授权；失败即中止）
        List<Path> denyReadPaths = new ArrayList<>();
        for (String path : req.denyReadPaths()) {
            denyReadPaths.add(Path.of(path));
        }
        try {
            DenyReadState.sync(req.stateRoot(), req.groupSid(), denyReadPaths, ops);
        } catch (IOException e) {
            throw new IOException("apply deny-read ACLs failed: " + e.getMessage(), e);
        }

        // 2) write 根授权（组 + root cap 双主体）
        Set<String> seenRoots = new LinkedHashSet<>();
        for (String root : req.writeRoots()) {
            if (!seenRoots.add(WorkspaceProtect.canonicalKey(Path.of(root)))) {
                continue;
            }
            Path rootPath = Path.of(root);
            if (!Files.exists(rootPath)) {
                continue; // 缺失写根跳过（对齐 run_setup_full）
            }
            String capSid = lookupCapSid(req.capSids(), root);
            if (capSid == null) {
                throw new IOException("write root " + root
                        + " has no capability SID in provisioning request; refusing");
            }
            boolean needGrant;
            try {
                needGrant = ops.pathWriteAcesNeedRefresh(rootPath,
                        List.of(req.groupSid(), capSid));
            } catch (IOException e) {
                errors.add("write ACE check failed on " + root + ": " + e.getMessage());
                needGrant = true; // 检查失败按需刷新处理（对齐 codex）
            }
            if (needGrant) {
                try {
                    ops.ensureAllowWriteAces(rootPath, List.of(req.groupSid(), capSid));
                } catch (IOException e) {
                    errors.add("write ACE failed on " + root + ": " + e.getMessage());
                }
            }
        }

        // 3) deny-write carveout（物化 + 按重叠 cap 选择）
        Set<String> seenDeny = new LinkedHashSet<>();
        for (String denyPath : req.denyWritePaths()) {
            if (!seenDeny.add(WorkspaceProtect.canonicalKey(Path.of(denyPath)))) {
                continue;
            }
            Path path = Path.of(denyPath);
            if (!Files.exists(path)) {
                Files.createDirectories(path); // 物化失败 = 致命（对齐 codex create_dir_all?）
            }
            for (String capSid : denyWriteCapSids(req.capSids(), req.writeRoots(), path)) {
                try {
                    ops.addDenyWriteAce(path, capSid);
                } catch (IOException e) {
                    errors.add("deny ACE failed on " + path + ": " + e.getMessage());
                }
            }
        }

        // 4) read 根组授权（内建已持 → 组已持 → 组 allow）
        Set<String> seenRead = new LinkedHashSet<>();
        for (String readRoot : req.readRoots()) {
            if (!seenRead.add(WorkspaceProtect.canonicalKey(Path.of(readRoot)))) {
                continue;
            }
            Path rootPath = Path.of(readRoot);
            if (!Files.exists(rootPath)) {
                continue;
            }
            try {
                if (ops.pathMaskAllows(rootPath, BUILTIN_RX_SIDS, AclMasks.READ_EXECUTE_MASK,
                        true)) {
                    continue; // 系统本就放行（如 C:\Windows），不触碰系统 ACL
                }
                if (ops.pathMaskAllows(rootPath, List.of(req.groupSid()),
                        AclMasks.READ_EXECUTE_MASK, true)) {
                    continue;
                }
                ops.ensureReadExecuteAces(rootPath, List.of(req.groupSid()));
            } catch (IOException e) {
                errors.add("read ACE failed on " + rootPath + ": " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            // 尽力而为语义（对齐 Full 模式 refresh_errors 不 bail）：记录告警不抛出；
            // 执行前 preflight（WriteRootRefresher）会再校验并 fail-closed。
            System.getLogger(ProvisioningAcl.class.getName())
                    .log(System.Logger.Level.WARNING,
                            "provisioning ACL finished with best-effort errors: {0}", errors);
        }
    }

    /**
     * 按路径选择 deny-write 主体 cap——对齐 {@code workspace_write_cap_sids_for_path}：
     * 重叠根（root 包含 path 或 path 包含 root，canonical 键比较）的 cap；
     * 无命中回退<b>全部</b>活动根 cap（codex 同款回退；write_roots 全空的 cwd 根
     * 回退由调用方以 cwd cap 预置进 capSids 实现）。
     */
    static List<String> denyWriteCapSids(Map<String, String> capSids, List<String> writeRoots,
            Path path) {
        String pathKey = WorkspaceProtect.canonicalKey(path);
        List<String> overlapping = new ArrayList<>();
        List<String> all = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String root : writeRoots) {
            String rootKey = WorkspaceProtect.canonicalKey(Path.of(root));
            String capSid = lookupCapSid(capSids, root);
            if (capSid == null || !seen.add(rootKey)) {
                continue;
            }
            all.add(capSid);
            boolean rootContainsPath = pathKey.equals(rootKey) || pathKey.startsWith(rootKey + "/");
            boolean pathContainsRoot = rootKey.equals(pathKey) || rootKey.startsWith(pathKey + "/");
            if (rootContainsPath || pathContainsRoot) {
                overlapping.add(capSid);
            }
        }
        return overlapping.isEmpty() ? all : overlapping;
    }

    /** capSids 键匹配：先原串、再 canonical 键（容忍大小写/分隔符变体）。 */
    static String lookupCapSid(Map<String, String> capSids, String root) {
        String direct = capSids.get(root);
        if (direct != null) {
            return direct;
        }
        String rootKey = WorkspaceProtect.canonicalKey(Path.of(root));
        for (Map.Entry<String, String> entry : capSids.entrySet()) {
            if (WorkspaceProtect.canonicalKey(Path.of(entry.getKey())).equals(rootKey)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
