package dev.everyagent.worker.os;

import dev.everyagent.plugin.api.shell.ExecResults;
import dev.everyagent.plugin.api.spi.ExecResult;
import dev.everyagent.worker.config.WorkerProperties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OsSandbox#spawnToFileRedirected} 的 Windows 实机冒烟测试(非 Spring)。
 *
 * <p><b>锁死的行为(BUG-1 / BUG-2 / BUG-4 的根)</b>:PowerShell 子进程的 stdout/stderr
 * 必须由<b>文件</b>承载而不是管道——管道模式下 PS 5.1 用系统 OEM 码页(中文=GBK)解码原生
 * 子进程输出,UTF-8 中文在子进程出口就被毁掉,读端无法还原;且原生 stderr 会被包成 CLIXML、
 * 命令退出码也传不出来。脚本组装方式与 {@code CommandExecutor} 完全同构
 * (UTF-8 BOM 临时 .ps1 + {@code -File} + 前缀 + 退出码尾部)。
 */
@EnabledOnOs(OS.WINDOWS)
class OsSandboxFileCarryTest {

    /**
     * 测试根目录建在模块工作区内(而非系统 TEMP):受限账户下 {@code java.io.tmpdir} 可能被拒写,
     * 这同时顺带验证了 {@link OsSandbox#createScratchFile} 的工作区兜底路径。
     */
    private Path dir;

    @BeforeEach
    void setUp() throws IOException {
        Path base = Path.of(System.getProperty("user.dir"), ".everyagent", "tmp-test");
        Files.createDirectories(base);
        dir = Files.createTempDirectory(base, "ea-case-");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 尽力清理
                }
            }
        }
    }

    private OsSandbox newSandbox() {
        return new OsSandbox(new WorkerProperties(), null);
    }

    /** 与 CommandExecutor 同构:前缀 + 用户命令 + 退出码尾部,UTF-8 BOM 落 .ps1。 */
    private Path writeScript(String userCommand) throws IOException {
        String script = ExecResults.POWERSHELL_PREFIX + userCommand
                + ExecResults.POWERSHELL_EXIT_TAIL;
        Path ps1 = dir.resolve("case.ps1");
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = script.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, all, 0, bom.length);
        System.arraycopy(body, 0, all, bom.length, body.length);
        Files.write(ps1, all);
        return ps1;
    }

    private ExecResult run(Path ps1) {
        return newSandbox().spawnToFileRedirected(
                new String[]{"powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                        "-File", ps1.toString()},
                dir, Map.of(), 60_000L);
    }

    @Test
    void 中文内容与非ASCII文件名均按UTF8原样回传() throws IOException {
        Files.write(dir.resolve("cn.txt"),
                "中文内容行 ALPHA\n第二行 数据库 timeout\n".getBytes(StandardCharsets.UTF_8));
        // 原生命令(cmd type)写出的 UTF-8 字节不得被 PS 转码成 GBK 乱码
        ExecResult r = run(writeScript(
                "Write-Output '中文cmdlet行'; cmd /c \"type cn.txt\""));

        assertTrue(r.stdout().contains("中文内容行 ALPHA"),
                "原生子进程的 UTF-8 中文必须原样还原,实得: " + r.stdout());
        assertTrue(r.stdout().contains("第二行 数据库"), "多行中文均不得乱码");
        assertTrue(r.stdout().contains("中文cmdlet行"), "cmdlet 自身中文输出同样须为 UTF-8");
        assertFalse(r.stdout().contains("\uFFFD"), "不得出现替换字符(信息已丢的证据)");
        assertEquals(0, r.exitCode(), "成功命令退出码 0:" + r.stderr());
    }

    @Test
    void 原生命令退出码经尾部传导到进程码() throws IOException {
        ExecResult seven = run(writeScript("cmd /c \"exit 7\""));
        assertEquals(7, seven.exitCode(), "尾部 exit 须把原生退出码转成 powershell.exe 进程码");

        ExecResult zero = run(writeScript("cmd /c \"exit 0\""));
        assertEquals(0, zero.exitCode(), "纯 cmdlet/成功路径不得残留上一次的退出码");
    }

    @Test
    void 原生stderr原样到读端且不被CLIXML吞掉() throws IOException {
        ExecResult r = run(writeScript("cmd /c \"echo boom_marker 1>&2\""));
        assertTrue(r.stderr().contains("boom_marker"),
                "原生 stderr 必须可见(旧实现整段删 CLIXML 会把错误吞没),实得: " + r.stderr());
        assertFalse(r.stderr().contains("CLIXML"), "文件承载下不应产生 CLIXML 包装");
        assertFalse(r.stdout().contains("boom_marker"), "stderr 不得混进 stdout 数据段");
    }

    @Test
    void powershell自身错误以纯文本呈现() throws IOException {
        ExecResult r = run(writeScript("nosuchcommand-ea-test; Write-Output ok"));
        assertTrue(r.stdout().contains("ok"), "非终止错误不应中断脚本");
        assertTrue(r.stderr().contains("nosuchcommand-ea-test"),
                "命令不存在的错误文本必须回灌给模型,实得: " + r.stderr());
        assertFalse(r.stderr().contains("<Objs"), "不得把 CLIXML 骨架交给模型");
    }

    @Test
    void 超时被强杀并标记aborted() throws IOException {
        // 1s 超时跑 20s 睡眠:必须中止且给出 aborted 标记,而不是无限等
        long t0 = System.currentTimeMillis();
        ExecResult r = newSandbox().spawnToFileRedirected(
                new String[]{"powershell.exe", "-NoProfile", "-Command", "Start-Sleep -Seconds 20"},
                dir, Map.of(), 1_000L);
        long cost = System.currentTimeMillis() - t0;
        assertTrue(r.aborted(), "超时须置 aborted");
        assertEquals(-1, r.exitCode(), "超时退出码约定为 -1");
        assertTrue(cost < 30_000L, "超时后应尽快收尾,实耗 " + cost + "ms");
        assertTrue(r.stderr().contains("超时中止"), "超时须留可读说明:" + r.stderr());
    }
}
