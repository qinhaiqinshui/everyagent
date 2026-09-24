/**
 * subagent 插件 —— Web 插件入口。
 *
 * 注册 composer 上方 agent 列表面板（从 web 核心 AgentListPanel 迁入）。
 */
import type { PluginModule } from '@everyagent/plugin-api'
import SubAgentListPanel from './SubAgentListPanel'
import './subagent.css'

const subagentPlugin: PluginModule = {
  activate(ctx) {
    ctx.ui.registerComposerAbovePanel({
      id: 'subagent-agent-list',
      Component: SubAgentListPanel,
    })
  },
}

export default subagentPlugin
