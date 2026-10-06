import React from 'react'
import type { ContextMonitorSnapshot } from '@/types'
import { formatTokenCount } from '@/utils/formatTokens'
import { AgentInfoHoverCard, type AgentListItem } from './AgentInfoHoverCard'

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

/** 上下文电池属性。 */
export type ContextBatteryProps = {
  /** 当前任务 ID（保留给调用方锚定语境；组件本体已不按任务订阅事件）。 */
  taskId: string
  /** 初始上下文监控快照（首次渲染用，运行时由事件实时覆盖）。 */
  monitor: ContextMonitorSnapshot | null | undefined
  /**
   * 详情卡数据（主 agent 列表项；缺省时由 monitor 快照兜底构造）。
   * 聊天页传 agentListItems 中 isMain 的项，任务列表行按 contextUsage + status 整形。
   */
  agentItem?: AgentListItem | null
}

type UsageLevel = 'ok' | 'warn' | 'danger'

/**
 * 顶部横向「电池」式上下文窗口用量指示器。
 *
 * - 电池格内已用部分按用量着色（绿 / 黄 / 红），剩余部分为轨道底色。
 * - 点击电池弹出详情卡：与子 agent 悬停信息卡共用 AgentInfoHoverCard（信息与样式
 *   完全一致——状态/标题/完整 agentId/创建时间/模型/累计 tokens 与上下文用量）。
 * - 数据经 `monitor` prop 镜像更新（任务流 usage 快照随 taskStore/taskStream 刷新）。
 */
export default function ContextBattery({ monitor, agentItem }: ContextBatteryProps) {
  const [live, setLive] = React.useState<ContextMonitorSnapshot | null>(monitor ?? null)
  const [open, setOpen] = React.useState(false)
  const rootRef = React.useRef<HTMLDivElement>(null)

  // 外部传入的快照更新时采纳（仅接受更新的时间戳，避免旧值覆盖实时值）。
  React.useEffect(() => {
    if (!monitor) {
      return
    }
    setLive((current) => (
      current && current.lastUpdatedAt >= monitor.lastUpdatedAt ? current : monitor
    ))
  }, [monitor])

  // 点击电池外部或按 Esc 关闭详情卡。卡内 mousedown 已由 AgentInfoHoverCard
  // stopPropagation(不会冒泡到 document),此处只需排除电池按钮自身。
  React.useEffect(() => {
    if (!open) {
      return
    }
    const handlePointer = (event: MouseEvent) => {
      const target = event.target as Node
      if (rootRef.current?.contains(target)) {
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
  const ratioPercent = totalTokens > 0 ? (usedTokens / totalTokens) * 100 : 0
  const fillPercent = Math.min(100, Math.max(0, ratioPercent))
  const ratioText = formatUsagePercent(ratioPercent)
  const level: UsageLevel = ratioPercent >= 85 ? 'danger' : ratioPercent >= 60 ? 'warn' : 'ok'

  const ariaLabel = hasData
    ? `上下文窗口用量 ${ratioText}，已用 ${formatTokenCount(usedTokens)} / 共 ${formatTokenCount(totalTokens)}`
    : '上下文窗口用量：暂无数据'

  // 详情卡数据:优先调用方传入的主 agent 列表项;缺省时由 monitor 快照兜底构造
  // (如聊天页 entry 未就绪的短暂窗口),缺失字段由卡片显示「—」。
  const cardItem: AgentListItem = agentItem ?? {
    agentId: '',
    title: '主 agent',
    status: 'idle',
    isMain: true,
    meta: hasData
      ? {
          agentId: '',
          model: live!.model,
          contextUsed: usedTokens,
          contextWindow: totalTokens > 0 ? totalTokens : undefined,
          updatedAt: live!.lastUpdatedAt,
        }
      : undefined,
    contextRatio: totalTokens > 0 ? Math.min(1, Math.max(0, usedTokens / totalTokens)) : undefined,
  }

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

      {/* 详情卡与子 agent 悬停卡同构(组件自带 portal + 定位);点击开关,卡内点击不冒泡。 */}
      {open ? (
        <AgentInfoHoverCard item={cardItem} anchorEl={rootRef.current} />
      ) : null}
    </div>
  )
}
