/**
 * subagent 插件 —— Agent 长条列表面板（从 web 核心 AgentListPanel 迁入）。
 *
 * 当前为迁移占位：组件签名从 props 改为接收 ComposerPanelCtx，
 * 数据接入（agents 列表、filterAgentId 联动）后续完善，暂时返回 null。
 *
 * 完成后将从 ctx 获取 agents 数据和 selectAgent 回调，
 * 渲染逻辑与原 AgentListPanel 一致。
 */
import type { ComposerPanelCtx } from '@everyagent/plugin-api'

export default function SubAgentListPanel(_ctx: ComposerPanelCtx) {
  // TODO: 从 ctx 获取 agents 数据（rpc task.agents + subscribeTaskEvents），
  //       调用 ctx.selectAgent 联动过滤，渲染 agent 胶囊列表。
  return null
}
