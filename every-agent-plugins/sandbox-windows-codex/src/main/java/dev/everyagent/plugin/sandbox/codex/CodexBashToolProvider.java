package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.api.shell.ShellExecutor;
import dev.everyagent.plugin.api.shell.ShellTool;
import dev.everyagent.plugin.api.spi.CommandGate;
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
 * <p>职责只做沙箱与 shell 工具：rg 等专项工具由独立插件提供，本后端既不解析、
 * 不注入 rg 二进制，描述里也不提及 rg——描述谎报红线：不宣称任何本后端管不了的
 * 搜索工具。
 *
 * <p>非 ASCII 正确性<b>不做命令名特判</b>：直出路径
 * 由 runner 的输出文件承载（ChildProcess.OutputFiles）保证；PS 管道内捕获由默认启用的
 * ConsoleProbe（继承控制台 + CP_UTF8，复测不过自动回退）统一治理——对所有原生命令一视同仁。
 */
public class CodexBashToolProvider implements ToolProvider {

    private final CodexSandboxManager manager;

    public CodexBashToolProvider(CodexSandboxManager manager) {
        this.manager = manager;
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
        CodexCommandExecutor exec = new CodexCommandExecutor(manager, workspaceRoot,
                ctx.toolOutcomes());
        // 门禁接入(§7.8):后端的命令执行器归本插件所有,worker 无法拦命令串,故由本插件
        // 在 spawn 前先过 worker 的授权门禁——否则「工作区外路径授权 → 下发沙箱」这条链
        // 根本不会启动(表现为被 OS 直接拒绝且从不弹窗)。授权通过后 worker 同步把授权范围
        // 下发给沙箱,故此处无需自行落地权限。
        // 描述全量自报(2026-12 第三批:核心零默认,提供者必传):用途/工作目录两句随移交由本
        // 后端自写。通用常识类条目(stdin 语义、-join/$OFS、非 ASCII 已解码、Out-String 收口、
        // 连接符版本)均已按用户决策从描述删除(沿革与残余风险见 ARCHITECTURE §7.10),
        // 后端不得在此私自加回;内容搜索引导 rg 的句子亦已随 rg 归属剥离删除——rg 工具由
        // 独立插件提供,本后端描述不提 rg、也不加 Select-String 建议(描述谎报红线:不宣称
        // 任何本后端管不了的搜索工具)。
        // shell 身份(名称+版本)并入描述首句(§7.10 第五批,2026-12):独立子句「实际执行
        // shell=<exe>」退役;首句 shell 名不硬编码——探测兜底到 cmd 时如实报 cmd(cmd 是
        // 执行器真实分支),PowerShell 时报名称+版本(一次 spawn 探测,失败降级 7+/5.1),
        // &&/|| 等版本判断依据对模型仍可见。
        // git 身份提示已随第四批退役(2026-12):执行器 injectGitConfig 经 GIT_CONFIG env
        // 注入 user.name/user.email,裸 git commit 直接成功——描述不再携带任何 git 指引
        // (护栏:backendDoesNotReAddPrunedBaselineClaims 断言「提交须带」「user.name」不在场)。
        return List.of(ShellTool.powershell("在系统上用 "
                        + CodexCommandExecutor.shellDisplay()
                        + " 执行真实 OS 命令;命令工作目录默认为任务工作区根;",
                gated(ctx.commandGate(), exec::execute)).callback());
    }

    /**
     * 门禁包装:先授权、再执行(授权拒绝 → 命令不执行,异常回灌模型)。
     *
     * <p>抽取为静态方法便于单测钉住「门禁先于执行、且拒绝即短路」这条契约。
     */
    static ShellExecutor gated(CommandGate gate, ShellExecutor delegate) {
        return (command, shell) -> {
            gate.authorize(command);
            return delegate.execute(command, shell);
        };
    }
}
