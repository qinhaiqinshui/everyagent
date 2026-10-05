package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.api.shell.ExecResults;
import dev.everyagent.plugin.sandbox.codex.CodexCommandExecutor.ExecSession;
import dev.everyagent.plugin.sandbox.codex.accounts.SandboxAccounts.NetworkIdentity;
import dev.everyagent.plugin.sandbox.codex.runner.FrameCodec.FramedMessage;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.Exit;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.Output;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.Stream;
import dev.everyagent.plugin.sandbox.codex.session.CodexSandboxSession;
import dev.everyagent.plugin.sandbox.codex.session.RunnerClient;

import com.sun.jna.Platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link CodexCommandExecutor}：argv/env/聚合/格式化纯逻辑 + fake 会话的
 * 执行链（preflight → SpawnRequest 组装 → 收帧聚合 → 尾注）。
 */
class CodexCommandExecutorTest {

    @TempDir
    Path tempDir;

    // ---- fake 会话 ----

    static final class FakeSession implements ExecSession {
        final Deque<FramedMessage> queue = new ArrayDeque<>();
        boolean endlessOutput;
        boolean terminated;
        boolean closed;

        FakeSession(FramedMessage... frames) {
            for (FramedMessage f : frames) {
                queue.add(f);
            }
        }

        @Override
        public FramedMessage receive() {
            if (endlessOutput && !terminated) {
                return out("x", Stream.STDOUT);
            }
            return queue.poll();
        }

        @Override
        public void terminate() {
            terminated = true;
            queue.clear();
            queue.add(new FramedMessage(6, new Exit(192, true)));
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    static FramedMessage out(String text, Stream stream) {
        return new FramedMessage(6, new Output(
                IpcMessage.encodeBytes(text.getBytes(StandardCharsets.UTF_8)), stream));
    }

    /** 会话/配置捕获器（断言 SpawnRequest 组装）。 */
    static final class Capture {
        CodexSandboxSession.SessionSpec spec;
        RunnerClient.RunnerConfig cfg;
        List<Path> prefetchedRoots;
        final FakeSession session;

        Capture(FakeSession session) {
            this.session = session;
        }
    }

    private CodexCommandExecutor executor(Capture capture, FakeSession session,
            long timeoutMs) {
        CodexSandboxManager manager = new CodexSandboxManager(
                new CodexSandboxOptions(tempDir, null, null, null, false, null), timeoutMs);
        return new CodexCommandExecutor(manager, tempDir.resolve("ws"), null, false,
                (cfg, spec) -> {
                    capture.cfg = cfg;
                    capture.spec = spec;
                    return session;
                },
                (options, capSids, writeRoots, readRoots) -> capture.prefetchedRoots =
                        new ArrayList<>(writeRoots),
                (options, username) -> new RunnerClient.RunnerConfig(
                        options.codexHome(), username, "pw", "cp", "java.home", "cwd"));
    }

    // ---- 纯函数 ----

    @Test
    void commandArgvIsPowerShellNoProfile() {
        CodexCommandExecutor exec = executor(new Capture(new FakeSession()), new FakeSession(), 30_000);
        assertEquals(List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                        "-Command", ExecResults.POWERSHELL_PREFIX + "echo hi"
                                + ExecResults.POWERSHELL_EXIT_TAIL),
                exec.commandArgv("echo hi"));
    }

    @Test
    void commandArgvIsCmdFallback() {
        CodexCommandExecutor exec = new CodexCommandExecutor(
                new CodexSandboxManager(new CodexSandboxOptions(tempDir, null, null, null, false, null), 30_000),
                tempDir.resolve("ws"), null, false, CodexCommandExecutor.ShellChoice.CMD,
                (cfg, spec) -> new FakeSession(new FramedMessage(6, new Exit(0, false))),
                (options, capSids, w, r) -> { },
                (options, username) -> new RunnerClient.RunnerConfig(
                        options.codexHome(), username, "pw", "cp", "jh", "cwd"));
        assertEquals(List.of("cmd.exe", "/c", "chcp 65001 >nul & echo hi"),
                exec.commandArgv("echo hi"));
    }

    @Test
    void childEnvPrependsRgDirToPath() {
        Path rg = tempDir.resolve("bin/rg.exe");
        Map<String, String> env = CodexCommandExecutor.childEnv(rg, null);
        String key = env.containsKey("Path") ? "Path" : "PATH";
        assertTrue(env.get(key).startsWith(rg.getParent().toString()),
                "rg 所在目录前置进 Path:" + env.get(key));
        Map<String, String> plain = CodexCommandExecutor.childEnv(null, null);
        assertEquals(dev.everyagent.plugin.api.util.SecretPatterns.scrubEnv(System.getenv()).env(),
                plain, "无 rg 时继承「凭据剔除后的父环境」(整块原样继承是泄露面,见 SecretPatterns)");
        assertFalse(plain.entrySet().stream()
                        .anyMatch(e -> dev.everyagent.plugin.api.util.SecretPatterns
                                .isSecretBearing(e.getKey(), e.getValue())),
                "沙箱 env 不得携带凭据形态变量");
        assertTrue(plain.size() > 0, "父环境仍被继承(不是清空)");
    }

    @Test
    void childEnvRedirectsTempToSandboxTmp() {
        Path codexHome = tempDir.resolve(".everyagent-codex-sandbox");
        Map<String, String> env = CodexCommandExecutor.childEnv(null, codexHome);
        String expected = codexHome.resolve(".sandbox").resolve("tmp").toString();
        assertEquals(expected, env.get("TEMP"),
                "沙箱账户对宿主 TEMP 无写权限,必须指到组可写的 .sandbox/tmp");
        assertEquals(expected, env.get("TMP"), "TMP 与 TEMP 同指一处");
        Map<String, String> untouched = CodexCommandExecutor.childEnv(null, null);
        assertEquals(dev.everyagent.plugin.api.util.SecretPatterns.scrubEnv(System.getenv()).env()
                        .get("TEMP"),
                untouched.get("TEMP"), "codexHome 为空时不碰 TEMP(维持宿主继承)");
    }

    @Test
    void appendCappedTruncatesAtLimitAndReports() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        assertFalse(CodexCommandExecutor.appendCapped(out, "abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals(3, out.size());
        out.reset();
        try {
            out.write(new byte[ExecResults.MAX_OUTPUT_CHARS - 2]);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        assertTrue(CodexCommandExecutor.appendCapped(out, "abcdef".getBytes(StandardCharsets.UTF_8)));
        assertEquals(ExecResults.MAX_OUTPUT_CHARS, out.size());
    }

    @Test
    void formatMatchesWslStyleAnnotations() {
        assertEquals("hello", CodexCommandExecutor.format(
                new CodexCommandExecutor.SessionRun("hello", "", 0, false, false, false)));
        assertEquals("out\n[stderr]\nboom\n[exit code: 3]", CodexCommandExecutor.format(
                new CodexCommandExecutor.SessionRun("out", "boom", 3, false, false, false)));
        assertEquals("x\n[命令被沙箱超时中止]\n[exit code: 192]", CodexCommandExecutor.format(
                new CodexCommandExecutor.SessionRun("x", "", 192, true, false, false)));
        assertEquals("[runner 管道在 exit 帧前关闭]", CodexCommandExecutor.format(
                new CodexCommandExecutor.SessionRun("", "", 0, false, true, false)));
        assertTrue(CodexCommandExecutor.format(
                new CodexCommandExecutor.SessionRun("y", "", 0, false, false, true))
                .endsWith("[输出已截断至 1000000 字符]"));
    }

    @Test
    void wireNameMapsIdentity() {
        assertEquals("offline", CodexCommandExecutor.wireName(NetworkIdentity.OFFLINE));
        assertEquals("online", CodexCommandExecutor.wireName(NetworkIdentity.ONLINE));
    }

    // ---- 输出解码（BUG-1：codex 沙箱中文乱码根因的回归护栏） ----

    @Test
    void decodeConsoleOutputPrefersUtf8() {
        // ASCII 在两种编码下一致
        assertEquals("hello", ExecResults.decodeConsoleOutput(
                "hello".getBytes(StandardCharsets.UTF_8)));
        // UTF-8 中文：严格解码成功（外部程序 git/rg 等输出路径）
        String cn = "你好世界，中文输出";
        assertEquals(cn, ExecResults.decodeConsoleOutput(cn.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void decodeConsoleOutputFallsBackToAnsiForNonUtf8() throws Exception {
        // PowerShell cmdlet 在管道重定向 + CLM 下按系统 ANSI 码页(如 GBK)编码中文。
        // GBK 字节非法 UTF-8 → 严格解码失败 → 回退系统 ANSI 码页。
        String cn = "你好世界";
        byte[] gbk = cn.getBytes("GBK");
        String decoded = ExecResults.decodeConsoleOutput(gbk);
        assertFalse(decoded.isEmpty(), "GBK 字节必须有输出");
        // 当系统 ANSI 码页恰为 GBK(中文 Windows)时精确还原
        if ("GBK".equalsIgnoreCase(System.getProperty("native.encoding", ""))) {
            assertEquals(cn, decoded, "ANSI 码页回退应还原中文");
        }
    }

    @Test
    void decodeConsoleOutputRecoversTruncatedUtf8Tail() {
        // 输出在字节上限处截断，末尾剩半个 3 字节 UTF-8 字符：
        // 唯一错误在缓冲末尾 → 按 UTF-8 宽容解码（残尾 U+FFFD），而非回退 ANSI。
        byte[] full = "你".getBytes(StandardCharsets.UTF_8); // E4 BD A0
        byte[] truncated = new byte[full.length - 1];
        System.arraycopy(full, 0, truncated, 0, truncated.length);
        String decoded = ExecResults.decodeConsoleOutput(truncated);
        assertTrue(decoded.contains("\uFFFD"),
                "截断残尾应按 UTF-8 宽容解码(含 U+FFFD): " + decoded);
    }

    // ---- 执行链(fake 会话) ----

    @Test
    void executeAggregatesStreamsAndFormatsResult() {
        FakeSession session = new FakeSession(out("hello", Stream.STDOUT),
                out("boom", Stream.STDERR), new FramedMessage(6, new Exit(3, false)));
        Capture capture = new Capture(session);
        String result = executor(capture, session, 30_000).execute("echo hi", "powershell");
        assertEquals("hello\n[stderr]\nboom\n[exit code: 3]", result);
        assertTrue(session.closed, "会话在 finally 中关闭");
        assertFalse(session.terminated, "正常退出不发 terminate");

        assertEquals(List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                        "-Command", ExecResults.POWERSHELL_PREFIX + "echo hi"
                                + ExecResults.POWERSHELL_EXIT_TAIL), capture.spec.command());
        assertEquals(tempDir.resolve("ws").toString(), capture.spec.cwd(), "cwd=工作区根");
        assertEquals(30_000L, capture.spec.timeoutMs(), "timeout=SandboxConfig/manager 值");
        assertFalse(capture.spec.stdinOpen(), "worker 契约 stdin 关闭");
        assertNull(capture.spec.privateDesktopName(), "私有桌面尚未接线");
        assertEquals("online", capture.spec.networkIdentity(), "auto+未断网 → online");
    }

    @Test
    void specCarriesWorkspaceCapAndWriteRoots() {
        FakeSession session = new FakeSession(new FramedMessage(6, new Exit(0, false)));
        Capture capture = new Capture(session);
        executor(capture, session, 30_000).execute("dir", "powershell");
        assertFalse(capture.spec.writeRoots().isEmpty(), "至少含工作区根");
        assertTrue(capture.spec.workspaceCapSid().startsWith("S-1-5-21-"),
                "workspace cap SID 惰性创建");
        assertEquals(1, capture.prefetchedRoots.size(), "preflight 收到会话写根");
        assertTrue(capture.cfg.username().endsWith("Online"),
                "auto+未断网 → Online 账户");
    }

    @Test
    void executeRejectsBlankCommand() {
        FakeSession session = new FakeSession();
        assertEquals("execute: command 不能为空",
                executor(new Capture(session), session, 30_000).execute("   ", "powershell"));
    }

    @Test
    void pipeEofBeforeExitMarksInterrupted() {
        FakeSession session = new FakeSession(out("partial", Stream.STDOUT));
        String result = executor(new Capture(session), session, 30_000)
                .execute("hang", "powershell");
        assertTrue(result.contains("partial"));
        assertTrue(result.endsWith("[runner 管道在 exit 帧前关闭]"));
    }

    @Test
    void parentWatchdogTerminatesOnDeadline() {
        FakeSession session = new FakeSession();
        session.endlessOutput = true;
        Capture capture = new Capture(session);
        String result = executor(capture, session, 20).execute("spin", "powershell");
        assertTrue(session.terminated, "超时后发出 terminate 帧");
        assertTrue(result.contains("[命令被沙箱超时中止]"));
        assertTrue(result.contains("[exit code: 192]"));
    }

    @Test
    void oversizedOutputTruncatedPerStream() {
        String chunk = "x".repeat(600_000);
        FakeSession session = new FakeSession(out(chunk, Stream.STDOUT),
                out(chunk, Stream.STDOUT), new FramedMessage(6, new Exit(0, false)));
        String result = executor(new Capture(session), session, 30_000)
                .execute("big", "powershell");
        int cut = result.indexOf("\n[输出已截断至");
        assertTrue(cut > 0, "含截断尾注: " + result.substring(Math.max(0, result.length() - 60)));
        assertEquals(ExecResults.MAX_OUTPUT_CHARS, cut, "stdout 精确截断到上限");
        assertTrue(result.endsWith("[输出已截断至 1000000 字符]"));
    }

    @Test
    void aggregateDecodesAnsiBytesViaFallback() throws Exception {
        // PowerShell cmdlet 在管道重定向 + CLM 下按系统 ANSI 码页(如 GBK)编码中文,
        // 经会话原始字节回传后,聚合端智能解码应还原而非乱码。
        String cn = "你好世界";
        byte[] gbk = cn.getBytes("GBK");
        FakeSession session = new FakeSession(
                new FramedMessage(6, new Output(IpcMessage.encodeBytes(gbk), Stream.STDOUT)),
                new FramedMessage(6, new Exit(0, false)));
        String result = executor(new Capture(session), session, 30_000)
                .execute("Write-Output x", "powershell");
        assertFalse(result.isEmpty(), "GBK 字节必须有输出");
        if ("GBK".equalsIgnoreCase(System.getProperty("native.encoding", ""))) {
            assertTrue(result.startsWith(cn), "ANSI 码页回退应还原中文: " + result);
        }
    }

    // ---- 闸门 ----

    @Test
    void nativeGuardRejectsNonWindows() {
        assumeTrue(!Platform.isWindows(), "本用例固化非 Windows 行为");
        FakeSession session = new FakeSession();
        CodexSandboxManager manager = new CodexSandboxManager(
                new CodexSandboxOptions(tempDir, null, null, null, false, null), 30_000);
        CodexCommandExecutor exec = new CodexCommandExecutor(manager,
                tempDir.resolve("ws"), null);
        String result = exec.execute("echo hi", "powershell");
        assertTrue(result.startsWith("[codex sandbox 仅在 Windows 上可用"),
                "非 Windows 给可读错误: " + result);
    }

    @Test
    void nativeGuardRejectsIncompleteSetupWithGuidance() {
        assumeTrue(Platform.isWindows(), "Windows 专属(marker 闸门)");
        FakeSession session = new FakeSession();
        CodexSandboxManager manager = new CodexSandboxManager(
                new CodexSandboxOptions(tempDir, null, null, null, false, null), 30_000);
        CodexCommandExecutor exec = new CodexCommandExecutor(manager,
                tempDir.resolve("ws"), null);
        String result = exec.execute("echo hi", "powershell");
        assertTrue(result.startsWith("[codex sandbox 未完成 setup"));
        assertTrue(result.contains("重新激活"), "错误里给出重新激活的指引");
    }

    @Test
    void sessionFailureReturnsReadableError() {
        CodexSandboxManager manager = new CodexSandboxManager(
                new CodexSandboxOptions(tempDir, null, null, null, false, null), 30_000);
        CodexCommandExecutor exec = new CodexCommandExecutor(manager,
                tempDir.resolve("ws"), null, false,
                (cfg, spec) -> {
                    throw new IOException("pipe broken");
                },
                (options, capSids, w, r) -> {
                },
                (options, username) -> new RunnerClient.RunnerConfig(
                        options.codexHome(), username, "pw", "cp", "jh", "cwd"));
        assertEquals("[codex sandbox 执行失败] pipe broken", exec.execute("x", "powershell"));
    }
}
