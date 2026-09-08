package dev.everyagent.worker.task;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.util.JacksonUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 包装 {@link ToolCallback},去掉 inputSchema 里的 {@code $schema} 键。
 *
 * <p>Spring AI 2.0.1 的 {@code JsonSchemaGenerator} 生成工具入参 schema 时固定输出
 * {@code "$schema":"https://json-schema.org/draft/2020-12/schema"};该声明对模型
 * 推理无用,却随每个工具定义逐字发给模型,白白占用上下文 token。框架的
 * {@code SchemaOption} 枚举没有关闭该输出的选项,故在本类出口统一剥除:
 * <ul>
 *   <li>{@code getToolDefinition()}:返回新 {@link ToolDefinition},name/description
 *       不变,inputSchema 删除 {@code $schema} 键(解析失败或无该键时原样回退);</li>
 *   <li>其余方法(call / call(toolInput, context) / getToolMetadata)原样委托被包装对象。</li>
 * </ul>
 *
 * <p>无状态、多任务并发安全;在 {@link AgentRunner} 统一包装后传入 prompt 的
 * toolCallbacks,主/子 agent 共用,不改动 {@code AgentEntity.tools} 原引用。
 */
public class SchemaStrippedToolCallback implements ToolCallback {

    private static final ObjectMapper MAPPER = JacksonUtils.getDefaultJsonMapper();

    private final ToolCallback delegate;

    public SchemaStrippedToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        ToolDefinition d = delegate.getToolDefinition();
        String schema = stripSchemaVersion(d.inputSchema());
        if (schema == null || schema.equals(d.inputSchema())) {
            return d; // 无 $schema 或解析失败:原样返回,不干扰主流程。
        }
        return ToolDefinition.builder()
                .name(d.name())
                .description(d.description())
                .inputSchema(schema)
                .build();
    }

    /** 解析 inputSchema 并删除 $schema 键;非对象/无该键/解析失败返回 null(调用方回退原样)。 */
    private static String stripSchemaVersion(String inputSchema) {
        if (inputSchema == null) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(inputSchema);
            if (!node.isObject() || !node.has("$schema")) {
                return null;
            }
            ((ObjectNode) node).remove("$schema");
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return delegate.call(toolInput, toolContext);
    }
}
