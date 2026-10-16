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
import type { UiWorkspaceTabTypeDefinition, WorkspaceTabCreateInput, WorkspaceTabRenderContext } from '@/plugin/types'
import { FilesIcon } from '@/components/icon'
import { FileTypeIcon } from '@/components/shared/FileTypeGlyphs'
import { createLazyRouteComponent } from '@/components/shared/LazyRouteView'
import { createWorkspaceFileTab } from '@/components/app/workspaceShellState'

const LazyFileTabPage = createLazyRouteComponent(() => import('@/components/files/FileTabPage'))

export function buildFileTabId(target: {
  workspaceRoot: string
  filePath: string
}): `file:${string}` {
  // 多工作区并行:同相对路径分属不同工作区的文件是两个标签。
  return `file:${target.workspaceRoot}::${target.filePath}` as const
}

export function getFileNameFromPath(filePath: string): string {
  // 工作区外绝对路径可能带反斜杠(Windows 机器路径,如 skill.md 详情),须同时按 \ 取文件名。
  const slashIndex = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'))
  return slashIndex >= 0 ? filePath.slice(slashIndex + 1) : filePath
}

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
  /**
   * 按文件名渲染文件类型图标:已知扩展名用 FileTypeIcon 的语言徽章;
   * 未识别扩展名/无后缀沿用原来的 FilesIcon 兜底。
   */
  renderIcon: (tab: WorkspaceTab) => (
    tab.tabType === 'file'
      ? <FileTypeIcon fileName={tab.fileName} fallback={<FilesIcon />} />
      : <FilesIcon />
  ),
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
  /**
   * 创建文件标签。参数袋键：`workspaceRoot`、`filePath`、`mode?`('readwrite'|'readonly')、
   * `lineNumber?`(数字字符串)、`startNameEditing?`('1')。
   * id 由 `buildFileTabId` 确定性构造；已存在同 id 标签时保留其 mode/reloadKey。
   */
  createTab: (input: WorkspaceTabCreateInput): WorkspaceTab => {
    const data = input.data
    const workspaceRoot = data.workspaceRoot ?? ''
    const filePath = data.filePath ?? ''
    const fileTabId = buildFileTabId({ workspaceRoot, filePath })
    // 标签页已存在时，保留原 reloadKey（避免重新读盘导致编辑器卸载/重挂载闪烁），
    // 仅更新行定位字段。仅新建标签页时才设 reloadKey。
    const existing = input.currentTabs.find(
      (item) => item.id === fileTabId && item.tabType === 'file',
    ) as WorkspaceFileTab | undefined
    const mode = data.mode === 'readwrite' || data.mode === 'readonly' ? data.mode : undefined
    const lineNumber = data.lineNumber !== undefined ? Number(data.lineNumber) : undefined
    return createWorkspaceFileTab({
      id: fileTabId,
      workspaceRoot,
      filePath,
      fileName: getFileNameFromPath(filePath),
      mode: mode ?? existing?.mode ?? 'readonly',
      reloadKey: existing?.reloadKey ?? Date.now(),
      nameEditRequestedAt: data.startNameEditing ? Date.now() : undefined,
      lineNumber,
      lineLocateRequestedAt: lineNumber !== undefined ? Date.now() : undefined,
    })
  },
}
