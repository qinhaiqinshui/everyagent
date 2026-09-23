/**
 * Git 历史标签类型定义（插件版，注册表 key `git-history`）。
 *
 * 使用 WorkspacePluginTab（pluginTabType='git-history'），从 tab.data 读取
 * workspaceRoot/path/name 等展示参数。GitHistoryPanel 自行经 gitGateway.log 懒加载。
 */

import type { ReactNode } from 'react'
import type { WorkspaceTab } from '@/types'
import type { UiWorkspaceTabTypeDefinition, WorkspaceTabRenderContext } from '@/plugin/types'
import { GitIcon } from '@/components/icon'
import { createLazyRouteComponent } from '@/components/shared/LazyRouteView'

const LazyGitHistoryPanel = createLazyRouteComponent(() => import('./GitHistoryPanel'))

function getData(tab: WorkspaceTab, key: string): string {
  if (tab.tabType !== 'plugin') return ''
  return tab.data?.[key] ?? ''
}

export const gitHistoryTabType: UiWorkspaceTabTypeDefinition = {
  tabTypeKey: 'git-history',
  pluginId: 'git',
  renderTab: (tab: WorkspaceTab, _ctx: WorkspaceTabRenderContext): ReactNode => {
    if (tab.tabType !== 'plugin' || tab.pluginTabType !== 'git-history') return null
    return (
      <LazyGitHistoryPanel
        workspaceRoot={getData(tab, 'workspaceRoot')}
        path={getData(tab, 'path')}
        name={getData(tab, 'name')}
      />
    )
  },
  renderIcon: () => <GitIcon />,
  getLabel: (tab: WorkspaceTab) => (tab.tabType === 'plugin' ? (tab.title ?? '') : ''),
  getTitle: (tab: WorkspaceTab) => getData(tab, 'path') || getData(tab, 'workspaceRoot'),
  getCloseAriaLabel: (tab: WorkspaceTab) => `关闭 Git 历史 ${tab.tabType === 'plugin' ? (tab.title ?? '') : ''}`,
  getSidebarActivityId: () => null,
}
