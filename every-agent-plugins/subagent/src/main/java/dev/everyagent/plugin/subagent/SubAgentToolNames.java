package dev.everyagent.plugin.subagent;

import org.springframework.ai.tool.annotation.Tool;

/**
 * 用于 REMOVE 模式按工具名匹配移除的工具名占位类。
 *
 * <p>子 agent 工具集不含 agent 工具(结构上禁递归)和 ask_user(提问只能由主 agent 发起)。
 * 装配子 agent 时用 REMOVE 模式移除这些工具,需要 ToolCallback 对象做名称匹配。
 * 本类提供同名的 @Tool 方法,经 ToolCallbacks.from() 生成同名 ToolCallback 供移除匹配。
 */
public class SubAgentToolNames {

    @Tool(description = "")
    public String run_agent() { return ""; }

    @Tool(description = "")
    public String list_agents() { return ""; }

    @Tool(description = "")
    public String wait_agents() { return ""; }

    @Tool(description = "")
    public String stop_agent() { return ""; }

    @Tool(description = "")
    public String ask_user() { return ""; }
}
