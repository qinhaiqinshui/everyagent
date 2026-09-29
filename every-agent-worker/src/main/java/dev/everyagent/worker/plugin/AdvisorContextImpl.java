package dev.everyagent.worker.plugin;

import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.worker.agent.AgentEntity;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * {@link AdvisorContext} 默认实现。
 *
 * <p>封装 per-agent 信息（taskId、agentId、workspaceRoot）和核心只读服务
 * （ToolCallingManager）。此外，内置适配器（{@code dev.everyagent.worker.plugin.adapters}）
 * 需要访问完整 {@link AgentEntity} 来构造 per-run Advisor，故额外暴露
 * {@link #agentEntity()}——此方法不属于 SPI 接口，仅供 worker 内部适配器使用，
 * 第三方插件应仅依赖 {@link AdvisorContext} 契约。
 */
public class AdvisorContextImpl implements AdvisorContext {

    private final String taskId;
    private final String agentId;
    private final Path workspaceRoot;
    private final ToolCallingManager toolCallingManager;
    private final AgentEntity agentEntity;
    private final String configId;

    public AdvisorContextImpl(AgentEntity agentEntity, ToolCallingManager toolCallingManager,
            String taskId, Path workspaceRoot, String configId) {
        this.agentEntity = agentEntity;
        this.agentId = agentEntity.agentId;
        this.taskId = taskId;
        this.workspaceRoot = workspaceRoot;
        this.toolCallingManager = toolCallingManager;
        this.configId = configId;
    }

    @Override
    public String taskId() {
        return taskId;
    }

    @Override
    public String agentId() {
        return agentId;
    }

    @Override
    public Path workspaceRoot() {
        return workspaceRoot;
    }

    @Override
    public ToolCallingManager toolCallingManager() {
        return toolCallingManager;
    }

    @Override
    public String configId() {
        return configId;
    }

    /**
     * 完整 AgentEntity（仅供内置适配器使用，非 SPI 契约）。
     */
    public AgentEntity agentEntity() {
        return agentEntity;
    }
}
