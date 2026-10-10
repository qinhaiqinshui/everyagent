package dev.everyagent.plugin.api.shell;

/**
 * 已组装好的 shell 命令执行器（授权 + 沙箱隔离已内建于实现），
 * 供插件注册 {@link ShellTool} 时使用。
 *
 * <p>三个现有执行器（worker {@code CommandExecutor} / codex {@code CodexCommandExecutor} /
 * wsl-ubuntu {@code WslUbuntuCommandExecutor}）的 {@code execute(String, String)} 签名
 * 与此一致，可直接方法引用适配（{@code executor::execute}）。
 */
@FunctionalInterface
public interface ShellExecutor {

    /**
     * 执行命令：授权检查 + 沙箱隔离执行，结果格式化后返回。
     *
     * @param command 要执行的命令（交由指定 shell 解析）
     * @param shell   shell 方言（如 powershell / bash）
     * @return 结果文本（stdout 数据段 + 可选 [stderr] 段 + exit code 尾注）
     */
    String execute(String command, String shell);
}
