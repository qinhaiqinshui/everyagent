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
 * 判定）。createTools：经 {@link ToolContext#shellExecutor(Path)} 拿到带 rg 注入的
 * 执行器，创建 {@link ShellTool#powershell} 工具。
 *
 * <p>rg 归属下放：rg 由插件自带（{@code <pluginDir>/bin/rg.exe}），activate 时经
 * {@link MicRg#resolve} 解析后传入（已转为其所在目录路径），不再依赖 worker 核心的 rg。
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
        ShellExecutor base = rgDir != null ? ctx.shellExecutor(rgDir) : ctx.shellExecutor();
        if (base == null) {
            return List.of();
        }
        return List.of(ShellTool.powershell(base)
                .appendDescription("rg 已加入 PATH,可直接执行 rg 命令，内容搜索尽量使用rg命令，性能更好;"
                        + "输出编码已自动设为 UTF-8,无需手动切换。")
                .callback());
    }
}
