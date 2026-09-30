package dev.everyagent.plugin.api.task;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.event.EventLogReader;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.permission.TaskInfo;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 任务运行时接口 —— 插件用此替代对 worker {@code TaskEntry} 的直接引用。
 *
 * <p>继承 {@link TaskInfo} 的基础只读方法(taskId / status / terminal / metadata / taskDir)，
 * 额外暴露插件运行时实际需要的读写成员。worker 的 {@code TaskEntry} 实现此接口；
 * 需要完整任务运行时数据的内置组件可直接依赖 {@code TaskEntry} 具体类。
 *
 * <p>读写混合接口：subagent 插件需要写 {@link #agents()} Map(put / get / values / containsKey)，
 * file-change 插件需要读写 fileChanges 系列槽位，task-edit-resend 插件需要清空这些槽位。
 * 只暴露插件实际调用的方法，不做过度设计。
 */
public interface TaskRuntime extends TaskInfo {

    // ---- 只读字段(构造时确定或任务级定死) ----

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

    /**
     * 主 + 子 agent 集合(可读写 Map：subagent 需 put / get / values / containsKey)。
     * key = agentId，value = {@link AgentContext}。
     */
    Map<String, AgentContext> agents();

    /** 主 agent 当前实体(runTask 建好后置；运行期可能为 null)。 */
    AgentContext main();

    /** 事件日志只读接口(供插件读取任务事件流)。 */
    EventLogReader log();

    // ---- fileChanges 回合槽(file-change / task-edit-resend 插件读写) ----

    /** 当前回合文件改动收集器(无则 null)。 */
    FileChangesCollector fileChanges();

    /** 设置当前回合文件改动收集器(null = 清空)。 */
    void fileChanges(FileChangesCollector collector);

    /** 本轮文件改动轻量摘要(无则 null)。 */
    JsonNode fileChangesLight();

    /** 设置本轮文件改动轻量摘要(null = 清空)。 */
    void fileChangesLight(JsonNode light);

    /** 本轮文件改动全文(无则 null)。 */
    JsonNode fileChangesFull();

    /** 设置本轮文件改动全文(null = 清空)。 */
    void fileChangesFull(JsonNode full);

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
