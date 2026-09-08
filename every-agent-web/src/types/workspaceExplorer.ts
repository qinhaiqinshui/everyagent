import type { OpenWorkspaceFileOptions } from '@/types'

/**
 * 工作区资源树中文件节点的打开目标。
 * 组件层只消费这个描述，不直接了解底层存储实现。
 */
export interface WorkspaceExplorerOpenTarget {
  /** 所属工作区根(worker 机器绝对路径;打开操作落对应工作区)。 */
  workspaceRoot: string
  /** 文件路径。 */
  filePath: string
  /** 文件名。 */
  fileName: string
  /** 打开文件时附带的选项。 */
  options?: OpenWorkspaceFileOptions
}

/**
 * 工作区资源树节点的上下文目标。
 * 供更多操作、删除和重命名等命令入口复用。
 */
export interface WorkspaceExplorerContextTarget {
  /** 所属工作区根(worker 机器绝对路径;命令落对应工作区)。 */
  workspaceRoot: string
  /** 节点路径。 */
  path: string
  /** 节点名称。 */
  name: string
  /** 节点类型。 */
  type: 'directory' | 'file'
  /** 如果是文件节点，可附带打开目标。 */
  openTarget?: WorkspaceExplorerOpenTarget
}

/**
 * 工作区资源树节点。
 * 查询层负责构造，组件层只负责展示和交互。
 * 懒加载:目录节点 children === undefined 表示尚未加载,loaded 标记是否已尝试加载。
 */
export interface WorkspaceExplorerNode {
  /** 节点路径。 */
  path: string
  /** 节点名称。 */
  name: string
  /** 节点类型。 */
  type: 'directory' | 'file'
  /** 当前节点大小。文件为字节数;目录懒加载后不做子树聚合,恒为 0。 */
  size: number
  /** 最近修改时间。 */
  mtimeMs: number
  /** 子节点列表。目录节点已加载后才有值(空数组 = 已加载且为空目录)。 */
  children?: WorkspaceExplorerNode[]
  /** 目录节点:子节点是否已尝试加载(避免空目录重复请求)。 */
  loaded?: boolean
  /** 文件打开目标。仅文件节点会附带。 */
  openTarget?: WorkspaceExplorerOpenTarget
}
