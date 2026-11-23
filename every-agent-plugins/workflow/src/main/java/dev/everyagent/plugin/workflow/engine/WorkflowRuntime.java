package dev.everyagent.plugin.workflow.engine;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.agent.ConversationArchive;
import dev.everyagent.plugin.api.event.Channels;
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.plugin.api.event.StreamEmitter;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.interaction.AskResult;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.workflow.model.AgentEventLine;
import dev.everyagent.plugin.workflow.model.InstanceStatus;
import dev.everyagent.plugin.workflow.store.WorkflowStore;
import tools.jackson.databind.node.ObjectNode;

/**
 * 工作流执行主体（实例级 ExecContext）——非任务主体复用 agent 层执行链的落点
 * （架构 §7.20.7）。subjectId=instanceId；workspaceRoot/Id 由 workspaceId 经注册表解析；
 * dataDir=实例目录；terminal()=实例状态是否终态。
 *
 * <p><b>per-node 视图</b>：{@link NodeExecView}（内部类）每节点执行一个轻量视图——
 * snapshot() 返回该节点解析出的 {@link ModelConfig}（agent 声明默认 + 节点覆盖），
 * 不共享可变 → 并行安全；emitter/dataDir/agents()/metadata()/interaction()/terminal()
 * 全部委托实例级共享对象。
 *
 * <p><b>emitter 双路由</b>（自实现 {@link EventEmitter}）：
 * <ul>
 *   <li>agent 域事件（user.message/message/tool.result/usage，按事件 agentId 归户）
 *       → 追加进 {@code agents/<name>.jsonl}（行形态照抄 task 域 wire 映射，
 *       {@link AgentSessions#projectPayload}；行内 agentId={@code <agentName>#<instanceId>}）；</li>
 *   <li>实例域事件（wf./node./supervisor. 前缀）→ events.jsonl + 内存尾部队列；</li>
 *   <li>AI 节点经 {@link #view(String, String)} 的 node 绑定视图装配时，agent 域
 *       delta/thinking 增量以 {@link #NODE_DELTA} 瞬态扇出实例流频道（不落盘、
 *       不进内存尾部队列，架构 §7.13）；</li>
 *   <li>其余（tool.started 瞬态、agent./error/cancelled/task.*、未经 node 视图包装的
 *       delta/thinking）不入工作流持久面；匿名 AI 节点
 *       （agentId 不以 {@code #<instanceId>} 结尾）不落 agents/。</li>
 * </ul>
 * emit 内异常一律自吞（不得抛出打断任务线程——与 AgentEntity.safeEmit 同纪律）。
 *
 * <p><b>WorkflowSubject 标记</b>：实现标记接口（通道①）且构造时在 metadata 写
 * {@code MARKER_KEY=true}（通道②）——WorkflowToolProvider 据此剔除 workflow CRUD 工具。
 */
public final class WorkflowRuntime implements ExecContext, WorkflowSubject {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRuntime.class);

    /**
     * AI 节点流式瞬态 wire 事件名（架构 §7.13）：AI 节点运行期间 agent 域 delta/thinking
     * 增量经实例流频道（Channels.workflowStream）广播扇出，payload
     * {@code {nodeId, agentId, kind:'thinking'|'text', content}}。瞬态：不进 events.jsonl、
     * 不进实例内存尾部队列（补拉/重放天然不含即为正确）；seq 雪花占号、磁盘回放有洞合法。
     */
    public static final String NODE_DELTA = "node.delta";

    private final WorkflowStore store;
    private final WorkerServices services;
    private final String workspaceId;
    private final String instanceId;
    private final Path instanceDir;
    private final String workspaceRoot;

    /** 值必须是工厂产物 AgentEntity（AgentBuilder.build() 的 put 机制）。 */
    private final ConcurrentHashMap<String, AgentContext> agents = new ConcurrentHashMap<>();
    /** 可变 CHM：read_file 图片附件路径等横切层会写入；构造时写域标记。 */
    private final ConcurrentHashMap<String, Object> metadata = new ConcurrentHashMap<>();
    /** agents/&lt;name&gt;.jsonl 追加串行化（同名文件的最后防线；语义串行靠引擎 fair 锁）。 */
    private final ConcurrentHashMap<String, Object> agentFileLocks = new ConcurrentHashMap<>();

    private volatile InstanceStatus status;

    /** 实例域事件的双路由发射口（emit 内自吞异常）。 */
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

    /** 会话档案口：读 agents/&lt;name&gt;.jsonl 按 ConversationLoader 同款谓词重建。 */
    private final ConversationArchive archive;

    /** 运行时缺省模型快照（configId=null → 装配时回退 worker 默认配置）。 */
    private final ModelConfig defaultSnapshot;

    private volatile AgentFactory boundFactory;

    WorkflowRuntime(WorkflowStore store, WorkerServices services, String workspaceId,
            String instanceId, Path instanceDir, String workspaceRoot) {
        this.store = store;
        this.services = services;
        this.workspaceId = workspaceId;
        this.instanceId = instanceId;
        this.instanceDir = instanceDir;
        this.workspaceRoot = workspaceRoot;
        this.status = InstanceStatus.CREATED;
        this.metadata.put(WorkflowSubject.MARKER_KEY, Boolean.TRUE);
        this.defaultSnapshot = new ModelConfig(null, null, null, null, null);
        this.archive = agentId -> {
            try {
                String name = agentNameOf(agentId);
                if (name == null) {
                    return List.of();
                }
                return AgentSessions.rebuild(store.readAgentEvents(workspaceId, instanceId, name));
            } catch (RuntimeException e) {
                log.warn("[workflow] 会话档案读取失败(按无历史处理) agent={} instance={}",
                        agentId, instanceId, e);
                return List.of();
            }
        };
    }

    // ══════════════════════════ 状态 ══════════════════════════

    /** 实例状态迁移（引擎调用；waiting-user 挂起/恢复与终态都经此反映到 terminal()）。 */
    void status(InstanceStatus status) {
        this.status = status;
    }

    InstanceStatus status() {
        return status;
    }

    // ══════════════════════════ per-node 视图 ══════════════════════════

    /**
     * 构造节点执行视图：snapshot()=节点解析出的模型配置（节点覆盖 configId →
     * services().modelConfigs() 查表；查不到/未声明给裸 configId 占位——参数缺失时
     * contextWindowTokens 等消费方走默认回退）。
     */
    NodeExecView view(String effectiveConfigId) {
        return new NodeExecView(resolveModelConfig(effectiveConfigId), effectiveConfigId, null);
    }

    /**
     * 同 {@link #view(String)}，但携带节点上下文：AI 节点装配用——视图的
     * {@code emitter()} 为 node 绑定包装（agent 域 delta/thinking 增量转
     * {@link #NODE_DELTA} 瞬态扇出实例流频道，架构 §7.13；其余事件转发实例级双路由，
     * 行为与无包装视图完全一致）。
     */
    NodeExecView view(String effectiveConfigId, String nodeId) {
        return new NodeExecView(resolveModelConfig(effectiveConfigId), effectiveConfigId, nodeId);
    }

    private ModelConfig resolveModelConfig(String configId) {
        if (configId == null || configId.isBlank()) {
            return defaultSnapshot;
        }
        try {
            List<ModelConfig> all = services.modelConfigs();
            if (all != null) {
                for (ModelConfig c : all) {
                    if (c != null && configId.equals(c.configId())) {
                        return c;
                    }
                }
            }
        } catch (RuntimeException e) {
            log.debug("[workflow] 模型配置表读取失败,回退裸快照 configId={}", configId, e);
        }
        return new ModelConfig(configId, null, null, null, null);
    }

    /**
     * 节点执行视图（「Agent 层复用设计」B 节 ②）：每节点 run 一个，snapshot 不共享可变。
     * 其余槽位全部委托实例级共享对象（emitter/落盘/注册表/交互口/终态判定）。
     */
    public final class NodeExecView implements ExecContext, WorkflowSubject {

        private final ModelConfig snapshot;
        private final String configId;
        /** 节点绑定标记（AI 节点装配传入 node.id()；null=纯模型解析视图）。 */
        private final String nodeId;
        /** node 绑定包装 emitter（nodeId null 时为 null，emitter() 直委托实例级）。 */
        private final EventEmitter nodeEmitter;
        private volatile AgentFactory factory;

        private NodeExecView(ModelConfig snapshot, String configId, String nodeId) {
            this.snapshot = snapshot;
            this.configId = configId;
            this.nodeId = nodeId;
            this.nodeEmitter = nodeId == null ? null : e -> routeNodeDelta(nodeId, e);
        }

        /** 本视图解析出的有效 configId（装配 create(agentKey, configId) 直用）。 */
        public String configId() {
            return configId;
        }

        @Override
        public String subjectId() {
            return WorkflowRuntime.this.subjectId();
        }

        @Override
        public String workspaceRoot() {
            return WorkflowRuntime.this.workspaceRoot();
        }

        @Override
        public String workspaceId() {
            return WorkflowRuntime.this.workspaceId();
        }

        @Override
        public ModelConfig snapshot() {
            return snapshot;
        }

        @Override
        public EventEmitter emitter() {
            return nodeEmitter != null ? nodeEmitter : WorkflowRuntime.this.emitter();
        }

        @Override
        public AgentFactory agentFactory() {
            AgentFactory f = factory;
            if (f == null) {
                f = services.agentFactoryFor(this);
                factory = f;
            }
            return f;
        }

        @Override
        public Map<String, Object> metadata() {
            return WorkflowRuntime.this.metadata();
        }

        @Override
        public Path dataDir() {
            return WorkflowRuntime.this.dataDir();
        }

        @Override
        public boolean terminal() {
            return WorkflowRuntime.this.terminal();
        }

        @Override
        public InteractionService interaction() {
            return WorkflowRuntime.this.interaction();
        }

        @Override
        public ConversationArchive conversationArchive() {
            return WorkflowRuntime.this.conversationArchive();
        }

        @Override
        public Map<String, AgentContext> agents() {
            return WorkflowRuntime.this.agents();
        }
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

    @Override
    public AgentFactory agentFactory() {
        AgentFactory f = boundFactory;
        if (f == null) {
            f = services.agentFactoryFor(this);
            boundFactory = f;
        }
        return f;
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

    @Override
    public ConversationArchive conversationArchive() {
        return archive;
    }

    @Override
    public Map<String, AgentContext> agents() {
        return agents;
    }

    // ══════════════════════════ emitter 双路由 ══════════════════════════

    /**
     * 双路由实现（异常自吞，永不抛）。agent 域按 agentId 归户追加 per-agent jsonl
     * （行序 = 事件发射序，同名文件内追加经小锁串行）；实例域进 events.jsonl（store
     * 内单 writer 锁 + 内存尾部）。
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
                return e.id();
            }
            switch (kind) {
                case Events.USER_MESSAGE, Events.MESSAGE, Events.TOOL_RESULT, Events.USAGE -> {
                    String name = agentNameOf(e.agentId());
                    if (name != null) {
                        ObjectNode payload = AgentSessions.projectPayload(e);
                        AgentEventLine line = new AgentEventLine(Long.toString(e.id()),
                                System.currentTimeMillis(), kind, e.agentId(), payload);
                        synchronized (agentFileLocks.computeIfAbsent(name, k -> new Object())) {
                            store.appendAgentEventLine(workspaceId, instanceId, name, line);
                        }
                    }
                }
                default -> {
                    // tool.started 瞬态与 agent.*/error/cancelled/task.*：不入工作流持久面。
                    // delta/thinking 落此即未经 node 视图包装直发（AI 节点装配的 node.delta
                    // 扇出在包装层拦截，不经本路由）——supervisor 等无节点上下文的瞬态照旧丢弃。
                }
            }
            return e.id();
        } catch (RuntimeException ex) {
            log.warn("[workflow] 事件发射失败(自吞,不打断任务线程) instance={} kind={} agent={}",
                    instanceId, e == null ? null : e.kind(), e == null ? null : e.agentId(), ex);
            return e == null ? 0L : e.id();
        }
    }

    // ══════════════════════════ node.delta 瞬态扇出 ══════════════════════════

    /**
     * node 绑定路由（NodeExecView 的包装 emitter 委托点）：agent 域瞬态增量
     * （delta=正文/thinking=思考）转 {@link #NODE_DELTA} 扇出实例流频道
     * （Channels.workflowStream，与插件实例事件扇出同通道同帧形）；其余事件原样
     * 转发实例级双路由 {@link #route}（行为与无包装视图完全一致）。
     *
     * <p>瞬态契约（架构 §7.13）：不进 events.jsonl、不进内存尾部队列（不经
     * {@code store.appendEvent}）；seq 用事件自带雪花号（占号不落盘，磁盘回放有洞合法）；
     * 帧外层 {@code {seq,ts,event,payload}}、内层 {@code {nodeId,agentId,kind,content}}。
     * 扇出异常自吞——瞬态可丢，绝不打断任务线程。
     */
    private long routeNodeDelta(String nodeId, EmitEvent e) {
        if (e != null && (Events.DELTA.equals(e.kind()) || Events.THINKING.equals(e.kind()))) {
            try {
                StreamEmitter stream = services.stream();
                String wid = stream.workerId();
                ObjectNode payload = Json.obj()
                        .put("nodeId", nodeId)
                        .put("agentId", e.agentId() == null ? "" : e.agentId())
                        .put("kind", Events.THINKING.equals(e.kind()) ? "thinking" : "text")
                        .put("content", e.content() == null ? "" : e.content());
                ObjectNode frame = Json.obj()
                        .put("seq", Long.toString(e.id()))
                        .put("ts", System.currentTimeMillis())
                        .put("event", NODE_DELTA)
                        .set("payload", payload);
                stream.fanout(k -> Channels.workflowStream(k, wid, instanceId), NODE_DELTA,
                        e.id(), frame, null);
            } catch (RuntimeException ex) {
                log.warn("[workflow] node.delta 扇出失败(自吞,瞬态可丢) instance={} node={} agent={}",
                        instanceId, nodeId, e.agentId(), ex);
            }
            return e.id();
        }
        return route(e);
    }

    /**
     * agentId → 裸 agent 名：仅认 {@code <name>#<instanceId>} 归户键（匿名 AI 节点的
     * 一次性 agentId 不带该后缀 → null → 不落 agents/）。
     */
    private String agentNameOf(String agentId) {
        if (agentId == null || agentId.isEmpty()) {
            return null;
        }
        String suffix = "#" + instanceId;
        if (!agentId.endsWith(suffix) || agentId.length() <= suffix.length()) {
            return null;
        }
        return agentId.substring(0, agentId.length() - suffix.length());
    }
}
