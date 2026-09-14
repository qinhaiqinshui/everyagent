package dev.everyagent.worker.os.pty;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PTY 引擎冒烟测试(非 Spring)。
 *
 * <p>在系统临时目录建工作目录,{@link TerminalPtyFactory#open} 拉起交互式 shell,
 * 写 {@code echo EA_PTY_OK} 命令,阻塞读输出直到出现标记或超时(5s)。
 * 仅在 Linux/macOS 实跑(Windows ConPTY 由 pty4j 覆盖,沙箱无 Windows 环境)。
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class TerminalPtySmokeTest {

    private Path tmpDir;
    private TerminalPty pty;

    @AfterEach
    void tearDown() {
        if (pty != null) {
            try {
                pty.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
        if (tmpDir != null) {
            deleteRecursively(tmpDir);
        }
    }

    @Test
    void smokeEchoCommand() throws Exception {
        tmpDir = Files.createTempDirectory("ea-pty-smoke");

        pty = TerminalPtyFactory.open(tmpDir, 80, 24, null, Map.of("TERM", "xterm-256color"));
        assertTrue(pty.isAlive(), "shell should be alive after open");

        // 按平台选行尾:Windows CRLF,Unix LF
        boolean isWin = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        String eol = isWin ? "\r\n" : "\n";
        pty.write(("echo EA_PTY_OK" + eol).getBytes(StandardCharsets.UTF_8));

        // 阻塞循环 read() 直到输出含 EA_PTY_OK 或超时 5s
        boolean hit = false;
        long deadline = System.currentTimeMillis() + 5000;
        StringBuilder sb = new StringBuilder();
        while (System.currentTimeMillis() < deadline) {
            byte[] chunk = pty.read();
            if (chunk == null) {
                break; // EOF
            }
            sb.append(new String(chunk, StandardCharsets.UTF_8));
            if (sb.toString().contains("EA_PTY_OK")) {
                hit = true;
                break;
            }
        }

        assertTrue(hit, "PTY output should contain EA_PTY_OK within 5s, got: " + sb);

        // resize 不抛异常(pty4j 内部处理 SIGWINCH)
        pty.resize(120, 40);

        // close 后子进程不应存活
        pty.close();
        assertFalse(pty.isAlive(), "closed PTY should not be alive");

        // 幂等:再次 close 无副作用
        pty.close();
    }

    private static void deleteRecursively(Path dir) {
        try {
            try (var stream = Files.walk(dir)) {
                stream.sorted((a, b) -> b.compareTo(a))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                                // ignore
                            }
                        });
            }
        } catch (IOException ignored) {
            // ignore
        }
    }
}
