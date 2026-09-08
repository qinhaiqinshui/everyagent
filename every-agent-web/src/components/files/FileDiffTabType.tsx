/**
 * src/components/files/FileDiffTabType.tsx
 *
 * 内置「文件差异对比」标签类型定义（注册表 key `'diff'`）。
 *
 * `FileDiffPanel` 已升级为顶级标签：它只认展示参数
 * （filePath / fileName / changeType / beforeContent / afterContent），
 * 不感知内容来自 Git 还是本轮文件改动。内容读取的责任在「打开方」：
 * Git 打开时自行读 Git 历史、文件改动追踪打开时直接用快照，二者都把
 * 读好的内容经 `openDiffTab(...)` 传给同一个 `diff` 标签。
 */

import type { ReactNode } from 'react'
import type { WorkspaceTab, WorkspaceDiffTab } from '@/types'
import type { UiWorkspaceTabTypeDefinition, WorkspaceTabRenderContext } from '@/plugin/types'
import { FilesIcon } from '@/components/icon'
import { createLazyRouteComponent } from '@/components/shared/LazyRouteView'

const LazyFileDiffPanel = createLazyRouteComponent(() => import('@/components/files/FileDiffPanel'))

export const diffTabType: UiWorkspaceTabTypeDefinition = {
  tabTypeKey: 'diff',
  pluginId: 'core',
  renderTab: (tab: WorkspaceTab, _ctx: WorkspaceTabRenderContext): ReactNode => {
    if (tab.tabType !== 'diff') return null
    const diffTab = tab as WorkspaceDiffTab
    return (
      <LazyFileDiffPanel
        workspaceRoot={diffTab.workspaceRoot}
        fileChange={{
          filePath: diffTab.filePath,
          changeType: diffTab.changeType,
          beforeContent: diffTab.beforeContent,
          afterContent: diffTab.afterContent,
        }}
      />
    )
  },
  renderIcon: () => <FilesIcon />,
  getLabel: (tab: WorkspaceTab) => (tab.tabType === 'diff' ? tab.fileName : ''),
  getTitle: (tab: WorkspaceTab) => (tab.tabType === 'diff' ? tab.filePath : ''),
  getCloseAriaLabel: (tab: WorkspaceTab) =>
    (tab.tabType === 'diff' ? `关闭变更对比 ${tab.fileName}` : '关闭变更对比'),
  getSidebarActivityId: () => null,
}
