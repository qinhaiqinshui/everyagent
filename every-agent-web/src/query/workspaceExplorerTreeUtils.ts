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

/**
 * 合并目录的最新子节点列表(增量刷新场景):以磁盘最新列表为准,
 * 但对仍存在且已加载的子目录,保留其已加载子树(children + loaded)。
 *
 * 背景:loadChildren 返回的子目录节点一律 loaded:false、无 children。
 * 若直接整体替换父目录 children,会把幸存兄弟目录已展开的子树清空,
 * 而其路径仍留在 expandedPaths → antd 仍按展开渲染(▼)却没有子节点,
 * 表现为「折叠图标状态不对」。此处按 path 匹配,把已加载子树接回新节点
 * (同时用最新元信息 size/mtime 覆盖),路径已消失/类型变化的节点按新列表。
 */
export function mergeExplorerChildrenPreservingLoaded(
  existingChildren: WorkspaceExplorerNode[] | undefined,
  freshChildren: WorkspaceExplorerNode[],
): WorkspaceExplorerNode[] {
  if (!existingChildren?.length) return freshChildren
  const existingByPath = new Map(existingChildren.map((child) => [child.path, child]))
  return freshChildren.map((fresh) => {
    const existing = existingByPath.get(fresh.path)
    if (existing && existing.type === 'directory' && fresh.type === 'directory' && existing.loaded) {
      return { ...fresh, children: existing.children, loaded: true }
    }
    return fresh
  })
}
