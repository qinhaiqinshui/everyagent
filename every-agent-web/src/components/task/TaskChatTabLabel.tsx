/**
 * src/components/task/TaskChatTabLabel.tsx
 *
 * 任务标签的动态标题:标题栏任务 tab 内联渲染「状态点 + 任务名」,任务运行时状态点
 * 显示旋转 spinner(进行中效果),随 taskStore(worker 镜像)实时刷新。
 *
 * 迁移自 n 分支 `LauncherTabLabel`(old/components/launcher/LauncherTabLabel.tsx):
 * - 旧版按 launcher 激活任务订阅 domainEventBus.TASK_STATUS_CHANGED;
 * - hub 版任务真相源在 worker,前端镜像 taskStore 由 tasks 频道事件实时 upsert,
 *   因此直接订阅 taskStore,每个任务 tab 用自身 taskId 独立取数,不再依赖激活任务概念。
 */

import React from 'react'
import { taskStore } from '@/hub/taskStore'
import TaskStatusDot from '@/components/task/TaskStatusDot'

/**
 * 任务标签内容。taskStore 查不到(草稿 tab / worker 离线)时回退纯标题、不渲染状态点。
 */
export default function TaskChatTabLabel({
  taskId,
  fallbackTitle,
}: {
  taskId: string
  /** taskStore 查不到时的回退标题(如草稿标签 DRAFT_TASK_ID)。 */
  fallbackTitle: string
}) {
  const [entry, setEntry] = React.useState(() => taskStore.get(taskId))

  React.useEffect(() => {
    // subscribe 会立即回调一次当前全量,顺带覆盖 taskId 切换时的首帧。
    return taskStore.subscribe(() => {
      setEntry(taskStore.get(taskId))
    })
  }, [taskId])

  if (!entry) {
    return <span className="launcher-tab-label">{fallbackTitle}</span>
  }
  return (
    <span className="launcher-tab-label">
      <TaskStatusDot status={entry.status} size={13} />
      <span className="launcher-tab-label__title">{entry.title}</span>
    </span>
  )
}
