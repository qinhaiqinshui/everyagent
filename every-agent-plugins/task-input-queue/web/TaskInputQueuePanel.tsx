/**
 * 任务输入队列面板(n 分支 QueuePanel 视觉移植 + 操作按钮)。
 *
 * 数据源:插件自持——`task.queueSnapshot` RPC(热任务内存队列 / 终态任务磁盘悬空队列),
 * 以 `ctx.subscribeTaskEvents('task.updated')` 为刷新信号(worker 队列入队/轮间消费/增删改
 * 均广播 task.updated 携带 pendingInputs,taskStore 收到即触发本面板重拉)。
 * 队列专有数据不进宿主公共类型(ComposerPanelCtx/TaskListEntry 均无 pendingInputs)。
 *
 * 队列在 worker,操作经 ctx.rpc 的 task.queueRemove/move 下发:
 * 每条项支持 插入 / ↑ / 编辑 / 删除;
 * 插入 = `task.run{taskId, input, metadata:{insert:true, index}}`——worker 侧 queue.dispatch
 * 按 index 从输入队列**摘掉该项**并入本轮主 agent 的插入队列,DialogInsertAdvisor 随下一轮
 * 工具结果以 role=user 提交给 AI 并发 `user.message`;摘除后的 pendingInputs 广播即本面板收敛信号
 * (故此处不再补发 queueRemove)。未及 drain 的项由 queue.loop 回收,不丢输入。
 * 编辑 = 先回填输入框再移除该项。
 * 队列为空整个卸载(return null),挂在输入框上方(abovePanel 插槽)。
 */
import React from 'react'
import type { ComposerPanelCtx } from '@everyagent/plugin-api'
import './task-input-queue.css'

export default function TaskInputQueuePanel(ctx: ComposerPanelCtx): React.ReactNode {
  const taskId = ctx.taskId
  const running = ctx.isRunning
  // 队列快照(插件自持状态;空=无排队,面板卸载)
  const [items, setItems] = React.useState<string[]>([])
  const [busy, setBusy] = React.useState(false)
  const [error, setError] = React.useState('')

  const itemsRef = React.useRef(items)
  itemsRef.current = items

  /** 拉取最新队列快照(静默失败:面板数据缺失不打扰用户)。 */
  const refresh = React.useCallback(() => {
    if (!taskId) return
    void Promise.resolve(ctx.rpc('task.queueSnapshot', { taskId }))
      .then((res) => {
        const next = (res as { pendingInputs?: string[] } | null)?.pendingInputs
        const list = Array.isArray(next) ? next : []
        // 仅在内容变化时 setState,避免 task.updated 高频信号下无谓重渲染
        const prev = itemsRef.current
        if (prev.length === list.length && prev.every((v, i) => v === list[i])) return
        itemsRef.current = list
        setItems(list)
      })
      .catch((refreshError) => {
        // 版本偏差(worker 未升级到含 task.queueSnapshot 的插件)等:静默降级为不显示
        console.warn('[TaskQueuePanel] 队列快照拉取失败:', refreshError)
      })
  }, [taskId, ctx.rpc])

  // 数据流:taskId 变化首拉 + task.updated 广播信号触发重拉。
  // subscribeTaskEvents 的 ctx.rpc 依赖随 memo 变化,重订阅无害(退订旧/订新)。
  React.useEffect(() => {
    if (!taskId) {
      setItems([])
      return
    }
    refresh()
    return ctx.subscribeTaskEvents((event) => {
      // 只响应 tasks 频道信号(task.updated:入队/轮间消费/增删改/status 变化);
      // task.stream(delta/thinking 高频流式)与队列无关,跳过防抖动。
      if (event === 'task.updated') refresh()
    })
  }, [taskId, ctx.subscribeTaskEvents, refresh])

  /** 统一异步外壳:忙态守卫 + 错误收口 + 完成后重拉快照(RPC ok 早于广播时兜底)。 */
  const runAction = (action: () => Promise<unknown>) => {
    if (busy) return
    setBusy(true)
    setError('')
    void action()
      .then(() => refresh())
      .catch((actionError) => {
        const message = actionError instanceof Error ? actionError.message : '队列操作失败'
        setError(message)
        console.warn('[TaskQueuePanel] 队列操作失败:', actionError)
      })
      .finally(() => setBusy(false))
  }

  const handleMoveUp = (idx: number) => {
    if (idx === 0) return
    runAction(() => ctx.rpc('task.queueMove', { taskId, fromIndex: idx, toIndex: idx - 1 }))
  }

  const handleInsert = (idx: number, text: string) => {
    // 插入到当前对话:通过 task.run 携带 metadata.insert=true,worker 侧在工具循环
    // 把该输入以 role=user 随工具结果一并提交给模型,同时从队列移除该项。
    runAction(() => ctx.rpc('task.run', { taskId, input: text, metadata: { insert: true, index: idx } }))
  }

  const handleEdit = (idx: number, _text: string) => {
    // 编辑语义:先回填输入框,再移除该项(避免移除失败但草稿没回填的割裂)。
    // 回填由宿主 ui.appendComposerText / ui.setComposerRawContent 处理,
    // 此处仅移除队列项。
    runAction(() => ctx.rpc('task.queueRemove', { taskId, index: idx }))
  }

  const handleDelete = (idx: number) => {
    runAction(() => ctx.rpc('task.queueRemove', { taskId, index: idx }))
  }

  if (!taskId || items.length === 0) return null

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
