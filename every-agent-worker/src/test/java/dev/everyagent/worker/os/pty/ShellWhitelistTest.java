package dev.everyagent.worker.os.pty;

import dev.everyagent.plugin.api.exception.BadParamsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ShellWhitelist 单测(安全整改 M-7):校验逻辑纯函数化,不真拉 PTY。
 * 覆盖:恶意路径拒绝 / 白名单名称通过(PATH 解析)/ 信任目录绝对路径通过 /
 * 信任目录外同名副本拒绝 / 配置扩展生效 / 空值与相对路径拒绝。
 */
class ShellWhitelistTest {

    @TempDir
    Path tmp;

    // ① 恶意路径(临时目录模拟 C:\tools\malware.exe)被拒:文件名就不在白名单
    @Test
    void malwarePathRejected() throws Exception {
        Path malware = tmp.resolve("malware.exe");
        Files.write(malware, new byte[]{0x4D, 0x5A});
        BadParamsException e = assertThrows(BadParamsException.class,
                () -> ShellWhitelist.check(malware.toString(), null));
        assertTrue(e.getMessage().contains("白名单"), "应说明白名单拒绝原因: " + e.getMessage());
    }

    // ② 名称 powershell 通过(PATH 解析,不预解析 realpath);大小写与 .exe 后缀不敏感
    @Test
    void whitelistedNamePasses() {
        ShellWhitelist.Checked c = ShellWhitelist.check("powershell", null);
        assertEquals("powershell", c.request());
        assertNull(c.realpath(), "名称形态不预解析路径,由 OS 经 PATH 解析");
        assertNull(ShellWhitelist.check("PowerShell.EXE", null).realpath());
    }

    // ③a 绝对路径 System32\cmd.exe 通过(真实系统文件,realpath 落信任目录)
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void system32CmdExePasses() {
        String sysRoot = System.getenv("SystemRoot");
        Path cmd = Path.of(sysRoot == null || sysRoot.isBlank() ? "C:\\Windows" : sysRoot,
                "System32", "cmd.exe");
        assumeTrue(Files.isRegularFile(cmd), "System32 cmd.exe 不存在(环境异常)");
        ShellWhitelist.Checked c = ShellWhitelist.check(cmd.toString(), null);
        assertNotNull(c.realpath());
        assertTrue(c.realpath().toLowerCase(java.util.Locale.ROOT).endsWith("cmd.exe"),
                "realpath 应指向 cmd.exe: " + c.realpath());
        // 大小写不敏感路径同样通过(Windows)
        String upper = cmd.toString().toUpperCase(java.util.Locale.ROOT);
        assertNotNull(ShellWhitelist.check(upper, null).realpath());
    }

    // ③b 临时目录下的 cmd.exe 副本被拒:文件名匹配白名单但路径不在信任目录
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void cmdCopyOutsideTrustedDirsRejected() throws Exception {
        Path copy = tmp.resolve("cmd.exe");
        Files.write(copy, new byte[]{0x4D, 0x5A});
        BadParamsException e = assertThrows(BadParamsException.class,
                () -> ShellWhitelist.check(copy.toString(), null));
        assertTrue(e.getMessage().contains("信任目录"), "应说明信任目录拒绝: " + e.getMessage());
    }

    // ④ 配置扩展生效:nu 默认拒绝,经 allowed-shells 扩展后名称放行
    @Test
    void configExtensionTakesEffect() {
        assertThrows(BadParamsException.class, () -> ShellWhitelist.check("nu", null));
        ShellWhitelist.Checked c = ShellWhitelist.check("nu", List.of("nu"));
        assertEquals("nu", c.request());
        // 扩展名同样参与绝对路径形态的文件名匹配,但路径仍须在信任目录
        assertThrows(BadParamsException.class,
                () -> ShellWhitelist.check("relative/nu", List.of("nu")));
    }

    // 补充:空值 / 相对路径 / 内置白名单全名称通过
    @Test
    void blankAndRelativeRejected_builtinNamesAllPass() {
        assertThrows(BadParamsException.class, () -> ShellWhitelist.check("", null));
        assertThrows(BadParamsException.class, () -> ShellWhitelist.check("   ", null));
        assertThrows(BadParamsException.class,
                () -> ShellWhitelist.check("tools\\malware", null), "相对路径不允许");
        for (String n : ShellWhitelist.DEFAULT_NAMES) {
            assertNull(ShellWhitelist.check(n, null).realpath(), n + " 应为名称形态放行");
        }
    }

    // 补充:不存在的绝对路径直接拒绝(不留给 PTY 层报错)
    @Test
    void nonexistentAbsolutePathRejected() {
        String missing = Path.of(tmp.toString(), "no-such-shell.exe").toString();
        BadParamsException e = assertThrows(BadParamsException.class,
                () -> ShellWhitelist.check(missing, null));
        assertTrue(e.getMessage().contains("不存在") || e.getMessage().contains("白名单"),
                e.getMessage());
    }
}
