package dev.everyagent.plugin.sandbox.codex;

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
 * {@link CodexRg#resolve} 解析后传入 {@link CodexCommandExecutor}（其所在目录注入子进程
 * PATH），不再依赖 worker 核心的 rg。
 *
 * <p>非 ASCII 正确性<b>不做命令名特判</b>（历史 rg 包装 plugin-api RgShim 已删除）：直出路径
 * 由 runner 的输出文件承载（ChildProcess.OutputFiles）保证；PS 管道内捕获由默认启用的
 * ConsoleProbe（继承控制台 + CP_UTF8，复测不过自动回退）统一治理——对所有原生命令一视同仁，
 * 不再只护住 rg 一个命令。
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
        return List.of(ShellTool.powershell(exec::execute)
                .appendDescription("rg 已加入 PATH，内容搜索尽量使用rg命令，性能更好;"
                        + "rg 未给搜索路径时会静默过滤 null stdin 而返回空,请显式给搜索路径;"
                        + "中文等非 ASCII 输出已正确解码;"
                        + "用户目录(含 Maven 仓库/npm/pip/gradle 缓存)已指向沙箱账户 profile,"
                        + "可写且持久,各工具直接用默认位置即可,勿手动指定仓库/缓存路径;"
                        + "临时目录(TEMP)在工作区 .everyagent/tmp,随任务清理。")
                .callback());
    }
}
