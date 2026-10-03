package dev.everyagent.plugin.sandbox.codex.setup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * setup helper 调试日志（可观测性专用，不改变任何行为逻辑）。
 *
 * <p>helper 是 SW_HIDE 的提权 JVM，stdout/stderr 不可见，失败时仅落一个
 * setup_error.json（单错误码，信息量不足）。本类把每次 COM 调用的完整轨迹
 * （CLSID/IID、方法名、DISPID、flags、参数指针、返回 HRESULT、VARIANT vt）
 * 追加写入 {@code <codexHome>/.sandbox/setup-helper.log}，供事后定位。
 *
 * <p>设计约束：任何日志失败（IO 异常等）静默吞掉——日志绝不影响 setup 行为；
 * worker 进程内未 {@link #init} 时全部 no-op（同一 jar 在两个进程复用）。
 */
public final class HelperLog {
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static volatile Path logFile;

    private HelperLog() {
    }

    /** 绑定日志文件（helper 进程入口调用一次；worker 进程不调用=全 no-op）。 */
    public static void init(Path codexHome) {
        try {
            Path dir = codexHome.resolve(".sandbox");
            Files.createDirectories(dir);
            logFile = dir.resolve("setup-helper.log");
            log("---- helper session start (pid=" + ProcessHandle.current().pid() + ") ----");
        } catch (Exception ignored) {
            logFile = null;
        }
    }

    /** 追加一行日志（时间戳前缀；任何异常静默）。 */
    public static void log(String message) {
        Path file = logFile;
        if (file == null) {
            return;
        }
        try {
            String line = LocalDateTime.now().format(TS) + " " + message
                    + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 日志失败不影响 setup 行为
        }
    }

    /** 记录异常（含堆栈）。 */
    public static void log(String prefix, Throwable t) {
        StringBuilder sb = new StringBuilder(prefix).append(": ").append(t);
        for (StackTraceElement el : t.getStackTrace()) {
            sb.append(System.lineSeparator()).append("    at ").append(el);
        }
        log(sb.toString());
    }

    /** HRESULT 格式化（0xXXXXXXXX）。 */
    public static String hex(long hr) {
        return "0x" + Long.toUnsignedString(hr & 0xFFFFFFFFL, 16);
    }
}
