package dev.everyagent.plugin.sandbox.codex;

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
        assertEquals(List.of("powershell.exe", "-NoProfile", "-Command", CodexCommandExecutor.POWERSHELL_PREFIX + "echo hi"),
                CodexCommandExecutor.commandArgv("echo hi"));
    }

    @Test
    void childEnvPrependsRgDirToPath() {
        Path rg = tempDir.resolve("bin/rg.exe");
        Map<String, String> env = CodexCommandExecutor.childEnv(rg);
        String key = env.containsKey("Path") ? "Path" : "PATH";
        assertTrue(env.get(key).startsWith(rg.getParent().toString()),
                "rg 所在目录前置进 Path:" + env.get(key));
        Map<String, String> plain = CodexCommandExecutor.childEnv(null);
        assertEquals(System.getenv(), plain, "无 rg 时原样继承环境");
    }

    @Test
    void appendCappedTruncatesAtLimitAndReports() {
        StringBuilder sb = new StringBuilder();
        assertFalse(CodexCommandExecutor.appendCapped(sb, "abc"));
        assertEquals(3, sb.length());
        sb.setLength(CodexCommandExecutor.MAX_OUTPUT_CHARS - 2);
        assertTrue(CodexCommandExecutor.appendCapped(sb, "abcdef"));
        assertEquals(CodexCommandExecutor.MAX_OUTPUT_CHARS, sb.length());
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

        assertEquals(List.of("powershell.exe", "-NoProfile", "-Command", CodexCommandExecutor.POWERSHELL_PREFIX + "echo hi"), capture.spec.command());
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
        assertEquals(CodexCommandExecutor.MAX_OUTPUT_CHARS, cut, "stdout 精确截断到上限");
        assertTrue(result.endsWith("[输出已截断至 1000000 字符]"));
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
        assertTrue(result.contains("codex_sandbox_setup"), "错误里给出显式 setup 指引");
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
