import React from 'react'
import { createPortal } from 'react-dom'
import type { ContextMonitorSnapshot } from '@/types'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'

/** 弹层与触发按钮的间距。 */
const POPOVER_GAP = 8
/** 弹层与视口边缘的最小留白。 */
const VIEWPORT_MARGIN = 8

/**
 * 格式化上下文占比为百分比文本。
 *
 * 这里保留 1 位小数，避免低占用时被四舍五入成 0%。
 */
function formatUsagePercent(value: number): string {
  if (!Number.isFinite(value)) {
    return '0.0%'
  }
  return `${Math.max(0, value).toFixed(1)}%`
}

/** 24 小时制「时:分:秒」(本地时区)。 */
function formatUpdateTime(value: number): string {
  const date = new Date(value)
  const pad = (num: number) => String(num).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/** 上下文电池属性。 */
export type ContextBatteryProps = {
  /** 当前任务 ID（用于订阅其私有事件）。 */
  taskId: string
  /** 初始上下文监控快照（首次渲染用，运行时由事件实时覆盖）。 */
  monitor: ContextMonitorSnapshot | null | undefined
}

type UsageLevel = 'ok' | 'warn' | 'danger'

/**
 * 格式化 token 数量（万 / k 缩写）。
 */
export function formatTokenCount(value: number | undefined): string {
  const count = value ?? 0
  if (count >= 10000) return `${(count / 10000).toFixed(count >= 100000 ? 0 : 1)}万`
  if (count >= 1000) return `${(count / 1000).toFixed(count >= 10000 ? 0 : 1)}k`
  return String(count)
}

/**
 * 顶部横向「电池」式上下文窗口用量指示器。
 *
 * - 电池格内已用部分按用量着色（绿 / 黄 / 红），剩余部分为轨道底色。
 * - 点击电池弹出小窗，展示总窗口大小、已用、可用、占比等详情。
 * - 通过 `TASK_CONTEXT_MONITOR_CHANGED` 事件实时更新（任务执行中、上下文裁剪后均生效）。
 */
export default function ContextBattery({ taskId, monitor }: ContextBatteryProps) {
  const [live, setLive] = React.useState<ContextMonitorSnapshot | null>(monitor ?? null)
  const [open, setOpen] = React.useState(false)
  const [position, setPosition] = React.useState<{ top: number; left: number } | null>(null)
  const rootRef = React.useRef<HTMLDivElement>(null)
  const popoverRef = React.useRef<HTMLDivElement>(null)

  // 实时订阅本任务上下文监控变化。
  React.useEffect(() => {
    const unsubscribe = domainEventBus.subscribe(DOMAIN_EVENTS.TASK_CONTEXT_MONITOR_CHANGED, (payload) => {
      if (payload.taskId !== taskId || !payload.snapshot) {
        return
      }
      setLive(payload.snapshot)
    })
    return unsubscribe
  }, [taskId])

  // 外部传入的快照更新时采纳（仅接受更新的时间戳，避免覆盖事件推来的实时值）。
  React.useEffect(() => {
    if (!monitor) {
      return
    }
    setLive((current) => (
      current && current.lastUpdatedAt >= monitor.lastUpdatedAt ? current : monitor
    ))
  }, [monitor])

  // 弹层挂在 body 上，按触发按钮实时定位：优先向上展开，空间不足则向下，并做视口边界钳制。
  React.useLayoutEffect(() => {
    if (!open) {
      setPosition(null)
      return
    }
    const updatePosition = () => {
      const trigger = rootRef.current
      const popover = popoverRef.current
      if (!trigger || !popover) {
        return
      }
      const triggerRect = trigger.getBoundingClientRect()
      const popoverRect = popover.getBoundingClientRect()
      const left = Math.max(
        VIEWPORT_MARGIN,
        Math.min(triggerRect.left, window.innerWidth - popoverRect.width - VIEWPORT_MARGIN),
      )
      const upwardTop = triggerRect.top - popoverRect.height - POPOVER_GAP
      const top = upwardTop >= VIEWPORT_MARGIN
        ? upwardTop
        : Math.max(
          VIEWPORT_MARGIN,
          Math.min(triggerRect.bottom + POPOVER_GAP, window.innerHeight - popoverRect.height - VIEWPORT_MARGIN),
        )
      setPosition({ top, left })
    }
    updatePosition()
    // 捕获阶段监听滚动，任何祖先滚动容器（如任务列表）滚动时都重新贴合触发按钮。
    window.addEventListener('scroll', updatePosition, true)
    window.addEventListener('resize', updatePosition)
    return () => {
      window.removeEventListener('scroll', updatePosition, true)
      window.removeEventListener('resize', updatePosition)
    }
  }, [open])

  // 点击电池外部或按 Esc 关闭详情窗。
  React.useEffect(() => {
    if (!open) {
      return
    }
    const handlePointer = (event: MouseEvent) => {
      const target = event.target as Node
      if (rootRef.current?.contains(target) || popoverRef.current?.contains(target)) {
        return
      }
      setOpen(false)
    }
    const handleKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', handlePointer)
    document.addEventListener('keydown', handleKey)
    return () => {
      document.removeEventListener('mousedown', handlePointer)
      document.removeEventListener('keydown', handleKey)
    }
  }, [open])

  const hasData = Boolean(live)
  const usedTokens = hasData
    ? (live!.promptTokens ?? live!.totalTokens ?? 0)
    : 0
  const totalTokens = hasData ? live!.maxTokens : 0
  const availableTokens = Math.max(totalTokens - usedTokens, 0)
  const ratioPercent = totalTokens > 0 ? (usedTokens / totalTokens) * 100 : 0
  const fillPercent = Math.min(100, Math.max(0, ratioPercent))
  const ratioText = formatUsagePercent(ratioPercent)
  const level: UsageLevel = ratioPercent >= 85 ? 'danger' : ratioPercent >= 60 ? 'warn' : 'ok'

  const ariaLabel = hasData
    ? `上下文窗口用量 ${ratioText}，已用 ${formatTokenCount(usedTokens)} / 共 ${formatTokenCount(totalTokens)}`
    : '上下文窗口用量：暂无数据'

  return (
    <div className="ctx-battery" ref={rootRef}>
      <button
        type="button"
        className="ctx-battery__btn"
        aria-label={ariaLabel}
        aria-expanded={open}
        title={ariaLabel}
        onClick={(event) => {
          // 电池可能嵌在可点击的列表行内，避免连带触发行的选中 / 打开。
          event.stopPropagation()
          setOpen((current) => !current)
        }}
        onDoubleClick={(event) => event.stopPropagation()}
      >
        <span className="ctx-battery__cell" role="img" aria-hidden="true">
          <span className={`ctx-battery__fill ctx-battery__fill--${level}`} style={{ width: `${fillPercent}%` }} />
        </span>
        <span className="ctx-battery__nub" aria-hidden="true" />
      </button>

      {open && typeof document !== 'undefined' ? createPortal(
        <div
          ref={popoverRef}
          className="ctx-battery__popover"
          role="dialog"
          aria-label="上下文窗口用量详情"
          style={{
            position: 'fixed',
            top: position?.top ?? -9999,
            left: position?.left ?? -9999,
          }}
          onClick={(event) => event.stopPropagation()}
          onDoubleClick={(event) => event.stopPropagation()}
          onMouseDown={(event) => event.stopPropagation()}
        >
          <div className="ctx-battery__popover-head">
            <span className="ctx-battery__popover-title">上下文窗口</span>
            {hasData ? (
              <span className="ctx-battery__popover-model" title={live!.model}>{live!.model}</span>
            ) : null}
          </div>

          {hasData ? (
            <>
              <div className="ctx-battery__bar" aria-hidden="true">
                <span className={`ctx-battery__bar-fill ctx-battery__fill--${level}`} style={{ width: `${fillPercent}%` }} />
              </div>

              <dl className="ctx-battery__details">
                <div className="ctx-battery__row">
                  <dt>总窗口大小</dt>
                  <dd>{formatTokenCount(totalTokens)} tokens</dd>
                </div>
                <div className="ctx-battery__row">
                  <dt>已用</dt>
                  <dd>{formatTokenCount(usedTokens)} tokens</dd>
                </div>
                <div className="ctx-battery__row">
                  <dt>可用</dt>
                  <dd>{formatTokenCount(availableTokens)} tokens</dd>
                </div>
                <div className="ctx-battery__row ctx-battery__row--highlight">
                  <dt>占比</dt>
                  <dd className={`ctx-battery__pct--${level}`}>{ratioText}</dd>
                </div>
              </dl>

              <div className="ctx-battery__meta">
                <span>来源：模型返回</span>
                <span>更新：{formatUpdateTime(live!.lastUpdatedAt)}</span>
              </div>
            </>
          ) : (
            <div className="ctx-battery__empty">该任务暂无上下文用量数据</div>
          )}
        </div>,
        document.body,
      ) : null}
    </div>
  )
}
