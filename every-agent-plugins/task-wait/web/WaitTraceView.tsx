/**
 * 等待任务 trace 行视图（ui.kind_views，kind='task.wait'，整行接管）。
 *
 * payload 为 trace 形状 `{title, summary?, content?, status?, data?}`（宿主 eventFolder
 * 默认 handler 按 seq upsert 原样透传）；`data` 由后端 WaitTaskAdvisor 落契约：
 * `{state: 'waiting'|'done'|'error'|'cancelled', targetTaskId, targetTitle,
 *   startedAt, endedAt?, waitedMs?, targetStatus?}`。
 *
 * - waiting：时钟图标 + 「等待「目标标题」」+ 每 1s 重算已等待 H:MM:SS
 *   （startedAt 缺失显示「等待中」；卸载 / 终态清定时器）；
 * - done：「等待完成（XmYs）」（用 data.waitedMs 格式化）；
 * - error：「目标任务未成功完成，等待中止」（error 色）；
 * - cancelled：「本任务已取消，等待中止」。
 *
 * 整行可点（有 targetTaskId 时）→ openTargetTask 回调（activate 闭包注入的
 * ctx.ui.openTab('task', …)，见 index.ts）。data 可能整体缺失：全部字段安全读取。
 */
import React from 'react'
import type { PluginKindViewProps } from '@everyagent/plugin-api'
import './task-wait.css'

/** 组件额外 props：activate 闭包注入的目标任务打开回调。 */
export interface WaitTraceViewProps extends PluginKindViewProps {
  openTargetTask: (taskId: string, title: string) => void
}

/** trace data 契约形状（后端 WaitTaskAdvisor 落盘字段）。 */
interface WaitTraceData {
  state: 'waiting' | 'done' | 'error' | 'cancelled'
  targetTaskId: string
  targetTitle: string
  startedAt: number | null
  endedAt: number | null
  waitedMs: number | null
}

/** 宽松读取字符串（空串 → null）。 */
function readString(value: unknown): string | null {
  return typeof value === 'string' && value.trim().length > 0 ? value : null
}

/** 宽松读取有限数字（→ null）。 */
function readNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

/** 从 payload（trace 形状）安全读取 data 契约字段；data 缺失时逐项降级。 */
function readTraceData(payload: unknown): WaitTraceData {
  const record =
    payload !== null && typeof payload === 'object' && !Array.isArray(payload)
      ? (payload as Record<string, unknown>)
      : {}
  const dataRaw = record.data
  const data =
    dataRaw !== null && typeof dataRaw === 'object' && !Array.isArray(dataRaw)
      ? (dataRaw as Record<string, unknown>)
      : {}
  const stateRaw = readString(data.state) ?? readString(record.status)
  const state: WaitTraceData['state'] =
    stateRaw === 'done' || stateRaw === 'error' || stateRaw === 'cancelled' ? stateRaw : 'waiting'
  const title = readString(data.targetTitle) ?? readString(record.title)
  return {
    state,
    targetTaskId: readString(data.targetTaskId) ?? '',
    targetTitle: title ?? '任务',
    startedAt: readNumber(data.startedAt),
    endedAt: readNumber(data.endedAt),
    waitedMs: readNumber(data.waitedMs),
  }
}

/** 已等待时长格式化：H:MM:SS（读秒用）。 */
function formatElapsed(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

/** 完成耗时格式化：XmYs / Ns（与后端 WaitTaskAdvisor.formatDuration 同口径）。 */
function formatDuration(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return minutes === 0 ? `${seconds}s` : `${minutes}m${seconds}s`
}

/** 时钟图标（waiting 态读秒行首，currentColor 上色）。 */
function ClockGlyph(): React.ReactNode {
  return (
    <svg
      viewBox="0 0 16 16"
      width="14"
      height="14"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <circle cx="8" cy="8" r="6" />
      <path d="M8 4.5V8l2.5 1.5" />
    </svg>
  )
}

/** 完成图标（勾）。 */
function DoneGlyph(): React.ReactNode {
  return (
    <svg
      viewBox="0 0 16 16"
      width="14"
      height="14"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <circle cx="8" cy="8" r="6" />
      <path d="M5.2 8.2 7 10l3.8-4" />
    </svg>
  )
}

/** 失败图标（叉）。 */
function ErrorGlyph(): React.ReactNode {
  return (
    <svg
      viewBox="0 0 16 16"
      width="14"
      height="14"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <circle cx="8" cy="8" r="6" />
      <path d="M6 6l4 4M10 6l-4 4" />
    </svg>
  )
}

/** 取消图标（斜杠）。 */
function CancelledGlyph(): React.ReactNode {
  return (
    <svg
      viewBox="0 0 16 16"
      width="14"
      height="14"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <circle cx="8" cy="8" r="6" />
      <path d="M5.6 5.6l4.8 4.8" />
    </svg>
  )
}

export default function WaitTraceView(props: WaitTraceViewProps): React.ReactNode {
  const { payload, openTargetTask } = props
  const data = readTraceData(payload)

  // waiting 态读秒：每 1s 重算（now - startedAt）；卸载 / 终态自动清定时器。
  const [now, setNow] = React.useState(() => Date.now())
  React.useEffect(() => {
    if (data.state !== 'waiting' || data.startedAt === null) {
      return
    }
    setNow(Date.now())
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [data.state, data.startedAt])

  const title = `等待「${data.targetTitle}」`

  let icon: React.ReactNode
  let meta: React.ReactNode
  let toneClass: string
  switch (data.state) {
    case 'done': {
      icon = <DoneGlyph />
      const waited =
        data.waitedMs !== null
          ? formatDuration(data.waitedMs)
          : readString((payload as Record<string, unknown> | null)?.summary) ?? ''
      meta = waited ? `等待完成（${waited}）` : '等待完成'
      toneClass = 'task-wait-trace--done'
      break
    }
    case 'error': {
      icon = <ErrorGlyph />
      meta = '目标任务未成功完成，等待中止'
      toneClass = 'task-wait-trace--error'
      break
    }
    case 'cancelled': {
      icon = <CancelledGlyph />
      meta = '本任务已取消，等待中止'
      toneClass = 'task-wait-trace--cancelled'
      break
    }
    default: {
      icon = <ClockGlyph />
      // startedAt 缺失（防御）：不读秒，静态「等待中」
      meta = data.startedAt !== null ? `已等待 ${formatElapsed(now - data.startedAt)}` : '等待中'
      toneClass = 'task-wait-trace--waiting'
      break
    }
  }

  const clickable = data.targetTaskId.length > 0
  const handleOpen = React.useCallback(() => {
    if (clickable) {
      openTargetTask(data.targetTaskId, data.targetTitle)
    }
  }, [clickable, data.targetTaskId, data.targetTitle, openTargetTask])

  return (
    <div
      className={`task-wait-trace ${toneClass}${clickable ? ' task-wait-trace--clickable' : ''}`}
      role={clickable ? 'button' : undefined}
      tabIndex={clickable ? 0 : undefined}
      title={clickable ? '打开目标任务' : undefined}
      onClick={handleOpen}
      onKeyDown={(event) => {
        if (clickable && (event.key === 'Enter' || event.key === ' ')) {
          event.preventDefault()
          handleOpen()
        }
      }}
    >
      <span className="task-wait-trace__icon">{icon}</span>
      <span className="task-wait-trace__title">{title}</span>
      <span className="task-wait-trace__meta">{meta}</span>
    </div>
  )
}
