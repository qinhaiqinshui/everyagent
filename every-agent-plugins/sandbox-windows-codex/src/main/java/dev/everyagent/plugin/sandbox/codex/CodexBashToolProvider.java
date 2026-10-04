package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.api.shell.RgShim;
import dev.everyagent.plugin.api.shell.ShellExecutor;
import dev.everyagent.plugin.api.shell.ShellTool;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;

import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;

/**
 * codex 沙箱自己的命令工具 ToolProvider（形态对照 WslUbuntuBashToolProvider）。
 *
 * <p>appliesTo：只在当前沙箱后端 id=="codex" 时生效（经 {@link ToolContext#sandbox}
 * 判定——后端被 worker 选中即 setup 已就绪，工具内不会再遇到未 setup 的静默降级）。
 * createTools：创建 {@link ShellTool}（使用 {@link CodexCommandExecutor}），
 * 返回 {@code List.of(ShellTool.powershell(...).callback())}。
 *
 * <p>rg 归属下放：rg 由插件自带（{@code <pluginDir>/bin/rg.exe}），activate 时经
 * {@link CodexRg#resolve} 解析后传入，不再依赖 worker 核心的 rg。
 *
 * <p>命令前置 {@link RgShim} 的 rg 包装——codex 子进程<b>没有控制台</b>，PS 5.1 只能按系统
 * OEM 码页(中文=GBK)解码原生子进程输出;实测 runner 里 PS 自身中文已正确,但 PS 内部再调
 * rg 仍是乱码(且乱码文件名回灌 rg 会 os error 2)。包装把 rg 的输出口改为文件承载,并补齐
 * 「没给搜索路径」的语义,两件事都不该让模型去避坑。
 */
public class CodexBashToolProvider implements ToolProvider {

    private final CodexSandboxManager manager;
    private final Path rgPath;

    public CodexBashToolProvider(CodexSandboxManager manager, Path rgPath) {
        this.manager = manager;
        this.rgPath = rgPath;
    }

    @Override
    public String pluginId() {
        return "sandbox-windows-codex";
    }

    @Override
    public boolean appliesTo(ToolContext ctx) {
        return ctx.sandbox() != null && "codex".equals(ctx.sandbox().id());
    }

    @Override
    public List<ToolCallback> createTools(ToolContext ctx) {
        Path workspaceRoot = ctx.workspaceRoot() != null ? Path.of(ctx.workspaceRoot()) : null;
        CodexCommandExecutor exec = new CodexCommandExecutor(manager, workspaceRoot, rgPath);
        return List.of(ShellTool.powershell(withRgShim(exec::execute, rgPath, workspaceRoot))
                .appendDescription("rg 已加入 PATH，内容搜索尽量使用rg命令，性能更好;"
                        + "rg 省略搜索路径时默认搜当前工作区,不会静默读空 stdin;"
                        + "中文等非 ASCII 输出已正确解码。")
                .callback());
    }

    /**
     * 前置 rg 包装（见 {@link RgShim}）。rg 路径或工作区根缺失时原样返回——退化到无包装,
     * 不比现状更差,且绝不因包装缺失而让工具注册失败。
     *
     * @param base           原始执行器
     * @param rgExe          插件自带 rg 可执行文件绝对路径(可空)
     * @param workspaceRoot  工作区根,承载文件落点 {@code <root>/.everyagent/tmp}(可空)
     * @return 包装后的执行器
     */
    static ShellExecutor withRgShim(ShellExecutor base, Path rgExe, Path workspaceRoot) {
        if (rgExe == null || workspaceRoot == null || !rgExe.isAbsolute()) {
            return base;
        }
        String shim = RgShim.build(rgExe.toString(), workspaceRoot);
        return (command, shell) -> "powershell".equals(shell)
                ? base.execute(shim + command, shell)
                : base.execute(command, shell);
    }
}
