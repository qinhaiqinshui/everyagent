package dev.everyagent.plugin.sandbox.codex.session;

import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;

import dev.everyagent.plugin.sandbox.codex.accounts.SandboxAccounts;
import dev.everyagent.plugin.sandbox.codex.accounts.SandboxSecrets;
import dev.everyagent.plugin.sandbox.codex.runner.EnvBlock;
import dev.everyagent.plugin.sandbox.codex.runner.FrameCodec;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage;
import dev.everyagent.plugin.sandbox.codex.runner.RunnerPaths;
import dev.everyagent.plugin.sandbox.codex.runner.Win32Exception;
import dev.everyagent.plugin.sandbox.codex.setup.SandboxDirs;
import dev.everyagent.plugin.sandbox.codex.setup.SetupPayload;
import dev.everyagent.plugin.sandbox.codex.win.Kernel32Ex;
import dev.everyagent.plugin.sandbox.codex.win.WinErr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * runner 进程拉起与通道（设计文档 §4.1/§4.3，对齐 codex runner_client.rs）。
 *
 * <p>{@link CreateProcessWithLogonW}：账户=沙箱用户名、域 {@code "."}、密码 =
 * {@link SandboxSecrets#readPassword} 解出的明文（JNA UNICODE_OPTIONS 把 String/WString
 * 直接按 UTF-16LE 编组，全程不经平台默认 charset）；旗标
 * CREATE_NO_WINDOW|CREATE_UNICODE_ENVIRONMENT、STARTF_FORCEOFFFEEDBACK、不传
 * LOGON_WITH_PROFILE（无 execution alias，免建 profile）；runner 的 TEMP/TMP 显式指到
 * {@code <codexHome>/.sandbox/tmp}；spawn 前后 SetErrorMode(0x3) 保存恢复。
 *
 * <p>失败分类（对齐 runner_client.rs::retry_runner_spawn_once /
 * is_refreshable_windows_error）：1056（Secondary Logon 服务忙）原凭据重试一次；
 * 1326/1331/1387（+codex 同类的 1312）凭据类 → {@link CredentialMismatchException}
 * （上层引导重新 setup，不做首期自动密码轮换）。握手失败 TerminateProcess 收尸。
 */
public final class RunnerClient {

    /** 最近一次 spawn 的 env 诊断（条目数/字节数）。 */
    private static volatile String lastEnvDiag;

    private static final System.Logger LOG =
            System.getLogger(RunnerClient.class.getName());

    /** 连接与 spawn_ready 等待上限（RUNNER_PIPE_CONNECT_TIMEOUT / RUNNER_SPAWN_READY_TIMEOUT = 15s）。 */
    public static final long PIPE_CONNECT_TIMEOUT_MS = 15_000;
    public static final long SPAWN_READY_TIMEOUT_MS = 15_000;
    /** codex RUNNER_ERROR_MODE_FLAGS = 0x1|0x2。 */
    private static final int RUNNER_ERROR_MODE_FLAGS = 0x0001 | 0x0002;
    /** 凭据类失败码（任务口径 1326/1331/1387 + codex is_refreshable 的 1312）。 */
    private static final List<Integer> CREDENTIAL_MISMATCH_CODES = List.of(
            WinErr.ERROR_LOGON_FAILURE, WinErr.ERROR_ACCOUNT_DISABLED,
            WinErr.ERROR_NO_SUCH_MEMBER, WinErr.ERROR_NO_SUCH_LOGON_SESSION);

    private RunnerClient() {
    }

    /** 沙箱账户凭据失配——引导重新 setup（对齐 SandboxAccountCredentialMismatch 语义）。 */
    public static final class CredentialMismatchException extends RuntimeException {
        private final int windowsErrorCode;

        public CredentialMismatchException(String username, int windowsErrorCode) {
            super("sandbox account credential mismatch for " + username
                    + " (Windows error " + windowsErrorCode + "); re-run setup");
            this.windowsErrorCode = windowsErrorCode;
        }

        public int windowsErrorCode() {
            return windowsErrorCode;
        }
    }

    /** runner 启动配置（凭据 + 已物化 classpath + java.home）。 */
    public record RunnerConfig(Path codexHome, String username, String password,
            String classpath, String javaHome, String workingDirectory) {
    }

    /** 凭据装载：DPAPI 解密码 → String（UTF-8 解码已在 readPassword 完成）。 */
    public static String loadPassword(Path codexHome, String username) throws IOException {
        return SandboxSecrets.readPassword(codexHome, SetupPayload.SETUP_VERSION, username);
    }

    /**
     * 拉起 runner 并建立双管道通道（不含 spawn_request 握手——由会话层做）。
     * 任何失败：TerminateProcess(hProcess,1) 收尸 + 关闭已建管道（fail-closed）。
     */
    public static Channel start(RunnerConfig cfg) throws IOException {
        long t0 = System.nanoTime();
        String nonce = RunnerPaths.newNonce();
        String inName = RunnerPaths.inPipeName(nonce);
        String outName = RunnerPaths.outPipeName(nonce);
        String sandboxSid = SandboxAccounts.sidString(cfg.username());
        long tSid = System.nanoTime();
        RunnerPipe in = null;
        RunnerPipe out = null;
        try {
            in = RunnerPipe.create(inName, RunnerPaths.PIPE_ACCESS_OUTBOUND, sandboxSid);
            out = RunnerPipe.create(outName, RunnerPaths.PIPE_ACCESS_INBOUND, sandboxSid);
            long tPipes = System.nanoTime();
            WinBase.PROCESS_INFORMATION pi = spawnWithLogon(cfg, inName, outName);
            long tSpawn = System.nanoTime();
            try {
                in.connect(pi.dwProcessId.intValue(), PIPE_CONNECT_TIMEOUT_MS);
                long tIn = System.nanoTime();
                out.connect(pi.dwProcessId.intValue(), PIPE_CONNECT_TIMEOUT_MS);
                LOG.log(System.Logger.Level.INFO,
                        "[runner] pid={0} timing sidResolve={1}ms pipesCreate={2}ms"
                                + " spawnWithLogon={3}ms connectIn={4}ms connectOut={5}ms",
                        new Object[] { pi.dwProcessId.intValue(),
                                (tSid - t0) / 1_000_000L, (tPipes - tSid) / 1_000_000L,
                                (tSpawn - tPipes) / 1_000_000L,
                                (tIn - tSpawn) / 1_000_000L,
                                (System.nanoTime() - tIn) / 1_000_000L });
                return new Channel(in, out, pi);
            } catch (IOException | RuntimeException e) {
                Kernel32Ex.INSTANCE.TerminateProcess(pi.hProcess, 1); // 收尸
                throw e;
            } finally {
                closeQuietly(pi.hThread);
            }
        } catch (IOException | RuntimeException e) {
            if (in != null) {
                in.close();
            }
            if (out != null) {
                out.close();
            }
            throw e;
        }
    }

    /** CreateProcessWithLogonW + 1056 原凭据重试 + 凭据类失败分类。 */
    private static WinBase.PROCESS_INFORMATION spawnWithLogon(RunnerConfig cfg,
            String pipeInName, String pipeOutName) throws IOException {
        for (int attempt = 1; attempt <= 2; attempt++) {
            int previousErrorMode = Kernel32.INSTANCE.SetErrorMode(RUNNER_ERROR_MODE_FLAGS);
            WinBase.PROCESS_INFORMATION pi;
            int failure;
            try {
                pi = new WinBase.PROCESS_INFORMATION();
                List<String> argv = runnerArgv(cfg.javaHome(), cfg.classpath(),
                        pipeInName, pipeOutName, cfg.codexHome());
                String cmdline = joinCommandLine(argv);
                java.nio.file.Path envTmp = SandboxDirs.sandboxDir(cfg.codexHome()).resolve("tmp");
                Files.createDirectories(envTmp);
                java.util.Map<String, String> runnerEnv =
                        new java.util.LinkedHashMap<>(System.getenv());
                runnerEnv.put("TEMP", envTmp.toString());
                runnerEnv.put("TMP", envTmp.toString());
                lastEnvDiag = runnerEnv.size() + "/" + envBlockBytes(runnerEnv);
                com.sun.jna.Pointer envBlock = EnvBlock.makeEnvBlock(runnerEnv);
                boolean ok = Advapi32.INSTANCE.CreateProcessWithLogonW(
                        cfg.username(),
                        ".",
                        cfg.password(), // JNA unicode 映射 → UTF-16LE，不经默认 charset
                        0, // 不传 LOGON_WITH_PROFILE（无 execution alias）
                        argv.get(0), // lpApplicationName = java.exe 绝对路径（对齐 codex）
                        cmdline,
                        Kernel32Ex.CREATE_NO_WINDOW | WinBase.CREATE_UNICODE_ENVIRONMENT,
                        envBlock,
                        cfg.workingDirectory(),
                        startupInfo(),
                        pi);
                if (ok) {
                    return pi;
                }
                failure = Kernel32.INSTANCE.GetLastError();
                if (failure == 0x80070057) { // E_INVALIDARG：参数二分诊断（每个变体独立打印错误码）
                    String envDiag = lastEnvDiag;
                    LOG.log(System.Logger.Level.WARNING,
                            "[runner] E_INVALIDARG 诊断: passwordLen={0} argv0={1} cwd={2} "
                                    + "envEntries={3} envBlockBytes={4} cmdlineLen={5}",
                            cfg.password() == null ? -1 : cfg.password().length(),
                            argv.get(0), cfg.workingDirectory(),
                            envDiag == null ? -1 : envDiag.substring(0, envDiag.indexOf('/')),
                            envDiag == null ? -1 : envDiag.substring(envDiag.indexOf('/') + 1),
                            cmdline.length());
                    // cmdline 全文 + argv 逐项（含可打印性检测：非 ASCII/控制字符以 U+XXXX 标注）
                    LOG.log(System.Logger.Level.WARNING,
                            "[runner] E_INVALIDARG cmdline 全文: <{0}>", cmdline);
                    for (int ai = 0; ai < argv.size(); ai++) {
                        LOG.log(System.Logger.Level.WARNING,
                                "[runner] E_INVALIDARG argv[{0}] len={1}: <{2}>",
                                ai, argv.get(ai).length(), printable(argv.get(ai)));
                    }
                    diagnoseSpawn(cfg, argv, cmdline);
                }
                closeQuietly(pi.hThread);
                closeQuietly(pi.hProcess);
            } finally {
                Kernel32.INSTANCE.SetErrorMode(previousErrorMode);
            }
            classifyLogonFailure(cfg.username(), failure);
            if (failure == WinErr.ERROR_SERVICE_ALREADY_RUNNING && attempt == 1) {
                continue; // 1056：原凭据重试一次（对齐 retry_runner_spawn_once）
            }
            throw new IOException("CreateProcessWithLogonW failed for runner: " + failure);
        }
        throw new IOException("CreateProcessWithLogonW failed for runner (retried)");
    }

    /**
     * runner JVM 的 {@code -Djna.boot.library.path} 取值：预提取产物所在目录
     * （{@link SandboxDirs#sandboxBinDir}，见 {@link RunnerMaterializer#ensureJnidispatch}），
     * 目录内无 {@code jnidispatch.dll} 则返回 null（不带该参数，行为回退默认）。
     *
     * <p><b>属性名易踩坑（本仓 Linux 实测）</b>：生效的是 {@code jna.boot.library.path}
     * 且必须<b>直接</b>指向含 native 库文件的目录（放 arch 子目录下仍会走解压）；
     * {@code jnidispatch.path} 是 JNA 解压<b>之后自己写出</b>的结果属性，作为入参会被
     * 覆写、完全无效。Windows 侧文件名无 {@code lib} 前缀（{@code jnidispatch.dll}），
     * 由 {@code System.loadLibrary("jnidispatch")} 按平台补全。
     *
     * <p><b>动机</b>：JNA 默认每个新进程都把 jnidispatch.dll 从 jar 解压到自身 TEMP 再
     * LoadLibrary、退出时删除；runner 的 TEMP 是沙箱账户私有目录，于是每条命令都制造一次
     * 「用户可写目录里的陌生新 DLL」——既是固定启动开销，也是 EDR 强查特征（实测 runner
     * 启动卡 8.2s 正落在 tee 之后、首次 JNA native 调用前后的窗口）。预提取目录 ACL 锁定
     * （组 R+X、沙箱账户不可写），解压路径不再发生。
     */
    static String bootLibraryPath(java.nio.file.Path codexHome) {
        if (codexHome == null) {
            return null;
        }
        java.nio.file.Path binDir = SandboxDirs.sandboxBinDir(codexHome);
        return java.nio.file.Files.isRegularFile(binDir.resolve("jnidispatch.dll"))
                ? binDir.toString() : null;
    }

    /**
     * classpath 通配符收敛：同目录多 jar → {@code <dir>\*}。
     *
     * <p><b>动机（2026-10-04 宿主实验实锤）</b>：CreateProcessWithLogonW 命令行
     * 上限 1024 字符（未文档化；阈值精确复现 1024=OK/1025=E_INVALIDARG）。8 jar
     * 全路径 classpath 使命令行达 1095 必然失败。Java 的 {@code dir\*} 通配符
     * 恰好展开为该目录全部 .jar（物化目录只含 runner 依赖 jar，语义等价），
     * 命令行降至 ~481 字符并留足余量。
     */
    static String collapseClasspathWildcard(String classpath) {
        if (classpath == null || classpath.indexOf(';') < 0) {
            return classpath;
        }
        String[] parts = classpath.split(";");
        java.nio.file.Path parent = null;
        for (String part : parts) {
            if (!part.endsWith(".jar")) {
                return classpath; // 非 jar 条目不收敛
            }
            java.nio.file.Path p = java.nio.file.Path.of(part).getParent();
            if (p == null || (parent != null && !p.equals(parent))) {
                return classpath; // 跨目录不收敛
            }
            parent = p;
        }
        return parent == null ? classpath : parent + "\\*";
    }

    /** 诊断用：非 ASCII/控制字符转 U+XXXX（不可见字符一眼可见）。 */
    private static String printable(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c < 0x7F) {
                sb.append(c);
            } else {
                sb.append(String.format("[U+%04X]", (int) c));
            }
        }
        return sb.toString();
    }

    /**
     * E_INVALIDARG 参数二分诊断（只打印不改变行为；成功变体立即杀进程留痕）。
     * 变体矩阵逐个隔离参数：NULL-env / 无 FORCEOFFEEDBACK / 无 lpApplicationName /
     * 最小命令行（无 ErrorFile/classpath/runner 参数）。
     */
    private static void diagnoseSpawn(RunnerConfig cfg, List<String> argv, String cmdline) {
        String minimal = "\"" + argv.get(0) + "\" -version";
        WinBase.STARTUPINFO plain = new WinBase.STARTUPINFO();
        plain.cb = new WinDef.DWORD(plain.size());
        record Variant(String tag, String app, String cmd, com.sun.jna.Pointer env,
                WinBase.STARTUPINFO si) { }
        java.util.List<Variant> tries = new java.util.ArrayList<>();
        tries.add(new Variant("V1-nullEnv", argv.get(0), cmdline,
                com.sun.jna.Pointer.NULL, startupInfo()));
        tries.add(new Variant("V2-plainStartup", argv.get(0), cmdline, null, plain));
        tries.add(new Variant("V3-noAppName", null, cmdline, null, startupInfo()));
        tries.add(new Variant("V4-minimal", argv.get(0), minimal,
                com.sun.jna.Pointer.NULL, plain));
        for (Variant v : tries) {
            WinBase.PROCESS_INFORMATION pi = new WinBase.PROCESS_INFORMATION();
            com.sun.jna.Pointer env = v.env() == null
                    ? EnvBlock.makeEnvBlock(java.util.Map.of())
                    : v.env();
            try {
                boolean ok = Advapi32.INSTANCE.CreateProcessWithLogonW(
                        cfg.username(), ".", cfg.password(), 0,
                        v.app(), v.cmd(),
                        Kernel32Ex.CREATE_NO_WINDOW | WinBase.CREATE_UNICODE_ENVIRONMENT,
                        env, cfg.workingDirectory(), v.si(), pi);
                if (ok) {
                    LOG.log(System.Logger.Level.WARNING,
                            "[runner] E_INVALIDARG 二分: {0} => OK(pid={1}) ← 参数组合可定位",
                            v.tag(), pi.dwProcessId);
                    Kernel32Ex.INSTANCE.TerminateProcess(pi.hProcess, 1);
                } else {
                    LOG.log(System.Logger.Level.WARNING,
                            "[runner] E_INVALIDARG 二分: {0} => err={1}",
                            v.tag(), Kernel32.INSTANCE.GetLastError());
                }
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.WARNING,
                        "[runner] E_INVALIDARG 二分: {0} => ex {1}", v.tag(), t);
            } finally {
                closeQuietly(pi.hThread);
                closeQuietly(pi.hProcess);
            }
        }
    }

    /** STARTUPINFO：cb + STARTF_FORCEOFFFEEDBACK（对齐 runner_client.rs；无桌面字段）。 */
    private static WinBase.STARTUPINFO startupInfo() {
        WinBase.STARTUPINFO si = new WinBase.STARTUPINFO();
        si.cb = new com.sun.jna.platform.win32.WinDef.DWORD(si.size());
        si.dwFlags |= WinBase.STARTF_FORCEOFFFEEDBACK;
        return si;
    }

    /** 凭据失败分类：凭据类抛 CredentialMismatchException，其余留给调用方处理。 */
    static void classifyLogonFailure(String username, int code) {
        if (CREDENTIAL_MISMATCH_CODES.contains(code)) {
            throw new CredentialMismatchException(username, code);
        }
    }

    /** 纯函数（可测）：runner 命令行参数（JVM flags 对齐设计 §4.1）。 */
    public static List<String> runnerArgv(String javaHome, String classpath,
            String pipeInName, String pipeOutName) {
        return runnerArgv(javaHome, classpath, pipeInName, pipeOutName, null);
    }

    /** 带 codexHome 重载：JVM 崩溃文件落 .sandbox/tmp（沙箱进程无控制台，hs_err 默认进黑洞）。 */
    public static List<String> runnerArgv(String javaHome, String classpath,
            String pipeInName, String pipeOutName, java.nio.file.Path codexHome) {
        // Windows 语义固定反斜杠（runner 仅在 Windows 上被拉起；跨平台单测断言此形态）
        String home = javaHome.endsWith("\\") || javaHome.endsWith("/")
                ? javaHome.substring(0, javaHome.length() - 1) : javaHome;
        String javaExe = home + "\\bin\\java.exe";
        java.util.List<String> argv = new java.util.ArrayList<>(java.util.List.of(javaExe,
                "-XX:+UseSerialGC", "-Xshare:auto", "-Dfile.encoding=UTF-8"));
        String bootPath = bootLibraryPath(codexHome);
        if (bootPath != null) {
            argv.add("-Djna.boot.library.path=" + bootPath);
        }
        if (codexHome != null) {
            try {
                java.nio.file.Path tmp = SandboxDirs.sandboxDir(codexHome).resolve("tmp");
                java.nio.file.Files.createDirectories(tmp);
                argv.add("-XX:ErrorFile=" + tmp.resolve("runner-hs_err.log"));
            } catch (java.io.IOException ignored) {
                // 崩溃文件不可用时默认行为
            }
        }
        argv.addAll(java.util.List.of(
                "-cp", collapseClasspathWildcard(classpath),
                RunnerMaterializer.RUNNER_MAIN,
                "--pipe-in=" + pipeInName,
                "--pipe-out=" + pipeOutName));
        return java.util.Collections.unmodifiableList(argv);
    }

    /** env 块字节数（UTF-16，双 null 终结；与 EnvBlock 同口径，仅诊断用）。 */
    static int envBlockBytes(java.util.Map<String, String> env) {
        int chars = 1; // 末尾终结符
        for (java.util.Map.Entry<String, String> e : env.entrySet()) {
            chars += e.getKey().length() + 1 + e.getValue().length() + 1;
        }
        return chars * 2;
    }

    /** runner 环境变量：继承当前环境 + TEMP/TMP → {@code <codexHome>/.sandbox/tmp}。 */
    static Map<String, String> runnerEnvironment(Path codexHome) throws IOException {
        Path tmp = SandboxDirs.sandboxDir(codexHome).resolve("tmp");
        Files.createDirectories(tmp);
        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        env.put("TEMP", tmp.toString());
        env.put("TMP", tmp.toString());
        return env;
    }

    /** 命令行合成（对齐 winutil.rs::argv_to_command_line）。 */
    static String joinCommandLine(List<String> argv) {
        StringBuilder sb = new StringBuilder();
        for (String arg : argv) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(quoteWindowsArg(arg));
        }
        return sb.toString();
    }

    /** 单参数 CRT 引号转义（对齐 winutil.rs::quote_windows_arg；与 runner/setup 同款实现）。 */
    static String quoteWindowsArg(String arg) {
        boolean needsQuotes = arg.isEmpty();
        for (int i = 0; i < arg.length() && !needsQuotes; i++) {
            char c = arg.charAt(i);
            needsQuotes = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\"';
        }
        if (!needsQuotes) {
            return arg;
        }
        StringBuilder quoted = new StringBuilder(arg.length() + 2).append('\"');
        int backslashes = 0;
        for (int i = 0; i < arg.length(); i++) {
            char ch = arg.charAt(i);
            if (ch == '\\') {
                backslashes++;
            } else if (ch == '\"') {
                quoted.append("\\".repeat(backslashes * 2 + 1)).append('\"');
                backslashes = 0;
            } else {
                if (backslashes > 0) {
                    quoted.append("\\".repeat(backslashes));
                    backslashes = 0;
                }
                quoted.append(ch);
            }
        }
        if (backslashes > 0) {
            quoted.append("\\".repeat(backslashes * 2));
        }
        return quoted.append('\"').toString();
    }

    private static void closeQuietly(WinNT.HANDLE h) {
        if (h != null && h.getPointer() != null) {
            Kernel32Ex.INSTANCE.CloseHandle(h);
        }
    }

    /** 已连接的 runner 通道（AutoCloseable：进程句柄 + 双管道）。 */
    public static final class Channel implements AutoCloseable {
        private final RunnerPipe in; // 父写 runner 读
        private final RunnerPipe out; // runner 写父读
        private final WinBase.PROCESS_INFORMATION pi;

        Channel(RunnerPipe in, RunnerPipe out, WinBase.PROCESS_INFORMATION pi) {
            this.in = in;
            this.out = out;
            this.pi = pi;
        }

        /** runner 进程 PID（PID 校验与诊断用）。 */
        public int runnerPid() {
            return pi.dwProcessId.intValue();
        }

        /** 父→runner 写一帧。 */
        public void send(IpcMessage message) {
            FrameCodec.writeFrame(in.handle(), message);
        }

        /** runner→父 阻塞读一帧；帧边界 EOF 返回 null。 */
        public FrameCodec.FramedMessage readFrame() {
            return FrameCodec.readFrame(out.handle());
        }

        /**
         * 限时读一帧：PeekNamedPipe 5ms 轮询等<b>完整帧</b>就绪再读
         * （对齐 framed_io.rs::wait_for_complete_frame + 15s spawn_ready 上限）。
         */
        public FrameCodec.FramedMessage readFrame(long timeoutMs) throws IOException {
            waitForCompleteFrame(out.handle(), timeoutMs);
            return readFrame();
        }

        static void waitForCompleteFrame(WinNT.HANDLE pipe, long timeoutMs) throws IOException {
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            byte[] head = new byte[4];
            while (true) {
                IntByReference read = new IntByReference();
                IntByReference avail = new IntByReference();
                if (!Kernel32.INSTANCE.PeekNamedPipe(pipe, head, head.length, read,
                        avail, null)) {
                    throw new IOException("PeekNamedPipe failed: "
                            + Kernel32.INSTANCE.GetLastError());
                }
                if (read.getValue() >= 4) {
                    int len = (head[0] & 0xFF) | ((head[1] & 0xFF) << 8)
                            | ((head[2] & 0xFF) << 16) | ((head[3] & 0xFF) << 24);
                    if (avail.getValue() >= 4 + len
                            && len <= FrameCodec.MAX_FRAME_LEN) {
                        return; // 完整帧已缓冲
                    }
                    if (len > FrameCodec.MAX_FRAME_LEN) {
                        throw new FrameCodec.FrameException("frame too large: " + len);
                    }
                }
                if (System.nanoTime() >= deadline) {
                    throw new IOException("timed out after " + timeoutMs
                            + "ms waiting for complete frame");
                }
                try {
                    Thread.sleep(5); // codex wait_for_complete_frame 轮询节奏
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while waiting for frame", e);
                }
            }
        }

        /** TerminateProcess 收尸（握手失败/会话中止兜底）。 */
        public void terminateRunner() {
            Kernel32Ex.INSTANCE.TerminateProcess(pi.hProcess, 1);
        }

        /** 清理：进程句柄 + 双管道（管道关闭即 runner 退出信号）。 */
        @Override
        public void close() {
            in.close();
            out.close();
            closeQuietly(pi.hProcess);
        }
    }
}
