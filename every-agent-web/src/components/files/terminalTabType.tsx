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
}
