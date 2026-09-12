package dev.everyagent.worker.modules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 工作区最后活动时间跟踪器(架构 §7.17/D16 的收口增强)。
 *
 * <p>任务终态收口({@code TaskManager.finish})时经本组件把该任务挂靠工作区注册表
 * 条目的「最后活动时间」刷到当前时刻,供前端按最近活动倒序渲染工作区(任务面板 /
 * 文件管理器 / 源代码管理器)。本组件保持极薄:更新语义落在
 * {@link WorkspaceManager#touchActivity},这里只做调用点封装与失败吞掉的职责边界——
 * 收口路径绝不被注册表写入阻塞(失败仅记日志,任务照常终态落盘)。
 */
@Component
public class WorkspaceActivityTracker {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceActivityTracker.class);

    private final WorkspaceManager workspaces;

    public WorkspaceActivityTracker(WorkspaceManager workspaces) {
        this.workspaces = workspaces;
    }

    /**
     * 任务收口时调用:刷新 {@code workspaceRoot} 对应工作区的最后活动时间。
     * 工作区未注册 / root 为空 / 落盘失败均静默忽略,不抛异常、不阻塞收口。
     */
    public void onTaskFinished(String workspaceRoot) {
        try {
            workspaces.touchActivity(workspaceRoot);
        } catch (RuntimeException e) {
            log.debug("工作区最后活动时间更新失败(不影响任务收口): {}", workspaceRoot, e);
        }
    }
}
