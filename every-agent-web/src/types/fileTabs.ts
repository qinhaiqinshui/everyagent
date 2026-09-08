import type { FileEditorKind, FileTabOpenMode } from '@/types'

/**
 * 文件页当前正在操作的文件资源描述。
 * 供查询层、命令层和组件层共享同一份文件上下文。
 * 文件以 `filePath` 为唯一身份（工作区模型，已无 scope 概念）。
 */
export interface FileTabResource {
  /** 文件标签 ID（形如 `file:${workspaceRoot}::${filePath}`）。 */
  id: `file:${string}`
  /** 所属工作区根(worker 机器绝对路径;多工作区并行)。 */
  workspaceRoot: string
  /** 文件相对路径。 */
  filePath: string
  /** 文件显示名称。 */
  fileName: string
  /** 关联任务 ID。 */
  taskId?: string
  /** 内容重载键。 */
  reloadKey: number
  /** 请求进入重命名态的时间戳。 */
  nameEditRequestedAt?: number
  /** 请求定位到的目标行号。 */
  lineNumber?: number
  /** 请求定位到目标行号的时间戳。 */
  lineLocateRequestedAt?: number
  /** 当前文件打开模式。 */
  mode?: FileTabOpenMode
  /** 当前匹配到的编辑器类型。 */
  editorKind?: FileEditorKind
}
