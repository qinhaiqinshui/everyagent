package dev.everyagent.worker.plugin.adapters;

import dev.everyagent.plugin.api.spi.ExecResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 直接进程 spawn 的共享工具（供 SandboxBackend 实现的 spawnNative 使用）。
 *
 * <p>逻辑与 {@link dev.everyagent.worker.os.OsSandbox#runDirectCommand} 一致：
 * ProcessBuilder argv 直传、stdin 接 null 设备、超时强杀、每流输出截断、
 * 网络 env 处理。各后端的 spawnNative 行为须与此一致（原生 git 等平台受控操作）。
 */
final class DirectSpawnSupport {

    /** 单流输出字符上限。 */
    static final int MAX_OUTPUT_CHARS = 1_000_000;

    /** stdin 的 null 设备（Windows=NUL，其余=/dev/null）。 */
    private static final java.io.File NULL_INPUT = new java.io.File(
            System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                    ? "NUL" : "/dev/null");

    private DirectSpawnSupport() {
    }

    /** 直接执行（argv 直传，无 shell 解析；带超时 + 每流输出上限 + 网络 env 处理）。 */
    static ExecResult runDirect(List<String> cmd, java.nio.file.Path cwd,
            Map<String, String> extraEnv, boolean allowNetwork, long timeoutMs,
            ExecutorService exec) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        pb.redirectInput(ProcessBuilder.Redirect.from(NULL_INPUT));
        pb.environment().putAll(sanitizedEnv(extraEnv, allowNetwork));
        try {
            Process p = pb.start();
            Future<String> out = exec.submit(() -> drain(p.getInputStream()));
            Future<String> err = exec.submit(() -> drain(p.getErrorStream()));
            boolean aborted = false;
            String outText;
            String errText;
            try {
                outText = out.get(timeoutMs, TimeUnit.MILLISECONDS);
                errText = awaitQuiet(err);
            } catch (TimeoutException e) {
                aborted = true;
                p.destroyForcibly();
                outText = awaitQuiet(out);
                errText = awaitQuiet(err);
                errText += "\n[exec 超时中止: >" + timeoutMs + "ms]";
            }
            int code = aborted ? -1 : p.waitFor();
            return new ExecResult(capOutput(outText), capOutput(errText), code, aborted);
        } catch (IOException e) {
            return new ExecResult("", "exec 启动失败: " + e.getMessage(), 1, false);
        } catch (Exception e) {
            return new ExecResult("", "exec 异常: " + e.getMessage(), 1, false);
        }
    }

    /** 等待读取任务收尾，超时/异常回退空串。 */
    private static String awaitQuiet(Future<String> task) {
        try {
            return task.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    /** 单流输出截断。 */
    private static String capOutput(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.length() > MAX_OUTPUT_CHARS
                ? s.substring(0, MAX_OUTPUT_CHARS) + "\n[输出已截断至 " + MAX_OUTPUT_CHARS + " 字符]"
                : s;
    }

    /** 按网络许可清理环境：allowNetwork=false 时移除代理相关变量。 */
    private static Map<String, String> sanitizedEnv(Map<String, String> extra, boolean allowNetwork) {
        Map<String, String> env = new java.util.HashMap<>(extra == null ? Map.of() : extra);
        if (!allowNetwork) {
            List.of("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy",
                    "ALL_PROXY", "all_proxy", "NO_PROXY", "no_proxy").forEach(env::remove);
        }
        return env;
    }

    private static String drain(java.io.InputStream in) throws IOException {
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toString(StandardCharsets.UTF_8);
        }
    }
}
