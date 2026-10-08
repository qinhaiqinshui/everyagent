package dev.everyagent.plugin.sandbox.wslubuntu;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.shell.ShellTool;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;
import dev.everyagent.plugin.api.spi.WorkspaceManager;

import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * wsl-ubuntu 沙箱自己的命令工具 ToolProvider。
 *
 * <p>appliesTo：只在当前沙箱是 wsl-ubuntu 时生效。
 * createTools：使用 {@link ShellTool#bash} + {@link WslUbuntuCommandExecutor}，
 * 返回 {@link ToolCallback}。
 *
 * <p>rg 归属：命令跑在 **WSL 发行版内**，宿主侧 rg 三档解析（{@code CodexRg}/
 * {@code RipgrepBinary}/{@code ToolContext.rgBinary()}）在此全部不适用，判据换成
 * {@link #rgDeclaredByManagedImage()}（托管镜像出处），工具描述按该判据<b>条件化生成</b>——
 * 判据不成立时如实说明未探测、回退 grep，绝不无条件宣称「已在 PATH」（§7.10 硬约束）。
 *
 * <p>禁网开关按 taskId 实时读取(每条命令执行瞬间查任务
 * {@code metadata[networkBlocked]}),故运行中用户选中/取消 /禁用网络 对本轮后续命令即时生效。
 */
public class WslUbuntuBashToolProvider implements ToolProvider {

    private final WorkerConfig props;
    private final WorkspaceManager workspaces;
    private final Path pluginDir;
    private final WorkerServices services;

    public WslUbuntuBashToolProvider(WorkerConfig props, WorkspaceManager workspaces,
            Path pluginDir, WorkerServices services) {
        this.props = props;
        this.workspaces = workspaces;
        this.pluginDir = pluginDir;
        this.services = services;
    }

    @Override
    public String pluginId() {
        return "sandbox-wsl-ubuntu";
    }

    @Override
    public boolean appliesTo(ToolContext ctx) {
        return ctx.sandbox() != null && "wsl-ubuntu".equals(ctx.sandbox().id());
    }

    @Override
    public List<ToolCallback> createTools(ToolContext ctx) {
        Path workspaceRoot = ctx.workspaceRoot() != null ? Path.of(ctx.workspaceRoot()) : null;
        WslUbuntuCommandExecutor exec = new WslUbuntuCommandExecutor(props, workspaceRoot,
                workspaces, pluginDir, taskNetworkBlocked(ctx.subjectId()));
        // 描述全量自报(2026-12 第三批:核心零默认,提供者必传):用途/工作目录两句随移交由本
        // 后端自写;通用常识类条目(stdin 语义、Out-String 收口、连接符版本)已按用户决策从
        // 描述删除(沿革见 ARCHITECTURE §7.10),后端不得在此私自加回。这里只留本沙箱
        // 特有事实,bash 语境下对照命令是 grep 而非 findstr。
        //
        // rg 描述条件化(§7.10「WSL 侧 rg 另有一套判据」,四后端里最后一个漏项):判据见
        // rgDeclaredByManagedImage()。不可判定分支**降级为中性表述**——既不宣称「已在 PATH」
        // (谎报:命令不存在会被当成无匹配),也不写「rg 不可用」(反向的无法验证断言:自装发行版
        // 里 rg 常常在位,写了就把它无据摘掉);只交代未探测 + 回退命令。bash 下命令不存在报
        // command not found(rc=127),与「无匹配」(rc=1)可分辨,不触发 §7.10 那类最恶劣误读。
        String rgNote = rgDeclaredByManagedImage()
                ? "内容搜索用 rg(已在 PATH,尊重 .gitignore,全仓递归远快于 grep -r;"
                        + "未给文件参数时会改读空 stdin,务必显式给出路径如 rg <pattern> .);"
                : "内容搜索优先 rg(非托管发行版,是否预装 rg 未探测;"
                        + "报 command not found 就改用 grep);";
        return List.of(ShellTool.bash("在系统上用 bash 执行真实 OS 命令;"
                        + "命令工作目录默认为任务工作区根;"
                        + rgNote,
                exec::execute).callback());
    }

    /**
     * 目标发行版是否是本插件自带的托管发行版(其 rootfs 预装了 ripgrep)。
     *
     * <p>两个条件同时成立才算数:①运行期目标发行版解析为托管名 {@code EveryAgent}
     * (显式配置成该名,或未配置且镜像在位——见 {@link WslCommon#effectiveDistro});
     * ②本插件的 rootfs 镜像在位({@link WslCommon#tarballFor} 三级链命中)——镜像是这条
     * 出处链唯一可静态核验的锚点,缺失时托管名可能来自别处导入的同名发行版,不再据其宣称 rg 可用。
     * 托管镜像的 rg 出处 = 构建脚本 {@code scripts/wsl-rootfs-build.{sh,ps1}} 的
     * {@code apt-get install ... ripgrep}(实测镜像内 {@code usr/bin/rg} 在位)。
     *
     * <p><b>时机与成本:恒不 spawn wsl</b>(纯配置 + 文件存在性判定),故可直接放在工具创建路径上。
     * 不做运行时探测的理由:描述在 agent 装配热路径上逐任务/逐子 agent 重新生成,唯一可靠的探测形态
     * 是每趟 spawn {@code wsl.exe -e /bin/sh -c 'command -v rg'}——冷启动秒级、WSL 卡死还要吃满
     * 探测超时,并把「WSL 起不来」放大成「工具组不出来」,为一句描述付这个代价不合算。将来若要真判,
     * 正解是把 rg 并入 {@link WslUbuntuSandbox#probe} 已有的那一趟 wsl 调用(结果已被
     * {@code WslUbuntuSandboxProvider.isAvailable()} 进程内缓存),而不是另起一次 spawn;且 rg
     * 缺失不得升级为后端不可用(插件探针脚本把它列为建议项)。
     */
    private boolean rgDeclaredByManagedImage() {
        if (props == null) {
            return false;
        }
        return WslCommon.MANAGED_DISTRO.equals(WslCommon.effectiveDistro(props, pluginDir))
                && WslCommon.tarballFor(props, pluginDir) != null;
    }

    /** 任务级禁网开关读取器(每次调用实时查任务 metadata;任务服务缺失时恒 false)。 */
    private BooleanSupplier taskNetworkBlocked(String subjectId) {
        if (services == null || services.task() == null) {
            return () -> false;
        }
        return () -> NetworkTaskFlag.isOn(services.task().get(subjectId));
    }
}
