package dev.everyagent.plugin.api.agent;

import dev.everyagent.plugin.api.model.EventEmitter;

import java.util.Map;

/**
 * Agent 工厂接口（plugin-api 契约）。
 *
 * <p>插件通过此工厂创建 agent 装配会话。工厂内部自行解析配置（configId）
 * 并构建模型，不暴露 apiKey 等敏感参数。
 *
 * <p>典型用法：
 * <pre>{@code
 * Agent agent = factory.create(agentId, configId, emitter, properties)
 *     .title(title)
 *     .tools(tools, ModifyMode.REMOVE)
 *     .systemPrompt(systemPrompt)
 *     .userInput(input)
 *     .build();
 * }</pre>
 */
public interface AgentFactory {

    /**
     * 创建 agent 装配会话（fluent builder）。
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
