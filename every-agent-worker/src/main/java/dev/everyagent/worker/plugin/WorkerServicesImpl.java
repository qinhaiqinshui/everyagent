package dev.everyagent.worker.plugin;

import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.interaction.InteractionService;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.TokenEstimator;
import dev.everyagent.plugin.api.permission.TaskInfo;
import dev.everyagent.plugin.api.task.TaskService;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.worker.interaction.InteractionServiceImpl;
import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.task.TaskManager;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * WorkerServices 实现 —— 插件经此访问 worker 核心只读服务。
 */
@Component
public class WorkerServicesImpl implements WorkerServices {

    private final OsSandbox sandbox;
    private final WorkspaceManager workspaces;
    private final AtomicReference<TokenEstimator> tokenEstimator;
    private final TaskManager taskManager;
    private final InteractionServiceImpl interaction;

    public WorkerServicesImpl(OsSandbox sandbox, WorkspaceManager workspaces,
            TokenEstimator tokenEstimator, @Lazy TaskManager taskManager, InteractionServiceImpl interaction) {
        this.sandbox = sandbox;
        this.workspaces = workspaces;
        this.tokenEstimator = new AtomicReference<>(tokenEstimator);
        this.taskManager = taskManager;
        this.interaction = interaction;
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
            public TaskInfo get(String taskId) {
                return taskManager.get(taskId);
            }

            @Override
            public void publishUpdated(String taskId) {
                taskManager.publishTaskUpdated(taskId);
            }
        };
    }

    @Override
    public InteractionService interaction() {
        return interaction;
    }
}
