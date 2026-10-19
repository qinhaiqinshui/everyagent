/**
 * file-change 插件——PluginModule 入口（纯 Web 端）。
 *
 * 经 ctx.ui.registerRoundTailPanel（`ui.round_tail_panels` 扩展点）
 * 注册轮末文件变更视图组件；并订阅宿主的**通用**领域事件：
 * - `plugin-task-event`（宿主 taskStream 对 plugin.* 瞬态直推的统一透传,§7.15.2）:
 *   滤出本插件的 `plugin.file-change.roundChanges`（轮闭合落盘后 worker 直推的轻量摘要）,
 *   upsert 进插件自持缓存——不经「作废→重拉」间接失效;
 * - `task-deleted`：任务删除，顺手清缓存。
 * 事件名不含任何文件变更语义，核心不感知本插件（架构 §7.15.2）。
 */
import type { PluginContext, PluginModule, UiRoundTailPanelDefinition } from '@everyagent/plugin-api'
import RoundFileChangesView from './RoundFileChangesView'
import { invalidateRoundChanges, upsertRoundChanges, type RoundFileChangeSummary } from './roundChangesStore'
import { setPluginContext } from './pluginRuntime'

/** 本插件 worker 侧直推的轮末摘要事件名(与 FileChangeAdvisorProvider.ROUND_CHANGES_EVENT 一致)。 */
const ROUND_CHANGES_EVENT = 'plugin.file-change.roundChanges'

/** plugin-task-event 载荷(宿主 taskStream 透传,见宿主 domainEvents.PLUGIN_TASK_EVENT)。 */
interface PluginTaskEventPayload {
  taskId?: string
  workerId?: string
  event?: string
  data?: { roundId?: string; changes?: RoundFileChangeSummary[] }
}

const fileChangePlugin: PluginModule = {
  activate(ctx: PluginContext) {
    setPluginContext(ctx)
    const def: UiRoundTailPanelDefinition = {
      pluginId: 'file-change',
      Component: RoundFileChangesView,
    }
    ctx.ui.registerRoundTailPanel(def)
    // 轮闭合直推 → 按轮 upsert 摘要缓存（组件 useSyncExternalStore 感知版本变化自动重渲染）。
    ctx.events.on('plugin-task-event', (payload) => {
      const p = payload as PluginTaskEventPayload | undefined
      if (p?.event !== ROUND_CHANGES_EVENT) return
      if (!p.taskId || !p.data?.roundId) return
      upsertRoundChanges(p.taskId, p.data.roundId, Array.isArray(p.data.changes) ? p.data.changes : [])
    })
    ctx.events.on('task-deleted', (payload) => {
      const taskId = (payload as { taskId?: string })?.taskId
      if (taskId) invalidateRoundChanges(taskId)
    })
  },
}

export default fileChangePlugin
