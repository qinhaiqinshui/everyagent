package dev.everyagent.plugin.sandbox.codex.acl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorkspaceProtect 单测（跨平台；对齐 workspace_acl.rs：目录存在才施加
 * deny-write、is_command_cwd_root 判定；every-agent 对应 .everyagent 配置目录）。
 */
class WorkspaceProtectTest {

    private static final String GROUP = "S-1-5-21-7-7-7-7";

    @TempDir
    Path tmp;

    @Test
    void protectsExistingEveryAgentDir() throws IOException {
        Path workspace = Files.createDirectories(tmp.resolve("workspace"));
        Path configDir = Files.createDirectories(workspace.resolve(".everyagent"));
        RecordingAclOperations ops = new RecordingAclOperations();

        assertTrue(WorkspaceProtect.protectEveryAgentDir(workspace, GROUP, ops));
        assertEquals(1, ops.calls.size());
        assertEquals("denyWrite", ops.calls.get(0).op());
        assertEquals(configDir, ops.calls.get(0).path());
        assertEquals(List.of(GROUP), ops.calls.get(0).sids());
    }

    @Test
    void skipsMissingSubdirWithoutMaterializing() throws IOException {
        Path workspace = Files.createDirectories(tmp.resolve("workspace"));
        RecordingAclOperations ops = new RecordingAclOperations();

        assertFalse(WorkspaceProtect.protectEveryAgentDir(workspace, GROUP, ops));
        assertTrue(ops.calls.isEmpty());
        assertFalse(Files.exists(workspace.resolve(".everyagent")),
                "缺失不物化，避免在工作区留哨兵目录");
    }

    @Test
    void isCommandCwdRootMatchesCanonicalEquals() throws IOException {
        Path workspace = Files.createDirectories(tmp.resolve("workspace"));
        Path sub = Files.createDirectories(workspace.resolve("sub"));
        assertTrue(WorkspaceProtect.isCommandCwdRoot(workspace, workspace));
        assertTrue(WorkspaceProtect.isCommandCwdRoot(sub.resolve(".."), workspace.toRealPath()),
                "canonicalize(root) == canonical_command_cwd（.. 变体拼法命中）");
        assertFalse(WorkspaceProtect.isCommandCwdRoot(tmp, workspace));
    }
}
