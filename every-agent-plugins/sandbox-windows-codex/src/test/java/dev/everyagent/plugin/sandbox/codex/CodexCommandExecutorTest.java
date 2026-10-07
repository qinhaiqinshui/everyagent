package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.api.shell.ExecResults;
import dev.everyagent.plugin.sandbox.codex.CodexCommandExecutor.ExecSession;
import dev.everyagent.plugin.sandbox.codex.accounts.SandboxAccounts.NetworkIdentity;
import dev.everyagent.plugin.sandbox.codex.accounts.SandboxSecrets;
import dev.everyagent.plugin.sandbox.codex.runner.FrameCodec.FramedMessage;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.ErrorStage;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.Exit;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.Output;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.Stream;
import dev.everyagent.plugin.sandbox.codex.session.CodexSandboxSession;
import dev.everyagent.plugin.sandbox.codex.session.CodexSandboxSession.RunnerStartupException;
import dev.everyagent.plugin.sandbox.codex.session.RunnerClient;
import dev.everyagent.plugin.sandbox.codex.setup.SetupMarker;
import dev.everyagent.plugin.sandbox.codex.setup.SetupPayload;

import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Win32Exception;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void commandArgvIsPowerShellCmdChcpEncoded() {
        CodexCommandExecutor exec = executor(new Capture(new FakeSession()), new FakeSession(), 30_000);
        List<String> argv = exec.commandArgv("echo hi");
        assertEquals("cmd.exe", argv.get(0), "PowerShell 分支必须经 cmd 包装以便先 chcp");
        assertEquals("/d", argv.get(1));
        assertEquals("/s", argv.get(2));
        assertEquals("/c", argv.get(3));
        String payload = argv.get(4);
        assertTrue(payload.startsWith("chcp.com 65001 >nul 2>&1 & powershell.exe -NoProfile"
                + " -ExecutionPolicy Bypass -EncodedCommand "),
                "载荷形态:chcp 前置 + -EncodedCommand 投递,cmd 安全字符集免疫引号嵌套:" + payload);
        String b64 = payload.substring(payload.indexOf("-EncodedCommand ")
                + "-EncodedCommand ".length());
        String script = new String(java.util.Base64.getDecoder().decode(b64),
                StandardCharsets.UTF_16LE);
        assertEquals(ExecResults.POWERSHELL_PREFIX + "echo hi" + ExecResults.POWERSHELL_EXIT_TAIL,
                script, "base64(UTF-16LE,无 BOM) 应还原 prefix+命令+exit 尾部");
        assertFalse(b64.startsWith("/"), "Java UTF_16LE 编码不带 BOM(PS -EncodedCommand 裸载荷)");
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
    void buildScriptPutsUserCommandOnOwnLine() {
        String script = CodexCommandExecutor.buildScript("java -version 2>&1");
        String[] lines = script.split("\n", -1);
        assertEquals(3, lines.length, "三段式:prefix/用户命令/exit 尾部各占一行");
        assertEquals(ExecResults.POWERSHELL_PREFIX.trim(), lines[0].trim(), "第 1 行=包装前缀");
        assertEquals("java -version 2>&1", lines[1],
                "用户命令独占一行:PS 报错定位引用用户命令而非内部包装前缀"
                        + "(At <脚本>:2 char:1 + java -version 2>&1)");
        assertEquals(ExecResults.POWERSHELL_EXIT_TAIL, lines[2], "第 3 行=exit 传导尾部");
    }

    @Test
    void commandArgvForFileUsesQuoteFreeWorkspaceRelativePath() {
        CodexCommandExecutor exec = executor(new Capture(new FakeSession()),
                new FakeSession(), 30_000);
        String fileArg = Path.of(".everyagent", "tmp", "ea-cmd-1.ps1").toString();
        List<String> argv = exec.commandArgvForFile(fileArg);
        assertEquals(List.of("cmd.exe", "/d", "/s", "/c",
                "chcp.com 65001 >nul 2>&1 & powershell.exe -NoProfile"
                        + " -ExecutionPolicy Bypass -File " + fileArg), argv,
                "cmd-chcp 包装不变(码页先于 PS 启动);-File 载荷=无引号相对路径——载荷内的"
                        + "字面引号经 runner argvToCommandLine 转义成反斜杠引号,cmd /s /c 保留后"
                        + "被 PS 解析成路径字符,实测报 Illegal characters in path");
        assertFalse(argv.get(4).contains("\""),
                "回归护栏:整个载荷不得出现任何字面引号(2026-12 codex 沙箱实测回归)");
    }

    @Test
    void scriptFileArgRelativizesAgainstWorkspaceAndRejectsOutside() throws IOException {
        Path ws = Files.createDirectories(tempDir.resolve("ws-filearg"));
        Path script = ws.resolve(".everyagent").resolve("tmp").resolve("ea-cmd-7-ab.ps1");
        String arg = CodexCommandExecutor.scriptFileArg(ws, script);
        assertEquals(Path.of(".everyagent", "tmp", "ea-cmd-7-ab.ps1").toString(), arg,
                "cwd=工作区根,-File 用工作区相对路径");
        assertFalse(arg.contains(" ") || arg.contains("\""),
                "自生成分量无空格无引号,任何序列化层都不会给它加引号:" + arg);
        assertThrows(IOException.class, () -> CodexCommandExecutor.scriptFileArg(ws,
                ws.resolveSibling("outside.ps1")),
                "脚本不在工作区内→IOException→调用方回退 -EncodedCommand"
                        + "(绝不回退成带引号绝对路径)");
    }

    @Test
    void writeCommandScriptEmitsUtf8BomPs1AndCleanupIsCallerSide() throws IOException {
        Path ws = Files.createDirectories(tempDir.resolve("ws-script"));
        Path script = CodexCommandExecutor.writeCommandScript(ws, "echo 中文");
        try {
            assertTrue(script.getFileName().toString().startsWith("ea-cmd-"), "命名前缀");
            assertTrue(script.getFileName().toString().endsWith(".ps1"),
                    "-File 强制 .ps1 扩展:" + script);
            byte[] bytes = Files.readAllBytes(script);
            assertEquals(0xEF, bytes[0] & 0xFF, "UTF-8 BOM(PS 5.1 读无 BOM 文件按 ACP 解)");
            assertEquals(0xBB, bytes[1] & 0xFF);
            assertEquals(0xBF, bytes[2] & 0xFF);
            String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
            assertEquals(CodexCommandExecutor.buildScript("echo 中文"), text,
                    "内容=三段式脚本本体");
        } finally {
            Files.deleteIfExists(script);
        }
    }

    @Test
    void childEnvPrependsRgDirToPath() {
        Path rg = tempDir.resolve("bin/rg.exe");
        Map<String, String> env = CodexCommandExecutor.childEnv(rg, null, null);
        String key = env.containsKey("Path") ? "Path" : "PATH";
        assertTrue(env.get(key).startsWith(rg.getParent().toString()),
                "rg 所在目录前置进 Path:" + env.get(key));
        Map<String, String> plain = CodexCommandExecutor.childEnv(null, null, null);
        Map<String, String> expected = new java.util.LinkedHashMap<>(
                dev.everyagent.plugin.api.util.SecretPatterns.scrubEnv(System.getenv()).env());
        expected.putIfAbsent("JAVA_TOOL_OPTIONS",
                "-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8");
        assertEquals(expected, plain,
                "无 rg 时继承「凭据剔除后的父环境」+ JVM 流编码钉住(整块原样继承是泄露面,"
                        + "见 SecretPatterns;JAVA_TOOL_OPTIONS 见 childEnvPinsJvmStreamEncodingToUtf8)");
        assertFalse(plain.entrySet().stream()
                        .anyMatch(e -> dev.everyagent.plugin.api.util.SecretPatterns
                                .isSecretBearing(e.getKey(), e.getValue())),
                "沙箱 env 不得携带凭据形态变量");
        assertTrue(plain.size() > 0, "父环境仍被继承(不是清空)");
    }

    @Test
    void childEnvPinsJvmStreamEncodingToUtf8() {
        Map<String, String> env = CodexCommandExecutor.childEnv(null, null, null);
        assertEquals("-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8",
                env.get("JAVA_TOOL_OPTIONS"),
                "JDK 18+(JEP 400)重定向流默认按 native.encoding(中文机器=GBK,实测"
                        + " java -XshowSettings:properties 显示 stdout/stderr.encoding=GBK)编码:"
                        + "直出 GBK 字节混流触发整流 GBK 回退(PS 字面量中文反向乱码),"
                        + "PS 管道捕获时被按 UTF-8 有损解成 U+FFFD(不可逆,实测 mvn 中文"
                        + "断言消息全损);注入后中文探针 stdout/stderr 全对");
    }

    @Test
    void childEnvInjectsGitSafeDirectoryForRepoRoot() throws IOException {
        Path ws = tempDir.resolve("ws-git");
        Files.createDirectories(ws.resolve(".git"));
        Map<String, String> env = CodexCommandExecutor.childEnv(null, ws, null);
        String root = ws.toAbsolutePath().normalize().toString().replace('\\', '/');
        assertEquals("2", env.get("GIT_CONFIG_COUNT"), "树根+嵌套两条,对齐 codex");
        assertEquals("safe.directory", env.get("GIT_CONFIG_KEY_0"));
        assertEquals(root, env.get("GIT_CONFIG_VALUE_0"), "路径用 / (git 口径)");
        assertEquals("safe.directory", env.get("GIT_CONFIG_KEY_1"));
        assertEquals(root + "/*", env.get("GIT_CONFIG_VALUE_1"), "嵌套仓库/子模块一并信任");
    }

    @Test
    void childEnvWalksUpToFindGitRoot() throws IOException {
        Path ws = tempDir.resolve("ws-git-up");
        Files.createDirectories(ws.resolve(".git"));
        Path nested = ws.resolve("a").resolve("b");
        Files.createDirectories(nested);
        Map<String, String> env = CodexCommandExecutor.childEnv(null, nested, null);
        String root = ws.toAbsolutePath().normalize().toString().replace('\\', '/');
        assertEquals(root, env.get("GIT_CONFIG_VALUE_0"),
                "workspaceRoot 在仓库子目录时向上找到树根(codex 同款语义)");
    }

    @Test
    void childEnvNoGitRootNoInjection() throws IOException {
        Path ws = tempDir.resolve("ws-nogit").resolve("deep");
        Files.createDirectories(ws);
        // @TempDir 常落在工作区(仓库树)内部,向上找必命中工作区 .git——该场景属于
        // walk-up 的正确行为而非「无注入」;仅当 tempDir 之上确无 .git 时本用例才有效。
        boolean repoAbove = false;
        for (Path p = tempDir.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            if (Files.exists(p.resolve(".git"))) {
                repoAbove = true;
                break;
            }
        }
        assumeTrue(!repoAbove, "tempDir 之上存在 git 树根时跳过(walk-up 命中属正确行为)");
        Map<String, String> env = CodexCommandExecutor.childEnv(null, ws, null);
        assertNull(env.get("GIT_CONFIG_COUNT"), "无 .git 树根时不注入(不碰宿主已有配置)");
    }

    @Test
    void childEnvRedirectsHomeToSandboxProfile() {
        Path ws = tempDir.resolve("ws-profile");
        Path profile = Path.of("C:", "Users", "EACodexOnline");
        Map<String, String> env = CodexCommandExecutor.childEnv(null, ws, profile);
        assertEquals(profile.toString(), env.get("USERPROFILE"),
                "env 型工具(git/npm/pip)读 USERPROFILE,必须指沙箱真 profile 而非宿主目录"
                        + "(宿主只读且 .gitconfig/.npmrc 凭据不得泄露)");
        assertEquals(profile.toString(), env.get("HOME"), "跨平台工具(git/msys)优先读 HOME");
        assertEquals(profile.resolve("AppData").resolve("Roaming").toString(),
                env.get("APPDATA"));
        assertEquals(profile.resolve("AppData").resolve("Local").toString(),
                env.get("LOCALAPPDATA"), "npm cache/pip cache 默认落 LOCALAPPDATA");
        assertNull(env.get("MAVEN_ARGS"),
                "profile 方案下无任何工具特判(mvn 仓库随 user.home 走真 profile)");
        assertNull(env.get("JDK_JAVA_OPTIONS"), "JVM 的 user.home 走账户 profile,无需注入");
    }

    @Test
    void childEnvRedirectsTempIntoWorkspace() {
        Path ws = tempDir.resolve("ws-root");
        Map<String, String> env = CodexCommandExecutor.childEnv(null, ws, null);
        String expected = ws.resolve(".everyagent").resolve("tmp").toString();
        assertEquals(expected, env.get("TEMP"),
                "命令子进程是 WRITE_RESTRICTED 受限令牌,组 ACE 对其写检查无效,"
                        + "TEMP 必须指到 capability 覆盖的工作区 .everyagent/tmp");
        assertEquals(expected, env.get("TMP"), "TMP 与 TEMP 同指一处");
        Map<String, String> untouched = CodexCommandExecutor.childEnv(null, null, null);
        assertEquals(dev.everyagent.plugin.api.util.SecretPatterns.scrubEnv(System.getenv()).env()
                        .get("TEMP"),
                untouched.get("TEMP"), "workspaceRoot 为空时不碰 TEMP(维持宿主继承)");
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

        List<String> argv = capture.spec.command();
        assertEquals("cmd.exe", argv.get(0), "PowerShell 命令经 cmd-chcp 包装(BUG-1 正解)");
        assertEquals("/d", argv.get(1));
        assertEquals("/s", argv.get(2));
        assertEquals("/c", argv.get(3));
        String payload = argv.get(4);
        assertTrue(payload.startsWith("chcp.com 65001 >nul 2>&1 & powershell.exe -NoProfile"
                        + " -ExecutionPolicy Bypass -File "),
                "主形态=脚本文件承载(-File):" + payload);
        assertTrue(payload.endsWith(".ps1"),
                "扩展名 .ps1(powershell -File 强制):" + payload);
        assertFalse(payload.contains("\""),
                "载荷零引号:相对路径免引号,载荷内字面引号会被二次序列化转义成路径字符:"
                        + payload);
        assertTrue(payload.contains("ea-cmd-"), "命名 ea-cmd-<pid>-<纳秒>.ps1:" + payload);
        String fileArg = payload.substring(payload.indexOf("-File ") + "-File ".length());
        assertTrue(fileArg.startsWith(".everyagent"),
                "-File 载荷=工作区相对路径(cwd=工作区根下解析):" + payload);
        Path scriptPath = tempDir.resolve("ws").resolve(fileArg);
        assertFalse(Files.exists(scriptPath), "命令脚本在 finally 中清理:" + scriptPath);
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
    void nativeGuardHealsIncompleteSetupInsteadOfRejecting() throws IOException {
        assumeTrue(Platform.isWindows(), "Windows 专属(marker 闸门)");
        FakeSession session = new FakeSession(out("healed", Stream.STDOUT),
                new FramedMessage(6, new Exit(0, false)));
        AtomicInteger reprovisioned = new AtomicInteger();
        CodexCommandExecutor exec = new CodexCommandExecutor(manager(),
                tempDir.resolve("ws"), null, true,
                CodexCommandExecutor.ShellChoice.POWERSHELL,
                (cfg, spec) -> session,
                (options, capSids, w, r) -> {
                },
                (options, username) -> new RunnerClient.RunnerConfig(
                        options.codexHome(), username, "pw", "cp", "jh", "cwd"),
                m -> {
                    reprovisioned.incrementAndGet();
                    // fake 重 setup：补写 marker + 凭据占位（纯文件写，不弹 UAC）
                    try {
                        SetupMarker.commit(tempDir, SetupPayload.SETUP_VERSION,
                                "EACodexOffline", "EACodexOnline", List.of(), false);
                        Files.createDirectories(SandboxSecrets.secretsFile(tempDir).getParent());
                        Files.createFile(SandboxSecrets.secretsFile(tempDir));
                    } catch (IOException e) {
                        throw new IllegalStateException("fake reprovision failed", e);
                    }
                });
        String result = exec.execute("echo hi", "powershell");
        assertEquals("healed", result,
                "marker 被删 → 守卫自动重 setup 修复 → 不再要求重启 worker,继续执行命令");
        assertEquals(1, reprovisioned.get());
    }

    @Test
    void nativeGuardHealFailureReturnsReadableError() {
        assumeTrue(Platform.isWindows(), "Windows 专属(marker 闸门)");
        CodexCommandExecutor exec = new CodexCommandExecutor(manager(),
                tempDir.resolve("ws"), null, true,
                CodexCommandExecutor.ShellChoice.POWERSHELL,
                (cfg, spec) -> {
                    throw new IOException("unreachable");
                },
                (options, capSids, w, r) -> {
                },
                (options, username) -> new RunnerClient.RunnerConfig(
                        options.codexHome(), username, "pw", "cp", "jh", "cwd"),
                m -> {
                    throw new IllegalStateException("codex 沙箱 setup 失败: code=xx UAC 被拒");
                });
        String result = exec.execute("echo hi", "powershell");
        assertTrue(result.startsWith("[codex sandbox 未完成 setup 且自动修复失败]"),
                "可读错误前缀: " + result);
        assertTrue(result.contains("重发命令"),
                "错误里给出恢复指引(同意 UAC 后重发命令再次触发自愈): " + result);
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

    // ---- 账户·凭据类自愈（BUG：账户/密码被外部改动或删除后直接报错、不自愈；§4.3.1 矩阵） ----

    /** 自愈路径的 seam 注入构造器（nativeGuard=false，跨平台可测；preflight=no-op）。 */
    private CodexCommandExecutor healingExecutor(CodexSandboxManager manager,
            CodexCommandExecutor.SessionOpener opener,
            CodexCommandExecutor.Reprovisioner reprovisioner) {
        return healingExecutor(manager, opener, reprovisioner,
                (options, capSids, w, r) -> {
                });
    }

    /** 自愈路径的 seam 注入构造器（preflight 亦可注入——组被删场景由 preflight 抛 1332）。 */
    private CodexCommandExecutor healingExecutor(CodexSandboxManager manager,
            CodexCommandExecutor.SessionOpener opener,
            CodexCommandExecutor.Reprovisioner reprovisioner,
            CodexCommandExecutor.PreflightRefresher preflight) {
        return new CodexCommandExecutor(manager, tempDir.resolve("ws"), null, false,
                CodexCommandExecutor.ShellChoice.POWERSHELL, opener, preflight,
                (options, username) -> new RunnerClient.RunnerConfig(
                        options.codexHome(), username, "pw", "cp", "jh", "cwd"),
                reprovisioner);
    }

    private CodexSandboxManager manager() {
        return new CodexSandboxManager(
                new CodexSandboxOptions(tempDir, null, null, null, false, null), 30_000);
    }

    /** 1326（密码被外部改动）→ 重 setup → 原地重试一次成功，命令结果正常返回。 */
    @Test
    void credentialMismatchTriggersReprovisionAndRetriesOnce() {
        FakeSession session = new FakeSession(out("healed", Stream.STDOUT),
                new FramedMessage(6, new Exit(0, false)));
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger reprovisioned = new AtomicInteger();
        CodexCommandExecutor exec = healingExecutor(manager(), (cfg, spec) -> {
            if (opened.incrementAndGet() == 1) {
                throw new RunnerClient.CredentialMismatchException("EACodexOnline", 1326);
            }
            return session;
        }, m -> reprovisioned.incrementAndGet());
        assertEquals("healed", exec.execute("Write-Host test", "powershell"),
                "自愈重试后命令正常执行（不再把 1326 直接回给模型）");
        assertEquals(1, reprovisioned.get(), "重建（强制重 setup）恰触发一次");
        assertEquals(2, opened.get(), "同一条命令原地重试一次");
        assertTrue(session.closed, "重试会话正常关闭");
    }

    /** 重 setup 失败（如 UAC 被拒）→ 两层错误都回给模型，且给出恢复指引。 */
    @Test
    void credentialMismatchReprovisionFailureReportsBothErrors() {
        AtomicInteger opened = new AtomicInteger();
        CodexCommandExecutor exec = healingExecutor(manager(), (cfg, spec) -> {
            opened.incrementAndGet();
            throw new RunnerClient.CredentialMismatchException("EACodexOnline", 1326);
        }, m -> {
            throw new IllegalStateException("codex 沙箱 setup 失败: code=xx 用户在 UAC 弹窗拒绝了提权");
        });
        String result = exec.execute("Write-Host test", "powershell");
        assertTrue(result.startsWith("[codex sandbox 账户/凭据失配且自动修复失败]"),
                "可读错误前缀: " + result);
        assertTrue(result.contains("Windows error 1326"), "保留原始凭据失配错误: " + result);
        assertTrue(result.contains("UAC"), "保留重 setup 失败原因: " + result);
        assertEquals(1, opened.get(), "重 setup 失败后不再重试命令");
    }

    /** 重 setup 成功但重试仍凭据失配 → 只自愈一次（防循环），报重试失败。 */
    @Test
    void credentialMismatchRetriesOnlyOnce() {
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger reprovisioned = new AtomicInteger();
        CodexCommandExecutor exec = healingExecutor(manager(), (cfg, spec) -> {
            opened.incrementAndGet();
            throw new RunnerClient.CredentialMismatchException("EACodexOnline", 1326);
        }, m -> reprovisioned.incrementAndGet());
        String result = exec.execute("Write-Host test", "powershell");
        assertTrue(result.startsWith("[codex sandbox 执行失败] 账户/凭据自愈后重试仍失败"),
                "重试失败可读错误: " + result);
        assertTrue(result.contains("Windows error 1326"));
        assertEquals(1, reprovisioned.get(), "至多重建一次");
        assertEquals(2, opened.get(), "至多重试一次");
    }

    /** runner error 帧的凭据类 windows_error_code（1326）同样触发自愈（二次分类）。 */
    @Test
    void runnerStartupCredentialCodeTriggersHealing() {
        FakeSession session = new FakeSession(out("ok", Stream.STDOUT),
                new FramedMessage(6, new Exit(0, false)));
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger reprovisioned = new AtomicInteger();
        CodexCommandExecutor exec = healingExecutor(manager(), (cfg, spec) -> {
            if (opened.incrementAndGet() == 1) {
                throw new RunnerStartupException(new IpcMessage.Error(
                        "CreateProcessAsUserW failed", ErrorStage.SPAWN_CHILD, 1326));
            }
            return session;
        }, m -> reprovisioned.incrementAndGet());
        assertEquals("ok", exec.execute("x", "powershell"),
                "error 帧凭据码 → 自愈重试通过");
        assertEquals(1, reprovisioned.get());
        assertEquals(2, opened.get());
    }

    /** runner error 帧的非凭据类码（如 5）走通用失败路径，不重建。 */
    @Test
    void runnerStartupNonCredentialCodeDoesNotReprovision() {
        AtomicInteger reprovisioned = new AtomicInteger();
        CodexCommandExecutor exec = healingExecutor(manager(), (cfg, spec) -> {
            throw new RunnerStartupException(new IpcMessage.Error(
                    "access denied", ErrorStage.SPAWN_CHILD, 5));
        }, m -> reprovisioned.incrementAndGet());
        String result = exec.execute("x", "powershell");
        assertTrue(result.startsWith("[codex sandbox 执行失败]"), "通用失败路径: " + result);
        assertEquals(0, reprovisioned.get(), "非凭据类失败不得触发重 setup");
    }

    /** 通用失败（pipe broken）不触发重建（回归护栏）。 */
    @Test
    void genericFailureDoesNotReprovision() {
        AtomicInteger reprovisioned = new AtomicInteger();
        CodexCommandExecutor exec = healingExecutor(manager(), (cfg, spec) -> {
            throw new IOException("pipe broken");
        }, m -> reprovisioned.incrementAndGet());
        assertEquals("[codex sandbox 执行失败] pipe broken",
                exec.execute("x", "powershell"));
        assertEquals(0, reprovisioned.get(), "非凭据类失败不得触发重 setup");
    }

    // ---- §4.3.1 #2：组/账户被删 → sidString 的 LookupAccountName 1332 ----

    /** 组 EACodexSandboxUsers 被删：preflight 的 sidString 抛 1332（用户实测报错形态）→ 重建后重试通过。 */
    @Test
    void groupDeletedSidResolutionFailureTriggersHealing() {
        FakeSession session = new FakeSession(out("ok", Stream.STDOUT),
                new FramedMessage(6, new Exit(0, false)));
        AtomicInteger refreshed = new AtomicInteger();
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger reprovisioned = new AtomicInteger();
        CodexCommandExecutor exec = healingExecutor(manager(), (cfg, spec) -> {
            opened.incrementAndGet();
            return session;
        }, m -> reprovisioned.incrementAndGet(),
                (options, capSids, w, r) -> {
                    if (refreshed.incrementAndGet() == 1) {
                        // Advapi32Util.getAccountByName 对已删账户/组的抛法：Win32Exception(1332)
                        throw new Win32Exception(1332);
                    }
                });
        assertEquals("ok", exec.execute("x", "powershell"),
                "组被删 → 重建组+重挂成员 → 重试 preflight/命令通过");
        assertEquals(1, reprovisioned.get());
        assertEquals(2, refreshed.get(), "preflight 重试一次");
        assertEquals(1, opened.get());
    }

    /** SID 解析失败但码不可愈（如 5 拒绝访问）→ 通用失败路径，不重建（护栏）。 */
    @Test
    void nonHealableSidResolutionFailureDoesNotReprovision() {
        AtomicInteger reprovisioned = new AtomicInteger();
        CodexCommandExecutor exec = healingExecutor(manager(), (cfg, spec) -> {
            throw new IOException("unreachable");
        }, m -> reprovisioned.incrementAndGet(),
                (options, capSids, w, r) -> {
                    throw new Win32Exception(5);
                });
        String result = exec.execute("x", "powershell");
        assertTrue(result.startsWith("[codex sandbox 执行失败]"), "通用失败路径: " + result);
        assertEquals(0, reprovisioned.get(), "非可愈码不得触发重 setup");
    }

    // ---- §4.3.1 #3：凭据文件 sandbox_users.json 被删/损坏 ----

    /** 凭据文件被删：runnerConfigFactory 读密码抛 CredentialsFileException → 重 setup 重写文件后重试通过。 */
    @Test
    void secretsFileMissingTriggersHealing() {
        FakeSession session = new FakeSession(out("ok", Stream.STDOUT),
                new FramedMessage(6, new Exit(0, false)));
        AtomicInteger built = new AtomicInteger();
        AtomicInteger reprovisioned = new AtomicInteger();
        CodexCommandExecutor exec = new CodexCommandExecutor(manager(),
                tempDir.resolve("ws"), null, false,
                CodexCommandExecutor.ShellChoice.POWERSHELL,
                (cfg, spec) -> {
                    return session;
                },
                (options, capSids, w, r) -> {
                },
                (options, username) -> {
                    if (built.incrementAndGet() == 1) {
                        throw new SandboxSecrets.CredentialsFileException(
                                "sandbox users file missing: "
                                        + SandboxSecrets.secretsFile(options.codexHome()));
                    }
                    return new RunnerClient.RunnerConfig(options.codexHome(), username,
                            "pw", "cp", "jh", "cwd");
                },
                m -> reprovisioned.incrementAndGet());
        assertEquals("ok", exec.execute("x", "powershell"),
                "凭据文件被删 → 重 setup 重写 DPAPI 凭据 → 重试通过");
        assertEquals(1, reprovisioned.get());
        assertEquals(2, built.get(), "runnerConfig 重试一次（重读新密码）");
    }

    // ---- §4.3.1 #4：setup marker 被删 → nativeGuard 双闸门自愈 ----

    /** marker 被删：healSetupDamage 触发重 setup（fake 侧补写 marker+凭据占位）→ 闸门恢复。 */
    @Test
    void markerDeletedHealsViaReprovision() throws IOException {
        CodexSandboxManager mgr = manager();
        AtomicInteger reprovisioned = new AtomicInteger();
        CodexCommandExecutor exec = healingExecutor(mgr, (cfg, spec) -> {
            throw new IOException("unreachable");
        }, m -> {
            reprovisioned.incrementAndGet();
            // fake 重 setup：重建 marker（commit 为纯文件写，跨平台）+ 凭据占位
            try {
                SetupMarker.commit(tempDir, SetupPayload.SETUP_VERSION,
                        "EACodexOffline", "EACodexOnline", List.of(), false);
                Files.createDirectories(SandboxSecrets.secretsFile(tempDir).getParent());
                Files.createFile(SandboxSecrets.secretsFile(tempDir));
            } catch (IOException e) {
                throw new IllegalStateException("fake reprovision failed", e);
            }
        });
        assertFalse(SetupMarker.isComplete(tempDir, SetupPayload.SETUP_VERSION),
                "前置：marker 未就绪");
        assertNull(exec.healSetupDamage(mgr.options(), "test: marker 被删"),
                "自愈成功返回 null（继续执行命令）");
        assertTrue(SetupMarker.isComplete(tempDir, SetupPayload.SETUP_VERSION),
                "重 setup 后双闸门就绪");
        assertEquals(1, reprovisioned.get());
    }

    /** marker 被删且重 setup 失败（UAC 被拒）→ 可读错误，不静默。 */
    @Test
    void markerDeletedReprovisionFailureIsReadable() {
        CodexCommandExecutor exec = healingExecutor(manager(), (cfg, spec) -> {
            throw new IOException("unreachable");
        }, m -> {
            throw new IllegalStateException("codex 沙箱 setup 失败: code=xx UAC 被拒");
        });
        String error = exec.healSetupDamage(manager().options(), "test: marker 被删");
        assertNotNull(error, "失败返回可读错误");
        assertTrue(error.startsWith("[codex sandbox 未完成 setup 且自动修复失败]"),
                "可读错误前缀: " + error);
    }
}
