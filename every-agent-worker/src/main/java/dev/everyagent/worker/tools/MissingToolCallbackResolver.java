package dev.everyagent.worker.tools;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;

/**
 * 兜底工具回调解析器：模型调用了当前 agent 未注册的工具名时，框架原生行为是抛
 * {@code IllegalStateException("No ToolCallback found for tool name: X")} 直接中断任务
 * （见 Spring AI 2.0.1 {@code DefaultToolCallingManager}）。本类把「未找到工具」降级为
 * 一次普通工具调用——返回一条明确的错误文本作为工具结果回传给模型，让 AI 自纠
 * （改用已注册工具或修正工具名），而不是终止任务。
 *
 * <p>接入方式（框架原生扩展点，不重写工具循环，符合 AGENTS.md 红线）：
 * {@code ToolCallingManager.builder().resolutionFallbackEnabled(true)
 * .toolCallbackResolver(new MissingToolCallbackResolver())}。仅当模型请求的工具名不在
 * 请求工具回调集合中时才会命中；命中后的兜底回调不参与
 * {@code resolveToolDefinitions}，因此不会污染发给模型的工具定义列表。
 */
public final class MissingToolCallbackResolver implements ToolCallbackResolver {

    private static final String INPUT_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    @Override
    public ToolCallback resolve(String toolName) {
        return new MissingToolCallback(toolName);
    }

    /** 未注册工具的兜底回调：无论入参是什么，都返回「工具不存在」错误文本。 */
    private static final class MissingToolCallback implements ToolCallback {

        private final String toolName;
        private final ToolDefinition definition;

        MissingToolCallback(String toolName) {
            this.toolName = toolName;
            this.definition = ToolDefinition.builder()
                .name(toolName)
                .description("兜底工具：当前 agent 未注册该工具，调用将返回错误信息供 AI 自行修正。")
                .inputSchema(INPUT_SCHEMA)
                .build();
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return ToolMetadata.builder().build();
        }

        @Override
        public String call(String toolInput) {
            return message();
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return message();
        }

        private String message() {
            return "[工具执行失败] No ToolCallback found for tool name: " + toolName
                    + "。该工具未在当前 agent 注册，请改用其它已注册工具完成目标，或修正工具名后重试。";
        }
    }
}
