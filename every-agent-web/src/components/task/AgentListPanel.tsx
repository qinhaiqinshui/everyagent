/**
 * Agent 长条列表面板(输入框上方 abovePanel 区)。
 *
 * 一行平铺、可横向滚动(原生滚动条隐藏,改用覆盖式指示条:滚动时淡入,停止滚动后淡出,
 * thumb 可拖拽);每个 agent 一个胶囊长条:左侧状态圆点 + 短 ID(主 agent =
 * task.mainAgentId,子 agent = sub_…)+ 可选标题,不同背景色 = 不同状态(running /
 * waiting-user / completed / error / stopped),running 态条内有流光滑过、圆点呼吸。
 * 点击某 agent → 线程只显示已加载内容中该 agent 的消息(TaskRoundsPanel 按归属过滤,
 * 纯渲染派生、不触发拉取);再次点击同一 agent 恢复「全部」。仅当任务存在子 agent 时渲染
 * (纯主 agent 任务无此面板)。
 *
 * 增强:
 * - 子 agent 胶囊底部 2px 上下文用量线(contextRatio,绿/黄/红按档位);父胶囊
 *   overflow:hidden + 圆角 999px 天然裁剪,100% 时线铺满下边框、两端收成贴合弧线。
 * - 悬停/聚焦胶囊弹出信息卡(AgentInfoHoverCard,portal 到 body):状态/标题/完整
 *   agentId/创建时间/模型/累计 tokens 与上下文用量(含 4px 进度条)。主 agent 同样
 *   显示(数据源键 ''),标「主 agent」徽标、不渲染用量线。
 */
import React from 'react'
import { createPortal } from 'react-dom'
import type { AgentStatus } from '@/types'
import type { AgentMetaSnapshot } from '@/task/eventFolder'
import { shortAgentId } from '@/utils/shortId'
import { cn } from '@/components/shared/ui/cn'
import OverlayScrollbar from '@/components/shared/ui/OverlayScrollbar'
import { formatTokenCount } from './ContextBattery'
import './AgentListPanel.css'

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

export interface AgentListPanelProps {
  agents: AgentListItem[]
  /** 当前过滤:'' = 全部;主 = mainAgentId;子 = 子 id。 */
  filterAgentId: string
  /** 选中回调:传 '' 表示恢复「全部」。 */
  onSelect: (agentId: string) => void
}

/** 信息卡与触发胶囊的间距(仿 ContextBattery 弹层)。 */
const CARD_GAP = 8
/** 信息卡与视口边缘的最小留白。 */
const VIEWPORT_MARGIN = 8
/** 鼠标离开后的延迟关闭缓冲(防抖动误关;再次 enter 即取消)。 */
const CLOSE_DELAY_MS = 120

/** AgentStatus 中文文案。 */
const AGENT_STATUS_TEXT: Record<AgentStatus, string> = {
  idle: '空闲',
  running: '运行中',
  'waiting-user': '等待用户',
  completed: '已完成',
  stopped: '已停止',
  error: '出错',
}

type UsageLevel = 'ok' | 'warn' | 'danger'

/** 上下文用量档位(>=0.85 红 / >=0.6 黄 / 其余绿),阈值与 ContextBattery 一致。 */
function contextUsageLevel(ratio: number | null | undefined): UsageLevel {
  if (ratio == null) {
    return 'ok'
  }
  if (ratio >= 0.85) {
    return 'danger'
  }
  return ratio >= 0.6 ? 'warn' : 'ok'
}

export default function AgentListPanel({ agents, filterAgentId, onSelect }: AgentListPanelProps) {
  const scrollRef = React.useRef<HTMLDivElement>(null)
  // 悬停信息卡(单例):同一时间只显示一张(当前 agentId | null)。enter/focus 打开,
  // leave/blur 经 120ms 缓冲后关闭(防鼠标在胶囊与卡之间抖动误关),再次 enter 取消关闭。
  const [hoverAgentId, setHoverAgentId] = React.useState<string | null>(null)
  const hoverAnchorRef = React.useRef<HTMLElement | null>(null)
  const closeTimerRef = React.useRef<number | null>(null)

  const clearCloseTimer = React.useCallback(() => {
    if (closeTimerRef.current != null) {
      window.clearTimeout(closeTimerRef.current)
      closeTimerRef.current = null
    }
  }, [])

  const scheduleClose = React.useCallback(() => {
    clearCloseTimer()
    closeTimerRef.current = window.setTimeout(() => {
      closeTimerRef.current = null
      setHoverAgentId(null)
    }, CLOSE_DELAY_MS)
  }, [clearCloseTimer])

  const openCard = React.useCallback((agentId: string, anchor: HTMLElement) => {
    clearCloseTimer()
    hoverAnchorRef.current = anchor
    setHoverAgentId(agentId)
  }, [clearCloseTimer])

  React.useEffect(() => clearCloseTimer, [clearCloseTimer])

  const hoverItem = hoverAgentId == null ? null : (agents.find((agent) => agent.agentId === hoverAgentId) ?? null)
  if (agents.length <= 1) return null
  return (
    <div className="nagent-agent-list" role="group" aria-label="任务 agent 列表">
      <div className="nagent-agent-list__frame">
        <div className="nagent-agent-list__scroll" ref={scrollRef}>
          {agents.map((agent) => {
            const label = shortAgentId(agent.agentId)
            const selected = filterAgentId === agent.agentId
            return (
              <button
                key={agent.agentId}
                type="button"
                className={cn(
                  'nagent-agent',
                  `nagent-agent--${agent.status}`,
                  selected && 'is-selected',
                )}
                onClick={() => onSelect(selected ? '' : agent.agentId)}
                aria-pressed={selected}
                aria-label={`agent ${label}${agent.title && agent.title !== label ? ` · ${agent.title}` : ''}（点击${
                  selected ? '恢复全部' : '只看该 agent'
                }，悬停查看详情）`}
                onMouseEnter={(event) => openCard(agent.agentId, event.currentTarget)}
                onMouseLeave={scheduleClose}
                onFocus={(event) => openCard(agent.agentId, event.currentTarget)}
                onBlur={scheduleClose}
              >
                <span className="nagent-agent__dot" aria-hidden="true" />
                <span className="nagent-agent__label">{label}</span>
                {agent.title && agent.title !== label ? (
                  <span className="nagent-agent__title">{agent.title}</span>
                ) : null}
                {!agent.isMain && agent.contextRatio != null ? (
                  <span
                    className={cn(
                      'nagent-agent__usage',
                      `nagent-agent__usage--${contextUsageLevel(agent.contextRatio)}`,
                    )}
                    style={{ width: `${agent.contextRatio * 100}%` }}
                    aria-hidden="true"
                  />
                ) : null}
              </button>
            )
          })}
        </div>
        <OverlayScrollbar targetRef={scrollRef} version={agents.length} hoverReveal />
      </div>
      {hoverItem ? (
        <AgentInfoHoverCard
          item={hoverItem}
          anchorEl={hoverAnchorRef.current}
          onMouseEnter={clearCloseTimer}
          onMouseLeave={scheduleClose}
        />
      ) : null}
    </div>
  )
}

interface AgentInfoHoverCardProps {
  /** 目标 agent 列表项(含 meta 与 contextRatio)。 */
  item: AgentListItem
  /** 触发胶囊元素(portal 卡按其 rect 定位;null 时暂不定位)。 */
  anchorEl: HTMLElement | null
  onMouseEnter: () => void
  onMouseLeave: () => void
}

/**
 * agent 悬停信息卡(portal 到 document.body)。定位仿 ContextBattery:向上优先、
 * 空间不足向下、视口 8px margin 钳制,捕获阶段监听 scroll / resize 实时贴合触发胶囊。
 * 内容:头部(状态圆点 + 短 ID + 主 agent 徽标 + 标题)与明细行(完整 agentId 等宽
 * 可折行、状态、创建时间、模型、累计 tokens 入/出/合计、上下文用量 + 4px 进度条)。
 */
function AgentInfoHoverCard({ item, anchorEl, onMouseEnter, onMouseLeave }: AgentInfoHoverCardProps) {
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
    // 捕获阶段监听滚动,任何祖先滚动容器滚动时都重新贴合触发胶囊。
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
    >
      <div className="nagent-agent-card__head">
        <span
          className={cn('nagent-agent-card__dot', `nagent-agent-card__dot--${item.status}`)}
          aria-hidden="true"
        />
        <span className="nagent-agent-card__id">{label}</span>
        {item.isMain ? <span className="nagent-agent-card__badge">主 agent</span> : null}
        {item.title && item.title !== label ? (
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
