package dev.everyagent.worker.modules;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkspaceManager#isUnsafeSandboxRoot(Path, List, List)} 单测
 * (§7.10 worker 侧下发防线;定位注入临时目录,Windows/Linux 语义一致)。
 */
class SandboxRootSafetyTest {

    @TempDir
    Path tmp;

    private List<Path> fullCover() {
        return List.of(tmp.resolve("win"), tmp.resolve("pf"));
    }

    private List<Path> surfaceOnly() {
        return List.of(tmp.resolve("programdata"), tmp.resolve("users"));
    }

    @Test
    void filesystemRootIsUnsafe() {
        Path root = tmp.getRoot();
        assertNotNull(root, "临时目录应在带根的绝对路径下");
        assertTrue(WorkspaceManager.isUnsafeSandboxRoot(root, fullCover(), surfaceOnly()),
                "盘根绝不下发沙箱(会被后端标注 ACL)");
    }

    @Test
    void fullCoverSelfDescendantAncestorUnsafe() {
        Path win = tmp.resolve("win");
        assertTrue(WorkspaceManager.isUnsafeSandboxRoot(win, fullCover(), surfaceOnly()), "自身");
        assertTrue(WorkspaceManager.isUnsafeSandboxRoot(win.resolve("System32"), fullCover(),
                surfaceOnly()), "其下");
        assertTrue(WorkspaceManager.isUnsafeSandboxRoot(tmp, fullCover(), surfaceOnly()),
                "其祖先(祖先放行 = 整体覆盖系统树)");
        assertTrue(WorkspaceManager.isUnsafeSandboxRoot(tmp.resolve("pf").resolve("WindowsApps"),
                fullCover(), surfaceOnly()), "Program Files 之下");
    }

    @Test
    void surfaceOnlySelfAndAncestorUnsafeButDescendantSafe() {
        assertTrue(WorkspaceManager.isUnsafeSandboxRoot(tmp.resolve("users"), fullCover(),
                surfaceOnly()), "Users 根自身");
        Path users = tmp.resolve("users");
        assertFalse(WorkspaceManager.isUnsafeSandboxRoot(users.resolve("EACodexOnline"),
                fullCover(), surfaceOnly()), "沙箱账户 profile 放行");
        assertFalse(WorkspaceManager.isUnsafeSandboxRoot(
                tmp.resolve("programdata").resolve("Microsoft").resolve("WER").resolve("ReportQueue"),
                fullCover(), surfaceOnly()), "WER 报告目录放行");
    }

    @Test
    void ordinaryRootsSafe() {
        assertFalse(WorkspaceManager.isUnsafeSandboxRoot(tmp.resolve("workspace"), fullCover(),
                surfaceOnly()), "普通工作区/外部根放行");
    }
}
