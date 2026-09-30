package dev.everyagent.plugin.sandbox.codex.runner;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.sun.jna.Pointer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管道名约定 + 环境块 + Windows 参数引用的跨平台单测（不触发 JNA 原生加载）。
 */
class RunnerPathsAndSpawnHelpersTest {

    // ---- RunnerPaths（设计 §2.7：\\.\pipe\every-agent-codex-runner-<nonce>-in/-out） ----

    @Test
    void pipeNamesFollowDesignConvention() {
        String nonce = "0123456789abcdef0123456789abcdef";
        assertEquals("\\\\.\\pipe\\every-agent-codex-runner-" + nonce + "-in",
                RunnerPaths.inPipeName(nonce));
        assertEquals("\\\\.\\pipe\\every-agent-codex-runner-" + nonce + "-out",
                RunnerPaths.outPipeName(nonce));
    }

    @Test
    void nonceIs128BitLowercaseHex() {
        for (int i = 0; i < 100; i++) {
            String nonce = RunnerPaths.newNonce();
            assertEquals(32, nonce.length());
            assertTrue(nonce.matches("[0-9a-f]{32}"), "nonce: " + nonce);
        }
        // 两次生成不同（随机性冒烟）
        assertNotEquals(RunnerPaths.newNonce(), RunnerPaths.newNonce());
    }

    @Test
    void malformedNonceRejected() {
        assertFalse(RunnerPaths.isValidNonce(null));
        assertFalse(RunnerPaths.isValidNonce(""));
        assertFalse(RunnerPaths.isValidNonce("ABCDEF")); // 太短
        assertFalse(RunnerPaths.isValidNonce("0123456789ABCDEF0123456789ABCDEF")); // 大写
        assertFalse(RunnerPaths.isValidNonce("g123456789abcdef0123456789abcdeg")); // 非 hex
        assertThrows(IllegalArgumentException.class,
                () -> RunnerPaths.inPipeName("../evil"));
    }

    @Test
    void sddlTemplateGrantsOnlySandboxAccountGenericAll() {
        assertEquals("D:(A;;GA;;;S-1-5-21-100-200-300-500)",
                String.format(RunnerPaths.PIPE_SDDL_FORMAT, "S-1-5-21-100-200-300-500"));
        // 方向常量与 codex runner_pipe.rs 手写值一致
        assertEquals(0x2, RunnerPaths.PIPE_ACCESS_OUTBOUND);
        assertEquals(0x1, RunnerPaths.PIPE_ACCESS_INBOUND);
        assertEquals(65536, RunnerPaths.PIPE_BUFFER_BYTES);
    }

    // ---- EnvBlock（codex process.rs::make_env_block） ----

    @Test
    void envBlockSortedCaseInsensitivelyAndUtf16() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", "C:\\Windows");   // 乱序输入
        env.put("abc", "1");
        env.put("Abd", "2");
        env.put("AAA", "3");
        env.put("aaa", "4");
        com.sun.jna.Memory mem = (com.sun.jna.Memory) EnvBlock.makeEnvBlock(env);
        String block = new String(mem.getByteArray(0, (int) mem.size()), StandardCharsets.UTF_16LE);
        // 大小写不敏感排序，同键名大小写差异按原串 tie-break：AAA < aaa < abc < Abd < PATH
        assertEquals("AAA=3\0aaa=4\0abc=1\0Abd=2\0PATH=C:\\Windows\0\0", block);
    }

    @Test
    void emptyEnvYieldsNullPointerToInheritParentEnv() {
        assertEquals(Pointer.NULL, EnvBlock.makeEnvBlock(null));
        assertEquals(Pointer.NULL, EnvBlock.makeEnvBlock(Map.of()));
    }

    // ---- Windows 参数引用（codex winutil.rs::quote_windows_arg） ----

    @Test
    void plainArgsStayUnquoted() {
        assertEquals("cmd.exe", ChildProcess.quoteWindowsArg("cmd.exe"));
        assertEquals("C:\\dir\\", ChildProcess.quoteWindowsArg("C:\\dir\\"),
                "无空白/引号不加外层引号（含尾反斜杠）");
    }

    @Test
    void argsWithSpaceTabOrQuotesGetQuoted() {
        assertEquals("\"\"", ChildProcess.quoteWindowsArg(""));
        assertEquals("\"hello world\"", ChildProcess.quoteWindowsArg("hello world"));
        assertEquals("\"a\tb\"", ChildProcess.quoteWindowsArg("a\tb"));
        assertEquals("\"say \\\"hi\\\"\"", ChildProcess.quoteWindowsArg("say \"hi\""));
        assertEquals("\"a\\\\\\\"b\"", ChildProcess.quoteWindowsArg("a\\\"b"),
                "2n+1 个反斜杠 + 引号 = 字面引号");
    }

    @Test
    void commandLineJoinsQuotedArgs() {
        String cmd = ChildProcess.argvToCommandLine(java.util.List.of(
                "cmd.exe", "/c", "echo", "hello world", "", "x\"y"));
        assertEquals("cmd.exe /c echo \"hello world\" \"\" \"x\\\"y\"", cmd);
    }
}
