package dev.everyagent.plugin.api.spi;

import org.springframework.ai.model.tool.ToolCallingManager;

import java.nio.file.Path;

/**
 * Advisor 创建上下文 —— {@link AdvisorProvider#create} 的参数。
 *
 * <p>封装 per-agent 信息（taskId、agentId、workspaceRoot）和核心只读服务
 * （ToolCallingManager 等），Advisor 提供者据此创建 Advisor 实例。
 */
public interface AdvisorContext {

    /** 任务 ID。 */
    String taskId();

    /** Agent ID。 */
    String agentId();

    /** 工作区根路径。 */
    Path workspaceRoot();

    /** 工具调用管理器（共享单例，LoopRepeatGuard 等需要）。 */
    ToolCallingManager toolCallingManager();
}
