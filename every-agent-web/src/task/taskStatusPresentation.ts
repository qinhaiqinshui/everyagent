// src/task/taskStatusPresentation.ts
//
// Task 状态展示工具（架构边界审计 7.1 A3：task 概念只允许落在 `src/task/`，已从 `src/agent/` 迁入）。
// 查询层和界面层共用这组有限取值，避免各自维护一份状态展示语义。

import type { TaskStatus } from '@/types'

export type TaskStatusTone = 'idle' | 'active' | 'stopped' | 'error' | 'completed'

export function formatTaskStatus(status: TaskStatus): string {
  switch (status) {
    case 'running':
      return '运行中'
    case 'completed':
      return '已完成'
    case 'stopped':
      return '已停止'
    case 'error':
      return '错误'
    default:
      return '未开始'
  }
}

export function resolveTaskStatusTone(status: TaskStatus): TaskStatusTone {
  switch (status) {
    case 'running':
      return 'active'
    case 'completed':
      return 'completed'
    case 'stopped':
      return 'stopped'
    case 'error':
      return 'error'
    default:
      return 'idle'
  }
}
