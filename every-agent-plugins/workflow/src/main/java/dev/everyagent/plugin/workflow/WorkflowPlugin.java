package dev.everyagent.plugin.workflow;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.workflow.store.WorkflowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * workflow 插件入口。
 *
 * <p>当前为<b>骨架阶段</b>（步骤 4）：模型/校验/存储层 + hidden 五工具 + 激活扫描。
 * 引擎（WorkflowRuntime/调度器/节点执行器）与 RPC 面由后续步骤补齐——本类 activate
 * 只做三件事：
 * <ol>
 *   <li>构造 {@link WorkflowStore} 并执行 activate 扫描（建内存索引 + 非终态实例标
 *       failed("worker 重启中断")）；</li>
 *   <li>注册 {@link WorkflowToolProvider}（workflow_create/get/update/delete/list 五个
 *       hidden 工具；技能包由 plugin.json 声明 {@code skills} 目录物化，不走代码注册）；</li>
 *   <li>（预留）引擎/RPC 注册位。</li>
 * </ol>
 */
public class WorkflowPlugin implements EveryAgentPlugin {

    private static final Logger log = LoggerFactory.getLogger(WorkflowPlugin.class);

    @Override
    public String id() {
        return "workflow";
    }

    @Override
    public void activate(WorkerPluginContext ctx) {
        // 存储层：systemDir/workspaces/<wsId>/workflows/（与 tasks/ 平级）；
        // workspaceId→root 经注册表解析（redirect 安全）
        WorkflowStore store = new WorkflowStore(ctx.services().workspaces());

        // 激活扫描：全扫建索引 + worker 重启中断的非终态实例标 failed
        WorkflowStore.ScanReport report = store.scanOnActivate();
        log.info("[workflow] 已激活：扫描 {} 个工作区，索引 {} 定义 / {} 实例，重启中断标 failed {} 个",
                report.workspaces(), report.definitions(), report.instances(), report.interrupted());

        // hidden 五工具（模型经 skill/workflow-tools/skill.md 认知后按名调用）
        ctx.registerToolProvider(new WorkflowToolProvider(store));

        // 预留：步骤 5 引擎（WorkflowRuntime/调度器）、步骤 6 RPC（workflow.* 14 方法）
    }
}
