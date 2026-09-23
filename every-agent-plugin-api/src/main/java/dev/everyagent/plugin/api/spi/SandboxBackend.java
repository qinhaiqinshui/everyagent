package dev.everyagent.plugin.api.spi;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 沙箱执行接口 —— {@link SandboxProvider#create} 的产物。
 *
 * <p>从 {@code OsSandbox} 的公共方法抽取。
 * OsSandbox 改造后变为薄选择器，实际执行委托给此接口的当前实现。
 */
public interface SandboxBackend {

    /**
     * 在沙箱中执行命令（指定 shell + 附加授权根 + 网络/提权许可）。
     *
     * @param command 待执行命令（交由 OS shell 解析）
     * @param cwd 工作目录（绝对路径，应等于 task.workspaceRoot）
     * @param extraEnv 额外注入的环境变量
     * @param shell auto/cmd/bash/powershell
     * @param extraRoots PermissionGate 已授权的命令 EXEC 根
     * @param allowNetwork 是否放行网络
     * @param allowPrivilege 是否允许提权运行
     * @return 执行结果
     */
    ExecResult spawnSandboxed(String command, Path cwd, Map<String, String> extraEnv,
            String shell, List<Path> extraRoots, boolean allowNetwork, boolean allowPrivilege);

    /**
     * 强制以 Windows 原生沙箱执行（不按解析后端分发）。
     * 供 WSL 后端下动态启用的 powershell 工具使用。
     */
    default ExecResult spawnSandboxedWindows(String command, Path cwd, Map<String, String> extraEnv,
            String shell, List<Path> extraRoots, boolean allowNetwork, boolean allowPrivilege) {
        // 非 Windows 后端默认退化为普通执行
        return spawnSandboxed(command, cwd, extraEnv, shell, extraRoots, allowNetwork, allowPrivilege);
    }

    /**
     * 宿主原生进程 argv 直传（不做 wsl/mic 降权;供 NativeGit 等平台受控操作使用）。
     */
    ExecResult spawnNative(String[] argv, Path cwd, Map<String, String> env, long timeoutMs);

    /** 当前后端是否为 wsl 系列（bwrap 或 direct）。 */
    boolean isWslBackend();

    /** 当前后端是否为 wsl-bwrap（隔离挂载命名空间）。 */
    default boolean isWslBwrap() {
        return false;
    }

    /** 当前后端是否为 wsl-direct（root 完整权限直连）。 */
    default boolean isWslDirect() {
        return false;
    }

    /**
     * 命令工具注册方言：true = 注册 BashTool（wsl 系列后端，或非 Windows）；
     * false = Windows mic 后端注册 PowerShellTool。
     */
    boolean registerBashTool();

    /** 后端 id（与 {@link SandboxProvider#id()} 一致）。 */
    String id();
}
