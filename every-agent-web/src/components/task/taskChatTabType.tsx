/**
 * src/components/task/taskChatTabType.tsx
 *
 * 内置「任务聊天」标签类型定义（注册表 key `'task'`）。
 *
 * 把 `Layout` 里任务标签的渲染、激活时清空选中路径迁移到此处。
 * 任务标签随时可关闭（含运行中）:关闭标签 ≠ 停止任务,任务在 worker 上照常运行、照常落盘。
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
    // 任务进行中也可关闭标签:关闭标签 ≠ 停止任务,任务在 worker 上照常运行、照常落盘。
    // 标签关闭 = 退订 stream 频道(不再收实时推送),重新打开时重新 sub + 拉取补齐。
    ctx.closeTab(taskTab.id)
  },
}
