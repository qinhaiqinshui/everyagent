/**
 * update-file-view 插件——PluginModule 入口（纯 Web 插件，无 worker 端）。
 *
 * 经 builtInPlugins.ts 自动发现加载，通过 ctx.ui.registerToolCallView
 * （`ui.tool_call_views` 扩展点）注册 update_file 的**完整视图接管**：
 * 折叠态（文件名 + 变更统计徽章 + 完整路径）与展开态（路径 chip + 内嵌
 * oldcontent→content 行级 diff）均由插件渲染，与核心内置 toolViews 同地位。
 *
 * Component 用 plugin-api 最小化 props 契约，运行时与核心 ToolViewProps
 * 同形，注册侧安全强转（与 pdf-viewer 注册文件编辑器同一模式）。
 */

import type { PluginContext, PluginModule, ToolCallViewDefinition } from '@everyagent/plugin-api'
import { UpdateFileToolView } from './UpdateFileToolView'

const updateFileViewPlugin: PluginModule = {
  activate(ctx: PluginContext) {
    const def: ToolCallViewDefinition = {
      pluginId: 'update-file-view',
      toolName: 'update_file',
      Component: UpdateFileToolView,
    }
    ctx.ui.registerToolCallView(def)
  },
}

export default updateFileViewPlugin
