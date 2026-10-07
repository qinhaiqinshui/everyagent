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
 * <p>rg 归属下放：rg 首选插件自带（{@code <pluginDir>/bin/rg.exe}），activate 时经
 * {@link CodexRg#resolve} 三档解析（插件根 bin/ → 程序根 runtime/bin/ → 系统 PATH）后传入
 * {@link CodexCommandExecutor}（其所在目录注入子进程 PATH）。第二档程序根 {@code runtime/bin/}
 * 与核心 {@code RipgrepBinary} 同一位置，是 desktop 打包态的实际命中位——插件自带 bin/ 未随包
 * 落地时靠它兜住，不再出现「装了包却没有 rg」。
 *
 * <p>工具描述里的 rg 可用性<b>按解析结果条件化生成</b>：三档皆无时如实告知模型 rg 不可用、
 * 内容搜索改用 {@code Select-String}，绝不无条件宣称「rg 已加入 PATH」——那会让模型的
 * 「命令不存在」被当成「无匹配、结果正常」，是最恶劣的一类描述谎报。
 *
 * <p>非 ASCII 正确性<b>不做命令名特判</b>（历史 rg 包装 plugin-api RgShim 已删除）：直出路径
 * 由 runner 的输出文件承载（ChildProcess.OutputFiles）保证；PS 管道内捕获由默认启用的
 * ConsoleProbe（继承控制台 + CP_UTF8，复测不过自动回退）统一治理——对所有原生命令一视同仁，
 * 不再只护住 rg 一个命令。
 */
public class CodexBashToolProvider implements ToolProvider {

    private final CodexSandboxManager manager;
    private final CodexRg.Rg rg;

    public CodexBashToolProvider(CodexSandboxManager manager, CodexRg.Rg rg) {
        this.manager = manager;
        this.rg = rg;
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
        CodexCommandExecutor exec = new CodexCommandExecutor(manager, workspaceRoot, rg.injectPath());
        // rg 提示按解析结果条件化(详见类注释):三档皆无时如实说明,不谎报「已加入 PATH」。
        // 「无路径会去读空 stdin」这条惯例已上收 ShellTool 基线(powershell/bash 两基线共用,
        // 且覆盖 rg/grep/findstr),此处不再重复;只留 rg 的优势与替代手段,并写清优势的真实原因
        // (实测 findstr /s 全仓递归 64s 且扫进 node_modules;rg 尊重 .gitignore)——空喊
        // 「性能更好」对模型没有决策价值。
        // 另报出探测到的 shell 可执行名,把 && / || 这类版本相关语法能否使用交给模型自己判断。
        String rgNote = rg.available()
                ? "rg 已加入 PATH,内容搜索优先用 rg——它尊重 .gitignore,比 findstr/Select-String "
                        + "的全仓递归快一个量级;"
                : "rg 二进制不可用(插件根 bin/、程序根 runtime/bin/、系统 PATH 三档均未命中),"
                        + "内容搜索请改用 PowerShell 的 Select-String,勿再尝试 rg;";
        return List.of(ShellTool.powershell(exec::execute)
                .appendDescription(rgNote
                        + "实际执行 shell=" + CodexCommandExecutor.detectShell().exe + ";"
                        + "中文等非 ASCII 输出已正确解码;"
                        + "用户目录(含 Maven 仓库/npm/pip/gradle 缓存)已指向沙箱账户 profile,"
                        + "可写且持久,各工具直接用默认位置即可,勿手动指定仓库/缓存路径;"
                        + "临时目录(TEMP)在工作区 .everyagent/tmp,随任务清理;"
                        + "git 不读宿主全局配置(已注入本仓库 safe.directory),提交时请用"
                        + " -c user.name=<名> -c user.email=<邮箱> 显式带入身份。")
                .callback());
    }
}
