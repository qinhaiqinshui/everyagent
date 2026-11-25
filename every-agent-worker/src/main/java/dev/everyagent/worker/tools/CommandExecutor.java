package dev.everyagent.worker.tools;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.shell.ExecResults;
import dev.everyagent.plugin.api.spi.ExecResult;
import dev.everyagent.plugin.api.spi.ToolOutcomeLog;
import dev.everyagent.plugin.api.util.SecretPatterns;
import dev.everyagent.worker.os.OsSandbox;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 真实 OS 进程命令执行器(非工具,供平台化命令工具复用)。取代旧 ExecuteCommandTool 的
 * execute_command 工具:统一承担「授权检查 + 沙箱隔离 + 结果格式化 + 审计日志」,
 * shell 由调用方指定(execute_command 的 shell 参数语义上移到各工具本身)。
 *
 * <p>被以下工具注入复用(不重复造轮子):
 * <ul>
 *   <li>{@link dev.everyagent.worker.plugin.adapters.DirectShellToolProvider}
 *       (DIRECT 无沙箱后端,按 OS 注册 bash / powershell);</li>
 * </ul>
 *
 * <p>安全边界(与旧 execute_command 完全一致):
 * <ul>
 *   <li>cwd 锁 {@code exec.workspaceRoot()},子进程工作目录固定在工作区内;</li>
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

    private final OsSandbox sandbox;
    private final ExecContext task;
    private final PermissionGate gate;
    private final String agentId;
    /**
     * per-run 工具结果账本(可空):每次命令执行后按结构化退出码记账,供
     * {@code tool.result} 事件的 {@code data.status} 判定(非零退出码 / 超时 → 前端标红)。
     */
    private final ToolOutcomeLog toolOutcomes;

    public CommandExecutor(OsSandbox sandbox, ExecContext task, PermissionGate gate, String agentId) {
        this(sandbox, task, gate, agentId, null);
    }

    public CommandExecutor(OsSandbox sandbox, ExecContext task, PermissionGate gate, String agentId,
                           ToolOutcomeLog toolOutcomes) {
        this.sandbox = sandbox;
        this.task = task;
        this.gate = gate;
        this.agentId = agentId;
        this.toolOutcomes = toolOutcomes;
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
        Path cwd = Path.of(task.workspaceRoot());
        if (!wsl) {
            // Medium IL 方案:沙箱进程运行在 Medium IL,天然可写工作区,无需预处理
            prepareWritableRoots(cwd, powershell);
        }
        Map<String, String> env = new HashMap<>();
        // powershell 专属(Windows 原生域):执行体委托 OsSandbox#spawnPowerShell 共享管线
        // (临时 .ps1(UTF-8 BOM) + PATH 探测 pwsh.exe 优先/回退出厂 powershell.exe +
        // stdout/stderr 文件承载 + CLIXML/ANSI 还原 + 退出码传导;脚本组装
        // ExecResults.buildPowerShellScript——UTF-8 编码偏好 + 非成功流抑制前缀、PS-003/PS-004
        // 包裹、退出码尾部——亦收在共享实现内)。权限检查与审计日志始终是用户原始命令;
        // 前缀先于用户命令执行,用户若显式设置该偏好,后写覆盖本前缀;真正的非 ASCII 正确性
        // 由「输出文件承载」保证(见 OsSandbox#spawnToFileRedirected),前缀只负责编码偏好与
        // 噪声抑制。工作流命令节点(NodeRunners.CommandNodeRunner)走同一实现,行为完全一致。
        // wsl-bwrap 后端:已授权 EXEC 根随调用挂载进沙箱(授权=绑定,撤销=下次不绑,零宿主状态);
        // 走 execRootsSandboxed(§7.8 过宽根过滤)——过度宽泛根(如历史 C:\\)不得进 --bind 白名单,
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
            // powershell 唯一执行形态:委托 OsSandbox#spawnPowerShell 共享管线(临时 .ps1(UTF-8
            // BOM) + -File + stdout/stderr 文件承载 + 退出码传导)。不再保留 -Command 分支——
            // 分支差异正是引号被 MSVCRT 转义吞掉(PS-002)的温床。
            // 宿主无法解析 PowerShell 可执行文件时共享实现抛 IllegalStateException:powershell
            // 是用户显式选择的 shell,不静默回退其它 shell——转为失败文本回灌模型(与旧路径
            // spawn 失败回灌 ExecResult 的形态一致,不让异常穿出工具回调)。
            try {
                r = sandbox.spawnPowerShell(command, cwd, env, sandbox.execTimeoutMs());
            } catch (IllegalStateException e) {
                r = new ExecResult("", "execute: " + e.getMessage(), 1, false);
            }
        } else {
            String[] shellPrefix = win ? new String[]{"cmd.exe", "/c"} : new String[]{"bash", "-c"};
            java.util.List<String> fullCmd = new java.util.ArrayList<>(java.util.List.of(shellPrefix));
            fullCmd.add(command);
            r = sandbox.spawnNative(fullCmd.toArray(new String[0]), cwd, env);
        }
        // powershell 专属:stderr 里若仍有 CLIXML 流记录,还原成真实错误文本——已收进
        // OsSandbox#spawnPowerShell 共享实现(含 pwsh 7 ANSI 剥离),此处不再重复兜底。
        log.info("[exec] task={} backend={} rc={} aborted={} cmd={}", task.subjectId(),
                sandbox.id(),
                r.exitCode(), r.aborted(), ExecResults.truncate(SecretPatterns.maskSecrets(command), 200));
        // 结构化结果记账(判定不依赖结果文本):shell 参数即工具名(ShellTool 回调传入 name),
        // 与发射侧按工具名 + 命令原文查账同键。
        if (toolOutcomes != null) {
            toolOutcomes.recordShellExit(shell, command, r.exitCode(), r.aborted());
        }
        return ExecResults.format(r);
    }

    // PowerShell 执行管线(临时 .ps1 + UTF-8 BOM + 宿主解析 + 文件承载 + CLIXML 还原 +
    // 退出码传导)已整体收拢为 OsSandbox#spawnPowerShell 共享实现——本类 execute() 的
    // powershell 分支直接委托;会话 powershell 工具与工作流命令节点同一实现、行为一致。
    // 设计动机(为什么文件承载/-File/BOM/退出码尾部)详见
    // {@link OsSandbox#spawnPowerShell} 与 {@link ExecResults#buildPowerShellScript} 的
    // javadoc,此处不再复述。

    /**
     * Medium IL 方案:沙箱进程运行在 Medium IL(Restricted Token 去特权但不降级),
     * 天然可写工作区与已授权目录,无需标注 Low 完整性或追加 DACL。
     * 零文件系统副作用,零残留。详见 docs/design-windows-mic-medium-il.md
     */
    private void prepareWritableRoots(Path cwd, boolean forceNative) {
        // Medium IL 天然可写,无需标注/ACL——空体
    }
}
