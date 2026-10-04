package dev.everyagent.plugin.sandbox.codex.session;

import dev.everyagent.plugin.sandbox.codex.acl.RootPolicy;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.SpawnRequest;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CodexSandboxSession 的 SpawnRequest 纯组装单测（跨平台）+ RunnerClient 命令行
 * 纯函数（对齐设计 §4.1：java.exe -cp 物化 classpath + JVM flags + pipe 参数）。
 */
class CodexSandboxSessionTest {

    private static final String WS_CAP = "S-1-5-21-1-1-1-2";
    private static final String EXTRA_CAP = "S-1-5-21-1-1-1-3";
    private static final String SHARED_CAP = "S-1-5-21-1-1-1-4";

    private CodexSandboxSession.SessionSpec spec(
            List<RootPolicy.WriteRoot> writeRoots, String workspaceCapSid) {
        return new CodexSandboxSession.SessionSpec(
                List.of("cmd.exe", "/c", "echo hi"),
                "C:\\ws",
                Map.of("K", "V"),
                30_000L,
                writeRoots,
                List.of("C:\\ws\\.git", "C:\\ws\\.everyagent"),
                workspaceCapSid,
                "offline",
                false,
                null);
    }

    @Test
    void capSidsAreWriteRootCapsThenWorkspaceCapDeduped() {
        RootPolicy.WriteRoot ws = new RootPolicy.WriteRoot(Path.of("C:\\ws"), WS_CAP);
        RootPolicy.WriteRoot extra = new RootPolicy.WriteRoot(Path.of("C:\\extra"), EXTRA_CAP);
        SpawnRequest request = CodexSandboxSession.buildSpawnRequest(
                spec(List.of(ws, extra), SHARED_CAP));
        assertEquals(List.of(WS_CAP, EXTRA_CAP, SHARED_CAP), request.capSids(),
                "各写根 cap + workspace cap，保序");
        assertEquals(List.of("C:\\ws", "C:\\extra"), request.writeRoots(),
                "写根路径透传（runner 侧信息展示）");
    }

    @Test
    void duplicateCapSidsCollapseKeepingFirstOccurrence() {
        RootPolicy.WriteRoot ws = new RootPolicy.WriteRoot(Path.of("C:\\ws"), WS_CAP);
        RootPolicy.WriteRoot alias = new RootPolicy.WriteRoot(Path.of("C:\\alias"), WS_CAP);
        SpawnRequest request = CodexSandboxSession.buildSpawnRequest(
                spec(List.of(ws, alias), WS_CAP));
        assertEquals(List.of(WS_CAP), request.capSids(), "同 cap 多根/与 workspace cap 重复去重");
        assertEquals(List.of("C:\\ws", "C:\\alias"), request.writeRoots());
    }

    @Test
    void passthroughFieldsAndFixedOnes() {
        RootPolicy.WriteRoot ws = new RootPolicy.WriteRoot(Path.of("C:\\ws"), WS_CAP);
        SpawnRequest request = CodexSandboxSession.buildSpawnRequest(spec(List.of(ws), null));
        assertEquals(List.of("cmd.exe", "/c", "echo hi"), request.command());
        assertEquals("C:\\ws", request.cwd());
        assertEquals(Map.of("K", "V"), request.env());
        assertEquals(30_000L, request.timeoutMs());
        assertEquals(List.of("C:\\ws\\.git", "C:\\ws\\.everyagent"), request.denyWritePaths());
        assertEquals("offline", request.networkIdentity());
        assertEquals(false, request.tty(), "ConPTY 暂缓（设计 §1.2）");
        assertEquals(false, request.stdinOpen());
        assertNull(request.privateDesktopName(), "PrivateDesktop 未接线");

        SpawnRequest noTimeout = CodexSandboxSession.buildSpawnRequest(
                new CodexSandboxSession.SessionSpec(List.of("cmd.exe"), null, null, null,
                        List.of(ws), List.of(), null, null, false, null));
        assertNull(noTimeout.timeoutMs());
        assertTrue(noTimeout.capSids().contains(WS_CAP), "仅写根 cap 亦可用");
    }

    @Test
    void emptyCommandOrCapsRejectedFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> CodexSandboxSession.buildSpawnRequest(
                spec(List.of(), null)), "无任何 cap SID → 拒绝（runner 同款校验）");
        assertThrows(IllegalArgumentException.class,
                () -> CodexSandboxSession.buildSpawnRequest(new CodexSandboxSession.SessionSpec(
                        List.of(), null, null, null, List.of(), List.of(), WS_CAP, null,
                        false, null)));
        assertThrows(NullPointerException.class,
                () -> CodexSandboxSession.buildSpawnRequest(new CodexSandboxSession.SessionSpec(
                        null, null, null, null, List.of(), List.of(), WS_CAP, null, false,
                        null)));
    }

    // ---- RunnerClient 命令行纯函数 ----

    @Test
    void runnerArgvMatchesDesignShape() {
        List<String> argv = RunnerClient.runnerArgv("C:\\jdk-25",
                "C:\\bin\\runner.jar;C:\\bin\\jna.jar",
                "\\\\.\\pipe\\every-agent-codex-runner-abc-in",
                "\\\\.\\pipe\\every-agent-codex-runner-abc-out");
        assertEquals("C:\\jdk-25\\bin\\java.exe", argv.get(0));
        assertEquals(List.of("-XX:+UseSerialGC", "-Xshare:auto", "-Dfile.encoding=UTF-8"),
                argv.subList(1, 4), "runner JVM flags（设计 §4.1）");
        assertEquals("-cp", argv.get(4));
        assertEquals("C:\\bin\\runner.jar;C:\\bin\\jna.jar", argv.get(5));
        assertEquals(RunnerMaterializer.RUNNER_MAIN, argv.get(6));
        assertEquals("--pipe-in=\\\\.\\pipe\\every-agent-codex-runner-abc-in", argv.get(7));
        assertEquals("--pipe-out=\\\\.\\pipe\\every-agent-codex-runner-abc-out", argv.get(8));
    }

    /** 预提取产物就位（.sandbox-bin/jnidispatch.dll）→ runner argv 带 boot.library.path。 */
    @Test
    void runnerArgvCarriesBootLibraryPathWhenPreExtracted(
            @org.junit.jupiter.api.io.TempDir Path codexHome) throws Exception {
        Path binDir = codexHome.resolve(".sandbox-bin");
        java.nio.file.Files.createDirectories(binDir);
        java.nio.file.Files.writeString(binDir.resolve("jnidispatch.dll"), "MZ");
        List<String> argv = RunnerClient.runnerArgv("C:\\jdk-25", "C:\\b\\runner.jar",
                "in", "out", codexHome);
        assertTrue(argv.contains("-Djna.boot.library.path=" + binDir),
                "应带 boot.library.path 指向 .sandbox-bin: " + argv);
    }

    /** 未预提取 → 不带该参数（回退 JNA 默认行为）。 */
    @Test
    void runnerArgvOmitsBootLibraryPathWhenAbsent(
            @org.junit.jupiter.api.io.TempDir Path codexHome) throws Exception {
        List<String> argv = RunnerClient.runnerArgv("C:\\jdk-25", "C:\\b\\runner.jar",
                "in", "out", codexHome);
        assertTrue(argv.stream().noneMatch(a -> a.startsWith("-Djna.boot.library.path=")),
                "未预提取时不得带该参数: " + argv);
        assertTrue(RunnerClient.runnerArgv("C:\\jdk-25", "C:\\b\\runner.jar", "in", "out")
                .stream().noneMatch(a -> a.startsWith("-Djna.boot.library.path=")),
                "无 codexHome 重载不带该参数");
    }

    /** 真 jna jar（测试 classpath 上就有）：提取产物为 PE，二次调用幂等复用。 */
    @Test
    void ensureJnidispatchExtractsFromRealJnaJar(
            @org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        List<Path> sources = RunnerMaterializer.materializationSources(
                System.getProperty("java.class.path"), System.getProperty("path.separator"));
        Path dll = RunnerMaterializer.ensureJnidispatch(sources, dir, null);
        org.junit.jupiter.api.Assumptions.assumeTrue(dll != null, "classpath 无 jna 核心 jar");
        assertEquals("jnidispatch.dll", dll.getFileName().toString());
        assertTrue(java.nio.file.Files.size(dll) > 1000, "dll 非空");
        byte[] head = new byte[2];
        try (var in = java.nio.file.Files.newInputStream(dll)) {
            assertEquals(2, in.read(head));
        }
        assertEquals('M', head[0]);
        assertEquals('Z', head[1]);
        assertEquals(dll, RunnerMaterializer.ensureJnidispatch(sources, dir, null),
                "二次调用复用既有产物");
    }

    @Test
    void commandLineQuotesOnlyArgsNeedingIt() {
        assertEquals("a.jar b", RunnerClient.joinCommandLine(List.of("a.jar", "b")));
        assertEquals("\"a b.jar\" c", RunnerClient.joinCommandLine(List.of("a b.jar", "c")));
        assertEquals("\"say \\\"hi\\\"\"",
                RunnerClient.quoteWindowsArg("say \"hi\""), "内部引号 2n+1 反斜杠转义");
        assertEquals("\"\"", RunnerClient.quoteWindowsArg(""));
    }

    // ---- RunnerClient 环境覆盖与失败分类（跨平台纯逻辑） ----

    @org.junit.jupiter.api.io.TempDir
    Path tmp;

    @Test
    void runnerEnvironmentOverridesTempAndTmpToSandboxTmp() throws java.io.IOException {
        java.nio.file.Path codexHome = java.nio.file.Files.createDirectories(
                tmp.resolve("codex"));
        java.util.Map<String, String> env = RunnerClient.runnerEnvironment(codexHome);
        String expected = codexHome.resolve(".sandbox").resolve("tmp").toString();
        assertEquals(expected, env.get("TEMP"), "TEMP 显式指到 .sandbox/tmp（设计 §4.1）");
        assertEquals(expected, env.get("TMP"));
        assertTrue(java.nio.file.Files.isDirectory(codexHome.resolve(".sandbox").resolve("tmp")));
    }

    @Test
    void logonFailuresAreClassifiedPerTaskSpec() {
        for (int code : List.of(1326, 1331, 1387, 1312)) {
            assertThrows(RunnerClient.CredentialMismatchException.class,
                    () -> RunnerClient.classifyLogonFailure("sandbox-user", code),
                    "凭据类错误码 " + code);
        }
        RunnerClient.classifyLogonFailure("sandbox-user", 1056); // 服务忙：由重试路径处理
        RunnerClient.classifyLogonFailure("sandbox-user", 5); // 其余：留给通用失败路径
    }
}
