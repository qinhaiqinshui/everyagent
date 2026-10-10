package dev.everyagent.plugin.api.task;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.event.EventLogReader;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;

import java.nio.file.Path;

/**
 * 任务运行时接口 —— 插件用此替代对 worker {@code TaskEntry} 的直接引用。
 *
 * <p>实现 {@link ExecContext} 统一执行上下文：{@code subjectId()} / {@code emitter()}
 * / {@code dataDir()} 槽位以 default 桥接方法映射到任务域成员（taskId / events /
 * taskDir）；{@code workspaceRoot()} / {@code workspaceId()} / {@code snapshot()} /
 * {@code agents()} / {@code metadata()} / {@code terminal()} 与 {@code ExecContext}
 * 抽象签名重合，由实现类提供。worker 的 {@code TaskEntry} 实现此接口；
 * 需要完整任务运行时数据的内置组件可直接依赖 {@code TaskEntry} 具体类。
 *
 * <p>任务域私有成员保留在本接口：taskId / status / taskDir（原 permission 包
 * {@code TaskInfo} 的三成员，该接口已随 S3 授权链收编退役删除）、mainAgentId / log、
 * 时间戳与运行时操作。{@code agents()} 已上移 {@code ExecContext}
 * （语义 = 主体活动 agent 注册表），本接口不再声明。
 *
 * <p>读写混合接口：主体内各插件派生 agent（子 agent / 审议 agent 等）经
 * {@code agents()}（{@code ExecContext} 槽位）put / get / values / containsKey。
 * 只暴露插件实际调用的方法，不做过度设计。
 */
public interface TaskRuntime extends ExecContext {

    // ---- 任务域只读成员(构造时确定或任务级定死) ----

    /** 任务 ID。 */
    String taskId();

    /**
     * 任务状态（wire 字符串：{@code "created"}/{@code "running"}/{@code "waiting-user"}
     * /{@code "done"}/{@code "failed"}/{@code "cancelled"}）。
     */
    String status();

    /** 任务数据目录路径（workspaces/&lt;workspaceId&gt;/tasks/&lt;taskId&gt;/）。 */
    Path taskDir();

    // ---- ExecContext 槽位桥接(映射任务域成员) ----

    /** 执行主体 ID：任务域即 {@link #taskId()}（未来 workflow 域映射 workflowId）。 */
    @Override
    default String subjectId() {
        return taskId();
    }

    /** 任务级事件口：桥接 {@link #events()}。 */
    @Override
    default EventEmitter emitter() {
        return events();
    }

    /** 数据目录：桥接 {@link #taskDir()}。 */
    @Override
    default Path dataDir() {
        return taskDir();
    }

    /** 工作区根路径(挂靠关系，任务数据存 workspaces/&lt;workspaceId&gt;/tasks 不随之迁移)。 */
    String workspaceRoot();

    /** 工作区稳定 ID(创建时定死，纠正路径/再运行均不变)。 */
    String workspaceId();

    /** 主 agent 稳定 ID(任务生命周期内不变，即 &lt;mainAgentId&gt;.jsonl 文件名)。 */
    String mainAgentId();

    /** 模型配置快照(任务创建时定死，配置后续变更不影响运行中任务)。 */
    ModelConfig snapshot();

    /** 事件发射器(发射时自动填 agentId)。 */
    EventEmitter events();

    /** 主 agent 当前实体(runTask 建好后置；运行期可能为 null)。 */
    AgentContext main();

    /** 事件日志只读接口(供插件读取任务事件流)。 */
    EventLogReader log();

    // ---- 时间戳 ----

    /** 任务开始时间戳(未开始返回 0)。 */
    long startedAt();

    /** 任务结束时间戳(未结束返回 0)。 */
    long endedAt();

    // ---- 运行时操作（编辑重发 / 队列插件用）----

    /** 更新最近活跃时间戳（队列 RPC 改动后调用）。 */
    void touch();

    /**
     * 任务运行时摘要 JSON（供 task.updated 广播 payload）。
     * 与磁盘 meta.json 的 summary 同形（队列插件用此拼装 pendingInputs 广播）。
     */
    tools.jackson.databind.node.ObjectNode summaryJson();

    /**
     * 截断内存事件日志：移除所有 seq &gt;= targetSeq 的记录，lastSeq 回退到 targetSeq - 1。
     * 用于编辑重发热路径——磁盘已由 TaskStoreService.truncateAfterSeq 截断，
     * 内存日志同步截断，防止 task.poll 从内存尾部返回已截断的旧事件。
     */
    void truncateLogAfter(long targetSeq);
}
