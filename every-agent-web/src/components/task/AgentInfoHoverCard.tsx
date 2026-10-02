/**
 * Agent 悬停信息卡(portal 到 document.body)。
 *
 * 从 AgentListPanel 拆出,供子 agent 胶囊悬停与上下文电池点击复用同一份详情卡
 * (定位逻辑、样式、信息完全一致,数据源为 AgentListItem)。
 *
 * 定位:向上优先、空间不足向下、视口 8px margin 钳制,捕获阶段监听 scroll / resize
 * 实时贴合触发元素(胶囊或电池按钮)。
 * 内容:头部(状态圆点 + 短 ID + 主 agent 徽标 + 标题)与明细行(完整 agentId 等宽
 * 可折行、状态、创建时间、模型、累计 tokens 入/出/合计、上下文用量 + 4px 进度条)。
 *
 * 触发方式由调用方决定:
 * - 胶囊场景:onMouseEnter/onMouseLeave 接入防抖关闭(openCard/scheduleClose)。
 * - 电池场景:点击开关,不传 mouse 回调(卡内 mousedown 已 stopPropagation,不会
 *   触发电池的 document mousedown 外部关闭判定)。
 */
import React from 'react'
import { createPortal } from 'react-dom'
import type { AgentStatus } from '@/types'
import type { AgentMetaSnapshot } from '@/task/eventFolder'
import { shortAgentId } from '@/utils/shortId'
import { formatTokenCount } from '@/utils/formatTokens'
import { cn } from '@/components/shared/ui/cn'
import './AgentListPanel.css'

/** agent 列表项(胶囊 + 悬停信息卡共用数据形状)。 */
export interface AgentListItem {
  /** 展示用 agentId(主 = task.mainAgentId,子 = 子 agent id)。 */
  agentId: string
  /** 会话标题(子 agent 有 title;主 agent 固定「主 agent」)。 */
  title: string
  /** 当前状态(主 agent 由任务状态映射,子 agent 由事件状态机提供)。 */
  status: AgentStatus
  /** 是否主 agent:悬停卡标「主 agent」徽标;主 agent 胶囊不渲染底部用量线。 */
  isMain?: boolean
  /** 元数据快照(悬停信息卡数据源;主 agent 取键 '',子 agent 取键 = agentId)。 */
  meta?: AgentMetaSnapshot
  /** 上下文窗口占比(0~1,clamp;无数据 undefined)→ 子 agent 胶囊底部用量线。 */
  contextRatio?: number
}

/** 信息卡与触发元素的间距。 */
const CARD_GAP = 8
/** 信息卡与视口边缘的最小留白。 */
const VIEWPORT_MARGIN = 8

/** AgentStatus 中文文案。 */
export const AGENT_STATUS_TEXT: Record<AgentStatus, string> = {
  idle: '空闲',
  running: '运行中',
  'waiting-user': '等待用户',
  completed: '已完成',
  stopped: '已停止',
  error: '出错',
}

type UsageLevel = 'ok' | 'warn' | 'danger'

/** 上下文用量档位(>=0.85 红 / >=0.6 黄 / 其余绿)。 */
export function contextUsageLevel(ratio: number | null | undefined): UsageLevel {
  if (ratio == null) {
    return 'ok'
  }
  if (ratio >= 0.85) {
    return 'danger'
  }
  return ratio >= 0.6 ? 'warn' : 'ok'
}

export interface AgentInfoHoverCardProps {
  /** 目标 agent 列表项(含 meta 与 contextRatio)。 */
  item: AgentListItem
  /** 触发元素(portal 卡按其 rect 定位;null 时暂不定位)。 */
  anchorEl: HTMLElement | null
  /** 卡内 hover 回调(胶囊场景接入防抖关闭;电池场景不传)。 */
  onMouseEnter?: () => void
  onMouseLeave?: () => void
}

/**
 * Agent 悬停信息卡。定位仿 ContextBattery 弹层:向上优先、空间不足向下、视口 8px
 * margin 钳制,捕获阶段监听 scroll / resize 实时贴合触发元素。
 */
export function AgentInfoHoverCard({ item, anchorEl, onMouseEnter, onMouseLeave }: AgentInfoHoverCardProps) {
  const cardRef = React.useRef<HTMLDivElement>(null)
  const [position, setPosition] = React.useState<{ top: number; left: number } | null>(null)

  const label = shortAgentId(item.agentId)
  const meta = item.meta
  const contextUsed = meta?.contextUsed
  const contextWindow = meta?.contextWindow
  const hasContext = item.contextRatio != null && contextUsed != null && contextWindow != null && contextWindow > 0
  const fillPercent = hasContext ? Math.min(100, Math.max(0, (item.contextRatio ?? 0) * 100)) : 0
  const contextLine = hasContext
    ? `已用 ${formatTokenCount(contextUsed!)} / 窗口 ${formatTokenCount(contextWindow!)}（${fillPercent.toFixed(1)}%）`
    : null

  React.useLayoutEffect(() => {
    if (!anchorEl) {
      setPosition(null)
      return
    }
    const updatePosition = () => {
      const card = cardRef.current
      if (!card) {
        return
      }
      const anchorRect = anchorEl.getBoundingClientRect()
      const cardRect = card.getBoundingClientRect()
      const left = Math.max(
        VIEWPORT_MARGIN,
        Math.min(anchorRect.left, window.innerWidth - cardRect.width - VIEWPORT_MARGIN),
      )
      const upwardTop = anchorRect.top - cardRect.height - CARD_GAP
      const top = upwardTop >= VIEWPORT_MARGIN
        ? upwardTop
        : Math.max(
          VIEWPORT_MARGIN,
          Math.min(anchorRect.bottom + CARD_GAP, window.innerHeight - cardRect.height - VIEWPORT_MARGIN),
        )
      setPosition({ top, left })
    }
    updatePosition()
    // 捕获阶段监听滚动,任何祖先滚动容器滚动时都重新贴合触发元素。
    window.addEventListener('scroll', updatePosition, true)
    window.addEventListener('resize', updatePosition)
    return () => {
      window.removeEventListener('scroll', updatePosition, true)
      window.removeEventListener('resize', updatePosition)
    }
  }, [anchorEl])

  return createPortal(
    <div
      ref={cardRef}
      className="nagent-agent-card"
      role="tooltip"
      aria-label={`agent ${label} 详情`}
      style={{ position: 'fixed', top: position?.top ?? -9999, left: position?.left ?? -9999 }}
      onMouseEnter={onMouseEnter}
      onMouseLeave={onMouseLeave}
      // 卡片嵌在可点击列表行(任务列表行)内时,阻止点击/按下事件冒泡到行,避免连带触发行选中;
      // 同时阻止 mousedown 冒泡到 document,使电池点击开关场景的外部点击关闭判定不会因点卡内而误关。
      onClick={(event) => event.stopPropagation()}
      onDoubleClick={(event) => event.stopPropagation()}
      onMouseDown={(event) => event.stopPropagation()}
    >
      <div className="nagent-agent-card__head">
        <span
          className={cn('nagent-agent-card__dot', `nagent-agent-card__dot--${item.status}`)}
          aria-hidden="true"
        />
        <span className="nagent-agent-card__id">{label}</span>
        {item.title && item.title !== item.agentId && item.title !== label ? (
          <span className="nagent-agent-card__title" title={item.title}>{item.title}</span>
        ) : null}
      </div>
      <dl className="nagent-agent-card__rows">
        <div className="nagent-agent-card__row nagent-agent-card__row--id">
          <dt>agentId</dt>
          <dd>{item.agentId}</dd>
        </div>
        <div className="nagent-agent-card__row">
          <dt>状态</dt>
          <dd>{AGENT_STATUS_TEXT[item.status] ?? item.status}</dd>
        </div>
        <div className="nagent-agent-card__row">
          <dt>创建时间</dt>
          <dd>{meta?.createdAt != null ? new Date(meta.createdAt).toLocaleString() : '—'}</dd>
        </div>
        <div className="nagent-agent-card__row">
          <dt>模型</dt>
          <dd className="nagent-agent-card__ellip" title={meta?.model}>{meta?.model || '—'}</dd>
        </div>
        <div className="nagent-agent-card__row">
          <dt>输入 tokens</dt>
          <dd>{meta?.inputTokens != null ? formatTokenCount(meta.inputTokens) : '—'}</dd>
        </div>
        <div className="nagent-agent-card__row">
          <dt>输出 tokens</dt>
          <dd>{meta?.outputTokens != null ? formatTokenCount(meta.outputTokens) : '—'}</dd>
        </div>
        <div className="nagent-agent-card__row">
          <dt>合计 tokens</dt>
          <dd>{meta?.totalTokens != null ? formatTokenCount(meta.totalTokens) : '—'}</dd>
        </div>
      </dl>
      {contextLine ? (
        <div className="nagent-agent-card__context">
          <div className="nagent-agent-card__context-label">
            <span>上下文用量</span>
            <span>{contextLine}</span>
          </div>
          <div className="nagent-agent-card__bar" aria-hidden="true">
            <span
              className={cn(
                'nagent-agent-card__bar-fill',
                `nagent-agent-card__bar-fill--${contextUsageLevel(item.contextRatio)}`,
              )}
              style={{ width: `${fillPercent}%` }}
            />
          </div>
        </div>
      ) : (
        <div className="nagent-agent-card__context nagent-agent-card__context--empty">上下文用量：暂无数据</div>
      )}
    </div>,
    document.body,
  )
}
