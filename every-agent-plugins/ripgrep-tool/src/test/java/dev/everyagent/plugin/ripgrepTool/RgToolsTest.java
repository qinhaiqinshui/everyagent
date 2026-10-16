package dev.everyagent.plugin.ripgrepTool;

import dev.everyagent.plugin.api.shell.ExecResults;
import dev.everyagent.plugin.api.spi.CommandGate;
import dev.everyagent.plugin.api.spi.ExecResult;
import dev.everyagent.plugin.api.spi.NativeExec;
import dev.everyagent.plugin.api.spi.ToolOutcomeLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link RgTools} 单测：argv 构造与 path 归一化（纯函数；path 省略 → argv 显式携带
 * 工作区根绝对路径，绝不产出无 path 的 argv）、结果格式化（exit 0/1/2/中止）、
 * 命令串形态、授权门禁次序（§7.8：拒绝即不执行）、记账口径（exit 1 无匹配不记失败），
 * 以及「真实 spawn 宿主 rg」用例（RgResolver 第一档：复制仓库自带 bin/rg.exe 到
 * TempDir 的假插件根，不可用时条件跳过）。
 */
class RgToolsTest {

    private static final Path RG = Path.of("C:\\tools\\rg.exe");
    private static final Path WS = Path.of("C:\\ws");

    // ── argv 构造 ─────────────────────────────────────────────────────

    @Test
    void argvMinimal() {
        assertArrayEquals(new String[] { "C:\\tools\\rg.exe", "--", "foo", "C:\\ws" },
                RgTools.buildArgv(RG, "foo", "C:\\ws", null, null, null, null));
    }

    @Test
    void argvRejectsMissingPathDefensively() {
        // path 契约「必非空」：null/blank 说明调用侧漏了归一化——宁可立即失败也不拼出
        // 「无 path argv」（那种形态下 rg 会去搜被重定向的 stdin，生产读到 EOF 出
        // exit 1 假「无匹配」，测试执行器则阻塞挂死）
        assertThrows(IllegalArgumentException.class,
                () -> RgTools.buildArgv(RG, "foo", null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> RgTools.buildArgv(RG, "foo", "  ", null, null, null, null));
    }

    @Test
    void normalizePathDefaultsBlankToWorkspaceRootAbsolute() {
        String abs = WS.toAbsolutePath().normalize().toString();
        assertEquals(abs, RgTools.normalizePath(null, WS));
        assertEquals(abs, RgTools.normalizePath("  ", WS));
        assertEquals("src/main", RgTools.normalizePath("src/main", WS),
                "非空 path 原样透传（相对路径由 spawn cwd=工作区根解析）");
    }

    @Test
    void argvWithPath() {
        assertArrayEquals(new String[] { "C:\\tools\\rg.exe", "--", "foo", "src/main" },
                RgTools.buildArgv(RG, "foo", "src/main", null, null, null, null));
    }

    @Test
    void argvAllFlagsInFixedOrderBeforeSeparator() {
        assertArrayEquals(new String[] { "C:\\tools\\rg.exe", "-i", "-l", "-m", "5",
                        "--glob", "*.java", "--", "foo", "src" },
                RgTools.buildArgv(RG, "foo", "src", "*.java", true, true, 5));
    }

    @Test
    void argvNonPositiveMaxAndNullFlagsOmitted() {
        assertArrayEquals(new String[] { "C:\\tools\\rg.exe", "--", "foo", "src" },
                RgTools.buildArgv(RG, "foo", "src", null, false, false, 0));
        assertArrayEquals(new String[] { "C:\\tools\\rg.exe", "--", "foo", "src" },
                RgTools.buildArgv(RG, "foo", "src", " ", null, null, -3));
    }

    @Test
    void argvSeparatorProtectsLeadingDashPattern() {
        // 模式以「-」开头也不被误读为 flag（「--」在前）
        assertArrayEquals(new String[] { "C:\\tools\\rg.exe", "--", "-m", "docs" },
                RgTools.buildArgv(RG, "-m", "docs", null, null, null, null));
    }

    // ── 命令串形态 ────────────────────────────────────────────────────

    @Test
    void commandFormQuotesWhitespaceAndEmptyArgs() {
        assertEquals("rg -- foo", RgTools.commandForm(new String[] { "rg", "--", "foo" }));
        assertEquals("rg -- \"foo bar\" \"C:\\x y\"",
                RgTools.commandForm(new String[] { "rg", "--", "foo bar", "C:\\x y" }));
        assertEquals("rg -- \"\" x", RgTools.commandForm(new String[] { "rg", "--", "", "x" }));
        assertEquals("rg -- \"say \\\"hi\\\"\"",
                RgTools.commandForm(new String[] { "rg", "--", "say \"hi\"" }));
    }

    // ── 结果格式化 ────────────────────────────────────────────────────

    @Test
    void formatExit0PassesStdoutThrough() {
        assertEquals("a.txt:foo\n", RgTools.formatResult(new ExecResult("a.txt:foo\n", "", 0, false)));
    }

    @Test
    void formatExit0EmptyStdoutUsesPlaceholder() {
        assertEquals(ExecResults.format(new ExecResult("", "", 0, false)),
                RgTools.formatResult(new ExecResult("", "", 0, false)));
    }

    @Test
    void formatExit1MarksNoMatchExplicitly() {
        assertEquals("[rg] 无匹配\n", RgTools.formatResult(new ExecResult("", "", 1, false)));
    }

    @Test
    void formatExit1KeepsStdoutAndStderr() {
        String s = RgTools.formatResult(new ExecResult("partial\n", "warn", 1, false));
        assertTrue(s.contains("partial"), "exit 1 前已产生的 stdout 照出");
        assertTrue(s.contains("[rg] 无匹配"));
        assertTrue(s.contains("[stderr]"), "exit 1 伴随 stderr 时附 [stderr] 段");
        assertTrue(s.contains("warn"));
    }

    @Test
    void formatExit2OutputsStderrWithExitNote() {
        String s = RgTools.formatResult(new ExecResult("", "regex parse error", 2, false));
        assertTrue(s.contains("[stderr]"));
        assertTrue(s.contains("regex parse error"));
        assertTrue(s.contains("[exit code: 2]"));
    }

    @Test
    void formatAbortedAddsTimeoutNoteAndNoNoMatchClaim() {
        // 超时中止（exit 1 是销毁时的进程码）不是「无匹配」：不得标「[rg] 无匹配」
        String s = RgTools.formatResult(new ExecResult("", "", 1, true));
        assertTrue(s.contains(ExecResults.TIMEOUT_NOTE));
        assertFalse(s.contains(RgTools.NO_MATCH_NOTE));
    }

    // ── 执行链：门禁 → spawn → 记账 ──────────────────────────────────

    @Test
    void blankPatternRejectedBeforeAnySpawn() {
        RecordingNativeExec exec = new RecordingNativeExec();
        RgTools tools = new RgTools(RG, WS, c -> { }, null, exec);
        assertThrows(IllegalArgumentException.class,
                () -> tools.rg("  ", null, null, null, null, null));
        assertEquals(0, exec.spawns, "pattern 缺失时不得 spawn");
    }

    @Test
    void gateRefusalShortCircuitsSpawn() {
        RecordingNativeExec exec = new RecordingNativeExec();
        RgTools tools = new RgTools(RG, WS, c -> {
            throw new RuntimeException("授权被拒");
        }, null, exec);
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> tools.rg("foo", "C:\\outside", null, null, null, null));
        assertEquals("授权被拒", ex.getMessage());
        assertEquals(0, exec.spawns, "授权被拒时命令不得执行（§7.8：先授权后执行）");
    }

    @Test
    void omittedPathNormalizesToWorkspaceRootInArgv() {
        // 「path 省略默认工作区根」由 argv 显式落实：末位恒为工作区根绝对路径，
        // 不依赖 rg「无 path 搜 cwd」语义（该前提在 stdin 重定向下不成立）
        RecordingNativeExec exec = new RecordingNativeExec();
        RgTools tools = new RgTools(RG, WS, c -> { }, null, exec);
        String abs = WS.toAbsolutePath().normalize().toString();

        tools.rg("foo", null, null, null, null, null);
        assertEquals(abs, exec.lastArgv[exec.lastArgv.length - 1]);
        assertEquals("--", exec.lastArgv[exec.lastArgv.length - 3], "分隔符在 pattern 之前");

        // 空白 path 同样归一化（blank 与 null 同一契约）
        tools.rg("foo", "   ", null, null, null, null);
        assertEquals(abs, exec.lastArgv[exec.lastArgv.length - 1]);
    }

    @Test
    void gateSeesCommandFormAndExecSeesArgvCwdEnvTimeout() {
        RecordingNativeExec exec = new RecordingNativeExec();
        exec.next = new ExecResult("a.txt:foo\n", "", 0, false);
        List<String> authorized = new ArrayList<>();
        RgTools tools = new RgTools(RG, WS, (CommandGate) authorized::add, null, exec);
        String out = tools.rg("foo bar", "C:\\outside dir", null, null, null, null);
        assertEquals("a.txt:foo\n", out);
        // 门禁收到的是与记账同源的命令串形态（含越界路径，交授权链判定）
        String[] argv = RgTools.buildArgv(RG, "foo bar", "C:\\outside dir", null, null, null, null);
        assertEquals(RgTools.commandForm(argv), authorized.get(0));
        // 执行侧收到的是未加壳的 argv（含空白原样），cwd=工作区根、env 空、超时 30s
        assertArrayEquals(argv, exec.lastArgv);
        assertEquals(WS, exec.lastCwd);
        assertEquals(Map.of(), exec.lastEnv);
        assertEquals(RgTools.TIMEOUT_MS, exec.lastTimeoutMs);
    }

    @Test
    void outcomeAccountingSkipsNoMatchButRecordsRealFailures() {
        ToolOutcomeLog log = new ToolOutcomeLog();
        RecordingNativeExec exec = new RecordingNativeExec();
        RgTools tools = new RgTools(RG, WS, c -> { }, log, exec);
        String key = RgTools.commandForm(RgTools.buildArgv(RG, "foo",
                RgTools.normalizePath(null, WS), null, null, null, null));

        // exit 0（有匹配）：正常结果不记账（查不到即成功）
        exec.next = new ExecResult("a.txt:foo\n", "", 0, false);
        tools.rg("foo", null, null, null, null, null);
        assertNull(log.consumeShellExit("rg", key), "exit 0 不记账");

        // exit 1（无匹配）：正常结局，同样不记账——照记会被「非零即红」标红
        exec.next = new ExecResult("", "", 1, false);
        tools.rg("foo", null, null, null, null, null);
        assertNull(log.consumeShellExit("rg", key), "exit 1 无匹配不记账");

        // exit 2（错误）：按失败记账
        exec.next = new ExecResult("", "regex parse error", 2, false);
        tools.rg("foo", null, null, null, null, null);
        ToolOutcomeLog.ShellExit err = log.consumeShellExit("rg", key);
        assertTrue(err != null && err.failed(), "exit 2 记失败");
        assertEquals(2, err.exitCode());

        // 超时中止：按失败记账（即使进程码是 1）
        exec.next = new ExecResult("", "", 1, true);
        tools.rg("foo", null, null, null, null, null);
        ToolOutcomeLog.ShellExit aborted = log.consumeShellExit("rg", key);
        assertTrue(aborted != null && aborted.failed(), "超时中止记失败");
        assertTrue(aborted.aborted());
    }

    // ── 真实 spawn 宿主 rg（RgResolver 第一档：仓库 bin/rg.exe 复制到 TempDir）──

    @Test
    void realSpawnSearch(@TempDir Path dir) throws Exception {
        RgTools tools = realRgTools(dir);
        Files.writeString(dir.resolve("alpha.txt"), "hello needle world\n");
        Files.writeString(Files.createDirectories(dir.resolve("sub")).resolve("beta.md"),
                "nothing here\n");

        // path 省略 → argv 显式携带工作区根绝对路径（dir），rg 与 stdin 无关；
        // 匹配行前缀按所传路径形态回显（绝对路径），用 contains 断言文件名
        String hit = tools.rg("needle", null, null, null, null, null);
        assertTrue(hit.contains("alpha.txt") && hit.contains("needle"), () -> "实际输出: " + hit);

        // 无匹配：exit 1 → 显式「[rg] 无匹配」
        assertEquals("[rg] 无匹配\n", tools.rg("absent-token-xyz", null, null, null, null, null));
    }

    @Test
    void realSpawnFlags(@TempDir Path dir) throws Exception {
        RgTools tools = realRgTools(dir);
        Files.writeString(dir.resolve("alpha.txt"), "HeLLo\n");
        Files.writeString(dir.resolve("beta.md"), "hello\n");

        // 大小写敏感：HeLLo 不匹配 hello；case_insensitive=true 命中。
        // rg 对单文件参数只输出匹配行、不带文件名前缀（前缀仅目录/多目标形态出现）
        assertEquals("[rg] 无匹配\n", tools.rg("hello", "alpha.txt", null, null, null, null));
        String ci = tools.rg("hello", "alpha.txt", null, true, null, null);
        assertTrue(ci.contains("HeLLo"), () -> "实际输出: " + ci);

        // glob 只搜 *.md
        String globbed = tools.rg("hello", null, "*.md", null, null, null);
        assertTrue(globbed.contains("beta.md") && !globbed.contains("alpha.txt"),
                () -> "实际输出: " + globbed);

        // files_with_matches：只列文件名、不带匹配行；path 已归一化为绝对路径，
        // -l 原样回显完整路径 → contains 断言文件名
        String names = tools.rg("hello", null, null, null, true, null);
        assertTrue(names.contains("beta.md") && !names.contains("hello"),
                () -> "实际输出: " + names);

        // max_results：3 行匹配只出 1 行
        Files.writeString(dir.resolve("dup.txt"), "dup\ndup\ndup\n");
        String limited = tools.rg("dup", "dup.txt", null, null, null, 1);
        assertEquals(1, limited.strip().split("\n").length, () -> "实际输出: " + limited);
    }

    @Test
    void realSpawnInvalidRegexReportsStderrAndExit2(@TempDir Path dir) throws Exception {
        RgTools tools = realRgTools(dir);
        Files.writeString(dir.resolve("a.txt"), "x\n");
        String bad = tools.rg("(", "a.txt", null, null, null, null);
        assertTrue(bad.contains("[exit code: 2]") && bad.contains("[stderr]"),
                () -> "实际输出: " + bad);
        assertTrue(bad.toLowerCase().contains("parse error") || bad.toLowerCase().contains("error"),
                () -> "实际输出: " + bad);
    }

    /**
     * 用 RgResolver 第一档造真实可执行环境：把仓库自带的
     * {@code every-agent-plugins/ripgrep-tool/bin/<rg>} 复制到 TempDir 假插件根的
     * {@code bin/} 下解析（surefire cwd=模块根，相对路径即达）；仓库未携带时跳过。
     * 执行器用测试专用的 ProcessBuilder 直跑（生产路径的超时/输出上限由宿主
     * NativeExec 实现，不在测试里复刻）。
     */
    private static RgTools realRgTools(Path dir) throws Exception {
        String name = System.getProperty("os.name").toLowerCase().contains("win") ? "rg.exe" : "rg";
        Path bundled = Path.of("bin", name);
        assumeTrue(Files.isReadable(bundled), "仓库未携带 " + bundled + "，跳过真实 rg 用例");
        Path pluginDir = Files.createDirectories(dir.resolve("plugin").resolve("bin")).getParent();
        Files.copy(bundled, pluginDir.resolve("bin").resolve(name));
        RgResolver.Resolved rg = RgResolver.resolve(pluginDir, null);
        assumeTrue(rg.available(), "第一档解析失败，跳过真实 rg 用例");
        return new RgTools(rg.binary(), dir, c -> { }, null, new HostNativeExec());
    }

    /** 记账 fake：记录 spawn 入参并回放预设结果（或抛预设异常）。 */
    static final class RecordingNativeExec implements NativeExec {
        int spawns;
        String[] lastArgv;
        Path lastCwd;
        Map<String, String> lastEnv;
        long lastTimeoutMs;
        ExecResult next = new ExecResult("", "", 0, false);

        @Override
        public ExecResult spawnNative(String[] argv, Path cwd, Map<String, String> env, long timeoutMs) {
            spawns++;
            lastArgv = argv;
            lastCwd = cwd;
            lastEnv = env;
            lastTimeoutMs = timeoutMs;
            return next;
        }
    }

    /**
     * 测试专用宿主执行器：ProcessBuilder argv 直跑 + 超时销毁。仅测试使用——
     * 生产路径必须走 ctx.services().nativeExec()（超时/输出上限内建，不自搓）。
     */
    static final class HostNativeExec implements NativeExec {
        @Override
        public ExecResult spawnNative(String[] argv, Path cwd, Map<String, String> env, long timeoutMs) {
            try {
                Process p = new ProcessBuilder(argv).directory(cwd.toFile()).start();
                // 防御性对齐生产语义：worker 的 OsSandbox.spawnNative 把子进程 stdin
                // 重定向到 NUL 设备（rg 若被无 path 调用会读到 EOF → exit 1 而非挂死）。
                // 此处 spawn 后立即关掉 stdin 写端模拟同一形态——一旦上游再拼出无 path
                // 的 argv，本测试得到的是可断言的快速结果而不是 rg 阻塞读管道挂死。
                p.getOutputStream().close();
                // 测试输出量极小，顺序读两流即可（不会撑爆管道缓冲）
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                    p.waitFor();
                    return new ExecResult(out, err, p.exitValue(), true);
                }
                return new ExecResult(out, err, p.exitValue(), false);
            } catch (Exception e) {
                throw new RuntimeException("测试 spawn 失败: " + argv[0], e);
            }
        }
    }
}
