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
 */
import React from 'react'
import type { AgentStatus } from '@/types'
import { shortAgentId } from '@/utils/shortId'
import { cn } from '@/components/shared/ui/cn'
import OverlayScrollbar from '@/components/shared/ui/OverlayScrollbar'
import './AgentListPanel.css'

export interface AgentListItem {
  /** 展示用 agentId(主 = task.mainAgentId,子 = 子 agent id)。 */
  agentId: string
  /** 会话标题(子 agent 有 title;主 agent 固定「主 agent」)。 */
  title: string
  /** 当前状态(主 agent 由任务状态映射,子 agent 由事件状态机提供)。 */
  status: AgentStatus
}

export interface AgentListPanelProps {
  agents: AgentListItem[]
  /** 当前过滤:'' = 全部;主 = mainAgentId;子 = 子 id。 */
  filterAgentId: string
  /** 选中回调:传 '' 表示恢复「全部」。 */
  onSelect: (agentId: string) => void
}

export default function AgentListPanel({ agents, filterAgentId, onSelect }: AgentListPanelProps) {
  const scrollRef = React.useRef<HTMLDivElement>(null)
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
                title={`${label}${agent.title && agent.title !== label ? ` · ${agent.title}` : ''}（点击${
                  selected ? '恢复全部' : '只看该 agent'
                }）`}
                aria-pressed={selected}
                aria-label={`agent ${label}`}
              >
                <span className="nagent-agent__dot" aria-hidden="true" />
                <span className="nagent-agent__label">{label}</span>
                {agent.title && agent.title !== label ? (
                  <span className="nagent-agent__title">{agent.title}</span>
                ) : null}
              </button>
            )
          })}
        </div>
        <OverlayScrollbar targetRef={scrollRef} version={agents.length} />
      </div>
    </div>
  )
}
