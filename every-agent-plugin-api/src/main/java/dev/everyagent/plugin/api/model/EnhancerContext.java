package dev.everyagent.plugin.api.model;

/**
 * ChatModelEnhancer 构建上下文。
 *
 * <p>由 ChatModelFactory 构建，提供组合 provider 配置、成员解析、事件发射器和成员构建回调。
 */
public interface EnhancerContext {

    /** 组合 provider 的原始配置（ModelConfig，其 model 字段为成员 configId 列表等插件私有格式，未解析）。 */
    ModelConfig poolConfig();

    /** 当前 agentId。 */
    String agentId();

    /** 事件发射器。 */
    EventEmitter events();

    /**
     * 按 configId 解析单个成员规格；不存在返回 null（供插件跳过+告警），不抛异常。
     */
    MemberSpec resolveMember(String configId);

    /**
     * 构建单个成员 ChatModel。
     *
     * <p>委托到 ChatModelFactory.build()，复用工厂的成员构建逻辑（单一真相源）。
     */
    org.springframework.ai.chat.model.ChatModel buildMember(MemberSpec member);
}
