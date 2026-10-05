package dev.everyagent.worker.tools;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.shell.ExecResults;
import dev.everyagent.plugin.api.spi.ExecResult;
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
 * <p>bash/powershell 子进程会注入打包 rg 二进制所在目录到 PATH(见 rgBinDir),方便直接调用 rg。
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
    /** 打包 rg 二进制所在目录(可空);非空时 bash/powershell 子进程把它注入 PATH。 */
    private final Path rgBinDir;
    public CommandExecutor(OsSandbox sandbox, ExecContext task, PermissionGate gate, String agentId) {
        this(sandbox, task, gate, agentId, null);
    }

    public CommandExecutor(OsSandbox sandbox, ExecContext task, PermissionGate gate, String agentId,
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
        Path cwd = Path.of(task.workspaceRoot());
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
        // powershell 专属(Windows 原生域):授权检查之后给命令串预置 UTF-8 偏好 +
        // 非成功流抑制(权限检查与审计日志始终是用户原始命令)。前缀先于用户命令执行,
        // 用户若显式设置该偏好,后写覆盖本前缀。真正的非 ASCII 正确性由「输出文件承载」
        // 保证(见 OsSandbox#spawnToFileRedirect),前缀只负责编码偏好与噪声抑制。
        String spawnCmd = powershell ? ExecResults.POWERSHELL_PREFIX + command : command;
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
            // powershell 唯一执行形态:临时 .ps1 + -File + stdout/stderr 文件承载 + 退出码传导。
            // 不再保留 -Command 分支——分支差异正是引号被 MSVCRT 转义吞掉(PS-002)的温床。
            r = executePowerShell(spawnCmd + ExecResults.POWERSHELL_EXIT_TAIL, win, cwd, env);
        } else {
            String[] shellPrefix = win ? new String[]{"cmd.exe", "/c"} : new String[]{"bash", "-c"};
            java.util.List<String> fullCmd = new java.util.ArrayList<>(java.util.List.of(shellPrefix));
            fullCmd.add(spawnCmd);
            r = sandbox.spawnNative(fullCmd.toArray(new String[0]), cwd, env);
        }
        // powershell 专属:stderr 里若仍有 CLIXML 流记录,还原成真实错误文本(绝不静默丢弃)
        if (powershell) {
            r = restoreClixml(r);
        }
        log.info("[exec] task={} backend={} rc={} aborted={} cmd={}", task.subjectId(),
                sandbox.id(),
                r.exitCode(), r.aborted(), ExecResults.truncate(command, 200));
        return ExecResults.format(r);
    }

    /**
     * PowerShell 脚本执行:临时 {@code .ps1}(UTF-8 BOM) + {@code -File} +
     * <b>stdout/stderr 文件承载</b>（BUG-1 根治 + PS-002 修复）。
     *
     * <p><b>为什么文件承载而不是管道</b>:PS 5.1 的 stdout 指向管道时,
     * {@code [Console]::OutputEncoding} 取系统 OEM 码页(中文 Windows=936),原生子进程
     *（rg/git 等）的 UTF-8 字节会被 PS 先按 GBK 解码再回编码,非法序列当场变 U+FFFD——
     * 信息在子进程出口就被毁掉,读取端无从还原(中文仓库里 rg 的文件名/内容全乱码,
     * 且乱码文件名回灌 rg 直接 os error 2,任务卡死)。改为文件承载后,子进程直接继承
     * 文件句柄写原始字节,PS 完全不参与转码;实测同一路径下 cmdlet 中文与原生 UTF-8
     * 输出在文件里<b>同为合法 UTF-8</b>,stderr 也不再被 CLIXML 包装。
     * 详见 {@link OsSandbox#spawnToFileRedirected}。
     *
     * <p><b>为什么 -File 而不是 -Command</b>:ProcessBuilder 按 MSVCRT 规则转义参数里的
     * 双引号({@code "} → {@code \"}),而 PowerShell {@code -Command} 的引号解析与 MSVCRT
     * 不一致,嵌套引号会被截断或吞掉(PS-002)。脚本经 Java 文件 IO 写入,不过命令行,
     * 引号与控制字符在进程边界被改写这一整类问题随之消失;顺带免去「有无引号走两条路」
     * 造成的行为分叉。
     *
     * <p><b>脚本文件编码</b>:UTF-8 <b>BOM</b>。PowerShell 5.1 读无 BOM 文件时按系统 ACP
     *（如 GBK）解码,命令串里的中文会被错解成另一个字。
     *
     * <p><b>退出码</b>:{@link ExecResults#POWERSHELL_EXIT_TAIL} 已在尾部把
     * {@code $LASTEXITCODE}(最后一个原生子进程)转成 powershell.exe 的进程退出码,
     * 否则 {@code [exit code: N]} 尾注恒为 0,模型无法区分 rg「无匹配(1)」与「用错(2)」。
     */
    private ExecResult executePowerShell(String script, boolean win,
            Path cwd, Map<String, String> env) {
        java.nio.file.Path tempScript = null;
        try {
            tempScript = OsSandbox.createScratchFile("ea-ps-", ".ps1", cwd).toPath();
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
            return sandbox.spawnToFileRedirected(fullCmd, cwd, env, sandbox.execTimeoutMs());
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
     * 还原 stderr 里的 PowerShell CLIXML 流记录为真实错误文本（兜底路径）。
     *
     * <p>PowerShell 5.1 在 stderr 被<b>重定向</b>(管道或文件——它只看是否控制台)时,把
     * error/warning/verbose 等流序列化成
     * CLIXML({@code #< CLIXML} + {@code <Objs>…</Objs>})。旧实现是<b>整段删除</b>,于是
     * {@code rg '(' file} 的「regex parse error」、命令不存在、路径不可读全成静默——只剩
     * {@code [exit code: 2]} 甚至什么都没有,AI 把「用错了」误读成「没匹配」(BUG-2)。
     * 现委托 {@link ExecResults#decodeClixml}:段内 {@code <S S="Error">} 文本抽出来留在
     * stderr,段外原生命令纯文本原样保留。CLIXML 与承载形态无关(2026-10 codex 后端文件
     * 承载下实测同样产生,codex 执行器聚合处已同样接入),此处覆盖本执行路径的残余。
     */
    private static ExecResult restoreClixml(ExecResult r) {
        String err = r.stderr();
        if (err == null || err.isEmpty() || err.indexOf("CLIXML") < 0) {
            return r;
        }
        return new ExecResult(r.stdout(), ExecResults.decodeClixml(err), r.exitCode(),
                r.aborted());
    }

    /**
     * Medium IL 方案:沙箱进程运行在 Medium IL(Restricted Token 去特权但不降级),
     * 天然可写工作区与已授权目录,无需标注 Low 完整性或追加 DACL。
     * 零文件系统副作用,零残留。详见 docs/design-windows-mic-medium-il.md
     */
    private void prepareWritableRoots(Path cwd, boolean forceNative) {
        // Medium IL 天然可写,无需标注/ACL——空体
    }
}
