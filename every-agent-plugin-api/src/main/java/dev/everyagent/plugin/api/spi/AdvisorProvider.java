package dev.everyagent.plugin.api.spi;

import org.springframework.ai.chat.client.advisor.api.Advisor;

/**
 * Advisor 提供者 SPI —— 插件实现此接口向 agent 链注入 Advisor。
 *
 * <p>核心只做聚合——从注册表遍历所有 provider，按 appliesTo() 筛选 + 按 order() 排序装配。
 * AgentBuilder 调用方可通过 .advisors(list, ModifyMode) 按需增删改。
 */
public interface AdvisorProvider {

    /** 插件 id。 */
    String pluginId();

    /** Advisor 顺序（数字，Spring @Order 语义）。 */
    int order();

    /**
     * 为指定任务创建 Advisor 实例。
     *
     * @param ctx Advisor 创建上下文
     * @return Advisor 实例
     */
    Advisor create(AdvisorContext ctx);

    /**
     * 可选：Advisor 是否适用于此任务。
     */
    default boolean appliesTo(AdvisorContext ctx) {
        return true;
    }
}
