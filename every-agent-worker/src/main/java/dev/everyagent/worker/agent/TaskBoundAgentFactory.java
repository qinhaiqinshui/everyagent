package dev.everyagent.worker.agent;

import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.model.EventEmitter;

import java.util.Map;

/**
 * 绑定 {@link ExecContext} 的 {@link AgentFactory} 静态代理（设计 §4.5，包内可见）。
 *
 * <p>闭包「工厂实现 + 执行上下文」双依赖：正式两参签名（create(agentId) /
 * create(agentId, configId)）委托 {@link AgentFactoryImpl} 的全参方法
 * {@code create(agentId, configId, bound)}——configId 为 null 即取绑定默认
 * {@code bound.snapshot().configId()}，emitter 固定取 {@code bound.emitter()}。
 * 调用方（子 agent / 审议 agent 创建方）单参即可创建 agent，不再手工组装
 * emitter/properties 四件套。
 *
 * <p>获取口唯一：{@code ExecContext.agentFactory()}（TaskEntry 经
 * {@link AgentFactoryImpl#bind(ExecContext)} 取得本代理，不直接暴露本类型）。
 * 未来 WorkflowBoundAgentFactory 同构：闭包 workflow 上下文，链式套娃。
 */
class TaskBoundAgentFactory implements AgentFactory {

    private final AgentFactoryImpl delegate;
    private final ExecContext bound;

    TaskBoundAgentFactory(AgentFactoryImpl delegate, ExecContext bound) {
        this.delegate = delegate;
        this.bound = bound;
    }

    @Override
    public dev.everyagent.plugin.api.agent.AgentBuilder create(String agentId) {
        return delegate.create(agentId, null, bound);
    }

    @Override
    public dev.everyagent.plugin.api.agent.AgentBuilder create(String agentId, String configId) {
        return delegate.create(agentId, configId, bound);
    }

    /**
     * 过渡签名（S5 退役；SubAgentManager / AiAuthReviewer 迁移到绑定工厂后删除）：
     * 原样委托 delegate 四参桥接，参数透传不注入绑定值。
     */
    @Override
    public dev.everyagent.plugin.api.agent.AgentBuilder create(
            String agentId, String configId,
            EventEmitter emitter, Map<String, Object> properties) {
        return delegate.create(agentId, configId, emitter, properties);
    }
}
