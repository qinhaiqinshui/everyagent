// src/task/taskStatusPresentation.ts
//
// Task 状态展示工具（架构边界审计 7.1 A3：task 概念只允许落在 `src/task/`，已从 `src/agent/` 迁入）。
// 查询层和界面层共用这组有限取值，避免各自维护一份状态展示语义。

import type { TaskStatus } from '@/types'

export type TaskStatusTone = 'idle' | 'active' | 'waiting' | 'stopped' | 'error' | 'completed'

export function formatTaskStatus(status: TaskStatus): string {
  switch (status) {
    case 'running':
      return '运行中'
    case 'waiting-user':
      return '等待用户'
    case 'completed':
      return '完成'
    case 'stopped':
      return '停止'
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
    case 'waiting-user':
      return 'waiting'
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

/**
 * 任务是否处于"活动"状态（running 或 waiting-user）。
 * 活动态任务在 worker 侧非终态，不可删除、可接收队列输入、聊天页显示停止按钮。
 */
export function isTaskActive(status: TaskStatus): boolean {
  return status === 'running' || status === 'waiting-user'
}
