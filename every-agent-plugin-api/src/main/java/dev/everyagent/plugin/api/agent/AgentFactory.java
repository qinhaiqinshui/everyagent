package dev.everyagent.plugin.api.agent;

/**
 * Agent 工厂接口（plugin-api 契约）。
 *
 * <p>插件通过此工厂创建 agent 装配会话。工厂内部自行解析配置（configId）
 * 并构建模型，不暴露 apiKey 等敏感参数。
 *
 * <p>实现为「绑定本主体的工厂」静态代理形态，预绑定在
 * {@code ExecContext.agentFactory()} 槽位上（worker 的 TaskBoundAgentFactory），
 * 调用方单参即可创建 agent，无需手工组装 emitter/properties。典型用法：
 * <pre>{@code
 * Agent agent = exec.agentFactory().create(agentId)
 *     .title(title).build();
 * }</pre>
 */
public interface AgentFactory {

    /**
     * 创建 agent 装配会话（单参）：用绑定 configId 创建 agent。
     * <p>绑定默认 configId = {@code ExecContext.snapshot().configId()}，
     * 由绑定本主体的工厂静态代理实现。
     *
     * @param agentId agent 标识（主 agent 或子 agent）
     * @return fluent {@link AgentBuilder}
     */
    AgentBuilder create(String agentId);

    /**
     * 创建 agent 装配会话（两参）：覆盖模型配置创建 agent
     * （{@code configId = null} = 用绑定默认值）。
     *
     * @param agentId  agent 标识（主 agent 或子 agent）
     * @param configId 模型配置标识覆盖（null = 绑定默认值）
     * @return fluent {@link AgentBuilder}
     */
    AgentBuilder create(String agentId, String configId);
}
