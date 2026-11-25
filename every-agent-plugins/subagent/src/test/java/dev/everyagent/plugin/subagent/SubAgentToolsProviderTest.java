package dev.everyagent.plugin.subagent;

import dev.everyagent.plugin.api.spi.ToolContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * subagent 四工具 hidden 池钉子:
 * <ul>
 *   <li>{@code createHiddenTools} 产出且仅产出四工具(run_agent/list_agents/wait_agents/
 *       stop_agent)——池内工具始终可执行,未暴露期间不进请求 tool 定义(读取
 *       agent-dispatch 知识包后自动暴露);</li>
 *   <li>{@code createTools} 恒空——常规池不出现派发工具(暴露形态由 worker 聚合层
 *       统一处理,与本 provider 无关)。</li>
 * </ul>
 */
class SubAgentToolsProviderTest {

    @Test
    void hiddenPoolCarriesExactlyTheFourDispatchTools() {
        SubAgentToolsProvider provider = new SubAgentToolsProvider(new SubAgentManager());
        ToolContext ctx = mock(ToolContext.class);

        List<String> names = provider.createHiddenTools(ctx).stream()
                .map(ToolCallback::getToolDefinition)
                .map(d -> d.name())
                .sorted()
                .toList();

        assertEquals(List.of("list_agents", "run_agent", "stop_agent", "wait_agents"), names,
                "hidden 池应恰好含四派发工具");
        // schema 仍完整生成(hidden 只是注册侧不进请求,定义本体不动)
        assertTrue(provider.createHiddenTools(ctx).stream()
                .allMatch(tc -> tc.getToolDefinition().inputSchema() != null
                        && !tc.getToolDefinition().inputSchema().isBlank()));
    }

    @Test
    void createToolsNoLongerExposesDispatchTools() {
        SubAgentToolsProvider provider = new SubAgentToolsProvider(new SubAgentManager());

        assertTrue(provider.createTools(mock(ToolContext.class)).isEmpty(),
                "四工具已迁 hidden 池,常规池必须为空");
    }
}
