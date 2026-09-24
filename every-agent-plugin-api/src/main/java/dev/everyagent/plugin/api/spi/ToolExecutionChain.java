package dev.everyagent.plugin.api.spi;

import org.springframework.ai.model.tool.ToolExecutionResult;

/**
 * 工具执行拦截链的下一环。
 */
@FunctionalInterface
public interface ToolExecutionChain {
    ToolExecutionResult proceed(ToolExecutionContext ctx) throws Exception;
}
