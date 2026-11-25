package dev.everyagent.plugin.subagent;

import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * subagent 工具提供者:四工具(run_agent/list_agents/wait_agents/stop_agent)一律走
 * <b>hidden 池</b>(createHiddenTools)——常驻可执行池,经 agent-dispatch 知识包的
 * frontmatter {@code tools} 绑定,模型读取该知识包后以完整定义自动进请求(自动暴露);
 * 未读取前不在请求工具列表,按文档硬调仍可执行。
 *
 * <p>自动暴露链(hidden 池聚合、读取触发、exposedTools 持久化与回灌)在 worker
 * 聚合层统一生效,本 provider 不感知,只管产出 hidden 池。子 agent 侧禁递归仍由
 * SubAgentManager 经 REMOVE 模式按工具名删(常规池与 hidden 池同步删)。
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
