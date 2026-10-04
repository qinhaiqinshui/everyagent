package dev.everyagent.plugin.sandbox.mic;

import dev.everyagent.plugin.api.shell.ShellExecutor;
import dev.everyagent.plugin.api.shell.ShellTool;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;

import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;

/**
 * windows-mic 沙箱的 shell 工具 ToolProvider（Shell 工具收敛 §3.3）。
 *
 * <p>appliesTo：只在当前沙箱后端 id=="windows-mic" 时生效（经 {@link ToolContext#sandbox}
 * 判定）。createTools：经 {@link ToolContext#shellExecutor()} 拿到基础执行器，
 * 若插件自带 rg 可用则包一层把 rg 目录注入子进程 PATH，再创建 {@link ShellTool#powershell}。
 *
 * <p>rg 归属下放：rg 由插件自带（{@code <pluginDir>/bin/rg.exe}），activate 时经
 * {@link MicRg#resolve} 解析后传入（已转为其所在目录路径），不再依赖 worker 核心的 rg。
 * PATH 注入由本插件自行包装 ShellExecutor 实现，核心不感知 extraBinDir。
 */
public class WindowsMicShellToolProvider implements ToolProvider {

    /** rg 二进制所在目录（可空；null 表示系统 PATH 已含 rg 或 rg 不可用）。 */
    private final Path rgDir;

    public WindowsMicShellToolProvider(Path rgDir) {
        this.rgDir = rgDir;
    }

    @Override
    public String pluginId() {
        return "sandbox-windows-mic";
    }

    @Override
    public boolean appliesTo(ToolContext ctx) {
        return ctx.sandbox() != null && "windows-mic".equals(ctx.sandbox().id());
    }

    @Override
    public List<ToolCallback> createTools(ToolContext ctx) {
        ShellExecutor base = ctx.shellExecutor();
        if (base == null) {
            return List.of();
        }
        ShellExecutor exec = rgDir != null ? withRgInPath(base, rgDir) : base;
        return List.of(ShellTool.powershell(exec)
                .appendDescription("rg 已加入 PATH,可直接执行 rg 命令，内容搜索尽量使用rg命令，性能更好;"
                        + "中文等非 ASCII 输出已自动正确解码，无需手动处理编码。")
                .callback());
    }

    /**
     * 包装执行器：命令前预置 PATH 注入（PowerShell 语法），使子进程能找到 rg。
     */
    private static ShellExecutor withRgInPath(ShellExecutor base, Path rgDir) {
        String dir = rgDir.toString().replace("'", "''");
        return (command, shell) -> {
            if ("powershell".equals(shell)) {
                return base.execute(
                        "$env:PATH = '" + dir + ";' + $env:PATH; " + command, shell);
            }
            return base.execute(command, shell);
        };
    }
}
