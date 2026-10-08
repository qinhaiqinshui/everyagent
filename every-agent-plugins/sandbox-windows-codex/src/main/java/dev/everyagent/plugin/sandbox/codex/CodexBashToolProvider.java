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
 * 内容搜索改用 {@code Select-String}，绝不无条件宣称 rg 可用——那会让模型的
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
        // 描述全量自报(2026-12 第三批:核心零默认,提供者必传):用途/工作目录两句随移交由本
        // 后端自写。通用常识类条目(stdin 语义、-join/$OFS、非 ASCII 已解码、Out-String 收口、
        // 连接符版本)均已按用户决策从描述删除(沿革与残余风险见 ARCHITECTURE §7.10),
        // 后端不得在此私自加回——唯一例外:搜索显式路径子句经 §7.10 恢复协议由用户决策
        // 恢复(2026-12),四后端同口径。rg 提示按解析结果条件化(详见类注释):三档皆无时
        // 如实说明,不谎报「已加入 PATH」;优势压到一行,给可信的区分依据而非空喊「性能更好」。
        // shell 身份(名称+版本)并入描述首句(§7.10 第五批,2026-12):独立子句「实际执行
        // shell=<exe>」退役;首句 shell 名不硬编码——探测兜底到 cmd 时如实报 cmd(cmd 是
        // 执行器真实分支),PowerShell 时报名称+版本(一次 spawn 探测,失败降级 7+/5.1),
        // &&/|| 等版本判断依据对模型仍可见。
        String rgNote = rg.available()
                ? "内容搜索用 rg(已在 PATH,尊重 .gitignore,全仓递归远快于 findstr;"
                        + "未给文件参数时会改读空 stdin,务必显式给出路径如 rg <pattern> .)"
                : "rg 不可用,内容搜索改用 Select-String";
        // git 身份提示已随第四批退役(2026-12):执行器 injectGitConfig 经 GIT_CONFIG env
        // 注入 user.name/user.email,裸 git commit 直接成功——描述不再携带任何 git 指引
        // (护栏:backendDoesNotReAddPrunedBaselineClaims 断言「提交须带」「user.name」不在场)。
        return List.of(ShellTool.powershell("在系统上用 "
                        + CodexCommandExecutor.shellDisplay()
                        + " 执行真实 OS 命令;命令工作目录默认为任务工作区根;"
                        + rgNote + "。",
                exec::execute).callback());
    }
}
