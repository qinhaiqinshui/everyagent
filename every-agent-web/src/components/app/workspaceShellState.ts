import type {
  TaskChatTabInput,
  TopLevelPageId,
  WorkspaceTaskChatTab,
  WorkspaceFileTab,
  WorkspaceLogTab,
  WorkspacePluginTab,
  WorkspacePageTab,
  WorkspaceDiffTab,
  WorkspaceTab,
} from '@/types'

/**
 * 工作区标签状态的纯函数工具。
 *
 * 去重模型（见 workspace-tab-types-refactor-plan.md Step 3.5）：
 * 标签 `id` 即身份——打开方在调用打开接口时用「内容参数」构造稳定的 `id`
 * （如文件页 `file:${filePath}`、任务页 `task:${taskId}`），状态层按 `id` 精确
 * 匹配：已存在则复用同一标签，不存在则新建。
 * 散落的 `Map` 与 `build*TabId` 去重函数已删除，去重语义完全由调用点控制。
 */

/**
 * 从统一标签列表中取出指定类型标签。
 */
export function filterWorkspaceTabs<T extends WorkspaceTab['tabType']>(
  current: WorkspaceTab[],
  tabType: T,
): Extract<WorkspaceTab, { tabType: T }>[] {
  return current.filter((item): item is Extract<WorkspaceTab, { tabType: T }> => item.tabType === tabType)
}

/**
 * 在统一标签列表中插入或更新标签（按 `id` 精确匹配）。
 */
export function upsertWorkspaceTab<T extends WorkspaceTab>(
  current: WorkspaceTab[],
  nextTab: T,
): WorkspaceTab[] {
  const index = current.findIndex((item) => item.id === nextTab.id)
  if (index < 0) {
    return [...current, nextTab]
  }
  return current.map((item) => (item.id === nextTab.id ? nextTab : item))
}

/**
 * 从统一标签列表中移除标签。
 */
export function removeWorkspaceTab(
  current: WorkspaceTab[],
  tabId: WorkspaceTab['id'],
): WorkspaceTab[] {
  return current.filter((item) => item.id !== tabId)
}

/**
 * 创建 Task 聊天标签。id 由 `task:${taskId}` 确定性构造——同一任务恒为同一标签。
 */
export function createWorkspaceTaskChatTab(tab: TaskChatTabInput): WorkspaceTaskChatTab {
  return {
    ...tab,
    id: `task:${tab.taskId}` as const,
    tabType: 'task',
  }
}

/**
 * 创建文件标签（id 由打开方在调用处按内容参数构造后透传）。
 */
export function createWorkspaceFileTab(tab: Omit<WorkspaceFileTab, 'tabType'>): WorkspaceFileTab {
  return {
    ...tab,
    tabType: 'file',
  }
}

/**
 * 创建日志标签（id 由打开方在调用处按内容参数构造后透传）。
 */
export function createWorkspaceLogTab(tab: Omit<WorkspaceLogTab, 'tabType'>): WorkspaceLogTab {
  return {
    ...tab,
    tabType: 'log',
  }
}

/**
 * 创建页面标签。id 由 `page:${pageId}` 确定性构造——同一页面恒为同一标签。
 */
export function createWorkspacePageTab(pageId: TopLevelPageId): WorkspacePageTab {
  return {
    id: `page:${pageId}` as const,
    tabType: 'page',
    pageId,
  }
}

/**
 * 创建插件自定义工作区标签（id 由打开方在调用处构造后透传）。
 */
export function createWorkspacePluginTab(tab: Omit<WorkspacePluginTab, 'tabType'>): WorkspacePluginTab {
  return {
    ...tab,
    tabType: 'plugin',
  }
}

/**
 * 创建顶级文件差异对比标签（id 由 `diff:${filePath}` 确定性构造——同一文件恒为同一标签）。
 */
export function createWorkspaceDiffTab(tab: Omit<WorkspaceDiffTab, 'tabType' | 'id'>): WorkspaceDiffTab {
  return {
    ...tab,
    id: `diff:${tab.filePath}` as const,
    tabType: 'diff',
  }
}

/**
 * 仅当 id 命中且确为文件标签时，更新其编辑模式。
 * 抽出到状态层，使 Layout 作为纯壳不出现 `tabType ===` 业务分支。
 */
export function setWorkspaceFileTabMode(
  current: WorkspaceTab[],
  fileTabId: `file:${string}`,
  mode: 'readwrite' | 'readonly',
): WorkspaceTab[] {
  return current.map((item) => (
    item.id === fileTabId && item.tabType === 'file'
      ? { ...item, mode }
      : item
  ))
}

/**
 * 仅当 id 命中且确为文件标签时，更新其文件名编辑请求时间戳。
 */
export function setWorkspaceFileTabNameEditRequested(
  current: WorkspaceTab[],
  fileTabId: `file:${string}`,
  requestedAt?: number,
): WorkspaceTab[] {
  return current.map((item) => (
    item.id === fileTabId && item.tabType === 'file'
      ? { ...item, nameEditRequestedAt: requestedAt }
      : item
  ))
}
