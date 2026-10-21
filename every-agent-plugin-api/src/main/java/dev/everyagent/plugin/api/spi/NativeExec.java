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
}
