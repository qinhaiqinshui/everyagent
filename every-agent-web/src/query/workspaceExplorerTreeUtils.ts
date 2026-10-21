import { isAbsoluteBusinessPath, WORKSPACE_ROOT } from '@/platform/fs/pathUtils'
import type { WorkspaceExplorerNode } from '@/types/workspaceExplorer'

/**
 * 懒加载资源树的纯函数工具:按路径查找节点、immutable 回填目录子节点、选中集合清理。
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

/**
 * 从选中集合移除「被删路径本身及其子树」的 key,其余选中项保持。
 * 无变化时返回原集合引用(调用方据此免去无谓的 state 更新)。
 */
export function removePathsFromSelection(selected: Set<string>, removedPaths: string[]): Set<string> {
  if (selected.size === 0 || removedPaths.length === 0) return selected
  const next = new Set(selected)
  for (const path of selected) {
    if (removedPaths.some((removed) => path === removed || path.startsWith(`${removed}/`))) {
      next.delete(path)
    }
  }
  return next.size === selected.size ? selected : next
}

/**
 * 目录刷新后清理选中集合里已不存在的 key(「清理树选中的 key」):
 * - 只清理「落在被刷新目录范围内」的工作区路径——范围外的选中项未被本次刷新影响,
 *   且可能位于未加载子树里(按树存在性判定会把它们误删);
 * - 工作区外绝对路径(业务形态 `/C:/...` 或 `//home/...`)本就不在树中,同样保留;
 * - dirPath 为业务绝对路径,`WORKSPACE_ROOT`('/')表示工作区根:覆盖整棵树第一层及其子树。
 * 无变化时返回原集合引用。
 */
export function pruneSelectionUnderDirectory(
  dirPath: string,
  tree: WorkspaceExplorerNode[],
  selected: Set<string>,
): Set<string> {
  if (selected.size === 0) return selected
  const isRoot = dirPath === WORKSPACE_ROOT
  let changed = false
  const next = new Set<string>()
  for (const path of selected) {
    const inScope = !isAbsoluteBusinessPath(path) && (isRoot || path.startsWith(`${dirPath}/`))
    if (inScope && !findExplorerNode(tree, path)) {
      changed = true
      continue
    }
    next.add(path)
  }
  return changed ? next : selected
}
