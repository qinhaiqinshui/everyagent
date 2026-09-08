package dev.everyagent.worker.git;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.os.OsSandbox.ExecResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * 原生 git 执行器(架构 §7.12;docs/GIT_NATIVE_MIGRATION.md):
 * git.* RPC 由 worker 宿主原生 git argv 直传执行——不自己复刻 git(VSCode 同款路线),
 * 不经 wsl/mic 沙箱后端(前端按钮触发的平台受控操作,复用 OsSandbox 的超时 / 输出上限 /
 * env 清理即可)。取代 JGit:修复 Windows 下 config / 凭证 / SSH / 语义差异类 bug。
 *
 * <p>职责:
 * <ul>
 *   <li>定位 git 可执行文件(配置 → 常见安装路径 → PATH;探测一次并缓存,冒烟 git --version);</li>
 *   <li>构造稳定化 argv({@code -C workspace}、{@code color.ui=false}、{@code core.quotepath=false}、
 *       {@code --no-pager},读命令加 {@code --no-optional-locks} 防 {@code index.lock} 竞争);</li>
 *   <li>凭证注入({@code GIT_ASKPASS}+{@code SSH_ASKPASS} env;凭证值经 env 传递,askpass 脚本只按
 *       prompt 路由 username/password,不经 shell 拼接防注入);本机默认档不注入,git 自行走
 *       {@code credential.helper} / credential manager / {@code ssh-agent} / {@code ~/.ssh};</li>
 *   <li>per-workspace 写操作串行锁(原生 git 并发写同一仓库会产生 {@code index.lock} 冲突);</li>
 *   <li>统一执行入口(OsSandbox.spawnNative,argv 直传无 shell 解析)。</li>
 * </ul>
 */
@Component
public class NativeGit {

    private static final Logger log = LoggerFactory.getLogger(NativeGit.class);

    private final WorkerProperties props;
    private final OsSandbox sandbox;
    private final boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

    /** git 可执行文件定位缓存(null = 未探测;定位失败置 probeFailed,不再重试)。 */
    private volatile String gitExe;
    private volatile boolean probeFailed;
    /** per-workspace 写锁(原生 git 并发写同一仓库会产生 index.lock 冲突)。 */
    private final Map<String, ReentrantLock> writeLocks = new ConcurrentHashMap<>();

    /** 凭证失败关键字(stderr 匹配,大小写不敏感;抄 VSCode git 扩展的识别集)。 */
    private static final String[] AUTH_FAILURE_MARKERS = {
        "authentication failed",
        "invalid username or password",
        "could not read username",
        "could not read password",
        "fatal: could not read username",
        "fatal: could not read password",
        "authentication is required",
        "not authorized",
        "unauthorized",
        "401",
        "403",
    };

    /** git 官方 unmerged(冲突)porcelain XY 状态码集合。 */
    private static final java.util.Set<String> UNMERGED =
            java.util.Set.of("DD", "AU", "UD", "UA", "DU", "AA", "UU");

    private static final String[] WINDOWS_GIT_CANDIDATES = {
        "C:/Program Files/Git/bin/git.exe",
        "C:/Program Files/Git/cmd/git.exe",
        "C:/Program Files (x86)/Git/bin/git.exe",
        "C:/Program Files (x86)/Git/cmd/git.exe",
        "D:/Program Files/Git/bin/git.exe",
        "D:/Program Files/Git/cmd/git.exe",
    };

    private static final String[] POSIX_GIT_CANDIDATES = {
        "/usr/local/bin/git",
        "/usr/bin/git",
        "/opt/local/bin/git",
    };

    private static final String GIT_UNAVAILABLE =
            "git 不可用: 未找到 git 可执行文件(请安装 Git for Windows / git,"
                    + "或在 worker.git.executable 配置其路径)";

    public NativeGit(WorkerProperties props, OsSandbox sandbox) {
        this.props = props;
        this.sandbox = sandbox;
    }

    // ---- 对外 API ----

    /** 执行 git 读命令:加 --no-optional-locks,不进写锁(并发安全)。 */
    public NativeResult runRead(Path workspace, List<String> args, CredentialSpec credential)
            throws IOException {
        return run(workspace, args, true, credential, props.getGit().getTimeoutMs());
    }

    /** 执行 git 写命令:per-workspace 串行锁,不加 --no-optional-locks。 */
    public NativeResult runWrite(Path workspace, List<String> args, CredentialSpec credential)
            throws IOException {
        return run(workspace, args, false, credential, props.getGit().getTimeoutMs());
    }

    /** 执行 git 写命令 + 自定义超时(clone 大仓库等)。 */
    public NativeResult runWrite(Path workspace, List<String> args, CredentialSpec credential,
            long timeoutMs) throws IOException {
        return run(workspace, args, false, credential, timeoutMs);
    }

    /** stderr 是否命中凭证失败关键字(exit 0 恒 false;供 GitService 决策 AUTH_REQUIRED)。 */
    public static boolean isAuthFailure(NativeResult r) {
        if (r.exitCode() == 0) {
            return false;
        }
        String err = r.stderr() == null ? "" : r.stderr().toLowerCase(Locale.ROOT);
        for (String m : AUTH_FAILURE_MARKERS) {
            if (err.contains(m)) {
                return true;
            }
        }
        return false;
    }

    /** stderr 是否「不是 git 仓库」(供 GitService 转 NotFoundException)。 */
    public static boolean isNotRepo(NativeResult r) {
        String err = r.stderr() == null ? "" : r.stderr().toLowerCase(Locale.ROOT);
        return err.contains("not a git repository") || err.contains("not a git repo");
    }

    /**
     * 解析 {@code git status --porcelain=v1 -z --untracked-files=all} 输出到现有 7 类字段。
     * 路径以 '/' 分隔(与 git 输出一致;Windows 上也如此)。
     */
    public static StatusData parseStatus(String stdout) {
        List<String> added = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> untracked = new ArrayList<>();
        List<String> conflicting = new ArrayList<>();
        if (stdout != null && !stdout.isEmpty()) {
            // -z:记录以 NUL 分隔;rename/copy 的 old path 是独立下一条,忽略即可
            String[] parts = stdout.split("\0", -1);
            for (String rec : parts) {
                if (rec.isEmpty()) {
                    continue;
                }
                if (rec.length() < 3) {
                    continue; // 防御:非法记录
                }
                String xy = rec.substring(0, 2);
                String path = rec.substring(3);
                if ("??".equals(xy)) {
                    untracked.add(path);
                } else if (UNMERGED.contains(xy)) {
                    conflicting.add(path);
                } else {
                    char x = xy.charAt(0);
                    char y = xy.charAt(1);
                    if (x == 'A' || x == 'R' || x == 'C') {
                        added.add(path); // 已暂存新增(含 rename/copy 目标,对齐 JGit getAdded)
                    } else if (x == 'M' || x == 'T') {
                        changed.add(path); // 已暂存修改
                    } else if (x == 'D') {
                        removed.add(path); // 已暂存删除
                    } else if (x == ' ' && y == 'D') {
                        missing.add(path); // 工作区删除(未暂存)
                    } else if (x == ' ' && (y == 'M' || y == 'T')) {
                        modified.add(path); // 工作区修改(未暂存)
                    }
                    // 其余(!! ignored 等)前端不需要
                }
            }
        }
        return new StatusData(added, changed, modified, removed, missing, untracked, conflicting);
    }

    /** git status --porcelain=v1 -z --untracked-files=all 的解析结果(7 类字段,对齐现有契约)。 */
    public record StatusData(List<String> added, List<String> changed, List<String> modified,
            List<String> removed, List<String> missing, List<String> untracked,
            List<String> conflicting) {
        public boolean clean() {
            return added.isEmpty() && changed.isEmpty() && modified.isEmpty() && removed.isEmpty()
                    && missing.isEmpty() && untracked.isEmpty() && conflicting.isEmpty();
        }

        /** 已跟踪且有内容变更的路径(供 discard 判定;对齐 JGit 的 trackedChanged 语义)。 */
        public java.util.Set<String> trackedChanged() {
            java.util.Set<String> s = new java.util.HashSet<>();
            s.addAll(changed);
            s.addAll(modified);
            s.addAll(removed);
            s.addAll(missing);
            s.addAll(conflicting);
            return s;
        }
    }

    // ---- 内部 ----

    private NativeResult run(Path workspace, List<String> args, boolean readOnly,
            CredentialSpec credential, long timeoutMs) throws IOException {
        List<String> argv = new ArrayList<>();
        argv.add(locateGit());
        argv.add("-C");
        argv.add(workspace.toString());
        argv.add("-c");
        argv.add("color.ui=false");
        argv.add("-c");
        argv.add("core.quotepath=false");
        argv.add("--no-pager");
        if (readOnly) {
            argv.add("--no-optional-locks"); // 读命令防 index.lock 残留/竞争(VSCode 同款)
        }
        argv.addAll(args);

        Map<String, String> env = new HashMap<>();
        env.put("GIT_TERMINAL_PROMPT", "0"); // 禁交互提示,缺凭证 fail-fast,绝不卡死
        env.put("LC_ALL", "C.UTF-8");
        Path askPassDir = null;
        if (credential != null && credential.explicit()) {
            askPassDir = writeAskPass(credential.username(), credential.password(), env);
        }

        try {
            String[] cmd = argv.toArray(String[]::new);
            if (readOnly) {
                ExecResult r = sandbox.spawnNative(cmd, workspace, env, timeoutMs);
                return new NativeResult(r.stdout(), r.stderr(), r.exitCode());
            }
            ReentrantLock lock = writeLocks.computeIfAbsent(
                    workspace.toAbsolutePath().normalize().toString(), k -> new ReentrantLock());
            lock.lock();
            try {
                ExecResult r = sandbox.spawnNative(cmd, workspace, env, timeoutMs);
                return new NativeResult(r.stdout(), r.stderr(), r.exitCode());
            } finally {
                lock.unlock();
            }
        } finally {
            if (askPassDir != null) {
                deleteQuietly(askPassDir);
            }
        }
    }

    /** 定位 git:配置 → 常见安装路径 → PATH;冒烟验证后缓存;失败置 probeFailed 不再重试。 */
    private String locateGit() throws IOException {
        String cached = gitExe;
        if (cached != null) {
            return cached;
        }
        if (probeFailed) {
            throw new IOException(GIT_UNAVAILABLE);
        }
        synchronized (this) {
            if (gitExe != null) {
                return gitExe;
            }
            if (probeFailed) {
                throw new IOException(GIT_UNAVAILABLE);
            }
            String exe = discover();
            if (exe == null) {
                probeFailed = true;
                throw new IOException(GIT_UNAVAILABLE);
            }
            ExecResult probe = sandbox.spawnNative(new String[] { exe, "--version" },
                    Path.of(System.getProperty("user.dir")), Map.of(), 15_000);
            if (probe.exitCode() != 0) {
                probeFailed = true;
                throw new IOException("git 探测失败(" + exe + "): " + probe.stderr());
            }
            log.info("[git] 使用原生 git: {} ({})", exe, probe.stdout().trim());
            gitExe = exe;
            return exe;
        }
    }

    /** 探测顺序:配置 → 常见安装路径(Windows)/标准路径(POSIX) → PATH 兜底。 */
    private String discover() {
        String configured = props.getGit().getExecutable();
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured.trim());
            if (Files.isRegularFile(p)) {
                return p.toString();
            }
            log.warn("[git] 配置的 worker.git.executable 不存在: {}", configured);
        }
        String[] candidates = windows ? WINDOWS_GIT_CANDIDATES : POSIX_GIT_CANDIDATES;
        for (String base : candidates) {
            if (Files.isRegularFile(Path.of(base))) {
                return base;
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            String sep = File.pathSeparator;
            for (String dir : path.split(Pattern.quote(sep))) {
                if (dir == null || dir.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(dir.trim()).resolve(windows ? "git.exe" : "git");
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }

    /**
     * 生成一次性 askpass 脚本并写入 env:凭证值经 {@code GIT_EA_USERNAME}/{@code GIT_EA_PASSWORD}
     * env 传递,脚本只按 prompt 路由——不把密码写进脚本文件、不经 shell 拼接,防特殊字符注入。
     * Windows 用 .bat(延迟展开 !VAR!,& 等特殊字符不会当命令分隔);POSIX 用 .sh。
     */
    private Path writeAskPass(String username, String password, Map<String, String> env)
            throws IOException {
        Path dir = Files.createTempDirectory("ea-git-askpass");
        Path script;
        if (windows) {
            script = dir.resolve("askpass.bat");
            String content = "@echo off\r\n"
                    + "setlocal EnableDelayedExpansion\r\n"
                    + "echo %~1 | findstr /C:\"Username\" >nul\r\n"
                    + "if %errorlevel%==0 (echo !GIT_EA_USERNAME!) else (echo !GIT_EA_PASSWORD!)\r\n";
            Files.writeString(script, content, StandardCharsets.UTF_8);
        } else {
            script = dir.resolve("askpass.sh");
            String content = "#!/bin/sh\n"
                    + "case \"$1\" in\n"
                    + "  *Username*) printf '%s\\n' \"$GIT_EA_USERNAME\" ;;\n"
                    + "  *) printf '%s\\n' \"$GIT_EA_PASSWORD\" ;;\n"
                    + "esac\n";
            Files.writeString(script, content, StandardCharsets.UTF_8);
            if (!script.toFile().setExecutable(true, true)) {
                throw new IOException("无法设置 askpass 脚本可执行权限: " + script);
            }
        }
        env.put("GIT_ASKPASS", script.toString());
        env.put("SSH_ASKPASS", script.toString());
        env.put("SSH_ASKPASS_REQUIRE", "force"); // 非交互也走 askpass(现代 OpenSSH 8.4+)
        env.put("GIT_EA_USERNAME", username);
        env.put("GIT_EA_PASSWORD", password);
        return dir;
    }

    /** 递归删除临时目录;Windows 上 git 子进程可能仍持有脚本句柄,失败交给 deleteOnExit 兜底。 */
    private static void deleteQuietly(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    p.toFile().deleteOnExit();
                }
            });
        } catch (IOException ignored) {
            dir.toFile().deleteOnExit();
        }
    }

    // ---- 数据 ----

    /** git 命令结果(stdout/stderr 原始文本 + exitCode)。 */
    public record NativeResult(String stdout, String stderr, int exitCode) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    /** 凭证规格:none = 不注入(本机默认档,git 自行走 credential.helper/ssh-agent);explicit = askpass 注入。 */
    public record CredentialSpec(String username, String password) {
        public static CredentialSpec none() {
            return new CredentialSpec(null, null);
        }

        public static CredentialSpec of(String username, String password) {
            return new CredentialSpec(username, password);
        }

        public boolean explicit() {
            return username != null && password != null;
        }
    }
}
