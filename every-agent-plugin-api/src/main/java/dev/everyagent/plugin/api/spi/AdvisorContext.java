package dev.everyagent.plugin.api.spi;

import dev.everyagent.plugin.api.agent.AgentContext;
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

    /** 模型配置 ID（TokenCalibrationAdvisor 等据此校准估算系数）。 */
    String configId();

    /**
     * 当前 agent 的上下文（plugin-api 契约接口，隐藏 worker 实现细节）。
     *
     * <p>插件经此获取 {@link AgentContext} 来读取 agent properties（如 taskEntry）
     * 或会话内存，无需依赖 worker 的 {@code AgentEntity} 具体类。
     *
     * @return 当前 agent 上下文
     */
    AgentContext agentEntity();
}
