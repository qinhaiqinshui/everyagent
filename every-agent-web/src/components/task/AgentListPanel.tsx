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
import { shortAgentId } from '@/utils/shortId'
import { cn } from '@/components/shared/ui/cn'
import OverlayScrollbar from '@/components/shared/ui/OverlayScrollbar'
import {
  AgentInfoHoverCard,
  contextUsageLevel,
  type AgentListItem,
} from './AgentInfoHoverCard'
import './AgentListPanel.css'

export type { AgentListItem } from './AgentInfoHoverCard'

export interface AgentListPanelProps {
  agents: AgentListItem[]
  /** 当前过滤:'' = 全部;主 = mainAgentId;子 = 子 id。 */
  filterAgentId: string
  /** 选中回调:传 '' 表示恢复「全部」。 */
  onSelect: (agentId: string) => void
}

/** 鼠标离开后的延迟关闭缓冲(防抖动误关;再次 enter 即取消)。 */
const CLOSE_DELAY_MS = 120

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
            // 胶囊只显示 title：有真实标题(title ≠ agentId)时显示 title；
            // 无标题时主 agent 显示「主 agent」，子 agent 显示短 ID。
            const displayName =
              agent.title && agent.title !== agent.agentId
                ? agent.title
                : agent.isMain
                  ? '主 agent'
                  : shortAgentId(agent.agentId)
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
                aria-label={`agent ${displayName}（点击${
                  selected ? '恢复全部' : '只看该 agent'
                }，悬停查看详情）`}
                onMouseEnter={(event) => openCard(agent.agentId, event.currentTarget)}
                onMouseLeave={scheduleClose}
                onFocus={(event) => openCard(agent.agentId, event.currentTarget)}
                onBlur={scheduleClose}
              >
                <span className="nagent-agent__dot" aria-hidden="true" />
                <span className="nagent-agent__label">{displayName}</span>
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
