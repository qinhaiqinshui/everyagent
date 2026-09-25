/**
 * 编辑重发状态 Hook（从 TaskChat.tsx 的 editTarget 逻辑迁入）。
 *
 * 模块级 store（按 taskId 隔离，跨任务标签独立）：
 * - EditMessageButton 点击 → startEdit（记录编辑目标 seq）；
 * - 再点 → cancelEdit（清除标记，不动输入框内容）；
 * - 提交贡献 provider（task.submit_contributions 扩展点）在提交前把
 *   { editSeq } 作为 metadata 透传给 task.run（核心不解释），提交成功后清除。
 */
import React from 'react'
import type { TaskRunSubmitContributionProvider } from '@everyagent/plugin-api'

/** 编辑目标：被编辑消息的 seq（字符串雪花 ID）。 */
export interface EditTarget {
  seq: string
}

const editTargets = new Map<string, EditTarget>()
const listeners = new Set<() => void>()

function notify(): void {
  for (const listener of listeners) listener()
}

/** 读取某任务当前的编辑目标（无编辑模式返回 null）。 */
export function getEditTarget(taskId: string): EditTarget | null {
  return editTargets.get(taskId) ?? null
}

/** 订阅编辑态变化（进入/退出编辑模式、提交成功清除）。 */
export function subscribeEditResend(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/**
 * 编辑重发状态 Hook。
 *
 * 返回 { editTarget, startEdit, cancelEdit }：
 * - startEdit: 进入编辑模式（调用方负责把原消息内容回填输入框）；
 * - cancelEdit: 退出编辑模式（清除编辑标记，不删除输入框内容）。
 */
export function useEditResend(taskId: string | undefined): {
  editTarget: EditTarget | null
  startEdit: (seq: number | string) => void
  cancelEdit: () => void
} {
  const editTarget = React.useSyncExternalStore(
    subscribeEditResend,
    () => (taskId ? getEditTarget(taskId) : null),
  )
  const startEdit = React.useCallback((seq: number | string) => {
    if (!taskId) return
    editTargets.set(taskId, { seq: String(seq) })
    notify()
  }, [taskId])
  const cancelEdit = React.useCallback(() => {
    if (!taskId) return
    if (editTargets.delete(taskId)) notify()
  }, [taskId])
  return { editTarget, startEdit, cancelEdit }
}

/**
 * 构造 task.run 提交贡献 provider（task.submit_contributions 扩展点）。
 *
 * 编辑模式下：
 * - getContribution → { metadata: { editSeq }, submitLabel: '重新发送', submitDanger: true }
 *   （editSeq 作为 metadata 透传到后端，由本插件的 EditResendNode(395.5) 消费截断）；
 * - onSubmitted → 提交成功后清除编辑目标。
 */
export function createEditResendContributionProvider(): TaskRunSubmitContributionProvider {
  return {
    id: 'task-edit-resend',
    getContribution: (taskId) => {
      const target = getEditTarget(taskId)
      if (!target) return null
      return {
        metadata: { editSeq: target.seq },
        submitLabel: '重新发送',
        submittingLabel: '重新发送中...',
        submitDanger: true,
      }
    },
    subscribe: subscribeEditResend,
    onSubmitted: (taskId) => {
      if (editTargets.delete(taskId)) notify()
    },
  }
}
