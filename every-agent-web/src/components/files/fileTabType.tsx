/**
 * src/components/files/fileTabType.tsx
 *
 * 内置「文件」标签类型定义（注册表 key `'file'`）。
 *
 * 把原先 `Layout.renderWorkspaceTabContent` 里对文件页的 import 与构造逻辑
 * 迁移到此处，使壳层不再 import 任何内容组件。`fileResource` 这一"壳层二次推导"
 * 的重复形状只存在于本模块（见方案 Step 2 / Step 8）。
 */

import type { ReactNode } from 'react'
import type { WorkspaceTab, WorkspaceFileTab } from '@/types'
import type { FileTabResource } from '@/types/fileTabs'
import type { UiWorkspaceTabTypeDefinition, WorkspaceTabRenderContext } from '@/plugin/types'
import { FilesIcon } from '@/components/icon'
import { createLazyRouteComponent } from '@/components/shared/LazyRouteView'

const LazyFileTabPage = createLazyRouteComponent(() => import('@/components/files/FileTabPage'))

function buildFileResource(tab: WorkspaceFileTab): FileTabResource {
  return {
    id: tab.id,
    workspaceRoot: tab.workspaceRoot,
    filePath: tab.filePath,
    fileName: tab.fileName,
    taskId: tab.taskId,
    reloadKey: tab.reloadKey,
    nameEditRequestedAt: tab.nameEditRequestedAt,
    lineNumber: tab.lineNumber,
    lineLocateRequestedAt: tab.lineLocateRequestedAt,
    mode: tab.mode,
    editorKind: tab.editorKind,
  }
}

export const fileTabType: UiWorkspaceTabTypeDefinition = {
  tabTypeKey: 'file',
  pluginId: 'core',
  renderTab: (tab: WorkspaceTab, ctx: WorkspaceTabRenderContext): ReactNode => {
    if (tab.tabType !== 'file') return null
    return (
      <LazyFileTabPage
        file={buildFileResource(tab)}
        onClose={() => ctx.closeTab(tab.id)}
      />
    )
  },
  renderIcon: () => <FilesIcon />,
  getLabel: (tab: WorkspaceTab) => (tab.tabType === 'file' ? tab.fileName : ''),
  getTitle: (tab: WorkspaceTab) => (tab.tabType === 'file' ? tab.filePath : ''),
  getCloseAriaLabel: (tab: WorkspaceTab) =>
    (tab.tabType === 'file' ? `关闭文件 ${tab.fileName}` : '关闭文件标签'),
  getSidebarActivityId: () => null,
  onActivate: (tab: WorkspaceTab, ctx: WorkspaceTabRenderContext) => {
    if (tab.tabType === 'file') {
      ctx.setSelectedFilePath(tab.filePath)
    }
  },
}
