/**
 * 任务输入队列面板(n 分支 QueuePanel 视觉移植 + 操作按钮)。
 *
 * 数据:taskStore 条目的 pendingInputs —— worker 侧 inputQueue 的镜像,
 * 入队/消费即 task.updated 广播实时刷新;刷新/重连经 tasks.list 内存行恢复
 * (运行时态不落盘)。队列在 worker,操作经 taskQueryService 的
 * queueMove / queueRemove RPC 下发:每条项支持 ↑ / ↓ / 编辑 / 删除;
 * 编辑 = 先回填输入框(onEditDraft)再移除该项。
 * 队列为空整个卸载(return null),挂在输入框上方(abovePanel 插槽)。
 */
import React from 'react'
import { taskStore } from '@/hub/taskStore'
import { taskQueryService } from '@/query/taskQueryService'
import './TaskQueuePanel.css'

export default function TaskQueuePanel({
  taskId,
  onEditDraft,
}: {
  taskId?: string
  onEditDraft?: (text: string) => void
}): React.ReactNode {
  const [items, setItems] = React.useState<string[]>(
    () => (taskId ? taskStore.get(taskId)?.pendingInputs : undefined) ?? [],
  )
  // 是否运行中:仅运行中任务可「插入到当前对话」(worker 侧只对热任务生效,终态随队列悬空)。
  const [running, setRunning] = React.useState<boolean>(
    () => (taskId ? taskStore.get(taskId)?.status === 'running' : false),
  )
  // 操作进行中:禁用全部按钮防止连点(worker 侧 RPC 完成前队列索引未刷新)。
  const [busy, setBusy] = React.useState(false)
  const [error, setError] = React.useState('')

  React.useEffect(() => {
    if (!taskId) return
    const update = () => {
      const entry = taskStore.get(taskId)
      setItems(entry?.pendingInputs ?? [])
      setRunning(entry?.status === 'running')
    }
    update()
    return taskStore.subscribe(update)
  }, [taskId])

  if (!taskId || items.length === 0) return null

  /** 统一异步外壳:忙态守卫 + 错误收口(面板内可读错误 + console.warn)。 */
  const runAction = (action: () => Promise<void>) => {
    if (busy) return
    setBusy(true)
    setError('')
    void action()
      .catch((actionError) => {
        const message = actionError instanceof Error ? actionError.message : '队列操作失败'
        setError(message)
        console.warn('[TaskQueuePanel] 队列操作失败:', actionError)
      })
      .finally(() => setBusy(false))
  }

  const handleMoveUp = (idx: number) => {
    if (idx === 0) return
    runAction(() => taskQueryService.moveQueuedInput(taskId, idx, idx - 1))
  }

  const handleMoveDown = (idx: number) => {
    runAction(() => taskQueryService.moveQueuedInput(taskId, idx, idx + 1))
  }

  const handleInsert = (idx: number, text: string) => {
    // 插入到当前对话:worker 侧 advisor 会随下一轮工具结果以 role=user 提交给 AI,
    // 同时从 pendingInputs 移除该项(插入即消费,避免后续被正常循环重复消化)。
    runAction(() => taskQueryService.insertQueuedInput(taskId, idx, text))
  }

  const handleEdit = (idx: number, text: string) => {
    // 编辑语义:先回填输入框,再移除该项(避免移除失败但草稿没回填的割裂)。
    onEditDraft?.(text)
    runAction(() => taskQueryService.removeQueuedInput(taskId, idx))
  }

  const handleDelete = (idx: number) => {
    runAction(() => taskQueryService.removeQueuedInput(taskId, idx))
  }

  return (
    <div className="task-queue-panel">
      <div className="task-queue-panel__list">
        {items.map((text, idx) => (
          <div key={`${idx}-${text}`} className="task-queue-panel__item">
            <div className="task-queue-panel__item-text">{text}</div>
            <div className="task-queue-panel__item-actions">
              <button
                type="button"
                className="task-queue-panel__btn task-queue-panel__btn--insert"
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => handleInsert(idx, text)}
                disabled={busy || !running}
                title={running ? '插入到当前对话' : '任务未在运行,无法插入'}
                aria-label="插入到当前对话"
              >
                <InsertGlyph />
              </button>
              <button
                type="button"
                className="task-queue-panel__btn"
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => handleMoveUp(idx)}
                disabled={busy || idx === 0}
                title="上移"
                aria-label="上移"
              >
                ↑
              </button>
              <button
                type="button"
                className="task-queue-panel__btn"
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => handleMoveDown(idx)}
                disabled={busy || idx === items.length - 1}
                title="下移"
                aria-label="下移"
              >
                ↓
              </button>
              <button
                type="button"
                className="task-queue-panel__btn"
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => handleEdit(idx, text)}
                disabled={busy}
                title="编辑"
                aria-label="编辑"
              >
                <EditGlyph />
              </button>
              <button
                type="button"
                className="task-queue-panel__btn task-queue-panel__btn--danger"
                onMouseDown={(event) => event.preventDefault()}
                onClick={() => handleDelete(idx)}
                disabled={busy}
                title="删除"
                aria-label="删除"
              >
                <DeleteGlyph />
              </button>
            </div>
          </div>
        ))}
      </div>
      {error ? <div className="task-queue-panel__error">{error}</div> : null}
    </div>
  )
}

/** 插入对话图标(右箭头进入气泡),用于队列项的「插入到当前对话」按钮。 */
function InsertGlyph() {
  return (
    <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M3 8H11M11 8L8.5 5.5M11 8L8.5 10.5" />
      <path d="M4.5 3H10.5M4.5 13H10.5" opacity="0.55" />
    </svg>
  )
}

/** 编辑图标，用于队列项的编辑按钮。 */
function EditGlyph() {
  return (
    <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M10.5 3.5L12.5 5.5L6.8 11.2L4.5 11.5L4.8 9.2Z" />
    </svg>
  )
}

/** 删除图标，用于队列项的删除按钮。 */
function DeleteGlyph() {
  return (
    <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M4 4.5H12M6.5 4.5V3.5H9.5V4.5M5 4.5L5.6 12.4H10.4L11 4.5" />
    </svg>
  )
}
