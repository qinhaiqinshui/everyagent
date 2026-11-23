/**
 * workflow 插件运行期上下文持有者（git 插件同款范式）。
 *
 * 插件 activate 时注入 PluginContext；组件/网关经 getPluginContext() 取用
 * （插件代码不得引用宿主 web 模块，一切宿主能力经 ctx 暴露）。
 *
 * 额外提供 workspaceId（稳定 id）→ {root, workerId} 反查：workflow.* RPC 的
 * workspace 参数是稳定工作区 id（wire 契约），而 RichComposer 的 @ 引用需要
 * workspace 根路径、RPC 定向需要 workerId——两者都经注册表解析。
 */
import type { PluginContext, PluginWorkspaceEntry } from '@everyagent/plugin-api'

let pluginCtx: PluginContext | null = null

export function setPluginContext(ctx: PluginContext): void {
  pluginCtx = ctx
}

export function getPluginContext(): PluginContext {
  if (!pluginCtx) {
    throw new Error('workflow 插件尚未激活（PluginContext 未注入）')
  }
  return pluginCtx
}

/** workspaceId 解析结果：根路径（RichComposer @ 引用）+ 归属 worker（RPC 定向）。 */
export interface WorkspaceRef {
  workspaceId: string
  root: string
  workerId: string
}

/**
 * 稳定工作区 id → {root, workerId}（经 ctx.sdk.workspace.list 注册表反查）。
 *
 * 解析顺序：注册表精确匹配 id → 插件加载时归属工作区（ctx.sdk.workspace.id，
 * 单 worker 场景兜底）→ null（未知工作区）。注册表为异步快照，调用方自行缓存。
 */
export async function resolveWorkspaceRef(workspaceId: string): Promise<WorkspaceRef | null> {
  const ctx = getPluginContext()
  let entries: PluginWorkspaceEntry[] = []
  try {
    entries = await ctx.sdk.workspace.list()
  } catch {
    entries = []
  }
  const hit = entries.find(e => e.id === workspaceId)
  if (hit) {
    return { workspaceId, root: hit.root, workerId: hit.workerId }
  }
  if (workspaceId && workspaceId === ctx.sdk.workspace.id) {
    return { workspaceId, root: ctx.sdk.workspace.rootPath, workerId: ctx.sdk.workerId }
  }
  return null
}
