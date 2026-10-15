package dev.everyagent.plugin.sandbox.mic;

import dev.everyagent.plugin.api.shell.ShellExecutor;
import dev.everyagent.plugin.api.shell.ShellTool;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * windows-mic 沙箱的 shell 工具 ToolProvider（Shell 工具收敛 §3.3）。
 *
 * <p>appliesTo：只在当前沙箱后端 id=="windows-mic" 时生效（经 {@link ToolContext#sandbox}
 * 判定）。createTools：直接用 {@link ToolContext#shellExecutor()} 的基础执行器创建
 * {@link ShellTool#powershell}，不做任何二次包装。
 *
 * <p>非 ASCII 正确性<b>不做命令名特判</b>：直出路径由输出承载契约（stdout/stderr 文件承载）保证。
 */
public class WindowsMicShellToolProvider implements ToolProvider {

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
        ShellExecutor exec = ctx.shellExecutor();
        if (exec == null) {
            return List.of();
        }
        // 描述全量自报(2026-12 第三批:核心零默认,提供者必传):用途/工作目录两句随移交由本
        // 后端自写。通用常识类条目(stdin 语义、非 ASCII 已解码、Out-String 收口、连接符版本)
        // 均已按用户决策从描述删除(沿革见 ARCHITECTURE §7.10),后端不得在此私自加回。
        // 描述谎报红线:本后端不携带任何搜索工具,不得宣称或暗示任何搜索命令可用。
        return List.of(ShellTool.powershell("在系统上用 PowerShell 执行真实 OS 命令;"
                        + "命令工作目录默认为任务工作区根。",
                exec).callback());
    }
}
