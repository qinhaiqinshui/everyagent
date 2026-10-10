/**
 * task-input-queue 插件 —— Web 插件入口。
 *
 * 注册 composer 上方输入队列面板。面板自持数据源:task.queueSnapshot RPC 拉取队列快照,
 * 以 task.updated 广播(入队/轮间消费/增删改时 worker 携带 pendingInputs 广播)为刷新信号;
 * 操作经 ctx.rpc() 调 task.queueRemove/move。
 * activate 时把 PluginContext 注入 pluginRuntime,供面板取 ctx.ui 的草稿回填能力(「编辑」项)。
 */
import type { PluginModule } from '@everyagent/plugin-api'
import TaskInputQueuePanel from './TaskInputQueuePanel'
import { setPluginContext } from './pluginRuntime'
import './task-input-queue.css'

const taskInputQueuePlugin: PluginModule = {
  activate(ctx) {
    setPluginContext(ctx)
    ctx.ui.registerComposerAbovePanel({
      id: 'task-input-queue-panel',
      Component: TaskInputQueuePanel,
    })
  },
}

export default taskInputQueuePlugin
