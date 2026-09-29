package dev.everyagent.plugin.api.spi;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * AI 工具提供者 SPI —— 插件实现此接口向 agent 注入工具。
 *
 * <p>核心只做聚合——从注册表遍历所有 provider，按 appliesTo() 筛选。
 * AgentBuilder 调用方可通过 .tools(list, ModifyMode) 按需增删改。
 */
public interface ToolProvider {

    /** 插件 id。 */
    String pluginId();

    /**
     * 为指定任务创建工具回调列表。
     *
     * @param ctx 工具创建上下文（含 taskId、workspaceRoot、sandbox、gate 等）
     * @return 该 provider 贡献的工具回调列表
     */
    List<ToolCallback> createTools(ToolContext ctx);

    /**
     * 可选：工具是否适用于此任务。
     */
    default boolean appliesTo(ToolContext ctx) {
        return true;
    }
}
