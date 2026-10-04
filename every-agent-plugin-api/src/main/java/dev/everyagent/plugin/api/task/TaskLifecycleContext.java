package dev.everyagent.plugin.api.task;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 任务生命周期上下文（节点的读写面）。
 * <p>外部插件只见此窄接口；内置节点在 worker 侧拿完整 TaskEntry（内部通道）。
 *
 * <p>实现 {@link ExecContext} 统一执行上下文：{@code subjectId()} / {@code emitter()}
 * / {@code dataDir()} / {@code snapshot()} / {@code agentFactory()} / {@code terminal()}
 * / {@code interaction()} / {@code agents()} 槽位以 default 桥接方法委托
 * {@link #taskRuntime()}（={@link #taskInfo()}）；{@code workspaceRoot()} /
 * {@code workspaceId()} / {@code metadata()} 与 {@code ExecContext} 抽象签名重合，
 * 由本接口自有声明/default 提供。早期 RPC 阶段任务尚未创建时 {@code taskRuntime()}
 * 返回 null，桥接方法相应返回 null / 空值：{@code emitter/dataDir/snapshot/
 * agentFactory/interaction} 返回 null，{@code metadata/agents} 返回 null/{@code Map.of()}，
 * {@code terminal()} 返回 false。运行期（{@code taskRuntime()} 非空）语义同
 * {@link TaskRuntime}，桥接委托至其实现。任务域私有成员（{@code taskId/status/
 * title/taskLock/startedAt/agentStatus/input/rawContent/runParams/rpcContext} 及 setter、
 * {@code taskInfo}）保留在本接口，不进 {@code ExecContext}。
 */
public interface TaskLifecycleContext extends ExecContext {

    /** 任务 ID。 */
    String taskId();

    /** 任务标题。 */
    String title();

    /** 工作区根路径。 */
    @Override
    String workspaceRoot();

    /** 工作区稳定 ID。 */
    @Override
    String workspaceId();

    /** 主 agent 稳定 ID。 */
    String mainAgentId();

    /** 当前任务状态（wire 字符串："created"/"running"/"waiting-user"/"done"/"failed"/"cancelled"）。 */
    String status();

    /** 任务运行时（TaskEntry 的窄面：可访问任务级持久化 metadata 及运行时能力；早期节点尚未创建任务时为 null）。 */
    TaskRuntime taskInfo();

    /** 任务同步原语（锁内节点上行段自行 synchronized）。 */
    Object taskLock();

    /** 任务开始时间戳（由 status.start 节点写入）。 */
    long startedAt();

    /** 设置任务开始时间戳。 */
    void startedAt(long ms);

    /**
     * 注入 usage 实时广播钩子（由 task.wires 节点调用）。
     * 钩子被 WorkerToolEventAdvisor 在每轮 usage 后触发。
     */
    void onUsageBroadcast(Runnable hook);

    /** task.run 的用户输入文本（首条输入，main.agent 节点消费）。 */
    String input();

    /** task.run 的原始内容（含 opaque token，供前端回放还原）。 */
    String rawContent();

    /** task.run 的通用插件参数容器（核心不解释，插件自行消费）。 */
    java.util.Map<String, Object> runParams();

    /**
     * 任务级持久化数据（便捷方法，委托给 {@link #taskInfo()}；同时满足
     * {@link ExecContext#metadata()} 契约：运行期委托 {@link TaskRuntime#metadata()}
     * 返回非空 Map；早期 RPC 阶段 taskInfo() 为 null 时返回 null——既有消费者
     * （FileReferenceProcessNode）已容忍 null，保持该语义不变）。
     * @return 任务运行时的 metadata Map，或 taskInfo() 为 null 时返回 null
     */
    @Override
    default java.util.Map<String, Object> metadata() {
        TaskRuntime info = taskInfo();
        return info != null ? info.metadata() : null;
    }

    /** RPC 应答器（链节点直接调 ctx.ok 返回前端；仅 RPC 线程阶段有效，虚拟线程阶段为 null）。 */
    Object rpcContext();

    // ---- 可写 setter（队列循环节点在 poll 后把队列项数据设到当前 ctx）----

    /** 设置用户输入文本（队列项 poll 后覆盖当前 ctx 的 input）。 */
    void input(String input);

    /** 设置原始内容（队列项 poll 后覆盖当前 ctx 的 rawContent）。 */
    void rawContent(String rawContent);

    /**
     * 设置通用插件参数容器（队列项 poll 后覆盖当前 ctx 的 runParams；null 忽略）。
     * task.run 的 metadata 参数是一次性插件参数（如 editSeq/insert），经此传递，不落盘。
     */
    void runParams(java.util.Map<String, Object> runParams);

    /** 设置 metadata（队列项 poll 后覆盖当前 ctx 的 metadata；null 或 taskEntry 为 null 时忽略）。 */
    void metadata(java.util.Map<String, Object> metadata);

    // ---- 运行时访问 ----

    /**
     * 获取任务运行时（与 {@link #taskInfo()} 同一对象的语义别名，保留既有插件调用点）。
     * <p>插件可访问 agents / events / log 等运行时能力。
     * taskEntry 尚未创建时返回 null（RPC 阶段早期节点）。
     */
    default TaskRuntime taskRuntime() {
        return taskInfo();
    }

    // ---- ExecContext 槽位桥接（委托 taskRuntime()；早期 taskRuntime() 为 null 时回退 null/空）----

    /** 执行主体 ID：任务域即 {@link #taskId()}（早期节点由 TaskIdGenerateNode 设置，先于 taskRuntime 存在）。 */
    @Override
    default String subjectId() {
        return taskId();
    }

    /** 任务级事件口：桥接 {@code taskRuntime().emitter()}；早期为 null。 */
    @Override
    default EventEmitter emitter() {
        TaskRuntime r = taskRuntime();
        return r != null ? r.emitter() : null;
    }

    /** 数据目录：桥接 {@code taskRuntime().dataDir()}；早期为 null。 */
    @Override
    default Path dataDir() {
        TaskRuntime r = taskRuntime();
        return r != null ? r.dataDir() : null;
    }

    /** 模型配置快照：桥接 {@code taskRuntime().snapshot()}；早期为 null。 */
    @Override
    default ModelConfig snapshot() {
        TaskRuntime r = taskRuntime();
        return r != null ? r.snapshot() : null;
    }

    /** Agent 工厂：桥接 {@code taskRuntime().agentFactory()}；早期为 null。 */
    @Override
    default AgentFactory agentFactory() {
        TaskRuntime r = taskRuntime();
        return r != null ? r.agentFactory() : null;
    }

    /** 主体是否已收口：桥接 {@code taskRuntime().terminal()}；早期返回 false。 */
    @Override
    default boolean terminal() {
        TaskRuntime r = taskRuntime();
        return r != null && r.terminal();
    }

    /** 用户交互口：桥接 {@code taskRuntime().interaction()}；早期为 null。 */
    @Override
    default InteractionService interaction() {
        TaskRuntime r = taskRuntime();
        return r != null ? r.interaction() : null;
    }

    /** 活动 agent 注册表：桥接 {@code taskRuntime().agents()}；早期返回空 Map。 */
    @Override
    default Map<String, AgentContext> agents() {
        TaskRuntime r = taskRuntime();
        return r != null ? r.agents() : Map.of();
    }
}
