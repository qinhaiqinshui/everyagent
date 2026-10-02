package dev.everyagent.plugin.api.agent;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Agent 装配 fluent builder（plugin-api 契约）。
 *
 * <p>由 {@link AgentFactory#create} 返回，供调用方链式配置后 {@link #build()} 出
 * {@link Agent} 实例。每个配置方法返回 {@code this} 以支持链式调用。
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@link #tools(List, ModifyMode)} 按模式增删改工具列表</li>
 *   <li>{@link #options(Consumer)} 供插件对 {@link ChatOptions} 做增量修改</li>
 *   <li>{@link #build()} 返回 {@link Agent}，内部自行解析配置 + 构建模型</li>
 * </ul>
 */
public interface AgentBuilder {

    /** 设置 agent 标题（展示用）。 */
    AgentBuilder title(String title);

    /** 设置 agent 元数据（如 creator 标记；随 agent.started 事件持久化到台账）。 */
    AgentBuilder agentMetadata(Map<String, Object> metadata);

    /**
     * 按模式增删改工具列表。
     *
     * @param tools 工具列表（REMOVE 模式下按工具名匹配移除）
     * @param mode  修改模式
     */
    AgentBuilder tools(List<ToolCallback> tools, ModifyMode mode);

    /** 设置 system prompt（追加为 SystemMessage）。 */
    AgentBuilder systemPrompt(String prompt);

    /** 设置用户输入（追加为 UserMessage）。 */
    AgentBuilder userInput(String input);

    /**
     * 对模型请求参数做增量修改。
     *
     * @param customizer 接收 {@link ChatOptions}，插件可调整 temperature 等参数
     */
    AgentBuilder options(Consumer<ChatOptions> customizer);

    /** 装配完成，返回可执行的 {@link Agent}。 */
    Agent build();

    /** 工具/advisor 修改模式。 */
    enum ModifyMode { ADD, REMOVE, REPLACE }
}
