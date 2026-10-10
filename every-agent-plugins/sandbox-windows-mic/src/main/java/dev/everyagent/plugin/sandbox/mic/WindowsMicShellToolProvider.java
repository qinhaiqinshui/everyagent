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
        // 描述全量自报(2026-12 第三批:核心零默认,提供者必传):用途/工作目录两句随移交由本
        // 后端自写。通用常识类条目(stdin 语义、非 ASCII 已解码、Out-String 收口、连接符版本)
        // 均已按用户决策从描述删除(沿革见 ARCHITECTURE §7.10),后端不得在此私自加回。
        // rg 可用性按解析结果条件化(§7.10 硬约束):rgDir==null 时 withRgInPath 不执行,
        // 若仍宣称「已在 PATH」,模型会把「命令不存在」误读成「无匹配、结果正常」——此处曾是
        // 该约束在四后端中的最后一个漏项(codex/direct 上一轮已修);措辞与 codex/direct/wsl 同口径。
        String rgNote = rgDir != null
                ? "内容搜索用 rg(已在 PATH,尊重 .gitignore,全仓递归远快于 findstr;"
                        + "未给文件参数时会改读空 stdin,务必显式给出路径如 rg <pattern> .);"
                : "rg 不可用,内容搜索改用 Select-String;";
        return List.of(ShellTool.powershell("在系统上用 PowerShell 执行真实 OS 命令;"
                        + "命令工作目录默认为任务工作区根;"
                        + rgNote,
                exec).callback());
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
