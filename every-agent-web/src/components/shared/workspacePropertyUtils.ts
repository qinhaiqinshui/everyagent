import { workspaceActivity, type WorkspaceEntry } from '@/hub/workspaceRegistry'
import type { PropertyItem } from './PropertiesDialog'
import { formatPropertyTime } from '@/components/files/filePropertyUtils'

/**
 * 工作区属性展示的共享纯函数：工作区显示名、属性条目组装。
 * 资源管理器工作区分组「更多」菜单与任务面板工作区组「更多」菜单共用，保证两处
 * 的「工作区属性」弹窗展示完全一致。
 */

/** 工作区显示名：根路径最后一段（如 D:\projects\novel → novel；盘符根/斜杠根退化为全路径）。 */
export function getWorkspaceDisplayName(root: string): string {
  const normalized = root.replace(/[\\/]+$/, '')
  const index = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'))
  return index >= 0 ? normalized.slice(index + 1) : normalized
}

/** 组装工作区属性弹窗条目：名称/根路径/Id/最后活动时间。 */
export function buildWorkspacePropertyItems(entry: WorkspaceEntry): PropertyItem[] {
  return [
    { label: '名称', value: getWorkspaceDisplayName(entry.root) },
    { label: '根路径', value: entry.root },
    { label: 'Id', value: entry.id ?? '—' },
    { label: '最后活动时间', value: formatPropertyTime(workspaceActivity(entry)) },
  ]
}