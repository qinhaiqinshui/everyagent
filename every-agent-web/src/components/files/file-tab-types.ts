import type { ComponentType } from 'react'
import type { FileEditorKind, FileTabOpenMode } from '@/types'
import type { FileTabResource } from '@/types/fileTabs'

export type { FileTabResource } from '@/types/fileTabs'

/**
 * 文件内容编辑器可注册到外壳标题栏的动作按钮。
 * 外壳负责统一布局和样式，编辑器只声明当前状态下需要什么动作。
 */
export type FileContentHeaderAction = {
  id: string
  label: string
  onClick: () => void
  title?: string
  disabled?: boolean
  active?: boolean
  groupId?: string
  /** 标题栏动作展示位置；默认进入编辑器动作区，`save-adjacent` 固定靠近保存按钮。 */
  placement?: 'default' | 'save-adjacent'
}

/**
 * 文件内容编辑器的统一 props。
 * 编辑器只负责自身展示与输入，不负责文件读写与标签操作。
 */
export type FileContentEditorProps = {
  file: FileTabResource
  mode: FileTabOpenMode
  content: string
  draftContent: string
  loading: boolean
  error: string
  lineNumber?: number
  lineLocateRequestedAt?: number
  onLineLocateApplied?: () => void
  onDraftChange: (nextValue: string) => void
  onHeaderActionsChange?: (actions: FileContentHeaderAction[]) => void
  /** 编辑器在只读态请求进入可编辑态（由外壳处理文件标签模式切换）。 */
  onRequestEditMode?: () => void
  /** 文件内查找正则（带 g 标志）；为空表示未启用查找。用于可编辑态（textarea）的高亮叠层。 */
  findRegex?: RegExp | null
  /** 当前命中序号（0-based），用于标亮「当前」匹配。 */
  findActiveIndex?: number
  /** 是否启用查找高亮（查找条打开为 true）。只读态由外层 useHighlightMatches 负责，这里仅驱动可编辑叠层。 */
  findEnabled?: boolean
}

/**
 * 单个文件类型编辑器描述。
 * 后续新增文件类型时，只需要新增 descriptor，不需要改文件标签页壳。
 */
export type FileContentEditorDescriptor = {
  /** 编辑器类型 ID。 */
  kind: FileEditorKind
  /** 用户可见名称。 */
  label: string
  /** 支持的扩展名列表，全部使用小写，包含点。 */
  extensions: string[]
  /** 是否作为未知扩展名时的兜底文本编辑器。这里只允许一个 true。 */
  isFallback?: boolean
  /** 编辑器组件。 */
  Component: ComponentType<FileContentEditorProps>
}
