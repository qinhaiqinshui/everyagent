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

    /**
     * 设置 agent 创建者标识（{@code task} / {@code subagent} / {@code ai-review} 等）。
     *
     * <p>顶级字段，随 {@code agent.started} 事件持久化进台账顶级 {@code creator}；
     * 消费方（{@code list_agents} / {@code task.agents}）据此决定展示范围。
     */
    AgentBuilder creator(String creator);

    /**
     * 设置 agent 元数据（其他元数据键；随 agent.started 事件持久化到台账 metadata）。
     *
     * <p>注意：{@code creator} 已提升为顶级字段（{@link #creator}），不再走本约定键。
     */
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
     * 设置会话种子（替换式）：以既有会话历史初始化 agent 会话内存。
     *
     * <p>跨轮续跑场景用（如 subagent 插件在本轮重建上一轮的子 agent）：prior 通常是
     * 「上一轮实体会话的快照」（含其 system 轮与最终回答轮），调用方不再另设
     * {@link #systemPrompt}，只追加新的 {@link #userInput}。
     *
     * <p>与 {@link #systemPrompt} / {@link #userInput} 同时设置时，装配侧把三者合并为
     * 一条完整消息序列 {@code [system?, ...prior, user(新)?]} 后一次性注入——system 在最前、
     * 历史居中、新指令在末尾，不会因种子的替换语义丢失或错位。
     *
     * @param conversation 会话历史消息（null 或空列表 = 无种子，等价于不调用）
     */
    AgentBuilder conversation(java.util.List<org.springframework.ai.chat.messages.Message> conversation);

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
