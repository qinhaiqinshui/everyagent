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
 *
 * <p>非 ASCII 正确性<b>不做命令名特判</b>（与 codex 后端共用的 rg 包装 plugin-api RgShim
 * 已删除，2026-10 用户决策）：直出路径由输出承载契约（stdout/stderr 文件承载）保证。
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
        // 「无路径会去读空 stdin」的惯例已由 ShellTool 基线统一承担(四后端共用,覆盖
        // rg/grep/findstr),此处不再重复;rg 优势给真实原因,措辞与 codex/direct 同口径。
        return List.of(ShellTool.powershell(exec)
                .appendDescription("rg 已加入 PATH,内容搜索优先用 rg——它尊重 .gitignore,"
                        + "比 findstr/Select-String 的全仓递归快一个量级;"
                        + "中文等非 ASCII 输出已正确解码,无需手动处理编码。")
                .callback());
    }

    /**
     * 包装执行器：命令前预置 PATH 注入（PowerShell 语法）。
     *
     * <p>PATH 注入语句必须是 {@code $env:PATH = '<dir>;' + $env:PATH;}。引号错位
     *（写成 {@code '...';' + $env:PATH;}）会留下未闭合单引号串,把后续包装与用户命令一起
     * 吞进字符串里：注入静默失效、命令语法走形——实测曾因 POWERSHELL_PREFIX 里恰好有引号
     * 而侥幸闭合,所以一直没暴露。
     */
    private static ShellExecutor withRgInPath(ShellExecutor base, Path rgDir) {
        String dir = rgDir.toString().replace("'", "''");
        String head = "$env:PATH = '" + dir + ";' + $env:PATH; ";
        return (command, shell) -> "powershell".equals(shell)
                ? base.execute(head + command, shell)
                : base.execute(command, shell);
    }
}
