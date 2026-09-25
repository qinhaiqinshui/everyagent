/**
 * task-input-queue 插件 —— Web 插件入口。
 *
 * 注册 composer 上方输入队列面板（从 web 核心 TaskQueuePanel 迁入）。
 * 面板订阅 task.updated 取 pendingInputs，操作经 ctx.rpc() 调 task.queueRemove/move。
 */
import type { PluginModule } from '@everyagent/plugin-api'
import TaskInputQueuePanel from './TaskInputQueuePanel'
import './task-input-queue.css'

const taskInputQueuePlugin: PluginModule = {
  activate(ctx) {
    ctx.ui.registerComposerAbovePanel({
      id: 'task-input-queue-panel',
      Component: TaskInputQueuePanel,
    })
  },
}

export default taskInputQueuePlugin
