/**
 * 任务等待选择框（ui.composer_above_panel 承载的 fixed 居中模态浮层）。
 *
 * 数据流：pickerStore 单例（slash select 回调 open() 挂起 → 用户选择 choose/cancel
 * 收口）。store 未 open 时本组件渲染 null；open 时渲染半透明遮罩 + 居中浮层。
 * 样式全部插件自管（./task-wait.css，类名前缀 task-wait-picker），不用 antd。
 *
 * 交互：Esc / 点遮罩 = 取消；Enter / 点击行 = 选中；↑/↓ 切换高亮行（Enter 作用于高亮行）。
 */
import React from 'react'
import type { ComposerPanelCtx } from '@everyagent/plugin-api'
import * as pickerStore from './pickerStore'
import type { PickerTask } from './candidates'
import './task-wait.css'

/** 状态徽标文案（wire status → 中文）。 */
const STATUS_LABELS: Record<string, string> = {
  running: '运行中',
  'waiting-user': '等待输入',
  created: '已创建',
}

function statusLabel(status: string): string {
  return STATUS_LABELS[status] ?? status
}

/** 状态徽标色调修饰类（配色自管，走 CSS 变量 token）。 */
function statusTone(status: string): string {
  if (status === 'running') {
    return 'task-wait-picker__badge--running'
  }
  if (status === 'waiting-user') {
    return 'task-wait-picker__badge--waiting'
  }
  return 'task-wait-picker__badge--created'
}

export default function WaitTaskPickerPanel(_props: ComposerPanelCtx): React.ReactNode {
  const snapshot = React.useSyncExternalStore(pickerStore.subscribe, pickerStore.state)
  // 高亮行下标（键盘 ↑/↓ / 鼠标 hover 联动；重开时归零）
  const [highlighted, setHighlighted] = React.useState(0)
  const rootRef = React.useRef<HTMLDivElement | null>(null)

  const isOpen = snapshot.open
  const candidates = snapshot.candidates

  // 打开时归零高亮 + 聚焦浮层（键盘事件挂在浮层根上，Esc/Enter 才有响应）
  React.useEffect(() => {
    if (isOpen) {
      setHighlighted(0)
      rootRef.current?.focus()
    }
  }, [isOpen])

  if (!isOpen) {
    return null
  }

  // 安全夹取：候选收缩时高亮下标不越界
  const safeIndex = candidates.length === 0 ? 0 : Math.min(highlighted, candidates.length - 1)

  const handleKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault()
      pickerStore.cancel()
      return
    }
    if (candidates.length === 0) {
      return
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      setHighlighted((safeIndex + 1) % candidates.length)
    } else if (event.key === 'ArrowUp') {
      event.preventDefault()
      setHighlighted((safeIndex - 1 + candidates.length) % candidates.length)
    } else if (event.key === 'Enter') {
      event.preventDefault()
      pickerStore.choose(candidates[safeIndex])
    }
  }

  return (
    <div
      className="task-wait-picker-overlay"
      onMouseDown={(event) => {
        // 点遮罩本身 = 取消（浮层内部的点击不冒泡到这里：onMouseDown 只在 target 为遮罩时收口）
        if (event.target === event.currentTarget) {
          pickerStore.cancel()
        }
      }}
    >
      <div
        ref={rootRef}
        className="task-wait-picker"
        role="dialog"
        aria-modal="true"
        aria-label="选择要等待的任务"
        tabIndex={-1}
        onKeyDown={handleKeyDown}
      >
        <div className="task-wait-picker__header">
          <div className="task-wait-picker__title">选择要等待的任务</div>
          <div className="task-wait-picker__desc">本任务将等待所选任务完成后再继续</div>
        </div>
        <div className="task-wait-picker__list">
          {candidates.length === 0 ? (
            // 空列表兜底（select 已短路，正常流程不到达）
            <div className="task-wait-picker__empty">当前工作区没有进行中的任务</div>
          ) : (
            candidates.map((task: PickerTask, index: number) => (
              <div
                key={task.taskId}
                className={
                  'task-wait-picker__item' + (index === safeIndex ? ' task-wait-picker__item--active' : '')
                }
                role="button"
                tabIndex={-1}
                onMouseEnter={() => setHighlighted(index)}
                onClick={() => pickerStore.choose(task)}
              >
                <span className="task-wait-picker__item-title" title={task.title}>
                  {task.title}
                </span>
                <span className={`task-wait-picker__badge ${statusTone(task.status)}`}>
                  {statusLabel(task.status)}
                </span>
              </div>
            ))
          )}
        </div>
        <div className="task-wait-picker__footer">
          <button
            type="button"
            className="task-wait-picker__cancel"
            onClick={() => pickerStore.cancel()}
          >
            取消
          </button>
        </div>
      </div>
    </div>
  )
}
