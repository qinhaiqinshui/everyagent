package dev.everyagent.worker.os.pty;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 基于 <b>pty4j</b> 的跨平台 PTY 工厂。
 *
 * <p>pty4j 封装了 Windows ConPTY/WinPTY 与 Unix openpty/posix_openpt,
 * API 干净、无需手写 JNA fork/exec——遵循「复用框架禁止重复造轮子」红线。
 *
 * <p>平台默认 shell:
 * <ul>
 *   <li>Windows:{@code %ComSpec%}(默认 {@code cmd.exe});</li>
 *   <li>Unix:{@code $SHELL} 或 {@code /bin/sh}。</li>
 * </ul>
 *
 * <p>环境变量:以当前进程 env 为底,叠加 {@code extraEnv}(含默认
 * {@code TERM=xterm-256color});stderr 合并到 stdout(终端一体化);
 * 非控制台模式(无额外控制台窗口)。
 */
public final class TerminalPtyFactory {

    private static final Logger log = LoggerFactory.getLogger(TerminalPtyFactory.class);

    private static final boolean WINDOWS =
            System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    private static final int READ_BUF_SIZE = 8192;

    private TerminalPtyFactory() {
    }

    /**
     * 在 cwd 拉起交互式 shell,返回 {@link TerminalPty} 实例。
     *
     * @param cwd      工作目录(必须已存在)
     * @param cols     初始列数({@code >= 1})
     * @param rows     初始行数({@code >= 1})
     * @param shell    shell 路径;{@code null} 按平台默认
     *                 (Windows: ComSpec/cmd.exe;Unix: $SHELL 或 /bin/sh)
     * @param extraEnv 额外环境变量;{@code null}=不追加(仍会注入默认 TERM)
     * @return 已打开的 {@link TerminalPty}
     * @throws IOException              cwd 不存在或 PTY 创建失败
     * @throws IllegalArgumentException cols/rows 非法
     */
    public static TerminalPty open(Path cwd, int cols, int rows, String shell, Map<String, String> extraEnv)
            throws IOException {
        if (cwd == null || !Files.isDirectory(cwd)) {
            throw new IOException("cwd must be an existing directory: " + cwd);
        }
        if (cols < 1 || rows < 1) {
            throw new IllegalArgumentException(
                    "cols and rows must be >= 1 (got cols=" + cols + ", rows=" + rows + ")");
        }

        String shellPath = (shell != null) ? shell : defaultShell();

        // 环境变量:当前进程 env + extraEnv + TERM
        Map<String, String> env = new HashMap<>(System.getenv());
        if (extraEnv != null) {
            env.putAll(extraEnv);
        }
        env.putIfAbsent("TERM", "xterm-256color");

        PtyProcess process = new PtyProcessBuilder()
                .setCommand(new String[]{shellPath})
                .setEnvironment(env)
                .setDirectory(cwd.toString())
                .setInitialColumns(cols)
                .setInitialRows(rows)
                .setConsole(false)
                .setRedirectErrorStream(true)
                .start();

        return new Pty4jTerminalPty(process);
    }

    /** 平台默认 shell 路径。 */
    private static String defaultShell() {
        if (WINDOWS) {
            return System.getenv().getOrDefault("ComSpec", "cmd.exe");
        }
        return System.getenv().getOrDefault("SHELL", "/bin/sh");
    }

    /** {@link TerminalPty} 的 pty4j 实现:薄封装 {@link PtyProcess}。 */
    private static final class Pty4jTerminalPty implements TerminalPty {

        private final PtyProcess process;
        private final InputStream input;
        private final OutputStream output;
        private final byte[] readBuf = new byte[READ_BUF_SIZE];
        private volatile boolean closed = false;

        Pty4jTerminalPty(PtyProcess process) {
            this.process = process;
            this.input = process.getInputStream();
            this.output = process.getOutputStream();
        }

        @Override
        public byte[] read() throws IOException {
            if (closed) {
                return null;
            }
            int n;
            try {
                n = input.read(readBuf);
            } catch (IOException e) {
                // 流关闭/子进程退出时 pty4j 可能抛 IOException,视为 EOF
                if (closed) {
                    return null;
                }
                throw e;
            }
            if (n < 0) {
                return null; // EOF
            }
            if (n == 0) {
                return new byte[0];
            }
            byte[] result = new byte[n];
            System.arraycopy(readBuf, 0, result, 0, n);
            return result;
        }

        @Override
        public void write(byte[] data) throws IOException {
            if (closed) {
                throw new IOException("PTY is closed");
            }
            if (data.length == 0) {
                return;
            }
            output.write(data);
            output.flush();
        }

        @Override
        public void resize(int cols, int rows) throws IOException {
            if (closed) {
                throw new IOException("PTY is closed");
            }
            if (cols < 1 || rows < 1) {
                throw new IllegalArgumentException("cols and rows must be >= 1");
            }
            // pty4j WinSize 构造签名:WinSize(columns, rows);内部处理 SIGWINCH/ConPTY resize
            process.setWinSize(new WinSize(rows, cols));
        }

        @Override
        public boolean isAlive() {
            if (closed) {
                return false;
            }
            return process.isAlive();
        }

        @Override
        public long pid() {
            if (closed) {
                return -1;
            }
            try {
                return process.pid();
            } catch (Exception e) {
                return -1;
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                input.close();
            } catch (IOException e) {
                log.debug("close PTY input stream failed", e);
            }
            try {
                output.close();
            } catch (IOException e) {
                log.debug("close PTY output stream failed", e);
            }
            process.destroy();
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
