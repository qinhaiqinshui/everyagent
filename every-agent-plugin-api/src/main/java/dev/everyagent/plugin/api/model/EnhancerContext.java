package dev.everyagent.plugin.api.model;

import java.util.List;

/**
 * ChatModelEnhancer 构建上下文。
 *
 * <p>由 ChatModelFactory 构建，提供池配置、成员列表、事件发射器和成员构建回调。
 */
public interface EnhancerContext {

    /** 池配置（ModelConfig，provider=model-pool）。 */
    ModelConfig poolConfig();

    /** 成员规格列表。 */
    List<MemberSpec> members();

    /** 当前 agentId。 */
    String agentId();

    /** 事件发射器。 */
    EventEmitter events();

    /**
     * 构建单个成员 ChatModel（含洋葱链包装）。
     *
     * <p>委托到 ChatModelFactory.build()，复用工厂的成员构建逻辑。
     */
    org.springframework.ai.chat.model.ChatModel buildMember(MemberSpec member);
}
