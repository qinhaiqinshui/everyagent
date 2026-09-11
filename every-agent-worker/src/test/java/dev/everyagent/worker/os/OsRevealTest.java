package dev.everyagent.worker.os;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OsReveal 进程拉起原语的回归测试(不依赖桌面环境):
 * 用 {@code java -version} 作为跨平台可用命令真实走 start(),覆盖进程创建、
 * 流重定向与 stdin EOF 语义。历史教训:start() 曾误用
 * {@code redirectInput(Redirect.DISCARD)}(DISCARD 类型为 WRITE,JDK 直接抛
 * IllegalArgumentException: Redirect invalid for reading),而所有 RPC 层
 * 测试只覆盖路径校验分支,进程从未真正拉起,故必须有直测 start() 的用例。
 */
class OsRevealTest {

    /** 跨平台可执行的 java 命令(测试 JVM 自身)。 */
    private static String javaBin() {
        String exe = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", exe).toString();
    }

    @Test
    @Timeout(60)
    void startCreatesProcessWithDiscardedIo() throws Exception {
        Process p = OsReveal.start(List.of(javaBin(), "-version"));
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "java -version 应正常退出");
        assertEquals(0, p.exitValue(), "java -version 退出码应为 0");
    }

    @Test
    @Timeout(60)
    void startStdinClosedMeansEofForChild() throws Exception {
        // 子进程立即读 stdin:start() 关闭写端后应得 EOF 并退出;若写端未关,
        // 子进程阻塞在 read 上,waitFor 必超时失败(回归 stdin 重定向语义)。
        String src = "public class P { public static void main(String[] a) throws Exception {"
                + "System.exit(System.in.read() == -1 ? 0 : 1);} }";
        Path dir = Path.of("target", "osreveal-test");
        java.nio.file.Files.createDirectories(dir);
        Path file = dir.resolve("P.java");
        java.nio.file.Files.writeString(file, src);
        Process p = OsReveal.start(List.of(javaBin(), file.toString())); // 单文件源码运行
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "stdin 写端已关闭,子进程应读到 EOF 并退出");
        assertEquals(0, p.exitValue(), "读到 EOF 时退出码应为 0");
    }

    @Test
    void startUnknownCommandFailsAsIoException() {
        // 拉起不存在的命令应报 IOException(而非 RuntimeException),供 RPC 层转错误应答。
        assertThrows(IOException.class,
                () -> OsReveal.start(List.of("definitely-no-such-cmd-everyagent")));
    }
}
