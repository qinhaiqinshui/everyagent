package dev.everyagent.plugin.sandbox.codex.acl;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AclTargetGuard 单测(§7.10 目标域守卫;跨平台:定位全部注入临时目录,
 * Windows/Linux 语义一致——盘根检测用 tmp 所在文件系统根)。
 */
class AclTargetGuardTest {

    @TempDir
    Path tmp;

    /** 模拟一套系统定位:win/pf 为全覆盖,programData/users 为仅自身。 */
    private AclTargetGuard.SystemLocations locations() {
        return new AclTargetGuard.SystemLocations(
                List.of(tmp.resolve("win"), tmp.resolve("pf")),
                List.of(tmp.resolve("programdata"), tmp.resolve("users")));
    }

    @Test
    void filesystemRootRejected() {
        Path root = tmp.getRoot(); // tmp 所在文件系统根(Linux '/' / Windows 'C:\...')
        assertNotNull(root, "临时目录应在带根的绝对路径下");
        IOException e = assertThrows(IOException.class,
                () -> AclTargetGuard.requireSafeTarget(root, "write root", locations()));
        assertTrue(e.getMessage().contains("filesystem root"), e.getMessage());
    }

    @Test
    void fullCoverSelfAndDescendantAndAncestorRejected() {
        AclTargetGuard.SystemLocations loc = locations();
        Path win = tmp.resolve("win");
        assertThrows(IOException.class,
                () -> AclTargetGuard.requireSafeTarget(win, "write root", loc), "自身拒绝");
        assertThrows(IOException.class,
                () -> AclTargetGuard.requireSafeTarget(win.resolve("System32").resolve("config"),
                        "write root", loc),
                "其下拒绝(System32\\config 形态)");
        assertThrows(IOException.class,
                () -> AclTargetGuard.requireSafeTarget(win.getParent(), "write root", loc),
                "其祖先拒绝(祖先放行 = 整体覆盖系统树)");
    }

    @Test
    void surfaceOnlySelfAndAncestorRejectedButDescendantAllowed() {
        AclTargetGuard.SystemLocations loc = locations();
        Path users = tmp.resolve("users");
        assertThrows(IOException.class,
                () -> AclTargetGuard.requireSafeTarget(users, "write root", loc), "自身拒绝");
        assertThrows(IOException.class,
                () -> AclTargetGuard.requireSafeTarget(users.getParent(), "write root", loc),
                "祖先拒绝");
        assertDoesNotThrow(
                () -> AclTargetGuard.requireSafeTarget(users.resolve("EACodexOnline"),
                        "write root", loc),
                "其下放行(沙箱账户 profile 是合法写根)");
        assertDoesNotThrow(
                () -> AclTargetGuard.requireSafeTarget(
                        tmp.resolve("programdata").resolve("Microsoft").resolve("Windows")
                                .resolve("WER").resolve("ReportQueue"),
                        "write root", loc),
                "ProgramData 其下放行(WER 报告目录是 codex 原版合法目标)");
    }

    @Test
    void ordinaryDirectoriesAllowed() {
        AclTargetGuard.SystemLocations loc = locations();
        assertDoesNotThrow(() -> AclTargetGuard.requireSafeTarget(tmp.resolve("workspace"),
                "write root", loc), "普通目录放行");
        // 注意:tmp 自身是注入系统定位(win/pf)的父目录,按「祖先拒绝」规则应被拒
        // (祖先放行 = 整体覆盖系统树);与生产语义一致,不算误报。
        assertThrows(IOException.class,
                () -> AclTargetGuard.requireSafeTarget(tmp, "write root", loc),
                "系统定位的祖先链拒绝");
    }

    @Test
    void denyReasonNullForSafeAndTextForUnsafe() {
        AclTargetGuard.SystemLocations loc = locations();
        assertNull(AclTargetGuard.denyReason(tmp.resolve("workspace"), loc));
        assertNotNull(AclTargetGuard.denyReason(tmp.resolve("win"), loc));
        assertNotNull(AclTargetGuard.denyReason(tmp.resolve("pf").resolve("WindowsApps"), loc),
                "Program Files 之下的 WindowsApps 被覆盖拒绝");
    }
}
