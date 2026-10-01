package dev.everyagent.plugin.api.agent;

import dev.everyagent.plugin.api.model.EventEmitter;

import java.util.Map;

/**
 * Agent 工厂接口（plugin-api 契约）。
 *
 * <p>插件通过此工厂创建 agent 装配会话。工厂内部自行解析配置（configId）
 * 并构建模型，不暴露 apiKey 等敏感参数。
 *
 * <p>单参/两参 {@code create} 为正式签名：以「绑定本主体的工厂」静态代理形态
 * 预绑定在 {@code ExecContext.agentFactory()} 上（S2 起由 worker
 * {@code TaskBoundAgentFactory} 实现），调用方单参即可创建 agent，无需手工
 * 组装 emitter/properties。四参签名为过渡形态（S5 退役）。
 *
 * <p>典型用法：过渡期（S1）经四参签名：
 * <pre>{@code
 * Agent agent = factory.create(agentId, configId, emitter, properties)
 *     .title(title)
 *     .tools(tools, ModifyMode.REMOVE)
 *     .systemPrompt(systemPrompt)
 *     .userInput(input)
 *     .build();
 * }</pre>
 * S2 起经绑定工厂单参创建：
 * <pre>{@code
 * Agent agent = exec.agentFactory().create(agentId)
 *     .title(title).build();
 * }</pre>
 */
public interface AgentFactory {

    /**
     * 创建 agent 装配会话（正式签名·单参）：用绑定 configId 创建 agent。
     * <p>绑定默认 configId = {@code ExecContext.snapshot().configId()}，
     * 由绑定本主体的工厂（S2 {@code TaskBoundAgentFactory} 静态代理）实现。
     *
     * @param agentId agent 标识（主 agent 或子 agent）
     * @return fluent {@link AgentBuilder}
     */
    default AgentBuilder create(String agentId) {
        throw new UnsupportedOperationException(
                "绑定工厂（S2 TaskBoundAgentFactory）尚未就位，过渡期请使用四参签名");
    }

    /**
     * 创建 agent 装配会话（正式签名·两参）：覆盖模型配置创建 agent
     * （{@code configId = null} = 用绑定默认值）。
     * <p>由绑定本主体的工厂（S2 {@code TaskBoundAgentFactory} 静态代理）实现。
     *
     * @param agentId  agent 标识（主 agent 或子 agent）
     * @param configId 模型配置标识覆盖（null = 绑定默认值）
     * @return fluent {@link AgentBuilder}
     */
    default AgentBuilder create(String agentId, String configId) {
        throw new UnsupportedOperationException(
                "绑定工厂（S2 TaskBoundAgentFactory）尚未就位，过渡期请使用四参签名");
    }

    /**
     * 创建 agent 装配会话（过渡签名，S5 退役；fluent builder）。
     *
     * @param agentId    agent 标识（主 agent 或子 agent）
     * @param configId   模型配置标识（工厂内部据此解析 apiKey / model 等参数）
     * @param emitter    事件发射器（逐层传播）
     * @param properties 上层黑盒数据（taskEntry / taskId / workspaceRoot 等，agent 核心不读）
     * @return fluent {@link AgentBuilder}
     */
    AgentBuilder create(String agentId, String configId,
                        EventEmitter emitter, Map<String, Object> properties);
}
