/**
 * Git 历史标签类型定义（插件版，注册表 key `git-history`）。
 *
 * 使用 PluginWorkspaceTab（plugin-api 最小化接口）；pluginTabType 字段不在最小化
 * 接口中，按需强转访问（不回头引用 web 内部 WorkspaceTab 类型）。
 */

import type { ReactNode } from 'react'
import type {
  PluginWorkspaceTab,
  UiWorkspaceTabTypeDefinition,
  WorkspaceTabRenderContext,
} from '@everyagent/plugin-api'
import { GitIcon } from './icons'
import { createLazyRouteComponent } from './lazy'

const LazyGitHistoryPanel = createLazyRouteComponent(() => import('./GitHistoryPanel'))

function getData(tab: PluginWorkspaceTab, key: string): string {
  if (tab.tabType !== 'plugin') return ''
  return tab.data?.[key] ?? ''
}

export const gitHistoryTabType: UiWorkspaceTabTypeDefinition = {
  tabTypeKey: 'git-history',
  pluginId: 'git',
  renderTab: (tab: PluginWorkspaceTab, _ctx: WorkspaceTabRenderContext): ReactNode => {
    const pluginTab = tab as PluginWorkspaceTab & { pluginTabType?: string }
    if (pluginTab.tabType !== 'plugin' || pluginTab.pluginTabType !== 'git-history') return null
    return (
      <LazyGitHistoryPanel
        workspaceRoot={getData(tab, 'workspaceRoot')}
        path={getData(tab, 'path')}
        name={getData(tab, 'name')}
      />
    )
  },
  renderIcon: () => <GitIcon />,
  getLabel: (tab: PluginWorkspaceTab) => (tab.tabType === 'plugin' ? (tab.title ?? '') : ''),
  getTitle: (tab: PluginWorkspaceTab) => getData(tab, 'path') || getData(tab, 'workspaceRoot'),
  getCloseAriaLabel: (tab: PluginWorkspaceTab) => `关闭 Git 历史 ${tab.tabType === 'plugin' ? (tab.title ?? '') : ''}`,
  getSidebarActivityId: () => null,
}
