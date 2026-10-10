package dev.everyagent.plugin.sandbox.codex.runner;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.WinNT;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import dev.everyagent.plugin.sandbox.codex.win.Advapi32Ex;
import dev.everyagent.plugin.sandbox.codex.win.Kernel32Ex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Windows 专属冒烟骨架（设计 §8：assumeTrue 守卫，非 Windows 跳过）。
 *
 * <p>仅验证「受限令牌可派生 + CreateProcessAsUserW 全链路能启动并收割退出码」；
 * 真正的隔离断言（写越界拒绝、deny-read、断网）属 windows-admin 端到端用例，
 * 见设计 §8 第三档。
 */
class WindowsRunnerSmokeTest {

    /** 合成 capability SID（S-1-5-21-a-b-c-d 形态，无账户名映射）。 */
    private static final String CAP_SID = "S-1-5-21-1000-2000-3000-4000";

    @Test
    void jnaBindingsLoad() {
        Assumptions.assumeTrue(Platform.isWindows(), "Windows 专属");
        assertNotNull(Kernel32Ex.INSTANCE);
        assertNotNull(Advapi32Ex.INSTANCE);
    }

    @Test
    void restrictedTokenDerivationSmoke() {
        Assumptions.assumeTrue(Platform.isWindows(), "Windows 专属");
        WinNT.HANDLE token = SandboxTokenFactory
                .createRestrictedTokenWithCaps(java.util.List.of(CAP_SID));
        try {
            assertNotNull(token, "受限令牌句柄非空（半成品令牌不外泄的契约由实现保证）");
        } finally {
            assertTrue(Kernel32Ex.INSTANCE.CloseHandle(token));
        }
    }

    @Test
    void childProcessSpawnAndWaitSmoke() {
        Assumptions.assumeTrue(Platform.isWindows(), "Windows 专属");
        WinNT.HANDLE token = SandboxTokenFactory
                .createRestrictedTokenWithCaps(java.util.List.of(CAP_SID));
        ChildProcess child = null;
        try {
            // 不带桌面名冒烟（PrivateDesktop 类就绪后端到端用例应总是传私有桌面）
            child = ChildProcess.spawn(token, java.util.List.of("cmd.exe", "/c", "exit 3"),
                    "C:\\", java.util.Map.of("EVERYAGENT_SMOKE", "1"), null);
            assertTrue(child.processId() > 0);
            ChildProcess.ExitResult r = child.waitForExit(15_000L);
            assertEquals(3, r.exitCode(), "cmd.exe /c exit 3 的退出码须如实回传");
            assertFalse(r.timedOut());
        } finally {
            if (child != null) {
                child.close();
            }
            Kernel32Ex.INSTANCE.CloseHandle(token);
        }
    }
}
