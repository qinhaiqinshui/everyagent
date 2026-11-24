package dev.everyagent.plugin.workflow.engine;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.agent.ConversationArchive;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.interaction.AskResult;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.workflow.model.InstanceStatus;
import dev.everyagent.plugin.workflow.store.WorkflowStore;
import tools.jackson.databind.node.ObjectNode;

/**
 * 工作流执行主体（实例级 ExecContext）——非任务主体复用 agent 层执行链的落点
 * （架构 §7.20.7）。subjectId=instanceId；workspaceRoot/Id 由 workspaceId 经注册表解析；
 * dataDir=实例目录；terminal()=实例状态是否终态。
 *
 * <p><b>任务嵌入（§7.20.8）</b>：AI 节点会话/呈现<b>单写 origin 任务域</b>——引擎运行
 * 入口接受任务 ExecContext 绑定（{@link #taskExec()}，任务内运行时非 null），AI 节点
 * 与督导经它的 agentFactory()/emitter()/conversationArchive() 装配与发射；实例目录只记
 * 节点级三样（meta/events/runs），不再持 agents/ 职责。
 *
 * <p><b>emitter 单路由</b>（自实现 {@link EventEmitter}）：实例域事件
 * （wf./node./supervisor. 前缀）→ events.jsonl + 内存尾部队列；其余事件
 * （agent 域 user.message、message、tool.result、usage 与 delta、thinking 瞬态，以及
 * agent、error、cancelled、task 族）不入工作流持久面——agent 域一律走 origin 任务的事件口。
 * emit 内异常一律自吞（不得抛出打断任务线程——与 AgentEntity.safeEmit 同纪律）。
 *
 * <p><b>WorkflowSubject 标记</b>：实现标记接口（通道①）且构造时在 metadata 写
 * {@code MARKER_KEY=true}（通道②）——WorkflowToolProvider 据此剔除 workflow CRUD 工具。
 */
public final class WorkflowRuntime implements ExecContext, WorkflowSubject {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRuntime.class);

    private final WorkflowStore store;
    private final String workspaceId;
    private final String instanceId;
    private final Path instanceDir;
    private final String workspaceRoot;

    /**
     * origin 任务绑定（引擎运行入口传入；RPC 直启/独立运行为 null）。
     * AI 节点/督导的 agentFactory、emitter、conversationArchive 全部经它——
     * 会话档案因此从 {@code workspaces/<wsId>/tasks/<taskId>/<agentId>.jsonl} 重建。
     */
    private final ExecContext taskExec;

    /** 主体活动 agent 注册表（ExecContext 契约槽位；AI 节点 agent 注册进 origin 任务域，此处恒空）。 */
    private final ConcurrentHashMap<String, AgentContext> agents = new ConcurrentHashMap<>();
    /** 可变 CHM：read_file 图片附件路径等横切层会写入；构造时写域标记。 */
    private final ConcurrentHashMap<String, Object> metadata = new ConcurrentHashMap<>();

    private volatile InstanceStatus status;

    /** 实例域事件的发射口（emit 内自吞异常）。 */
    private final EventEmitter emitter = this::route;

    /** 拒绝式交互桩：ask 一律 cancelled（v1 工作流 agent 无交互式授权弹窗，危险操作自动拒）。 */
    private static final InteractionService REJECTING_INTERACTION = new InteractionService() {
        @Override
        public AskResult ask(List<dev.everyagent.plugin.api.interaction.AskQuestion> questions,
                long timeoutMs, Map<String, String> context) {
            return new AskResult("cancelled", null);
        }

        @Override
        public void askAsync(List<dev.everyagent.plugin.api.interaction.AskQuestion> questions,
                long timeoutMs, Map<String, String> context,
                java.util.function.Consumer<AskResult> callback) {
            callback.accept(new AskResult("cancelled", null));
        }

        @Override
        public boolean hasPendingFor(String taskId, String agentId) {
            return false;
        }
    };

    /** 运行时缺省模型快照（configId=null → 装配时回退 worker 默认配置）。 */
    private final ModelConfig defaultSnapshot;

    WorkflowRuntime(WorkflowStore store, String workspaceId,
            String instanceId, Path instanceDir, String workspaceRoot, ExecContext taskExec) {
        this.store = store;
        this.workspaceId = workspaceId;
        this.instanceId = instanceId;
        this.instanceDir = instanceDir;
        this.workspaceRoot = workspaceRoot;
        this.taskExec = taskExec;
        this.status = InstanceStatus.CREATED;
        this.metadata.put(WorkflowSubject.MARKER_KEY, Boolean.TRUE);
        this.defaultSnapshot = new ModelConfig(null, null, null, null, null);
    }

    /** origin 任务绑定（引擎运行入口传入；无绑定为 null——AI 节点运行将被拒绝）。 */
    ExecContext taskExec() {
        return taskExec;
    }

    // ══════════════════════════ 状态 ══════════════════════════

    /** 实例状态迁移（引擎调用；waiting-user 挂起/恢复与终态都经此反映到 terminal()）。 */
    void status(InstanceStatus status) {
        this.status = status;
    }

    InstanceStatus status() {
        return status;
    }

    // ══════════════════════════ ExecContext 槽位（实例级） ══════════════════════════

    @Override
    public String subjectId() {
        return instanceId;
    }

    @Override
    public String workspaceRoot() {
        return workspaceRoot;
    }

    @Override
    public String workspaceId() {
        return workspaceId;
    }

    @Override
    public ModelConfig snapshot() {
        return defaultSnapshot;
    }

    @Override
    public EventEmitter emitter() {
        return emitter;
    }

    /**
     * 恒 null：agent 装配一律经 origin 任务的 {@code ExecContext.agentFactory()}
     * （AI 节点 = 任务里的子 agent，事件单写任务域，§7.20.8）——工作流主体自身不再
     * 装配 agent。槽位保留以满足 ExecContext 契约。
     */
    @Override
    public AgentFactory agentFactory() {
        return null;
    }

    @Override
    public Map<String, Object> metadata() {
        return metadata;
    }

    @Override
    public Path dataDir() {
        return instanceDir;
    }

    @Override
    public boolean terminal() {
        return status != null && status.terminal();
    }

    @Override
    public InteractionService interaction() {
        return REJECTING_INTERACTION;
    }

    /**
     * 会话档案口：委托 origin 任务（任务域档案 tasks/&lt;taskId&gt;/&lt;agentId&gt;.jsonl，
     * 与子 agent 续跑同一条重建路径）；未绑定时返回 null（调用方按无历史处理）。
     */
    @Override
    public ConversationArchive conversationArchive() {
        return taskExec == null ? null : taskExec.conversationArchive();
    }

    @Override
    public Map<String, AgentContext> agents() {
        return agents;
    }

    // ══════════════════════════ emitter 单路由 ══════════════════════════

    /**
     * 单路由实现（异常自吞，永不抛）：实例域（wf./node./supervisor. 前缀）进 events.jsonl
     * （store 内单 writer 锁 + 内存尾部）；其余事件不入工作流持久面——agent 域事件
     * （user.message/message/tool.result/usage 与瞬态）由 AI 节点经 origin 任务的
     * 事件口单写任务域，不经本 emitter。
     */
    private long route(EmitEvent e) {
        try {
            if (e == null || e.kind() == null || e.kind().isEmpty()) {
                return e == null ? 0L : e.id();
            }
            String kind = e.kind();
            if (kind.startsWith("wf.") || kind.startsWith("node.") || kind.startsWith("supervisor.")) {
                ObjectNode payload = AgentSessions.projectPayload(e);
                store.appendEvent(workspaceId, instanceId, kind, payload);
            }
            return e.id();
        } catch (RuntimeException ex) {
            log.warn("[workflow] 事件发射失败(自吞,不打断任务线程) instance={} kind={} agent={}",
                    instanceId, e == null ? null : e.kind(), e == null ? null : e.agentId(), ex);
            return e == null ? 0L : e.id();
        }
    }
}
