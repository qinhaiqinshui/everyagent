package dev.everyagent.worker.plugin;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.event.StreamEmitter;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.spi.IdGenerator;
import dev.everyagent.plugin.api.spi.NativeExec;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.TokenEstimator;
import dev.everyagent.plugin.api.slash.SlashTokenService;
import dev.everyagent.plugin.api.proto.ShortIds;
import dev.everyagent.plugin.api.proto.SnowflakeId;
import dev.everyagent.plugin.api.task.StoredTaskInfo;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.plugin.api.task.RoundClosedListener;
import dev.everyagent.plugin.api.task.TaskService;
import dev.everyagent.plugin.api.task.TaskStoreService;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.agent.AgentFactoryImpl;
import dev.everyagent.worker.modules.ConfigStore;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.hub.EventSink;
import dev.everyagent.worker.interaction.InteractionServiceImpl;
import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.os.SandboxPathRegistry;
import dev.everyagent.worker.task.TaskManager;
import dev.everyagent.worker.task.RoundIndexStore;
import dev.everyagent.worker.task.TaskStore;
import dev.everyagent.worker.ship.StreamSourceRegistry;
import dev.everyagent.worker.slash.SlashTaskScopeStore;
import dev.everyagent.worker.tools.FsToolSupport;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * WorkerServices 实现 —— 插件经此访问 worker 核心只读服务。
 */
@Component
public class WorkerServicesImpl implements WorkerServices {

    private final OsSandbox sandbox;
    private final SandboxPathRegistry pathRegistry;
    private final WorkspaceManager workspaces;
    private final AtomicReference<TokenEstimator> tokenEstimator;
    private final TaskManager taskManager;
    private final TaskStore taskStore;
    private final RoundIndexStore roundIndexStore;
    private final InteractionServiceImpl interaction;
    private final WorkerProperties workerProperties;
    private final EventSink eventSink;
    private final IdGenerator idGenerator;
    private final StreamSourceRegistry streamSources;
    /** slash 任务级 token 存储:构造器依赖 WorkerServices(本类),用 @Lazy 打断循环依赖。 */
    private final SlashTaskScopeStore slashStore;
    /** 文件工具共享层:文件写监听器注册通道(addFileWriteListener,架构 §7.15.2)。 */
    private final FsToolSupport fsToolSupport;
    /** agent 工厂(agentFactoryFor:非任务主体经此获取绑定工厂,架构 §7.20.7)。 */
    private final AgentFactoryImpl agentFactory;
    /** 模型配置存储(modelConfigs:全表掩码快照)。 */
    private final ConfigStore configStore;

    public WorkerServicesImpl(OsSandbox sandbox, SandboxPathRegistry pathRegistry,
            WorkspaceManager workspaces,
            TokenEstimator tokenEstimator, @Lazy TaskManager taskManager,
            TaskStore taskStore,
            RoundIndexStore roundIndexStore,
            InteractionServiceImpl interaction,
            WorkerProperties workerProperties, EventSink eventSink,
            StreamSourceRegistry streamSources,
            @Lazy SlashTaskScopeStore slashStore,
            FsToolSupport fsToolSupport,
            AgentFactoryImpl agentFactory,
            ConfigStore configStore) {
        this.sandbox = sandbox;
        this.pathRegistry = pathRegistry;
        this.workspaces = workspaces;
        this.tokenEstimator = new AtomicReference<>(tokenEstimator);
        this.taskManager = taskManager;
        this.taskStore = taskStore;
        this.roundIndexStore = roundIndexStore;
        this.interaction = interaction;
        this.workerProperties = workerProperties;
        this.eventSink = eventSink;
        this.streamSources = streamSources;
        this.slashStore = slashStore;
        this.fsToolSupport = fsToolSupport;
        this.agentFactory = agentFactory;
        this.configStore = configStore;
        this.idGenerator = new IdGenerator() {
            @Override
            public long next() {
                return SnowflakeId.next();
            }

            @Override
            public String shortId(String prefix) {
                return ShortIds.next(prefix);
            }
        };
    }

    /** 外部插件注册自定义 TokenEstimator 时替换内置实现。 */
    void replaceTokenEstimator(TokenEstimator estimator) {
        tokenEstimator.set(estimator);
    }

    @Override
    public SandboxBackend sandbox() {
        return sandbox;
    }

    @Override
    public NativeExec nativeExec() {
        return sandbox;
    }

    @Override
    public dev.everyagent.plugin.api.spi.WorkspaceManager workspaces() {
        return workspaces;
    }

    @Override
    public TokenEstimator tokenEstimator() {
        return tokenEstimator.get();
    }

    @Override
    public TaskService task() {
        return new TaskService() {
            @Override
            public TaskRuntime get(String taskId) {
                return taskManager.get(taskId);
            }

            @Override
            public StoredTaskInfo diskEntry(String taskId) {
                return taskManager.diskEntry(taskId);
            }

            @Override
            public void publishUpdated(String taskId) {
                taskManager.publishTaskUpdated(taskId);
            }
        };
    }

    @Override
    public TaskStoreService store() {
        return taskStore;
    }

    @Override
    public InteractionService interaction() {
        return interaction;
    }

    @Override
    public WorkerConfig config() {
        return workerProperties;
    }

    @Override
    public IdGenerator ids() {
        return idGenerator;
    }

    @Override
    public StreamEmitter stream() {
        return eventSink;
    }

    @Override
    public java.nio.file.Path dataDirOf(String subjectId) {
        return taskStore.dirOf(subjectId);
    }

    @Override
    public dev.everyagent.plugin.api.model.EventEmitter emitterOf(String subjectId) {
        dev.everyagent.worker.task.TaskEntry t = taskManager.runningTask(subjectId);
        return t != null ? t.events() : null;
    }

    /** 任务级 slash token 只读查询:委托斜杠层的 SlashTaskScopeStore(其恒以磁盘为真相源)。 */
    @Override
    public SlashTokenService slashTokens() {
        return new SlashTokenService() {
            @Override
            public java.util.List<String> tokensOf(String taskId) {
                return slashStore.tokensOf(taskId);
            }

            @Override
            public boolean hasKind(String taskId, String kind) {
                return slashStore.hasKind(taskId, kind);
            }

            /**
             * 消费式移除委托 {@link SlashTaskScopeStore#removeKind}:漏覆写时走接口默认
             * 实现(恒 false),任务级一次性 token(如旧版 /压缩上下文 bottom 胶囊)永远
             * 消费不掉——胶囊常驻输入框底部、每轮模型请求都强制压缩一次的根因。
             */
            @Override
            public boolean removeKind(String taskId, String kind) {
                return slashStore.removeKind(taskId, kind);
            }
        };
    }

    @Override
    public void addRoundClosedListener(RoundClosedListener listener) {
        roundIndexStore.addRoundClosedListener(listener);
    }

    @Override
    public void addFileWriteListener(dev.everyagent.plugin.api.spi.FileWriteListener listener) {
        fsToolSupport.addFileWriteListener(listener);
    }

    @Override
    public String toSandboxPath(java.nio.file.Path hostPath) {
        return pathRegistry.toSandboxPath(hostPath);
    }

    /**
     * 非任务主体(工作流等)获取绑定工厂:委托 {@link AgentFactoryImpl#bind}——与
     * {@code TaskEntry.agentFactory()} 同一获取口、同一装配口径(架构 §7.20.7)。
     */
    @Override
    public dev.everyagent.plugin.api.agent.AgentFactory agentFactoryFor(
            dev.everyagent.plugin.api.execution.ExecContext exec) {
        return agentFactory.bind(exec);
    }

    /**
     * 模型配置全表(掩码):ConfigStore 全量 DTO 剥离 apiKey 映射为 plugin-api
     * {@code ModelConfig} 快照——与 {@code ResolvedConfig.snapshot()} 同款口径。
     */
    @Override
    public java.util.List<dev.everyagent.plugin.api.model.ModelConfig> modelConfigs() {
        return configStore.list().stream()
                .map(c -> new dev.everyagent.plugin.api.model.ModelConfig(
                        c.configId(), c.provider(), c.baseUrl(), c.fullUrl(),
                        c.model(), c.params()))
                .toList();
    }

}
