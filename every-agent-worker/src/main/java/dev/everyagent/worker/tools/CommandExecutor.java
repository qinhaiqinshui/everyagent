package dev.everyagent.worker.tools;

import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.plugin.api.spi.ExecResult;

import dev.everyagent.worker.task.TaskEntry;

import java.nio.file.Path;
import java.util.HashMap;
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
     * PowerShell 脚本预置前缀。两项职责:
     *
     * <p><b>1. UTF-8 编码(PS-001 修复)</b>:
     * {@code [Console]::OutputEncoding=UTF8} 使 PowerShell 向管道输出时按 UTF-8 编码
     * (简体中文系统默认 GBK/936,Java 端统一按 UTF-8 解码会导致乱码);
     * {@code $OutputEncoding=UTF8} 使 PowerShell 管道数据传给原生子进程时也用 UTF-8;
     * {@code $PSDefaultParameterValues} 让 Get-Content / Set-Content / Out-File
     * 不带 {@code -Encoding} 时默认用 UTF-8 读写文件(PS 5.1 默认按系统 ACP 如 GBK 读,
     * UTF-8 中文文件会乱码)。前缀先于用户命令执行,用户显式指定 {@code -Encoding} 则覆盖。
     *
     * <p><b>2. 非成功流静默化</b>:静默 progress/information/warning/verbose/debug 流,
     * 避免个别 cmdlet / 模块显式 Write-Progress 等刷屏(不影响真实 stdout 数据与真实 stderr 错误)。
     * 残余 CLIXML 噪声由 {@link #stripClixml} 兜底剥除。不改变、也不要求改变 AI 的命令写法。
     */
    private static final String POWERSHELL_PREFIX =
            "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; "
            + "$OutputEncoding=[System.Text.Encoding]::UTF8; "
            + "$PSDefaultParameterValues['Get-Content:Encoding']='UTF8'; "
            + "$PSDefaultParameterValues['Set-Content:Encoding']='UTF8'; "
            + "$PSDefaultParameterValues['Out-File:Encoding']='UTF8'; "
            + "$ProgressPreference='SilentlyContinue'; $InformationPreference='SilentlyContinue'; "
            + "$WarningPreference='SilentlyContinue'; $VerbosePreference='SilentlyContinue'; "
            + "$DebugPreference='SilentlyContinue'; ";

    private final OsSandbox sandbox;
    private final TaskEntry task;
    private final PermissionGate gate;
    private final String agentId;
    /** 打包 rg 二进制所在目录(可空);非空时 bash/powershell 子进程把它注入 PATH。 */
    private final Path rgBinDir;
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
        // powershell shell 走宿主 Windows 原生 ProcessBuilder 执行(wsl 发行版内不保证安装 pwsh);
        // bash 等保持后端方言。
        boolean wsl = false; // WSL branches removed — direct execution only
        // wsl-bwrap 后端:模型命令是 bash/POSIX 方言(/workspace、/mnt/<盘>),授权判定仍在
        // Windows 路径域进行——喂给门禁的是翻译副本(真实执行的命令保持原文)。
        // wsl-direct 后端:发行版整体为可丢弃隔离单元(宿主 automount 关闭 + 只手动挂载
        // 工作区),命令危险动词/越界路径授权无需 worker 层门禁,gating 交由发行版隔离承担。
        boolean wslDirect = false;
        String gateCmd = null;
        if (!wslDirect) {
            gateCmd = command;
            try {
                gate.requireCommand(task, agentId, gateCmd);
            } catch (java.io.IOException e) {
                return "execute: 授权检查失败 " + e.getMessage();
            }
        }
        Path cwd = Path.of(task.workspaceRoot);
        if (!wsl) {
            // Medium IL 方案:沙箱进程运行在 Medium IL,天然可写工作区,无需预处理
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
        // powershell 专属(Windows 原生域):授权检查之后给命令串预置 UTF-8 编码设置 +
        // 非成功流抑制(权限检查与审计日志始终是用户原始命令)。前缀先于用户命令执行,
        // 用户若显式设置该偏好,后写覆盖本前缀。
        String spawnCmd = powershell ? POWERSHELL_PREFIX + command : command;
        // wsl-bwrap 后端:已授权 EXEC 根随调用挂载进沙箱(授权=绑定,撤销=下次不绑,零宿主状态);
        // 走 execRootsSandboxed(§13.3 L2 过滤)——过度宽泛根(如历史 C:\\)不得进 --bind 白名单,
        // 否则整个 /mnt/c 会被读写挂进沙箱,读隔离被击穿。wsl-direct 不建 bwrap 命名空间,
        // 授权根由动态 ensureMount 承担,此参数为空。powershell 走 Windows 原生,
        // 附加根 prepareWritableRoots 已空体(Medium IL 无需标注);不参与 bwrap 挂载。
        java.util.List<Path> extraRoots = java.util.List.of(); // WSL branches removed
        // 网络许可不在核心层判定:真断网只有个别后端做得到(如 wsl-ubuntu 的 `unshare -n`),
        // 全局 allow-network 与各后端任务级开关一律由各沙箱插件自己的 CommandExecutor 落地。
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
        // 直接 ProcessBuilder 执行（核心宿主访问工具,DIRECT 模式）
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        if (powershell) {
            // PS-002 修复:含双引号的命令经临时 .ps1 文件 + -File 执行,
            // 绕开 ProcessBuilder 的 MSVCRT 引号转义对 PowerShell 引号的截断/吞掉。
            // 不含双引号的简单命令仍走 -Command,省去临时文件 IO。
            boolean hasDoubleQuote = spawnCmd.indexOf('"') >= 0;
            if (hasDoubleQuote) {
                r = executePowerShellViaTempScript(spawnCmd, win, cwd, env);
            } else {
                String psExe = win ? "powershell.exe" : "pwsh";
                String[] fullCmd = {psExe, "-NoProfile", "-Command", spawnCmd};
                r = sandbox.spawnNative(fullCmd, cwd, env);
            }
        } else {
            String[] shellPrefix = win ? new String[]{"cmd.exe", "/c"} : new String[]{"bash", "-c"};
            java.util.List<String> fullCmd = new java.util.ArrayList<>(java.util.List.of(shellPrefix));
            fullCmd.add(spawnCmd);
            r = sandbox.spawnNative(fullCmd.toArray(new String[0]), cwd, env);
        }
        // powershell 专属:输出层剥除 CLIXML 流记录噪声(兜底,覆盖 Preference 未能抑制的残余)
        if (powershell) {
            r = stripClixml(r);
        }
        log.info("[exec] task={} backend={} rc={} aborted={} cmd={}", task.taskId,
                sandbox.id(),
                r.exitCode(), r.aborted(), truncate(command, 200));
        return format(r);
    }

    /**
     * PowerShell 脚本经临时 .ps1 文件 + -File 执行（PS-002 修复）。
     *
     * <p><b>问题</b>:ProcessBuilder 在 Windows 上按 MSVCRT 规则转义参数中的双引号
     *（{@code "} → {@code \"}）。但 PowerShell 的 {@code -Command} 参数接收命令行文本时,
     * 其引号解析规则与 MSVCRT 不完全一致,导致 PowerShell 原生 {@code ""} 嵌套引号语法
     *（双引号字符串内 {@code ""} 表示一个字面双引号）在经 MSVCRT 转义后被截断或吞掉。
     *
     * <p><b>修复</b>:仅当命令串含双引号时,将脚本内容写入临时 {@code .ps1} 文件,
     * 以 {@code -File} 参数执行。脚本内容经 Java 文件 IO 写入,不经过命令行引号转义;
     * 文件路径不含 {@code "} 字符,ProcessBuilder 对路径的引号包裹不会引入歧义。
     * 不含双引号的简单命令仍走 {@code -Command},省去临时文件 IO。
     *
     * <p><b>编码</b>:临时文件以 UTF-8 BOM 写入。PowerShell 5.1 无 BOM 时按系统 ACP
     *（如 GBK）解码脚本文件,含中文的脚本会乱码;BOM 强制 UTF-8 解码。
     * UTF-8 输出编码已由 {@link #POWERSHELL_PREFIX} 设置,无需额外处理。
     *
     * <p><b>CLIXML</b>:{@code -File} 模式与 {@code -Command} 模式行为一致——
     * Write-Host / Write-Output / 2>&1 / 原生 stderr 均以纯文本输出,不产生 CLIXML。
     * {@link #stripClixml} 仍作兜底。
     */
    private ExecResult executePowerShellViaTempScript(String script, boolean win,
            Path cwd, Map<String, String> env) {
        java.nio.file.Path tempScript = null;
        try {
            tempScript = java.nio.file.Files.createTempFile("ea-ps-", ".ps1");
            // UTF-8 BOM: PowerShell 5.1 需要它来正确识别 UTF-8 编码的脚本文件
            byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
            byte[] content = script.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] full = new byte[bom.length + content.length];
            System.arraycopy(bom, 0, full, 0, bom.length);
            System.arraycopy(content, 0, full, bom.length, content.length);
            java.nio.file.Files.write(tempScript, full);

            String psExe = win ? "powershell.exe" : "pwsh";
            String[] fullCmd = {psExe, "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", tempScript.toString()};
            return sandbox.spawnNative(fullCmd, cwd, env);
        } catch (java.io.IOException e) {
            return new ExecResult("", "execute: 无法创建临时脚本文件 " + e.getMessage(), 1, false);
        } catch (Exception e) {
            return new ExecResult("", "execute: 临时脚本执行异常 " + e.getMessage(), 1, false);
        } finally {
            if (tempScript != null) {
                try {
                    java.nio.file.Files.deleteIfExists(tempScript);
                } catch (Exception ignored) {
                    // 临时文件清理失败不影响结果
                }
            }
        }
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
     * Medium IL 方案:沙箱进程运行在 Medium IL(Restricted Token 去特权但不降级),
     * 天然可写工作区与已授权目录,无需标注 Low 完整性或追加 DACL。
     * 零文件系统副作用,零残留。详见 docs/design-windows-mic-medium-il.md
     */
    private void prepareWritableRoots(Path cwd, boolean forceNative) {
        // Medium IL 天然可写,无需标注/ACL——空体
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "...");
    }
}
