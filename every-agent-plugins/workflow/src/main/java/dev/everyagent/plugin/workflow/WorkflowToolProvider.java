package dev.everyagent.plugin.workflow;

import java.util.List;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.ToolProvider;
import dev.everyagent.plugin.workflow.engine.WorkflowEngine;
import dev.everyagent.plugin.workflow.engine.WorkflowSubject;
import dev.everyagent.plugin.workflow.store.WorkflowStore;

/**
 * workflow 工具提供者：定义 CRUD 五件 + 运行/查询/重开六件（workflow_run +
 * workflow_rerun + instance_status/log/events/cancel）一律走 <b>hidden 池</b>（createHiddenTools）
 * ——不进请求 tool 定义，模型经「工作流编排」skill 文档的工具参考认知后按名调用。
 *
 * <p><b>零 per-run 捕获</b>：本 provider 只持 store/engine（插件级单例）；
 * per-run 的 ToolContext 在 {@link #createHiddenTools} 调用时传入工具 POJO
 * （随工具回调同生命周期，契约允许的直接持有，同 subagent 先例）。
 *
 * <p><b>域隔离</b>：{@link #appliesTo} 排除工作流执行主体（WorkflowRuntime）——
 * 工作流自己的 agent 不携带 workflow 工具，防自我编辑/递归运行。开关语义
 * （worker.tools.hidden-enabled=false 应急回退）在 worker 聚合层统一生效，
 * 本 provider 不感知。
 */
public final class WorkflowToolProvider implements ToolProvider {

    private final WorkflowStore store;
    private final WorkflowEngine engine;

    public WorkflowToolProvider(WorkflowStore store, WorkflowEngine engine) {
        this.store = store;
        this.engine = engine;
    }

    @Override
    public String pluginId() {
        return "workflow";
    }

    @Override
    public List<ToolCallback> createTools(ToolContext ctx) {
        return List.of();
    }

    @Override
    public List<ToolCallback> createHiddenTools(ToolContext ctx) {
        return List.of(ToolCallbacks.from(new WorkflowTools(store, engine, ctx)));
    }

    @Override
    public boolean appliesTo(ToolContext ctx) {
        return !WorkflowSubject.isWorkflowSubject(ctx);
    }
}
