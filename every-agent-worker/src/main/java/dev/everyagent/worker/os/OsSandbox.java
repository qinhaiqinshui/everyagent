package dev.everyagent.worker.os;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.os.windows.WindowsSandbox;
import dev.everyagent.worker.os.wsl.WslBwrapSandbox;
import dev.everyagent.worker.os.wsl.WslDirectSandbox;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * OS 级进程沙箱门面。
 *
 * <p>职责:在 task 的工作区根内 spawn 真实 OS 进程(cmd / bash / git / mvn ...),
 * 并尽量把子进程降权隔离,防止逃逸 / 资源耗尽 / 乱读文件。
 *
 * <p>后端策略(worker.sandbox.type,docs/ARCHITECTURE.md §7.10):
 * <ul>
 *   <li><b>windows-mic(Windows 默认,成熟路径)</b>:委托
 *       {@link dev.everyagent.worker.os.windows.WindowsSandbox},经 jna-platform(+
 *       {@link dev.everyagent.worker.os.windows.Win32Ex} 补充原语)调用 Win32 ——
 *       Restricted Token(降权 + Low IL)+ Job Object(活动进程数上限/内存/CPU/
 *       KillOnJobClose)+ 目录 Low 标注(§13.6)。代码保留,仅随 §13 加固。</li>
 *   <li><b>wsl-bwrap(显式配置启用,type=wsl-bwrap)</b>:委托
 *       {@link dev.everyagent.worker.os.wsl.WslBwrapSandbox},命令经 wsl.exe 进托管
 *       发行版、bubblewrap 挂载命名空间运行——沙箱语义是每次调用的参数(授权根 =
 *       --bind 白名单),宿主 NTFS 零残留,网络 --unshare-net 硬拒,沙箱内读白名单
 *       (工作区之外宿主盘不可见)。要求 worker 自身非降权(WSL 服务拒 Low-IL 调用方)。
 *       默认不启用:更强隔离需要用户显式选择,探测/自动导入成本也只在显式选择时发生。</li>
 *   <li><b>非 Windows</b>:本版不做内核级沙箱,退化为「直接 spawn + 超时 + 输出上限 +
 *       清空网络代理 env」,并打印明确告警,由上层 FsToolSupport 的 Java 层 jail 兜底。
 *       type 配置在此平台无意义。</li>
 * </ul>
 *
 * <p>网络边界:wsl-bwrap 后端 OS 级硬拒(unshare-net);mic/direct 后端 {@code deny-all}
 * 仅剥离代理 env,真网络隔离做不到——这是两后端的能力差异,与语言无关。
 */
@Component
public final class OsSandbox {

    private static final Logger log = LoggerFactory.getLogger(OsSandbox.class);

    /** 单流输出字符上限(stdout / stderr 各自适用):超出截断,防止超大输出撑爆上下文 / 内存。 */
    private static final int MAX_OUTPUT_CHARS = 1_000_000;

    /** 子进程 stdin 的 null 设备(Windows=NUL 设备,其余=/dev/null),见命令 stdin 契约 §7.10。 */
    private static final java.io.File NULL_INPUT = new java.io.File(
            System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                    ? "NUL" : "/dev/null");

    /** 解析后的沙箱后端。 */
    public enum Backend {
        /** WSL2 发行版 + bubblewrap 挂载命名隔离。 */
        WSL_BWRAP,
        /** WSL2 发行版 root 完整权限直连(发行版可丢弃,宿主靠 automount 关闭隔离)。 */
        WSL_DIRECT,
        /** 原生 Windows:Restricted Token + Low IL + Job Object + 目录标注。 */
        WINDOWS_MIC,
        /** 不隔离(配置显式 none 或 sandbox.enabled=false 或非 Windows)。 */
        DIRECT
    }

    private final WorkerProperties props;
    private final WorkerProperties.Sandbox cfg;
    private final dev.everyagent.worker.modules.WorkspaceManager workspaces;
    private final boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    /** 后端解析结果(启动 @PostConstruct 即解析一次并打印,worker 生命周期内不重探;探测含冷启动 VM,代价不小)。 */
    private volatile Backend resolved;

    public OsSandbox(WorkerProperties props,
            dev.everyagent.worker.modules.WorkspaceManager workspaces) {
        this.props = props;
        this.cfg = props.getSandbox();
        this.workspaces = workspaces;
    }

    /**
     * 启动即解析并打印生效后端:后端选择从「首条命令时静默发生」变为「启动日志可见」。
     * 探测成本只在显式 type=wsl-bwrap 档发生(WSL 冒烟冷启动 VM 可达数秒、上限 30s;
     * 失败定位断因至多再加两次调用,≤75s;首次安装触发托管镜像自动导入另计,解包上限
     * 5min,一次性),auto/默认档零 WSL 调用;结果缓存复用。
     */
    @PostConstruct
    void logBackendAtStartup() {
        Backend b = backend();
        String configured = cfg.getType() == null || cfg.getType().isBlank()
                ? "auto(平台默认)" : cfg.getType().trim();
        switch (b) {
            case WSL_BWRAP -> log.info("[sandbox] 生效后端 = wsl-bwrap(配置 {}):distro={},授权根 = --bind 白名单,"
                    + "网络 {},提权 {},持久状态 {},命令方言 bash", configured,
                    WslBwrapSandbox.distroLabel(WslBwrapSandbox.effectiveDistro(props)),
                    cfg.networkDenied() ? "--unshare-net 硬拒" : "放行",
                    cfg.isAllowPrivilegeEscalation() ? "root(user namespace)" : "普通用户",
                    cfg.isPersistentState() ? "开(" + props.resolveSandboxPersistentRoot() + ")" : "关");
            case WSL_DIRECT -> log.info("[sandbox] 生效后端 = wsl-direct(配置 {}):distro={},root 完整权限,"
                    + "发行版可丢弃,宿主 automount 关闭 + 工作区手动挂载,命令方言 bash", configured,
                    WslBwrapSandbox.distroLabel(WslBwrapSandbox.effectiveDistro(props)));
            case WINDOWS_MIC -> log.info("[sandbox] 生效后端 = windows-mic(配置 {}):Restricted Token + Low IL "
                    + "+ Job Object,工作区/授权根目录有 Low 标注与 ACL 副作用(§13.6)", configured);
            case DIRECT -> {
                if (!cfg.isEnabled() || "none".equals(normalizeBackend(cfg.getType()))) {
                    log.info("[sandbox] 沙箱未启用,命令直接 spawn(仅超时/输出护栏):type={}", configured);
                } else {
                    log.warn("[sandbox] 本平台无内核级沙箱,退化为直接 spawn(Java 层 jail 兜底):"
                            + "type={},os={}", configured, System.getProperty("os.name"));
                }
            }
        }
    }

    /**
     * Windows 原生(mic)沙箱是否生效:受限 token + Low IL 后端被选中且启用。
     * 调用方(命令执行器)据此决定是否做工作区/授权根的 Low 完整性标注:
     * Low IL 进程写默认 Medium 的目录会被 OS 拒,须先标注才可写(§13.6);
     * wsl-bwrap 后端无宿主标注,恒为 false。
     */
    public boolean isWindowsSandboxActive() {
        return windows && cfg.isEnabled() && backend() == Backend.WINDOWS_MIC;
    }

    /** 当前后端是否为 wsl 系列(bwrap 或 direct):命令方言 = bash,AI 以 Linux 视角运行。 */
    public boolean isWslBackend() {
        Backend b = backend();
        return b == Backend.WSL_BWRAP || b == Backend.WSL_DIRECT;
    }

    /** 当前后端是否为 wsl-bwrap(隔离挂载命名空间)。 */
    public boolean isWslBwrap() {
        return backend() == Backend.WSL_BWRAP;
    }

    /** 当前后端是否为 wsl-direct(root 完整权限直连,发行版可丢弃)。 */
    public boolean isWslDirect() {
        return backend() == Backend.WSL_DIRECT;
    }

    /**
     * 命令工具注册方言:true = 注册 BashTool(wsl 系列后端,或非 Windows);
     * false = Windows mic 后端注册 PowerShellTool。
     */
    public boolean registerBashTool() {
        return !windows || isWslBackend();
    }

    /** 后端解析(懒、缓存):auto/未配置 → 平台默认(Windows=wsl-direct,其余=direct);
     *  显式 wsl-direct / wsl-bwrap → 探测,失败回退 windows-mic(可用性优先,日志分级)。 */
    public Backend backend() {
        Backend b = resolved;
        if (b != null) {
            return b;
        }
        synchronized (this) {
            if (resolved != null) {
                return resolved;
            }
            resolved = resolve();
            return resolved;
        }
    }

    /**
     * backend 值归一:{@code auto}(默认)| {@code wsl-direct} | {@code wsl-bwrap} |
     * {@code windows-mic} | {@code none};别名 {@code acl}→windows-mic、
     * {@code wsl}/{@code bwrap}→wsl-bwrap、{@code direct}→wsl-direct;
     * 未知/空值 → auto。纯函数,单测钉住契约。
     */
    static String normalizeBackend(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (s) {
            case "wsl-bwrap", "wsl", "bwrap" -> "wsl-bwrap";
            case "wsl-direct", "direct" -> "wsl-direct";
            case "windows-mic", "acl", "mic" -> "windows-mic";
            case "none" -> "none";
            default -> "auto";
        };
    }

    private Backend resolve() {
        String name = normalizeBackend(cfg.getType());
        if (!cfg.isEnabled() || "none".equals(name)) {
            return Backend.DIRECT;
        }
        if (!windows) {
            // 非 Windows 本版无内核级沙箱:type 配置无意义,恒直接 spawn(Java 层 jail 兜底)
            if ("wsl-bwrap".equals(name) || "wsl-direct".equals(name)) {
                log.error("[sandbox] 非 Windows 平台不支持 {} 后端,退化为直接 spawn", name);
            }
            return Backend.DIRECT;
        }
        // 用户配置了未知 type 值(normalize 后落 auto):提示后按默认 wsl-direct 处理
        if ("auto".equals(name) && cfg.getType() != null && !cfg.getType().isBlank()
                && !"auto".equalsIgnoreCase(cfg.getType().trim())) {
            log.warn("[sandbox] 未知 type 值 {},按 auto 处理(Windows 默认 wsl-direct)", cfg.getType());
        }
        // Windows:wsl-direct(默认)与 wsl-bwrap(显式)都探测发行版;windows-mic 为 ACL 路径
        if ("wsl-direct".equals(name)) {
            return resolveWslDirect("wsl-direct");
        }
        if ("wsl-bwrap".equals(name)) {
            return resolveWslBwrap();
        }
        if ("windows-mic".equals(name)) {
            return Backend.WINDOWS_MIC;
        }
        // "auto"(含未知值):Windows 默认 wsl-direct
        return resolveWslDirect("auto");
    }

    /** wsl-direct 探测:发行版可 root 进入 + bash/mount/findmnt 在位;失败回退 windows-mic。 */
    private Backend resolveWslDirect(String configured) {
        String distro = WslBwrapSandbox.distroLabel(WslBwrapSandbox.effectiveDistro(props));
        WslBwrapSandbox.ProbeResult pr = WslDirectSandbox.probe(props);
        if (!pr.ok() && pr.cause() == WslBwrapSandbox.Cause.DISTRO_NOT_FOUND) {
            // L2 自举:发行版缺失且托管镜像在位 → 自动 wsl --import 后按 direct 后端重探
            pr = WslBwrapSandbox.autoImport(props, pr, () -> WslDirectSandbox.probe(props));
            if (pr.ok()) {
                return Backend.WSL_DIRECT;
            }
        }
        if (pr.ok()) {
            return Backend.WSL_DIRECT;
        }
        log.error("[sandbox] wsl-direct 探测失败(配置 {},distro={},{});修正:{};回退 windows-mic(可用性优先)",
                configured, distro, pr.brief(), pr.fix());
        return Backend.WINDOWS_MIC;
    }

    /** wsl-bwrap 探测:发行版可 exec + python3 + bwrap 冒烟;失败回退 windows-mic。 */
    private Backend resolveWslBwrap() {
        String distro = WslBwrapSandbox.distroLabel(WslBwrapSandbox.effectiveDistro(props));
        WslBwrapSandbox.ProbeResult pr = WslBwrapSandbox.probe(props);
        if (!pr.ok() && pr.cause() == WslBwrapSandbox.Cause.DISTRO_NOT_FOUND) {
            // L2 自举:发行版缺失且托管镜像在位 → 自动 wsl --import 后按 bwrap 后端重探;否则原样返回
            pr = WslBwrapSandbox.autoImport(props, pr, () -> WslBwrapSandbox.probe(props));
        }
        if (pr.ok()) {
            return Backend.WSL_BWRAP;
        }
        log.error("[sandbox] wsl-bwrap 探测失败(distro={},{});修正:{};回退 windows-mic(可用性优先)",
                distro, pr.brief(), pr.fix());
        return Backend.WINDOWS_MIC;
    }

    /** 全局网络策略是否默认放行(worker.sandbox.allow-network=true 或 network-policy != deny-all)。 */
    public boolean networkAllowedByDefault() {
        return !cfg.networkDenied();
    }

    /** 全局提权是否默认放行(worker.sandbox.allow-privilege-escalation=true;否则按命令弹窗授权)。 */
    public boolean privilegeAllowedByDefault() {
        return cfg.isAllowPrivilegeEscalation();
    }

    /** 是否走 seccomp 内核级提权拦截(仅 wsl-bwrap 后端 + 未全局放行 + 开关开启)。 */
    public boolean useSeccompInterception() {
        return backend() == Backend.WSL_BWRAP
                && cfg.isInterceptPrivilege()
                && !cfg.isAllowPrivilegeEscalation();
    }

    /**
     * seccomp 内核级提权拦截执行(见 docs/ARCHITECTURE.md §7.11):
     * 沙箱内 exec setuid 二进制(sudo/su 等)时由发行版侧监听器上报,经 authorizer 授权。
     * 非 wsl-bwrap 后端或后端不可用时退化为普通 spawn。
     *
     * @param authorizer 授权回调:返回 true=放行该次 setuid exec;false=EPERM 拒绝
     */
    public ExecResult spawnSandboxedSeccomp(String command, Path cwd, Map<String, String> extraEnv,
            String shell, List<Path> extraRoots, boolean allowNetwork,
            WslBwrapSandbox.PrivilegeAuthorizer authorizer) {
        if (backend() != Backend.WSL_BWRAP) {
            // seccomp 仅 wsl-bwrap 后端可用;其余退化为普通执行(allowPrivilege 按全局)
            return spawnSandboxed(command, cwd, extraEnv, shell, extraRoots, allowNetwork,
                    cfg.isAllowPrivilegeEscalation());
        }
        WslBwrapSandbox.OsResult r = WslBwrapSandbox.runSeccomp(command, cwd, extraEnv, props, exec,
                MAX_OUTPUT_CHARS, shell, extraRoots == null ? List.of() : extraRoots, allowNetwork,
                authorizer);
        return new ExecResult(r.stdout(), r.stderr(), r.exitCode(), r.aborted());
    }

    /**
     * 在沙箱中执行命令(自动选择 shell)。
     *
     * @param command 待执行命令(交由 OS shell 解析)
     * @param cwd     工作目录(绝对路径,应等于 task.workspaceRoot)
     * @param extraEnv 额外注入的环境变量(会被 networkPolicy 处理)
     * @return 执行结果(stdout/stderr/exitCode/aborted)
     */
    public ExecResult spawnSandboxed(String command, Path cwd, Map<String, String> extraEnv) {
        return spawnSandboxed(command, cwd, extraEnv, "auto", List.of());
    }

    /**
     * 在沙箱中执行命令(指定 shell)。
     *
     * @param command 待执行命令(交由 OS shell 解析)
     * @param cwd     工作目录(绝对路径,应等于 task.workspaceRoot)
     * @param extraEnv 额外注入的环境变量(会被 networkPolicy 处理)
     * @param shell   auto(按 OS 选)/ cmd / bash / powershell;非法值回退 auto。
     *                wsl-bwrap 后端归一到 bash(或启用的 pwsh);Windows mic 后端上运行
     *                PowerShell cmdlet(如 Select-String)必须传 powershell。
     * @return 执行结果(stdout/stderr/exitCode/aborted)
     */
    public ExecResult spawnSandboxed(String command, Path cwd, Map<String, String> extraEnv, String shell) {
        return spawnSandboxed(command, cwd, extraEnv, shell, List.of());
    }

    /**
     * 在沙箱中执行命令(指定 shell + 附加授权根)。
     *
     * @param extraRoots PermissionGate 已授权的命令 EXEC 根(wsl-bwrap 后端经 --bind
     *                   白名单挂入沙箱;windows-mic 后端走既有 Low 标注路径,忽略此参)
     */
    public ExecResult spawnSandboxed(String command, Path cwd, Map<String, String> extraEnv,
            String shell, List<Path> extraRoots) {
        return spawnSandboxed(command, cwd, extraEnv, shell, extraRoots, !cfg.networkDenied(),
                cfg.isAllowPrivilegeEscalation());
    }

    public ExecResult spawnSandboxed(String command, Path cwd, Map<String, String> extraEnv,
            String shell, List<Path> extraRoots, boolean allowNetwork) {
        return spawnSandboxed(command, cwd, extraEnv, shell, extraRoots, allowNetwork,
                cfg.isAllowPrivilegeEscalation());
    }

    /**
     * 在沙箱中执行命令(指定 shell + 附加授权根 + 网络/提权许可)。
     *
     * @param allowNetwork   是否放行网络(任务级 /禁用网络 未开启 且 worker 默认放行)
     * @param allowPrivilege 是否允许以提权方式运行(命令含 sudo 等且用户已授权,或 worker 默认放行)
     */
    public ExecResult spawnSandboxed(String command, Path cwd, Map<String, String> extraEnv,
            String shell, List<Path> extraRoots, boolean allowNetwork, boolean allowPrivilege) {
        String s = normalizeShell(shell);
        if (!cfg.isEnabled() || backend() == Backend.DIRECT) {
            log.warn("[sandbox] 沙箱已禁用,exec 直接 spawn(仅超时/输出护栏): {}", truncate(command, 120));
            return runDirect(command, cwd, extraEnv, s, allowNetwork);
        }
        if (backend() == Backend.WSL_BWRAP) {
            WslBwrapSandbox.OsResult r = WslBwrapSandbox.run(command, cwd, extraEnv, props, exec,
                    MAX_OUTPUT_CHARS, s, extraRoots == null ? List.of() : extraRoots, allowNetwork,
                    allowPrivilege);
            return new ExecResult(r.stdout(), r.stderr(), r.exitCode(), r.aborted());
        }
        if (backend() == Backend.WSL_DIRECT) {
            WslBwrapSandbox.OsResult r = WslDirectSandbox.run(command, cwd, props, exec,
                    MAX_OUTPUT_CHARS, wslDirectMountRoots(), allowNetwork);
            return new ExecResult(r.stdout(), r.stderr(), r.exitCode(), r.aborted());
        }
        return WindowsSandbox.run(command, cwd, extraEnv, cfg, exec, MAX_OUTPUT_CHARS, s,
                allowNetwork, allowPrivilege);
    }

    /**
     * 强制以 Windows 原生沙箱执行(不按解析后端分发):供 WSL 后端下动态启用的 powershell
     * 工具使用——wsl 发行版内不保证安装 pwsh,PowerShell 命令回宿主 Windows 原生沙箱
     * (windows-mic 语义:Restricted Token + Low IL + Job Object + 目录标注/ACL)运行,
     * 与 WSL 后端的 bash 方言并存。沙箱禁用/后端不可用时退化为直接 spawn(与其它后端一致)。
     */
    public ExecResult spawnSandboxedWindows(String command, Path cwd, Map<String, String> extraEnv,
            String shell, List<Path> extraRoots, boolean allowNetwork, boolean allowPrivilege) {
        String s = normalizeShell(shell);
        if (!cfg.isEnabled() || backend() == Backend.DIRECT) {
            log.warn("[sandbox] 沙箱已禁用,powershell 直接 spawn(仅超时/输出护栏): {}",
                    truncate(command, 120));
            return runDirect(command, cwd, extraEnv, s, allowNetwork);
        }
        return WindowsSandbox.run(command, cwd, extraEnv, cfg, exec, MAX_OUTPUT_CHARS, s,
                allowNetwork, allowPrivilege);
    }

    /** 全部已注册工作区宿主路径(wsl-direct 动态挂载用);未初始化时回退空表。 */
    private List<Path> allWorkspaceRoots() {
        if (workspaces == null) {
            return List.of();
        }
        try {
            return workspaces.list().stream().map(r -> Path.of(r.root())).toList();
        } catch (RuntimeException e) {
            log.warn("[sandbox] 读取工作区注册表失败,忽略动态挂载: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * wsl-direct 挂载列表:全部已注册工作区根 <b>+</b> 全部工作区的外部授权根(§7.17,
     * 与工作区根同语义:跨任务共享、runner trusted 阶段幂等 _ensure_mount;WslDirectSandbox
     * 的 mountPairs 对非工作区路径按同一 WslPathMapper.toDirectMount 生成 {src,dest} 对)。
     * Path 去重(externalRoot 与工作区根或彼此重叠时);任一读取失败按可取到的子集降级。
     * 包私有供单测钉住载荷拼装契约。
     */
    List<Path> wslDirectMountRoots() {
        List<Path> roots = new java.util.ArrayList<>(allWorkspaceRoots());
        if (workspaces != null) {
            try {
                for (Path ext : workspaces.allExternalRoots()) {
                    if (!roots.contains(ext)) {
                        roots.add(ext);
                    }
                }
            } catch (RuntimeException e) {
                log.warn("[sandbox] 读取外部授权根失败,忽略该部分挂载: {}", e.getMessage());
            }
        }
        return roots;
    }

    private static String normalizeShell(String shell) {
        if (shell == null || shell.isBlank()) {
            return "auto";
        }
        String s = shell.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (s) {
            case "auto", "cmd", "bash", "powershell" -> s;
            default -> "auto";
        };
    }

    /** 非 Windows / 禁用沙箱时的直接执行(带超时 + 每流输出上限 + 网络 env 处理)。 */
    private ExecResult runDirect(String command, Path cwd, Map<String, String> extraEnv, String shell,
            boolean allowNetwork) {
        java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(shellInvocation(shell)));
        cmd.add(command);
        return runDirectCommand(cmd, cwd, extraEnv, allowNetwork, cfg.getTimeoutMs());
    }

    /**
     * 宿主原生进程 argv 直传(不做 wsl/mic 降权;供 NativeGit 等平台受控操作使用,
     * 见 docs/GIT_NATIVE_MIGRATION.md §4)。网络放行(git pull/push/clone 是用户显式
     * 发起的远端操作),超时沿用统一沙箱超时。
     */
    public ExecResult spawnNative(String[] argv, Path cwd, Map<String, String> env) {
        return spawnNative(argv, cwd, env, cfg.getTimeoutMs());
    }

    /**
     * 同上,可自定义超时(clone/pull/push 大仓库可能较慢,走 worker.git.timeout-ms)。
     */
    public ExecResult spawnNative(String[] argv, Path cwd, Map<String, String> env, long timeoutMs) {
        return runDirectCommand(java.util.List.of(argv), cwd, env, true, timeoutMs);
    }

    /** 直接执行(ProcessBuilder 以 argv 直传,无 shell 解析;带超时 + 每流输出上限 + 网络 env 处理)。 */
    private ExecResult runDirectCommand(java.util.List<String> cmd, Path cwd, Map<String, String> extraEnv,
            boolean allowNetwork, long timeoutMs) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        // stdin 接 null 设备(命令 stdin 契约,§7.10):本方法从不向 stdin 写入,保持默认
        // 管道只会给子进程留下一个「打开的空管道」——rg/grep 无路径参数时据 stdin 可读
        // 判定改读 stdin(静默空结果,与「无匹配」不可区分),cat 等阻塞读则挂到超时;
        // null 设备使读 stdin 的命令立即 EOF。Windows 用 NUL 设备(设备名路径解析)。
        pb.redirectInput(ProcessBuilder.Redirect.from(NULL_INPUT));
        pb.environment().putAll(sanitizedEnv(extraEnv, allowNetwork));
        try {
            Process p = pb.start();
            // stdout/stderr 分流捕获,且必须并发读:任一条只写不读都会写满管道缓冲卡死子进程
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
                // 进程已杀,句柄很快关闭 → 读任务 EOF 收尾,稍候即得已产出部分
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

    /** 等待读取任务收尾,超时/异常回退空串(进程已死后管道很快 EOF)。 */
    private static String awaitQuiet(Future<String> task) {
        try {
            return task.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    /** 单流输出截断(stdout / stderr 各自适用)。 */
    private static String capOutput(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.length() > MAX_OUTPUT_CHARS
                ? s.substring(0, MAX_OUTPUT_CHARS) + "\n[输出已截断至 " + MAX_OUTPUT_CHARS + " 字符]"
                : s;
    }

    /** 按网络许可清理环境:allowNetwork=false 时移除代理相关变量,防子进程绕过。 */
    private Map<String, String> sanitizedEnv(Map<String, String> extra, boolean allowNetwork) {
        Map<String, String> env = new java.util.HashMap<>(extra == null ? Map.of() : extra);
        if (!allowNetwork) {
            List<String> proxyKeys = List.of("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy",
                    "ALL_PROXY", "all_proxy", "NO_PROXY", "no_proxy");
            proxyKeys.forEach(env::remove);
        }
        return env;
    }

    /** 解析 shell 调用前缀(auto 按当前 OS 选)。 */
    private static String[] shellInvocation(String shell) {
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        return switch (normalizeShell(shell)) {
            case "powershell" -> win
                    ? new String[] { "powershell.exe", "-NoProfile", "-Command" }
                    : new String[] { "pwsh", "-NoProfile", "-Command" };
            case "bash" -> new String[] { "bash", "-c" };
            case "cmd" -> new String[] { "cmd.exe", "/c" };
            default -> win ? new String[] { "cmd.exe", "/c" } : new String[] { "bash", "-c" };
        };
    }

    private static String drain(java.io.InputStream in) throws IOException {
        try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "...");
    }

    /** 执行结果。 */
    public record ExecResult(String stdout, String stderr, int exitCode, boolean aborted) {
    }
}
