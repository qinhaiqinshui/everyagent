package dev.everyagent.plugin.sandbox.codex.acl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 工作区配置目录保护——对齐 codex {@code workspace_acl.rs}。
 *
 * <p>codex 对「恰为命令 cwd 的写根」施加 {@code cwd/.codex}、{@code cwd/.agents} 的
 * deny-write ACE（deny-<b>write</b> 保读拒写——防沙箱改写工作区内凭据/agent 配置，
 * 其他写根下的同名目录不受影响）。every-agent 对应：工作区下的
 * {@code .everyagent} 配置目录。
 *
 * <p>与 codex 的差异：codex 挂在<b>工作区 capability SID</b> 上（legacy 真实用户令牌
 * 流程）；本插件 elevated 流程的读/拒绝权威是<b>沙箱组 SID</b>（cap SID 是 deny-only
 * 组、其 deny 同样生效，但组 SID 同时压制两条通道，覆盖面更完整）——故按组 SID 施加。
 */
public final class WorkspaceProtect {

    /** every-agent 工作区配置目录名（codex 侧为 .codex / .agents）。 */
    public static final String EVERYAGENT_CONFIG_DIR = ".everyagent";

    private WorkspaceProtect() {
    }

    /** 该写根是否恰为命令 cwd（canonicalize 容忍缺失，对齐 is_command_cwd_root）。 */
    public static boolean isCommandCwdRoot(Path root, Path canonicalCommandCwd) {
        return canonicalKey(root).equals(canonicalKey(canonicalCommandCwd));
    }

    /**
     * 保护 {@code workspaceCwd} 下的指定子目录：目录存在才施加（对齐
     * {@code protect_workspace_subdir}——缺失不物化，避免在工作区留哨兵目录）。
     *
     * @return 是否确有 deny-write ACE 写入
     */
    public static boolean protectSubdir(Path workspaceCwd, String subdir, String groupSid,
            AclOperations ops) throws IOException {
        Path path = workspaceCwd.resolve(subdir);
        if (Files.isDirectory(path)) {
            return ops.addDenyWriteAce(path, groupSid);
        }
        return false;
    }

    /** 便捷入口：保护工作区 {@code .everyagent} 配置目录。 */
    public static boolean protectEveryAgentDir(Path workspaceCwd, String groupSid,
            AclOperations ops) throws IOException {
        return protectSubdir(workspaceCwd, EVERYAGENT_CONFIG_DIR, groupSid, ops);
    }

    /** 词法+canonical 归一键：toRealPath（失败原样）→ '/' 化 → 去尾 '/' → 小写。 */
    static String canonicalKey(Path path) {
        return DenyReadPlanner.lexicalPathKey(AllowDenyPaths.canonicalize(path));
    }
}
