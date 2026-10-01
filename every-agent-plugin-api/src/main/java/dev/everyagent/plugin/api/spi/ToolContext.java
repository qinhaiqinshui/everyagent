package dev.everyagent.plugin.api.spi;

import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.shell.ShellExecutor;
import java.nio.file.Path;

/**
 * 工具创建上下文 —— {@link ToolProvider#createTools} 的参数。
 *
 * <p>封装 per-task 信息（taskId、workspaceRoot）和核心只读服务
 * （沙箱、工作区管理器等），工具提供者据此创建工具实例。
 */
public interface ToolContext {

    /** 任务 ID。 */
    String taskId();

    /** Agent ID（主 agent 的 mainAgentId 或子 agent 的 agentId）。 */
    String agentId();

    /** 工作区根路径。 */
    Path workspaceRoot();

    /** 当前激活的沙箱后端（来自 SandboxProviderRegistry）。 */
    SandboxBackend sandbox();

    /** 工作区管理器（多工作区注册表）。 */
    WorkspaceManager workspaces();

    /** ripgrep 二进制路径（搜索工具用，可能为 null）。 */
    default Path rgBinary() {
        return null;
    }

    /** 用户交互服务（向用户发起提问，同步阻塞或异步回调）。 */
    default InteractionService interaction() {
        return null;
    }

    /** 已组装好的 shell 执行器（授权 + 沙箱已内建），插件用它注册 ShellTool。 */
    default ShellExecutor shellExecutor() {
        return null;
    }
}
