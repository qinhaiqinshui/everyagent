package dev.everyagent.plugin.subagent;

import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * subagent 工具提供者:四工具(run_agent/list_agents/wait_agents/stop_agent)一律走
 * <b>hidden 池</b>(createHiddenTools)——不进请求 tool 定义,模型经 agent-dispatch
 * skill 文档的工具参考认知后按名调用。
 *
 * <p>开关语义(worker.tools.hidden-enabled=false 的应急回退)在 worker 聚合层
 * (AgentBuilder.create)统一生效:hidden 池回落常规暴露(进请求 tool 定义、带 schema),
 * 本 provider 不感知开关,只管产出 hidden 池。子 agent 侧禁递归仍由 SubAgentManager
 * 经 REMOVE 模式按工具名删(常规池与 hidden 池同步删)。
 */
public class SubAgentToolsProvider implements ToolProvider {

    private final SubAgentManager subAgentManager;

    public SubAgentToolsProvider(SubAgentManager subAgentManager) {
        this.subAgentManager = subAgentManager;
    }

    @Override
    public String pluginId() {
        return "subagent";
    }

    @Override
    public List<ToolCallback> createTools(ToolContext ctx) {
        return List.of();
    }

    @Override
    public List<ToolCallback> createHiddenTools(ToolContext ctx) {
        return List.of(ToolCallbacks.from(new SubAgentTools(subAgentManager, ctx)));
    }
}
