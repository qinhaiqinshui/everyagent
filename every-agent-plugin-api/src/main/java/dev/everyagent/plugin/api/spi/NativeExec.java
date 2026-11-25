package dev.everyagent.plugin.api.spi;

import java.nio.file.Path;
import java.util.Map;

/**
 * 宿主原生进程执行器（plugin-api 契约）——供需要直接在宿主 OS 执行命令的插件使用。
 *
 * <p>与 {@link SandboxBackend} 的区别：{@code SandboxBackend} 只负责挂载与工作区生命周期，
 * <b>不执行命令</b>；本接口专注于宿主进程执行（argv 直传无 shell 解析、超时、每流输出上限）。
 *
 * <p>典型场景：git 插件的平台受控操作（前端按钮触发的 {@code git.*} RPC），不经 wsl/mic
 * 沙箱后端降权，复用本接口的超时 / 输出上限 / env 清理能力。
 *
 * <p>worker 的 {@code dev.everyagent.worker.os.OsSandbox} 实现此接口；
 * 插件经 {@code ctx.services().nativeExec()} 获取实例。
 *
 * <p><b>cwd 契约</b>:cwd 不得位于外部插件目录(worker 的 {@code worker.plugins-dir})内 ——
 * 长驻子进程以插件目录为工作目录会在 Windows 上锁死目录、令插件卸载必败
 * (卸载墓碑问题的预防侧,ARCHITECTURE §8.5);worker 实现对此直接拒绝并返回失败
 * {@code ExecResult},插件应改用工作区或临时目录。
 */
public interface NativeExec {

    /**
     * 宿主原生进程 argv 直传执行（不做 wsl/mic 降权；网络放行；带超时 + 每流输出上限）。
     *
     * @param argv      命令参数（argv 直传，无 shell 解析）
     * @param cwd       工作目录（不得位于外部插件目录内，见类级「cwd 契约」；违规调用直接返回失败结果）
     * @param env        额外环境变量（与进程默认 env 合并）
     * @param timeoutMs 超时毫秒数（超时强制销毁进程，aborted=true）
     * @return 执行结果（stdout / stderr / exitCode / aborted）
     */
    ExecResult spawnNative(String[] argv, Path cwd, Map<String, String> env, long timeoutMs);

    /**
     * 宿主 PowerShell 方言脚本执行（复用 worker 核心管线，非调用方手搓）。
     *
     * <p>{@code command} 是 PowerShell 方言脚本体，原样嵌入完整脚本交宿主 PowerShell 进程
     * 执行。worker 实现（{@code OsSandbox}）走核心共享管线：<b>临时 {@code .ps1}
     * （UTF-8 BOM）+ PATH 探测 {@code pwsh.exe}（PowerShell 7+）优先/回退出厂
     * {@code powershell.exe}（5.1）+ stdout/stderr 文件承载（防 PS 5.1 管道下按 OEM 码页
     * 转码毁 UTF-8）+ CLIXML/ANSI 还原 + 退出码传导</b>（脚本组装与退出码尾部见
     * {@code ExecResults#buildPowerShellScript / POWERSHELL_EXIT_TAIL}）。会话 powershell
     * 工具与工作流命令节点共用同一实现，行为完全一致。
     *
     * <p><b>宿主无法解析 PowerShell 可执行文件时抛 {@link IllegalStateException}</b>，
     * 调用方可捕获后回退其它 shell（如 Windows 工作流命令节点回退 {@code cmd.exe /c}）。
     * 命令执行本身的失败（非零退出码/超时/中断）仍以失败 {@code ExecResult} 返回，不抛异常。
     *
     * <p>cwd 禁入外部插件目录契约与 {@link #spawnNative} 相同（违规直接返回失败结果）。
     *
     * @param command   PowerShell 方言脚本体（不转写、不拆分，整段嵌入）
     * @param cwd       工作目录（不得位于外部插件目录内，见类级「cwd 契约」）
     * @param env        额外环境变量（与进程默认 env 合并）
     * @param timeoutMs 超时毫秒数（超时强制销毁进程树，aborted=true）
     * @return 执行结果（stdout / stderr 已解码与 CLIXML/ANSI 还原 / exitCode / aborted）
     */
    ExecResult spawnPowerShell(String command, Path cwd, Map<String, String> env, long timeoutMs);
}
