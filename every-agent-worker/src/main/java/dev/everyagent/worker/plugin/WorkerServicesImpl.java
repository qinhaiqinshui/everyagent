package dev.everyagent.worker.plugin;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.event.StreamEmitter;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.spi.IdGenerator;
import dev.everyagent.plugin.api.spi.NativeExec;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.TokenEstimator;
import dev.everyagent.plugin.api.proto.ShortIds;
import dev.everyagent.plugin.api.proto.SnowflakeId;
import dev.everyagent.plugin.api.task.StoredTaskInfo;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.plugin.api.task.RoundClosedListener;
import dev.everyagent.plugin.api.task.TaskService;
import dev.everyagent.plugin.api.task.TaskStoreService;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.hub.EventSink;
import dev.everyagent.worker.interaction.InteractionServiceImpl;
import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.os.SandboxPathRegistry;
import dev.everyagent.worker.task.TaskManager;
import dev.everyagent.worker.task.RoundIndexStore;
import dev.everyagent.worker.task.TaskStore;
import dev.everyagent.worker.ship.StreamSourceRegistry;
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

    public WorkerServicesImpl(OsSandbox sandbox, SandboxPathRegistry pathRegistry,
            WorkspaceManager workspaces,
            TokenEstimator tokenEstimator, @Lazy TaskManager taskManager,
            TaskStore taskStore,
            RoundIndexStore roundIndexStore,
            InteractionServiceImpl interaction,
            WorkerProperties workerProperties, EventSink eventSink,
            StreamSourceRegistry streamSources) {
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

    @Override
    public void addRoundClosedListener(RoundClosedListener listener) {
        roundIndexStore.addRoundClosedListener(listener);
    }

    @Override
    public String toSandboxPath(java.nio.file.Path hostPath) {
        return pathRegistry.toSandboxPath(hostPath);
    }

}
