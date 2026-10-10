package dev.everyagent.worker.plugin;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.spi.AdvisorContext;
import dev.everyagent.worker.agent.AgentEntity;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.nio.file.Path;
import java.util.Map;

/**
 * {@link AdvisorContext} 默认实现。
 *
 * <p>ExecContext 槽位全部委托 {@code agentEntity.execution()}(advisor 链类型化槽位
 * 取数主干);per-agent 信息(agentId、configId)与核心只读服务(ToolCallingManager)
 * 由 AgentBuilder 装配时显式传入。
 *
 * <p>此外,内置适配器({@code dev.everyagent.worker.plugin.adapters})需要访问完整
 * {@link AgentEntity} 来构造 per-run Advisor,故额外暴露 {@link #agentEntity()}——
 * 第三方插件应仅依赖 {@link AdvisorContext} 契约。
 */
public class AdvisorContextImpl implements AdvisorContext {

    private final AgentEntity agentEntity;
    private final String agentId;
    private final ToolCallingManager toolCallingManager;
    private final String configId;

    public AdvisorContextImpl(AgentEntity agentEntity, ToolCallingManager toolCallingManager,
            String configId) {
        this.agentEntity = agentEntity;
        this.agentId = agentEntity.agentId;
        this.toolCallingManager = toolCallingManager;
        this.configId = configId;
    }

    private ExecContext execution() {
        return agentEntity.execution();
    }

    @Override
    public String subjectId() {
        return execution().subjectId();
    }

    @Override
    public String workspaceRoot() {
        return execution().workspaceRoot();
    }

    @Override
    public String workspaceId() {
        return execution().workspaceId();
    }

    @Override
    public ModelConfig snapshot() {
        return execution().snapshot();
    }

    @Override
    public EventEmitter emitter() {
        return execution().emitter();
    }

    @Override
    public AgentFactory agentFactory() {
        return execution().agentFactory();
    }

    @Override
    public Map<String, Object> metadata() {
        return execution().metadata();
    }

    @Override
    public Path dataDir() {
        return execution().dataDir();
    }

    @Override
    public boolean terminal() {
        return execution().terminal();
    }

    @Override
    public InteractionService interaction() {
        return execution().interaction();
    }

    @Override
    public Map<String, AgentContext> agents() {
        return execution().agents();
    }

    @Override
    public String agentId() {
        return agentId;
    }

    @Override
    public String configId() {
        return configId;
    }

    @Override
    public ToolCallingManager toolCallingManager() {
        return toolCallingManager;
    }

    @Override
    public AgentContext agentEntity() {
        return agentEntity;
    }
}
