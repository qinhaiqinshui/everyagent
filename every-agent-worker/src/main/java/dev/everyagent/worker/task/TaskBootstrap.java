package dev.everyagent.worker.task;

import dev.everyagent.worker.modules.ConfigStore;
import dev.everyagent.worker.modules.ConfigStore.ResolvedConfig;
import dev.everyagent.worker.modules.WorkspaceManager;
import dev.everyagent.plugin.api.exception.NotFoundException;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 任务创建/再运行的准备路径组件(task 层):
 * 承接 workspace 解析(注册+校验+稳定 id+默认兜底)与模型配置解析(configId 解析链)。
 * 这些动作发生在 TaskEntry 构造之前(洋葱节点时序不满足——节点跑在任务线程且
 * TaskEntry 已构造完),故以独立组件承载,TaskManager 不再直持这两个基础设施依赖。
 */
@Component
public class TaskBootstrap {

    private final WorkspaceManager workspaces;
    private final ConfigStore configs;

    public TaskBootstrap(WorkspaceManager workspaces, ConfigStore configs) {
        this.workspaces = workspaces;
        this.configs = configs;
    }

    /**
     * 新建任务的工作区解析:注册 + 校验(必填;失败抛 IOException,由调用方转 RPC 错误)。
     */
    public WorkspaceManager.Root resolveWorkspace(String workspacePath) throws IOException {
        return workspaces.resolveAndRegister(workspacePath);
    }

    /** 路径→稳定工作区 id(未注册回退默认 id,任务目录据此归类)。 */
    public String workspaceIdOf(String rootPath) {
        String id = workspaces.idOfRoot(rootPath);
        return id != null ? id : WorkspaceManager.DEFAULT_WORKSPACE_ID;
    }

    /** 新建任务的模型配置解析(configId 可空=默认)。 */
    public ResolvedConfig resolveConfig(String configId) {
        return configs.resolve(configId);
    }

    /**
     * 再运行的模型配置解析三级回退:输入箱切换时以 overrideConfigId 优先;否则沿用任务
     * 最后运行的 configId(配置已删回退默认)。override 非法时回退任务原始 configId,
     * 再不行回退默认。
     */
    public ResolvedConfig resolveRerunConfig(String overrideConfigId, String metaConfigId) {
        ResolvedConfig cfg;
        String desiredConfigId = (overrideConfigId != null && !overrideConfigId.isEmpty())
                ? overrideConfigId
                : metaConfigId;
        try {
            cfg = configs.resolve(desiredConfigId);
        } catch (NotFoundException e) {
            try {
                cfg = configs.resolve(metaConfigId);
            } catch (NotFoundException e2) {
                cfg = configs.resolve(null);
            }
        }
        return cfg;
    }
}
