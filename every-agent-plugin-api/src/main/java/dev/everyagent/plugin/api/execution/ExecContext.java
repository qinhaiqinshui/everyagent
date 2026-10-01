package dev.everyagent.plugin.api.execution;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;

import java.nio.file.Path;
import java.util.Map;

/**
 * 统一执行上下文 —— 执行主体（task 或未来 workflow）对横切层暴露的域中性数据面。
 *
 * <p>取代原穿透 agent 层的无类型黑盒 map（{@code properties: {taskEntry, taskId,
 * workspaceRoot, configId}}）：上层（task 层）构造本接口实例沿
 * 「agent 层 → 工具执行链 → 授权链」逐层往下传；advisor / 工具 / 授权链 / 子 agent /
 * 审议 agent 只见本接口，不见 {@code TaskEntry}/{@code TaskRuntime}
 * 任务域类型。未来工作流层实现 {@code WorkflowRuntime implements ExecContext} 即可
 * 复用全部横切基础设施，横切层零改动。
 *
 * <p>本接口只收编横切消费者实际需要的域中性值与端口（emitter / agentFactory /
 * interaction 三个预绑定端口与 {@code AgentContext.emitter()} 的 agentId 包装同构）；
 * 任务域私有成员（status 完整状态 / touch / fileChanges 系列槽位等）留在
 * {@code TaskRuntime}，不进本接口。
 */
public interface ExecContext {

    /** 执行主体 ID（授权状态分区键/审计字段；今天=taskId，未来=workflowId）。 */
    String subjectId();

    /** 工作区根路径。 */
    String workspaceRoot();

    /** 工作区稳定 ID。 */
    String workspaceId();

    /** 完整模型配置快照（configId/params/模型参数；消费者经 snapshot().configId() 取 ID）。 */
    ModelConfig snapshot();

    /**
     * 已绑定本主体的任务级事件口（trace/审计/agent.started 等落此处）。
     * 注意：与 AgentContext.emitter()（agent 层 agentId 包装）是两层，各自保留。
     */
    EventEmitter emitter();

    /**
     * 已绑定本主体的 Agent 工厂（静态代理）；create(agentId) 单参创建，
     * create(agentId, configIdOverride) 覆盖模型。
     */
    AgentFactory agentFactory();

    /** 主体策略标记（unattended / ai-review 等；随 meta.json 落盘的持久数据；授权链节点判定用）。 */
    Map<String, Object> metadata();

    /** 数据目录（grants.json 等落盘；今天=workspaces/&lt;wsId&gt;/tasks/&lt;taskId&gt;/）。 */
    Path dataDir();

    /** 主体是否已收口（leak-guard：终态后不再发射事件）。 */
    boolean terminal();

    /**
     * 已绑定本主体的用户交互口（静态代理：ask 的 context map 自动填 subjectId
     * ——替代 HumanAuthorizationHandler / ImageReferenceHandler / AskUserTool 手动
     * 组装 Map.of("taskId",...)；agentId 由调用方按需经 context 参数补充）。
     * 与 emitter()/agentFactory() 同为预绑定端口。
     */
    InteractionService interaction();

    /**
     * 本主体的活动 agent 注册表（可读写 Map：put/get/values/containsKey）。
     * 收纳主体上下文内创建的全部 agent：主 agent + 各插件派生的 agent
     * （subagent 的子 agent、ai-review 的审议 agent 等，由创建方插件注册）。
     * 无子 agent 是正常形态（主 agent 独立完成全部工作）。
     */
    Map<String, AgentContext> agents();
}
