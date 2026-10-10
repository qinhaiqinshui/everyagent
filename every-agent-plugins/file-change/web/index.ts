/**
 * file-change 插件——PluginModule 入口（纯 Web 端）。
 *
 * 经 ctx.ui.registerRoundTailPanel（`ui.round_tail_panels` 扩展点）
 * 注册轮末文件变更视图组件；并订阅宿主的**通用**领域事件作作废本插件的轮次摘要缓存：
 * - `task-round-closed`：某轮闭合（worker 瞬态 round.closed → 宿主 emit），作废该任务缓存重拉；
 * - `task-deleted`：任务删除，顺手清缓存。
 * 事件名不含任何文件变更语义，核心不感知本插件（架构 §7.15.2）。
 */
import type { PluginContext, PluginModule, UiRoundTailPanelDefinition } from '@everyagent/plugin-api'
import RoundFileChangesView from './RoundFileChangesView'
import { invalidateRoundChanges } from './roundChangesStore'
import { setPluginContext } from './pluginRuntime'

const fileChangePlugin: PluginModule = {
  activate(ctx: PluginContext) {
    setPluginContext(ctx)
    const def: UiRoundTailPanelDefinition = {
      pluginId: 'file-change',
      Component: RoundFileChangesView,
    }
    ctx.ui.registerRoundTailPanel(def)
    // 轮闭合 → 作废该任务的摘要缓存（组件下一次渲染自动重拉）。
    ctx.events.on('task-round-closed', (payload) => {
      const taskId = (payload as { taskId?: string })?.taskId
      if (taskId) invalidateRoundChanges(taskId)
    })
    ctx.events.on('task-deleted', (payload) => {
      const taskId = (payload as { taskId?: string })?.taskId
      if (taskId) invalidateRoundChanges(taskId)
    })
  },
}

export default fileChangePlugin
