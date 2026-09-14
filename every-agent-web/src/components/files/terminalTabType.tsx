/**
 * src/components/files/terminalTabType.tsx
 *
 * 内置「终端」标签类型定义（注册表 key `'terminal'`）。
 *
 * 真实终端页（xterm.js 渲染 + worker PTY 频道订阅 + term.* RPC）由
 * TerminalPage 组件懒加载承载,本定义只负责把标签路由到该组件。
 */

import type { ReactNode } from 'react'
import type { WorkspaceTab } from '@/types'
import type { UiWorkspaceTabTypeDefinition, WorkspaceTabRenderContext } from '@/plugin/types'
import { TerminalIcon } from '@/components/shared/AppGlyphs'
import { createLazyRouteComponent } from '@/components/shared/LazyRouteView'

const LazyTerminalPage = createLazyRouteComponent(() => import('@/components/files/TerminalPage'))

export const terminalTabType: UiWorkspaceTabTypeDefinition = {
  tabTypeKey: 'terminal',
  pluginId: 'core',
  renderTab: (tab: WorkspaceTab, ctx: WorkspaceTabRenderContext): ReactNode => {
    if (tab.tabType !== 'terminal') return null
    return (
      <LazyTerminalPage tab={tab} onClose={() => ctx.closeTab(tab.id)} />
    )
  },
  renderIcon: () => <TerminalIcon size={15} />,
  getLabel: (tab: WorkspaceTab) =>
    (tab.tabType === 'terminal' ? (tab.title || `终端: ${tab.name || '工作区'}`) : ''),
  getTitle: (tab: WorkspaceTab) => (tab.tabType === 'terminal' ? tab.path : ''),
  getCloseAriaLabel: (tab: WorkspaceTab) =>
    (tab.tabType === 'terminal' ? `关闭终端 ${tab.name}` : '关闭终端标签'),
  getSidebarActivityId: () => null,
  /**
   * 关闭标签时显式清理:TerminalPage 的 useEffect cleanup 会调 term.close,
   * 此处不重复调,但确保 closeWorkspaceTabNow 被调用(触发 React unmount → cleanup)。
   * 若无 onClose,closeWorkspaceTab 也会 fallback 到 closeWorkspaceTabNow,
   * 但显式声明让清理意图更清晰,并防止未来 onClose 链路变更遗漏终端清理。
   */
  onClose: (_tab: WorkspaceTab, ctx: WorkspaceTabRenderContext) => {
    ctx.closeTab(_tab.id)
  },
}
