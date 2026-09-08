/**
 * src/components/task/taskChatTabType.tsx
 *
 * 内置「任务聊天」标签类型定义（注册表 key `'task'`）。
 *
 * 把 `Layout` 里任务标签的渲染、激活时清空选中路径、关闭时「运行中不允许关闭」
 * 的守卫逻辑迁移到此处（原 `syncSelectionAfterTabFocus` / `closeTaskChatTab`）。
 */

import type { ReactNode } from 'react'
import type { WorkspaceTab, WorkspaceTaskChatTab } from '@/types'
import type { UiWorkspaceTabTypeDefinition, WorkspaceTabRenderContext } from '@/plugin/types'
import { TaskChatIcon } from '@/components/icon'
import { taskQueryService } from '@/query/taskQueryService'
import { DRAFT_TASK_ID } from '@/components/task/taskChatDraft'
import TaskChatTabLabel from '@/components/task/TaskChatTabLabel'
import { createLazyRouteComponent } from '@/components/shared/LazyRouteView'

const LazyTaskChat = createLazyRouteComponent(() => import('@/components/task/TaskChat'))

export const taskChatTabType: UiWorkspaceTabTypeDefinition = {
  tabTypeKey: 'task',
  pluginId: 'core',
  renderTab: (tab: WorkspaceTab, ctx: WorkspaceTabRenderContext): ReactNode => {
    if (tab.tabType !== 'task') return null
    return <LazyTaskChat taskId={tab.taskId} agentId={tab.agentId} isActive={ctx.isActiveTab ?? false} />
  },
  renderIcon: () => <TaskChatIcon />,
  // 任务标签内联「状态点 + 任务名」:运行中显示旋转 spinner,随 taskStore 实时刷新。
  renderLabel: (tab: WorkspaceTab): ReactNode => {
    if (tab.tabType !== 'task') return null
    const taskTab = tab as WorkspaceTaskChatTab
    return <TaskChatTabLabel taskId={taskTab.taskId} fallbackTitle={taskTab.title} />
  },
  getLabel: (tab: WorkspaceTab) => (tab.tabType === 'task' ? tab.title : ''),
  getTitle: (tab: WorkspaceTab) => (tab.tabType === 'task' ? tab.title : ''),
  getCloseAriaLabel: () => '关闭任务聊天',
  getSidebarActivityId: () => 'tasks',
  onActivate: (tab: WorkspaceTab, ctx: WorkspaceTabRenderContext) => {
    if (tab.tabType === 'task') {
      ctx.setSelectedFilePath(null)
    }
  },
  onClose: (tab: WorkspaceTab, ctx: WorkspaceTabRenderContext) => {
    if (tab.tabType !== 'task') return
    const taskTab = tab as WorkspaceTaskChatTab
    if (taskTab.taskId === DRAFT_TASK_ID) {
      // 草稿标签无后端任务(taskStore 必查不到),直接关闭;需要时经组"+"原样重建。
      ctx.closeTab(taskTab.id)
      return
    }
    void (async () => {
      const summary = await taskQueryService.getTaskSummarySnapshot(taskTab.taskId)
      if (!summary) {
        throw new Error(`关闭 Task 标签失败：Task 不存在 ${taskTab.taskId}`)
      }
      if (summary.status === 'running') return
      ctx.closeTab(taskTab.id)
    })().catch((closeError) => {
      ctx.showToast?.(
        closeError instanceof Error ? closeError.message : '关闭 Task 标签失败',
        'error',
      )
    })
  },
}
