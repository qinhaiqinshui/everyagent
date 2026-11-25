package dev.everyagent.worker.os;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DIRECT 路径 Windows PowerShell 宿主解析回归({@link OsSandbox#resolveWindowsPowerShellExe}):
 * PATH 探测 {@code pwsh.exe}(PowerShell 7+)优先,未装回退出厂 {@code powershell.exe}(5.1)。
 * 与 codex 后端 detectShell 同策略(ARCHITECTURE §7.10),本测试锁死「装了最新版就必须用上」。
 * 解析逻辑随 PowerShell 共享管线({@link OsSandbox#spawnPowerShell})由 CommandExecutor
 * 迁入 OsSandbox(会话 powershell 工具与工作流命令节点共用),测试随之迁移。
 */
@EnabledOnOs(OS.WINDOWS)
class OsSandboxPowerShellHostProbeTest {

    @Test
    void 装有pwsh时优先解析到绝对路径否则回退内置5_1() {
        String resolved = OsSandbox.resolveWindowsPowerShellExe();
        String pwsh = OsSandbox.findInPath("pwsh.exe");
        if (pwsh != null) {
            assertEquals(pwsh, resolved, "装有 pwsh 时必须解析到 pwsh 绝对路径,不得回退 5.1");
            assertTrue(resolved.endsWith("pwsh.exe"), "解析结果须是 pwsh.exe: " + resolved);
        } else {
            assertEquals("powershell.exe", resolved, "未装 pwsh 时回退出厂 5.1");
            // 裸名回退须真实可寻(PATH 或 System32 出厂位置)——否则 spawnPowerShell 将抛
            // IllegalStateException(调用方回退其它 shell)
            assertTrue(OsSandbox.windowsPowerShellFindable(),
                    "回退 powershell.exe 时宿主必须真实可寻(PATH/System32)");
        }
    }

    @Test
    void findInPath对不存在的名字返回null() {
        assertNull(OsSandbox.findInPath("ea-definitely-missing-exe.exe"),
                "PATH 中不存在的可执行文件不得命中");
    }

    /**
     * 解析出的宿主必须真实可执行且能报告版本(进程冒烟):pwsh 路径 → 主版本 ≥ 6
     * (pwsh 品牌即 PowerShell 6+);回退 powershell.exe → 主版本 5。
     */
    @Test
    void 解析出的宿主真实可执行且报告版本() throws Exception {
        String exe = OsSandbox.resolveWindowsPowerShellExe();
        Process p = new ProcessBuilder(exe, "-NoProfile", "-NoLogo", "-NonInteractive",
                "-Command", "$PSVersionTable.PSVersion.Major").redirectErrorStream(true).start();
        String major;
        try {
            assertTrue(p.waitFor(20, TimeUnit.SECONDS), "宿主冒烟须在 20s 内返回: " + exe);
            assertEquals(0, p.exitValue(), "宿主须正常退出: " + exe);
            major = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } finally {
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
        assertTrue(major.matches("\\d+"), "版本主号须为纯数字,实得: '" + major + "'");
        int v = Integer.parseInt(major);
        if (exe.endsWith("pwsh.exe")) {
            assertTrue(v >= 6, "pwsh 路径应报告 PowerShell 6+,实得 " + v);
        } else {
            assertEquals(5, v, "回退 powershell.exe 应为 5.x,实得 " + v);
        }
    }
}
