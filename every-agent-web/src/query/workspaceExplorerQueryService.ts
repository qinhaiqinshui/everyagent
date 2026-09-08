import { GIT_DIR_NAME, WORKSPACE_ROOT } from '@/platform/fs/pathUtils'
import { workspaceGateway, type WorkspaceFileStat } from '@/platform/fs/workspaceGateway'
import type {
  WorkspaceExplorerNode,
  WorkspaceExplorerOpenTarget,
} from '@/types/workspaceExplorer'

/**
 * 工作区资源树根目录显示文案。
 * 组件层不再直接依赖底层路径常量。
 */
export const WORKSPACE_EXPLORER_ROOT_LABEL = WORKSPACE_ROOT

/** 资源树读取结果:nodes 为顶层节点(懒加载,目录子节点按需加载)。 */
export interface WorkspaceTreeResult {
  nodes: WorkspaceExplorerNode[]
}

/**
 * 工作区资源树查询服务。
 * 统一负责懒加载读取工作区目录树与衍生展示数据。
 *
 * 懒加载协议(替代旧的整树 fs.tree,杜绝大项目单次 RPC 超时):
 * - 初始折叠态只读第一层(fs.list 根目录);
 * - 展开某目录再按需 loadChildren(fs.list 该目录,零递归);
 * - 打开文件定位走 reveal(fs.reveal 沿路径逐段 stat 返回节点链,旁支零查找)。
 * 目录节点 children === undefined 表示未加载,loaded 标记是否已尝试加载。
 */
export const workspaceExplorerQueryService = {
  /**
   * 读取指定工作区根目录的第一层节点(折叠态)。
   */
  async readTree(workspaceRoot: string, options?: { includeInternalFiles?: boolean }): Promise<WorkspaceTreeResult> {
    const includeInternalFiles = options?.includeInternalFiles ?? false
    const rows = await workspaceGateway.listDir(workspaceRoot, '')
    const nodes = rows
      .filter((row) => includeInternalFiles || row.name !== GIT_DIR_NAME)
      .sort(compareWorkspaceRows)
      .map((row) => toExplorerNode(workspaceRoot, row, includeInternalFiles))
    return { nodes }
  },

  /**
   * 展开目录:按需加载该目录的直接子节点(零递归)。
   * 返回子节点数组,由组件 immutable 填充回树并置 loaded。
   */
  async loadChildren(workspaceRoot: string, node: WorkspaceExplorerNode, options?: { includeInternalFiles?: boolean }): Promise<WorkspaceExplorerNode[]> {
    const includeInternalFiles = options?.includeInternalFiles ?? false
    const rows = await workspaceGateway.listDir(workspaceRoot, node.path)
    return rows
      .filter((row) => includeInternalFiles || row.name !== GIT_DIR_NAME)
      .sort(compareWorkspaceRows)
      .map((row) => toExplorerNode(workspaceRoot, row, includeInternalFiles))
  },

  /**
   * 定位文件/目录:worker 沿路径逐段 stat 返回节点链(旁支零查找)。
   * 返回链上每级节点(最后一项为目标),组件据此逐级展开并选中目标。
   */
  async reveal(workspaceRoot: string, targetPath: string, options?: { includeInternalFiles?: boolean }): Promise<WorkspaceExplorerNode[]> {
    const includeInternalFiles = options?.includeInternalFiles ?? false
    const chain = await workspaceGateway.reveal(workspaceRoot, targetPath)
    return chain
      .filter((row) => includeInternalFiles || row.name !== GIT_DIR_NAME)
      .map((row) => toExplorerNode(workspaceRoot, row, includeInternalFiles))
  },
}

/**
 * 构造单个资源树节点(单层,目录子节点不内嵌——懒加载)。
 */
function toExplorerNode(workspaceRoot: string, row: WorkspaceFileStat, includeInternalFiles: boolean): WorkspaceExplorerNode {
  if (!row.isDirectory) {
    return {
      path: row.path,
      name: row.name,
      type: 'file',
      size: row.size,
      mtimeMs: row.mtimeMs,
      openTarget: buildOpenTarget(workspaceRoot, row.path, row.name),
    }
  }
  return {
    path: row.path,
    name: row.name,
    type: 'directory',
    // 懒加载后目录大小不做子树聚合(整树统计已随 fs.tree 移除),统一为 0。
    size: 0,
    mtimeMs: row.mtimeMs,
    loaded: false,
  }
}

/**
 * 工作区目录优先,随后按中文名称排序。
 */
function compareWorkspaceRows(
  left: { isDirectory: boolean; name: string },
  right: { isDirectory: boolean; name: string },
): number {
  if (left.isDirectory !== right.isDirectory) {
    return left.isDirectory ? -1 : 1
  }
  return left.name.localeCompare(right.name, 'zh-CN')
}

/**
 * 构造文件节点的打开目标。
 */
function buildOpenTarget(workspaceRoot: string, path: string, fileName: string): WorkspaceExplorerOpenTarget {
  return {
    workspaceRoot,
    filePath: path,
    fileName,
    options: {
      mode: 'readwrite',
    },
  }
}
