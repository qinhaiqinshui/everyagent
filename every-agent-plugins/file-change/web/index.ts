/**
 * file-change 插件——PluginModule 入口（纯 Web 端）。
 *
 * 经 ctx.ui.registerRoundTailPanel（`ui.round_tail_panels` 扩展点）
 * 注册轮末文件变更视图组件。
 */
import type { PluginContext, PluginModule, UiRoundTailPanelDefinition } from '@everyagent/plugin-api'
import RoundFileChangesView from './RoundFileChangesView'
import { setPluginContext } from './pluginRuntime'

const fileChangePlugin: PluginModule = {
  activate(ctx: PluginContext) {
    setPluginContext(ctx)
    const def: UiRoundTailPanelDefinition = {
      pluginId: 'file-change',
      Component: RoundFileChangesView,
    }
    ctx.ui.registerRoundTailPanel(def)
  },
}

export default fileChangePlugin
