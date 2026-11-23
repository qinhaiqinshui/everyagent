package dev.everyagent.plugin.workflow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 运行变量声明。实例运行时用户实输入（required 缺失在 {@code workflow.run} 校验拒绝；
 * 非 required 未提供且无默认 → 渲染为空串）。
 *
 * <p>变量渲染范围：仅 command 与 input 两字段做 {@code {{varName}}} 替换；
 * 值原样替换、不递归（值里再含 {{}} 不二次展开，防注入链）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VariableDef(
        String name,
        String label,
        Boolean required,
        String defaultValue,
        String description) {
}
