package dev.everyagent.worker.modules;

/**
 * 工作区级联操作端口：modules 层定义，task 域实现。
 * 用于 WorkspaceManager 在删除/重定向工作区时级联操作任务数据，
 * 而不直接依赖 TaskManager。
 */
public interface WorkspaceCascadePort {
    int deleteByWorkspaceId(String workspaceId);
    int redirectWorkspace(String oldRoot, String newRoot);
}
