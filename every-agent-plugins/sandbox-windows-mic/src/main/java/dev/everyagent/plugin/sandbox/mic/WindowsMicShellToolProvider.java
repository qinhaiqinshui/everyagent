package dev.everyagent.plugin.sandbox.mic;

import dev.everyagent.plugin.api.shell.RgShim;
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
 *
 * <p>rg 的 PowerShell 包装本插件不再自持一份,与 codex 后端共用 plugin-api 的 {@link RgShim}
 * ——要治的两件事(无控制台 + CLM 下原生输出被按 GBK 转码、无搜索路径时 rg 转去过滤 NUL
 * stdin)都由 PowerShell 宿主形态决定,与选哪个沙箱后端无关。
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
        String ws = ctx.workspaceRoot();
        ShellExecutor exec = (rgDir != null && ws != null && !ws.isBlank())
                ? withRgInPath(base, rgDir, Path.of(ws)) : base;
        return List.of(ShellTool.powershell(exec)
                .appendDescription("rg 已加入 PATH,可直接执行 rg 命令，内容搜索尽量使用rg命令，性能更好;"
                        + "rg 省略搜索路径时默认搜当前工作区(已自动补齐,不会静默读空 stdin);"
                        + "中文等非 ASCII 输出已正确解码,无需手动处理编码。")
                .callback());
    }

    /**
     * 包装执行器：命令前预置 PATH 注入 + rg 包装（PowerShell 语法）。
     *
     * <p>PATH 注入语句必须是 {@code $env:PATH = '<dir>;' + $env:PATH;}。引号错位
     *（写成 {@code '...';' + $env:PATH;}）会留下未闭合单引号串,把后续包装与用户命令一起
     * 吞进字符串里：注入静默失效、命令语法走形——实测曾因 POWERSHELL_PREFIX 里恰好有引号
     * 而侥幸闭合,所以一直没暴露。
     */
    private static ShellExecutor withRgInPath(ShellExecutor base, Path rgDir,
            Path workspaceRoot) {
        String dir = rgDir.toString().replace("'", "''");
        String head = "$env:PATH = '" + dir + ";' + $env:PATH; "
                + RgShim.build(rgDir.resolve("rg.exe").toString(), workspaceRoot);
        return (command, shell) -> "powershell".equals(shell)
                ? base.execute(head + command, shell)
                : base.execute(command, shell);
    }
}
