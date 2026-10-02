package dev.everyagent.plugin.api.spi;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.shell.ShellExecutor;
import java.nio.file.Path;

/**
 * 工具创建上下文 —— {@link ToolProvider#createTools} 的参数。
 *
 * <p>本身即统一执行上下文（extends {@link ExecContext}：subjectId / workspaceRoot /
 * snapshot / emitter 等类型化槽位），外加工具创建侧专属信息
 * （agentId、沙箱后端、工作区管理器、rg 二进制路径、shell 执行器），
 * 工具提供者据此创建工具实例。需要 {@link Path} 形态工作区根的消费者
 * 经 {@code Path.of(ctx.workspaceRoot())} 转换并自行 null 判定。
 */
public interface ToolContext extends ExecContext {

    /** Agent ID（主 agent 的 mainAgentId 或子 agent 的 agentId）。 */
    String agentId();

    /** 当前激活的沙箱后端（来自 SandboxProviderRegistry）。 */
    SandboxBackend sandbox();

    /** 工作区管理器（多工作区注册表）。 */
    WorkspaceManager workspaces();

    /** ripgrep 二进制路径（搜索工具用，可能为 null）。 */
    default Path rgBinary() {
        return null;
    }

    /** 已组装好的 shell 执行器（授权 + 沙箱已内建），插件用它注册 ShellTool。 */
    default ShellExecutor shellExecutor() {
        return null;
    }
}
