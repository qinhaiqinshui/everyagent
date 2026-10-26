/**
 * 任务等待选择框—— showDialog 主体内容组件。
 *
 * 宿主 PluginDialogHost 提供模态壳（遮罩 + 居中浮层 + 头部标题/副标题 +
 * Esc/点遮罩取消 + 程序聚焦）；本组件只负责候选列表渲染与键盘导航，
 * 经 props.close(task|null) 收口。
 *
 * 键盘：↑/↓ 切换高亮行，Enter 选中高亮行（Esc 由宿主壳统一处理）。
 * 样式沿用 task-wait-picker__* 类名（./task-wait.css），遮罩/浮层/头部类
 * 已随壳迁移到宿主（pluginDialogHost.css），本文件不再使用。
 */
import React from 'react'
import type { PluginDialogProps } from '@everyagent/plugin-api'
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

export interface WaitTaskPickerContentProps extends PluginDialogProps<PickerTask> {
  /** 候选任务列表（showDialog 调用方经闭包传入）。 */
  candidates: PickerTask[]
}

export default function WaitTaskPickerContent({ candidates, close }: WaitTaskPickerContentProps): React.ReactNode {
  // 高亮行下标（键盘 ↑/↓ / 鼠标 hover 联动）
  const [highlighted, setHighlighted] = React.useState(0)

  // 安全夹取：候选收缩时高亮下标不越界
  const safeIndex = candidates.length === 0 ? 0 : Math.min(highlighted, candidates.length - 1)

  const handleKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
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
      close(candidates[safeIndex])
    }
  }

  return (
    <div className="task-wait-picker__body" onKeyDown={handleKeyDown}>
      <div className="task-wait-picker__list">
        {candidates.length === 0 ? (
          // 空列表兜底（select 已短路 toast，正常流程不到达）
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
              onClick={() => close(task)}
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
          onClick={() => close(null)}
        >
          取消
        </button>
      </div>
    </div>
  )
}
