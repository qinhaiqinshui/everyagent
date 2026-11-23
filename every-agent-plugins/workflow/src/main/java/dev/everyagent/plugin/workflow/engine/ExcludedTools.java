package dev.everyagent.plugin.workflow.engine;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;

/**
 * REMOVE 模式按名剔除的工具名占位类（SubAgentToolNames 同款手法）。
 *
 * <p>工作流 agent 的工具面 = 全局聚合剔除两组：
 * <ul>
 *   <li><b>agent 族四工具</b>（run_agent/list_agents/wait_agents/stop_agent）——子 agent
 *       收口等待靠任务洋葱节点，工作流侧无对应收口点，节点 run 返回时子 agent 可能仍在跑
 *       （生命周期逃逸），v1 结构上禁递归派生；</li>
 *   <li><b>workflow CRUD 五工具</b>——防 AI 节点自我编辑工作流（与
 *       WorkflowToolProvider.appliesTo 的域隔离互为双保险：hidden 应急回退开关
 *       worker.tools.hidden-enabled=false 时五工具会以常规形态出现，REMOVE 兜底剔除）。</li>
 * </ul>
 */
final class ExcludedTools {

    private ExcludedTools() {
    }

    /** @Tool 方法只提供同名占位，经 {@link ToolCallbacks#from} 生成名称匹配用回调。 */
    private static final class Names {

        @Tool(description = "")
        public String run_agent() {
            return "";
        }

        @Tool(description = "")
        public String list_agents() {
            return "";
        }

        @Tool(description = "")
        public String wait_agents() {
            return "";
        }

        @Tool(description = "")
        public String stop_agent() {
            return "";
        }

        @Tool(description = "")
        public String workflow_create() {
            return "";
        }

        @Tool(description = "")
        public String workflow_get() {
            return "";
        }

        @Tool(description = "")
        public String workflow_update() {
            return "";
        }

        @Tool(description = "")
        public String workflow_delete() {
            return "";
        }

        @Tool(description = "")
        public String workflow_list() {
            return "";
        }
    }

    /** 剔除表（每次调用新建——ToolCallback 是一次性装配期对象，不共享防误改）。 */
    static List<ToolCallback> toRemove() {
        return List.of(ToolCallbacks.from(new Names()));
    }

    /** 剔除的九个工具名（诊断/测试断言用）。 */
    static final List<String> NAMES = List.of(
            "run_agent", "list_agents", "wait_agents", "stop_agent",
            "workflow_create", "workflow_get", "workflow_update",
            "workflow_delete", "workflow_list");
}
