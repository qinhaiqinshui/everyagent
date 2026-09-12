/**
 * src/components/files/GitHistoryTabType.tsx
 *
 * 内置「Git 历史」标签类型定义（注册表 key `'git-history'`）。
 *
 * 仿照 `diff` 标签：`GitHistoryPanel` 只认展示参数（workspaceRoot / path / name），
 * 打开方（文件树右键「显示 Git 历史」、源代码管理面板工作区历史）各自决定
 * 打开哪个路径的历史，内容加载由面板自行经 `gitGateway.log(..., path)` 懒加载。
 */

import type { ReactNode } from 'react'
import type { WorkspaceTab, WorkspaceGitHistoryTab } from '@/types'
import type { UiWorkspaceTabTypeDefinition, WorkspaceTabRenderContext } from '@/plugin/types'
import { GitIcon } from '@/components/icon'
import { createLazyRouteComponent } from '@/components/shared/LazyRouteView'

const LazyGitHistoryPanel = createLazyRouteComponent(() => import('@/components/files/GitHistoryPanel'))

export const gitHistoryTabType: UiWorkspaceTabTypeDefinition = {
  tabTypeKey: 'git-history',
  pluginId: 'core',
  renderTab: (tab: WorkspaceTab, _ctx: WorkspaceTabRenderContext): ReactNode => {
    if (tab.tabType !== 'git-history') return null
    const historyTab = tab as WorkspaceGitHistoryTab
    return (
      <LazyGitHistoryPanel
        workspaceRoot={historyTab.workspaceRoot}
        path={historyTab.path}
        name={historyTab.name}
      />
    )
  },
  renderIcon: () => <GitIcon />,
  getLabel: (tab: WorkspaceTab) => (tab.tabType === 'git-history' ? tab.title : ''),
  getTitle: (tab: WorkspaceTab) =>
    (tab.tabType === 'git-history' ? (tab.path || tab.workspaceRoot) : ''),
  getCloseAriaLabel: (tab: WorkspaceTab) =>
    (tab.tabType === 'git-history' ? `关闭 Git 历史 ${tab.title}` : '关闭 Git 历史'),
  getSidebarActivityId: () => null,
}