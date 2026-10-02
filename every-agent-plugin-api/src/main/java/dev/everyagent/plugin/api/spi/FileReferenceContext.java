package dev.everyagent.plugin.api.spi;

import dev.everyagent.plugin.api.execution.ExecContext;

/**
 * 文件引用处理上下文 —— {@link FileReferenceHandler#process} 的 per-任务参数，
 * 本身即 {@link ExecContext}（不再单独声明 {@code taskId()} / {@code workspaceId()}
 * / {@code workspaceRoot()} / {@code execution()}：均已由 {@link ExecContext} 收编）。
 *
 * <p>读取文件等能力由插件经
 * {@link dev.everyagent.plugin.api.WorkerPluginContext#services()} 获取
 * （jailed 路径校验、PermissionGate 授权链由 handler 自行走既有通道）。
 * 主体绑定端口（{@code interaction()} 发射授权 ask 自动补 subjectId 键）。
 *
 * <p>注意：早期节点（RPC 阶段）任务尚未创建、底层 TaskRuntime 不存在，
 * 此时非早期回退槽位（{@code snapshot()} / {@code emitter()} /
 * {@code agentFactory()} / {@code interaction()} / {@code dataDir()} /
 * {@code agents()} 等）可为 null；{@code subjectId()} / {@code workspaceId()}
 * / {@code workspaceRoot()} 由早期回退字段供给、非 null（若工作区尚未指定则
 * workspaceRoot 仍可为 null）。
 */
public interface FileReferenceContext extends ExecContext {
}
