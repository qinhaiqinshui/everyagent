import type { WorkspaceExplorerNode } from '@/types/workspaceExplorer'

/**
 * 懒加载资源树的纯函数工具:按路径查找节点、immutable 回填目录子节点。
 * 供资源管理器面板(OpenFilesSidebarPanel)与路径选择器(WorkspacePathPicker)共享。
 */

/** 在资源树中按路径查找节点(递归)。 */
export function findExplorerNode(nodes: WorkspaceExplorerNode[], path: string): WorkspaceExplorerNode | null {
  for (const node of nodes) {
    if (node.path === path) return node
    if (node.type === 'directory' && node.children) {
      const hit = findExplorerNode(node.children, path)
      if (hit) return hit
    }
  }
  return null
}

/**
 * 把目录节点的子节点 immutable 更新回树(置 loaded,空目录可空数组)。
 * 未找到目标路径时原样返回(树中无该目录,不破坏现有结构)。
 */
export function upsertExplorerChildren(nodes: WorkspaceExplorerNode[], dirPath: string, children: WorkspaceExplorerNode[]): WorkspaceExplorerNode[] {
  return nodes.map((node) => {
    if (node.path === dirPath && node.type === 'directory') {
      return { ...node, children, loaded: true }
    }
    if (node.type === 'directory' && node.children) {
      const updatedChildren = upsertExplorerChildren(node.children, dirPath, children)
      if (updatedChildren !== node.children) {
        return { ...node, children: updatedChildren }
      }
    }
    return node
  })
}
