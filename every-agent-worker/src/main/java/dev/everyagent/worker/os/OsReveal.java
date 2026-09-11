package dev.everyagent.worker.os;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 在运行 worker 的宿主系统文件管理器中定位并选中目标(架构 §5.5 fs.revealInOs,
 * 对标 VSCode Reveal in File Explorer):
 * - Windows:{@code explorer.exe /select,<path>} 在资源管理器中选中目标本身
 *   (explorer 拉起成功也返回非零退出码,故只 fire-and-forget 不校验);
 * - macOS:{@code open -R} 在 Finder 中显示并选中;
 * - Linux:优先 freedesktop FileManager1 dbus 协议(Nautilus/Dolphin/Nemo/Thunar
 *   等均已注册)选中目标本身,无 dbus-send/无注册实现/调用失败时退化 {@code xdg-open}
 *   打开所在目录。
 *
 * <p>进程 argv 直传、无 shell 解析,路径含空格/特殊字符安全;目标路径由调用方
 * (FsService)经 Sandbox 校验。无桌面环境(无头 worker/未装文件管理器)时抛
 * {@link IOException},由 RPC 层转为错误应答。
 */
public final class OsReveal {

    private static final Logger log = LoggerFactory.getLogger(OsReveal.class);

    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    private static final boolean MAC =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");

    /** dbus-send 拉起文件管理器的最长等待:超时按失败处理,退化 xdg-open。 */
    private static final long DBUS_TIMEOUT_MS = 5_000;
    /** open/xdg-open 常规等待上限(超时视为失败,错误应答回前端)。 */
    private static final long LAUNCH_TIMEOUT_MS = 10_000;

    private OsReveal() {
    }

    /** 在宿主系统文件管理器中定位并选中 target(文件选中自身,目录定位目录本身)。 */
    public static void reveal(Path target) throws IOException {
        if (WINDOWS) {
            start(List.of("explorer.exe", "/select," + target));
            return;
        }
        if (MAC) {
            awaitOk(start(List.of("open", "-R", target.toString())), LAUNCH_TIMEOUT_MS);
            return;
        }
        // Linux:先试 FileManager1 统一协议(能选中目标),失败再退化打开所在目录。
        try {
            awaitOk(start(showItemsArgv(target)), DBUS_TIMEOUT_MS);
            return;
        } catch (IOException | RuntimeException e) {
            log.debug("FileManager1 ShowItems 不可用,退化 xdg-open: {}", e.toString());
        }
        Path dir = Files.isDirectory(target) ? target : target.getParent();
        awaitOk(start(List.of("xdg-open", dir.toString())), LAUNCH_TIMEOUT_MS);
    }

    /** dbus-send 调 org.freedesktop.FileManager1.ShowItems 的完整 argv。 */
    private static List<String> showItemsArgv(Path target) throws IOException {
        // URI 中的逗号会被 dbus-send 当数组元素分隔符,手工百分号编码规避。
        String uri = target.toUri().toString().replace(",", "%2C");
        return List.of("dbus-send", "--session", "--print-reply=literal",
                "--dest=org.freedesktop.FileManager1", "/org/freedesktop/FileManager1",
                "org.freedesktop.FileManager1.ShowItems",
                "array:string:" + uri, "string:");
    }

    /** 拉起进程:stdin/输出全部丢弃(文件管理器不需要交互,防输出管道反压阻塞)。 */
    private static Process start(List<String> argv) throws IOException {
        return new ProcessBuilder(argv)
                .redirectInput(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    /** 等待进程退出并校验退出码:超时强杀、非零退出码均视为失败抛 IOException。 */
    private static void awaitOk(Process p, long timeoutMs) throws IOException {
        boolean finished;
        try {
            finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("等待文件管理器启动被中断", e);
        }
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("文件管理器启动超时");
        }
        if (p.exitValue() != 0) {
            throw new IOException("文件管理器启动失败,退出码 " + p.exitValue());
        }
    }
}
