package dev.everyagent.worker.tools;

import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.os.OsSandbox.ExecResult;
import dev.everyagent.worker.os.windows.WindowsAcl;
import dev.everyagent.worker.os.windows.WindowsIntegrity;
import dev.everyagent.worker.os.wsl.WslPathMapper;
import dev.everyagent.worker.task.TaskEntry;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 真实 OS 进程命令执行器(非工具,供平台化命令工具复用)。取代旧 ExecuteCommandTool 的
 * execute_command 工具:统一承担「授权检查 + 沙箱隔离 + 结果格式化 + 审计日志」,
 * shell 由调用方指定(execute_command 的 shell 参数语义上移到各工具本身)。
 *
 * <p>被以下工具注入复用(不重复造轮子):
 * <ul>
 *   <li>{@link BashTool}(Linux/macOS 注册,shell=bash);</li>
 *   <li>{@link PowerShellTool}(Windows 注册,shell=powershell);</li>
 * </ul>
 *
 * <p>bash/powershell 子进程会注入打包 rg 二进制所在目录到 PATH(见 rgBinDir),方便直接调用 rg。
 *
 * <p>安全边界(与旧 execute_command 完全一致):
 * <ul>
 *   <li>cwd 锁 {@code task.workspaceRoot},子进程工作目录固定在工作区内;</li>
 *   <li>执行前经 {@link PermissionGate#requireCommand}:危险命令(删除类动词)与命令串中
 *       越界已存在路径须用户授权(拒绝/超时回灌错误文本,不执行);</li>
 *   <li>经 OsSandbox(后端见 worker.sandbox.type):
 *       <b>wsl-bwrap</b>(Windows 默认)命令进 WSL2 发行版经 bubblewrap 挂载命名空间运行,
 *       授权根 = --bind 白名单(零宿主状态),网络 --unshare-net 硬拒,方言 bash;
 *       <b>windows-mic</b>(回退)活动进程数上限(默认 32,防失控进程树)/ 内存上限 /
 *       降权(Low IL)/ KillOnJobClose,生效时先把工作区与 EXEC 授权根标注 Low 完整性
 *       (§13.6)——否则 Low IL 进程在工作区内也只能读不能写;</li>
 *   <li>超时 + 输出上限 + 审计日志(taskId / exitCode / cmd 截断)由 OsSandbox + 本类共同保证。</li>
 * </ul>
 */
public class CommandExecutor {

    private static final Logger log = LoggerFactory.getLogger(CommandExecutor.class);

    /**
     * PowerShell 非成功流静默化前缀。CLIXML 噪声的根治在 spawn 层:WindowsSandbox 用
     * {@code -Command} + MSVCRT quoteArg(非 {@code -EncodedCommand})执行,该模式下
     * Write-Host / Write-Output / 2>&1 / 原生 stderr 均以纯文本输出、不产生 CLIXML。
     * 此前缀仅作工具层额外防御:静默 progress/information/warning/verbose/debug 流,
     * 避免个别 cmdlet / 模块显式 Write-Progress 等刷屏(不影响真实 stdout 数据与真实 stderr 错误)。
     * 残余噪声由 {@link #stripClixml} 兜底剥除。不改变、也不要求改变 AI 的命令写法。
     */
    private static final String POWERSHELL_PREFIX =
            "$ProgressPreference='SilentlyContinue'; $InformationPreference='SilentlyContinue'; "
            + "$WarningPreference='SilentlyContinue'; $VerbosePreference='SilentlyContinue'; "
            + "$DebugPreference='SilentlyContinue'; ";

    private final OsSandbox sandbox;
    private final TaskEntry task;
    private final PermissionGate gate;
    private final String agentId;
    /** 打包 rg 二进制所在目录(可空);非空时 bash/powershell 子进程把它注入 PATH。 */
    private final Path rgBinDir;
    /** 是否 Windows 宿主(powershell 工具仅 Windows 注册;Low 标注/ACL 亦仅 Windows 有意义)。 */
    private final boolean windowsHost = System.getProperty("os.name")
            .toLowerCase(Locale.ROOT).contains("win");
    /** Low 完整性标注失败的一次性告警标记(仅首次失败时记日志,防刷屏)。 */
    private volatile boolean writableRootWarned;

    public CommandExecutor(OsSandbox sandbox, TaskEntry task, PermissionGate gate, String agentId) {
        this(sandbox, task, gate, agentId, null);
    }

    public CommandExecutor(OsSandbox sandbox, TaskEntry task, PermissionGate gate, String agentId,
                           Path rgBinDir) {
        this.sandbox = sandbox;
        this.task = task;
        this.gate = gate;
        this.agentId = agentId;
        this.rgBinDir = rgBinDir;
    }

    /**
     * 执行命令:授权检查 → OsSandbox 沙箱隔离执行 → stdout/stderr 分流格式化
     * (stdout 在前;stderr 非空时以 [stderr] 标记独立成段附后)。
     *
     * @param command 要执行的命令(交由指定 shell 解析)
     * @param shell   auto(按 OS 选)/ cmd / bash / powershell
     * @return 结果文本(stdout 数据段 + 可选 [stderr] 段 + exit code 尾注)
     */
    public String execute(String command, String shell) {
        if (command == null || command.trim().isEmpty()) {
            return "execute: command 不能为空";
        }
        boolean powershell = "powershell".equalsIgnoreCase(shell);
        // powershell 工具始终走宿主 Windows 原生沙箱:WSL 后端任务级 /启用powershell 动态注册
        // 时也强制回 Windows 原生(wsl 发行版内不保证安装 pwsh),命令语义与 windows-mic 一致
        // (Restricted Token + Low IL + Job Object + 目录标注/ACL)。bash 等保持后端方言。
        boolean wsl = sandbox.isWslBackend() && !powershell;
        // wsl-bwrap 后端:模型命令是 bash/POSIX 方言(/workspace、/mnt/<盘>),授权判定仍在
        // Windows 路径域进行——喂给门禁的是翻译副本(真实执行的命令保持原文)。
        // wsl-direct 后端:发行版整体为可丢弃隔离单元(宿主 automount 关闭 + 只手动挂载
        // 工作区),命令危险动词/越界路径授权无需 worker 层门禁,gating 交由发行版隔离承担。
        boolean wslDirect = wsl && sandbox.isWslDirect();
        String gateCmd = null;
        if (!wslDirect) {
            gateCmd = wsl ? WslPathMapper.translateCommand(command, Path.of(task.workspaceRoot))
                    : command;
            try {
                gate.requireCommand(task, agentId, gateCmd);
            } catch (java.io.IOException e) {
                return "execute: 授权检查失败 " + e.getMessage();
            }
        }
        Path cwd = Path.of(task.workspaceRoot);
        if (!wsl) {
            // 需要宿主侧 Low 标注/ACL 预处理:windows-mic 后端(powershell/bash 均走)与
            // WSL 后端下的 powershell 强制 native(powershell=true 强制,即使全局非 mic)
            prepareWritableRoots(cwd, powershell);
        }
        Map<String, String> env = new HashMap<>();
        // bash/powershell 且配置了 rgBinDir 时,把打包 rg 二进制所在目录注入子进程 PATH,
        // 使模型可直接敲 rg(其余 shell 如 cmd/auto/raw 不注入,保持原行为)。
        // wsl 系列后端不注入:Windows 侧 rg.exe 进不了发行版,rg 由发行版自带(安装脚本/apt)
        if (!wsl && rgBinDir != null
                && (powershell || "bash".equalsIgnoreCase(shell))) {
            String sysPath = System.getenv("PATH");
            env.put("PATH", rgBinDir + java.io.File.pathSeparator + (sysPath == null ? "" : sysPath));
        }
        // powershell 专属(Windows 原生域):授权检查之后给命令串预置非成功流抑制(progress +
        // information,权限检查与审计日志始终是用户原始命令)。WindowsSandbox 两条 spawn 路径均把
        // 该串作为整段脚本执行,前缀同时生效;用户命令自身若显式设置该偏好,后写覆盖本前缀。
        String spawnCmd = powershell ? POWERSHELL_PREFIX + command : command;
        // wsl-bwrap 后端:已授权 EXEC 根随调用挂载进沙箱(授权=绑定,撤销=下次不绑,零宿主状态);
        // 走 execRootsSandboxed(§13.3 L2 过滤)——过度宽泛根(如历史 C:\\)不得进 --bind 白名单,
        // 否则整个 /mnt/c 会被读写挂进沙箱,读隔离被击穿。wsl-direct 不建 bwrap 命名空间,
        // 授权根由动态 ensureMount 承担,此参数为空。powershell 走 Windows 原生,
        // 附加根由 prepareWritableRoots 的 Low 标注/ACL 消费,不参与 bwrap 挂载。
        java.util.List<Path> extraRoots = !wsl && sandbox.isWslBwrap() ? gate.execRootsSandboxed(task)
                : java.util.List.of();
        // 网络许可:任务级 /禁用网络 开关未开 且 worker 全局默认放行 → 本次命令放行网络;
        // 否则按 deny 断网(三个后端各自落地:wsl --unshare-net / unshare -n / 剥代理 env)
        boolean allowNetwork = !task.networkBlocked && sandbox.networkAllowedByDefault();
        // 提权授权:优先走 seccomp 内核级拦截(仅 wsl-bwrap + 未全局放行 + 开关开启),
        // 它能覆盖文本扫描漏掉的别名/脚本内 setuid 提权;否则退回文本扫描启发式。
        // wsl-direct 恒 root,无提权授权概念。powershell 走 Windows 原生沙箱,
        // 提权由 Restricted Token / 是否保留当前 token 决定,同样先经门禁。
        boolean allowPrivilege = sandbox.privilegeAllowedByDefault();
        if (!allowPrivilege && gateCmd != null && PermissionGate.usesPrivilege(gateCmd)) {
            gate.requirePrivilege(task, agentId, gateCmd); // 拒绝/超时抛 PermissionDeniedException → 命令不执行
            allowPrivilege = true;
        }
        ExecResult r;
        if (powershell) {
            // WSL 后端下 powershell 工具也强制回宿主 Windows 原生沙箱(windows-mic 语义),
            // 不走 wsl 发行版 pwsh(bash 方言才进 WSL)。
            r = sandbox.spawnSandboxedWindows(spawnCmd, cwd, env, shell, extraRoots, allowNetwork,
                    allowPrivilege);
        } else if (sandbox.useSeccompInterception()) {
            r = sandbox.spawnSandboxedSeccomp(spawnCmd, cwd, env, shell, extraRoots, allowNetwork,
                    (execPath, pid, syscall) -> {
                        try {
                            // 复用 PermissionGate 授权链:AI 审议优先 → 无人值守拒绝 → 人工弹窗
                            // (授权 = worker 以 WSL root 重跑原始命令,沙箱内不提权 §6A.2)
                            log.info("[seccomp] 提权请求 task={} path={} pid={} syscall={}",
                                    task.taskId, execPath, pid, syscall);
                            gate.requirePrivilegeExec(task, agentId, execPath);
                            return true;
                        } catch (RuntimeException e) {
                            log.warn("[seccomp] 提权被拒 task={} path={} 原因: {}",
                                    task.taskId, execPath, e.getMessage());
                            return false; // 拒绝/超时 → seccomp 桥接转 EPERM
                        }
                    });
        } else {
            r = sandbox.spawnSandboxed(spawnCmd, cwd, env, shell, extraRoots, allowNetwork,
                    allowPrivilege);
        }
        // powershell 专属:输出层剥除 CLIXML 流记录噪声(兜底,覆盖 Preference 未能抑制的残余)
        if (powershell) {
            r = stripClixml(r);
        }
        log.info("[exec] task={} backend={} rc={} aborted={} cmd={}", task.taskId,
                wslDirect ? "wsl-direct" : (wsl ? "wsl-bwrap" : "windows"),
                r.exitCode(), r.aborted(), truncate(command, 200));
        return format(r);
    }

    /**
     * PowerShell 5.1 在 stdout/stderr 被管道重定向(非交互)时,把非成功流(progress/information/
     * verbose/warning/debug/error)序列化为 CLIXML 写进 stderr,格式为 {@code #< CLIXML} 头 +
     * {@code <Objs ...>...</Objs>} XML 块(「正在准备首次使用模块」、Write-Progress、Write-Host 等)。
     * 这些是流记录噪声,不是命令真实错误文本;{@link #POWERSHELL_PREFIX} 已从源头抑制 progress 与
     * information 流,此处兜底剥除残余(第三方 cmdlet / 个别模块),真实 stderr 错误文本保留。
     */
    private static final Pattern CLIXML_BLOCK = Pattern.compile("(?s)#< CLIXML.*?</Objs>");

    /** 剥除 stderr 中的 PowerShell CLIXML 流记录噪声;无噪声则原样返回,不影响其余字段。 */
    private static ExecResult stripClixml(ExecResult r) {
        String err = r.stderr();
        if (err == null || err.isEmpty() || err.indexOf("#< CLIXML") < 0) {
            return r;
        }
        String cleaned = CLIXML_BLOCK.matcher(err).replaceAll("");
        // 剥除后可能残留纯空白行/首尾空白:清掉,避免 [stderr] 段只剩空行
        cleaned = cleaned.replaceAll("(?m)^[ \\t]+$", "").replaceAll("\\n{3,}", "\n\n").strip();
        return new ExecResult(r.stdout(), cleaned, r.exitCode(), r.aborted());
    }

    /** stdout / [stderr] / 超时 / exit code 尾注的共享格式化(尾注仅非零时附加)。 */
    private static String format(ExecResult r) {
        StringBuilder sb = new StringBuilder();
        if (r.stdout() != null && !r.stdout().isEmpty()) {
            sb.append(r.stdout());
        }
        if (r.stderr() != null && !r.stderr().isEmpty()) {
            // stderr 独立成段带标记:stdout 段永远是命令的干净数据,
            // PowerShell 的 CLIXML 流记录(#< CLIXML 进度 XML)等噪音只出现在 [stderr] 段
            sb.append(sb.isEmpty() ? "" : "\n").append("[stderr]\n").append(r.stderr());
        }
        if (r.aborted()) {
            sb.append(sb.isEmpty() ? "" : "\n").append("[命令被沙箱超时中止]");
        }
        // exit code 尾注仅在非零时附加:0(成功)是噪音;非零(尤其 rg 无匹配=exit 1)
        // 是 AI 判读语义的关键信号,必须保留
        if (r.exitCode() != 0) {
            sb.append(sb.isEmpty() ? "" : "\n").append("[exit code: ").append(r.exitCode()).append("]");
        }
        return sb.toString();
    }

    /**
     * Windows 沙箱生效时把工作区与 EXEC 授权根配置为沙箱可写(§13.6,幂等:
     * 每 worker 进程每根一次):① Low 完整性标注(解决 MIC NO_WRITE_UP,工作区内
     * 原本也只能读);② DACL 追加本地 Users 可写 ACE(增删改查,解决「授权了也
     * 写不进去」的 ACL 残缺场景)。标注失败仅记一次日志告警——不向命令结果注入
     * 提示,避免每条命令尾部常驻噪声(真实写入失败会在命令自身的 stdout/stderr
     * 显现,模型/用户可见性不受影响);后续命令不再重复告警(防刷屏)。
     */
    private void prepareWritableRoots(Path cwd, boolean forceNative) {
        // 仅在真正走 Windows 原生进程时标注:windows-mic 全局后端(isWindowsSandboxActive),
        // 或 WSL 后端下 powershell 强制 native(forceNative=true,发行版内无 pwsh,命令回宿主执行)。
        // 非 Windows 宿主恒跳过(powershell 工具仅 Windows 注册;jna 平台库只在 Windows 可用)。
        if (!windowsHost || (!forceNative && !sandbox.isWindowsSandboxActive())) {
            return;
        }
        boolean ok = WindowsIntegrity.ensureWritable(cwd);
        ok &= WindowsAcl.grantWriteAccess(cwd);
        // §13.3 L2:EXEC 根按 isOverBroadRoot 过滤(防御纵深:门禁即使再出解析 bug,
        // 盘根/工作区祖先也点不燃标注/ACL 机器);L3 在 WindowsIntegrity/WindowsAcl 入口再断言一次
        for (Path extra : gate.execRootsSandboxed(task)) {
            ok &= WindowsIntegrity.ensureWritable(extra);
            ok &= WindowsAcl.grantWriteAccess(extra);
        }
        if (!ok && !writableRootWarned) {
            writableRootWarned = true;
            log.warn("[sandbox] 工作区/授权目录 Low 完整性标注或可写 ACL 未完全成功,部分路径的文件写入可能被系统拒绝");
        }
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "...");
    }
}
