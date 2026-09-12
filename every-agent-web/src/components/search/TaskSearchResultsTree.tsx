import React from 'react'
import { ChevronDownIcon } from '../shared/AppGlyphs'
import type {
  TaskContentSearchHit,
  TaskContentSearchResult,
  TaskContentSearchTaskResult,
} from '@/query/taskContentSearch'

export interface TaskSearchResultsTreeProps {
  /** 任务搜索结果(按任务聚合)。 */
  result: TaskContentSearchResult
  /** 处于折叠态的任务 ID 集合(不在集合内 = 展开),由面板统一持有。 */
  collapsedTasks: ReadonlySet<string>
  /** 切换某任务分组的折叠态。 */
  onToggleTask: (taskId: string) => void
  /** 点击命中行:打开任务聊天页。 */
  onOpenTask: (task: TaskContentSearchTaskResult) => void
}

/** 命中行三段式拆分:前段 + 高亮段 + 后段。 */
interface MatchSegments {
  prefix: string
  hit: string
  suffix: string
}

/** 单行最长展示字符数,超出时中间截断(优先围绕命中片段保留上下文)。 */
const MAX_LINE_LENGTH = 250
/** 中间截断时命中片段前后各保留的字符数。 */
const TRUNCATE_KEEP = 110

/** 命中行 → 三段式展示文本(与 SearchResultsTree 同款,任务正文可能很长,截断策略一致)。 */
function buildLineSegments(line: string, hit: TaskContentSearchHit): MatchSegments {
  const matchIndex = hit.matchIndex ?? -1
  const matchText = hit.matchText ?? ''
  const hasMatch = matchIndex >= 0
    && matchText.length > 0
    && matchIndex + matchText.length <= line.length
    && line.slice(matchIndex, matchIndex + matchText.length) === matchText

  if (line.length <= MAX_LINE_LENGTH) {
    if (!hasMatch) {
      return { prefix: line, hit: '', suffix: '' }
    }
    return {
      prefix: line.slice(0, matchIndex),
      hit: matchText,
      suffix: line.slice(matchIndex + matchText.length),
    }
  }

  if (hasMatch) {
    const hitStart = matchIndex
    const hitEnd = matchIndex + matchText.length
    const start = Math.max(0, hitStart - TRUNCATE_KEEP)
    const end = Math.min(line.length, hitEnd + TRUNCATE_KEEP)
    return {
      prefix: (start > 0 ? '…' : '') + line.slice(start, hitStart),
      hit: matchText,
      suffix: line.slice(hitEnd, end) + (end < line.length ? '…' : ''),
    }
  }

  return {
    prefix: `${line.slice(0, TRUNCATE_KEEP)} … `,
    hit: '',
    suffix: line.slice(Math.max(line.length - TRUNCATE_KEEP, TRUNCATE_KEEP + 5)),
  }
}

/** 命中字段展示名。 */
function fieldLabel(field: TaskContentSearchHit['field']): string {
  return field === 'user' ? '用户输入' : 'AI 回复'
}

/** worker 状态串 → 短标签(仅展示;未知原样显示)。 */
function statusLabel(status: string): string {
  switch (status) {
    case 'done':
      return '已完成'
    case 'running':
      return '运行中'
    case 'failed':
      return '失败'
    case 'cancelled':
      return '已取消'
    default:
      return status
  }
}

/**
 * 任务搜索结果树:按任务分组(折叠箭头 + 任务标题 + 状态 + 命中数徽章),
 * 展开后逐行渲染「轮次 #N · 用户输入/AI 回复:前段 + 高亮 + 后段」,
 * 点击命中行打开对应任务聊天页。
 */
export default function TaskSearchResultsTree({
  result,
  collapsedTasks,
  onToggleTask,
  onOpenTask,
}: TaskSearchResultsTreeProps) {
  return (
    <div style={treeStyle}>
      {result.files.map((task) => (
        <TaskResultGroup
          key={task.taskId}
          task={task}
          collapsed={collapsedTasks.has(task.taskId)}
          onToggle={() => onToggleTask(task.taskId)}
          onOpenTask={onOpenTask}
        />
      ))}
    </div>
  )
}

/** 单个任务分组:头部行(折叠切换 + 任务信息 + 命中数)+ 命中行列表。 */
function TaskResultGroup({
  task,
  collapsed,
  onToggle,
  onOpenTask,
}: {
  task: TaskContentSearchTaskResult
  collapsed: boolean
  onToggle: () => void
  onOpenTask: (task: TaskContentSearchTaskResult) => void
}) {
  const [hovered, setHovered] = React.useState(false)
  const matches = task.matches ?? []

  return (
    <div style={taskGroupStyle}>
      <div
        role="button"
        tabIndex={0}
        title={task.title}
        aria-expanded={!collapsed}
        style={{
          ...taskHeaderStyle,
          background: hovered ? 'var(--bg-hover)' : 'transparent',
        }}
        onClick={onToggle}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault()
            onToggle()
          }
        }}
        onMouseEnter={() => setHovered(true)}
        onMouseLeave={() => setHovered(false)}
      >
        <span style={taskChevronStyle}>
          <ChevronDownIcon size={13} style={collapsed ? taskChevronCollapsedStyle : undefined} />
        </span>
        <span style={taskNameStyle}>{task.title}</span>
        {task.status ? <span style={taskStatusStyle}>{statusLabel(task.status)}</span> : null}
        <span style={taskCountStyle}>{matches.length}</span>
      </div>
      {!collapsed ? (
        <div style={matchesStyle}>
          {matches.map((hit, index) => (
            <TaskMatchLine
              key={`${task.taskId}:${hit.roundIndex}:${hit.field}:${index}`}
              task={task}
              hit={hit}
              onOpenTask={onOpenTask}
            />
          ))}
        </div>
      ) : null}
    </div>
  )
}

/** 单条命中行:轮次/字段标记 + 三段式文本(命中片段高亮),点击打开任务聊天页。 */
function TaskMatchLine({
  task,
  hit,
  onOpenTask,
}: {
  task: TaskContentSearchTaskResult
  hit: TaskContentSearchHit
  onOpenTask: (task: TaskContentSearchTaskResult) => void
}) {
  const [hovered, setHovered] = React.useState(false)
  const segments = React.useMemo(() => buildLineSegments(hit.line, hit), [hit])

  return (
    <div
      role="button"
      tabIndex={0}
      title={`${task.title} · 第 ${hit.roundIndex} 轮 ${fieldLabel(hit.field)}`}
      style={{
        ...matchRowStyle,
        background: hovered ? 'var(--bg-hover)' : 'transparent',
      }}
      onClick={() => onOpenTask(task)}
      onKeyDown={(event) => {
        if (event.key === 'Enter') {
          event.preventDefault()
          onOpenTask(task)
        }
      }}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      <span style={matchMetaStyle}>
        #{hit.roundIndex} {fieldLabel(hit.field)}
      </span>
      <span style={matchTextStyle}>
        {segments.prefix}
        {segments.hit ? <mark style={matchHighlightStyle}>{segments.hit}</mark> : null}
        {segments.suffix}
      </span>
    </div>
  )
}

const treeStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  padding: '2px 0 8px',
}

const taskGroupStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
  minWidth: 0,
}

const taskHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 5,
  minWidth: 0,
  padding: '3px 6px',
  borderRadius: 'var(--radius-sm)',
  cursor: 'pointer',
  userSelect: 'none',
}

const taskChevronStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  flexShrink: 0,
  color: 'var(--text-muted)',
}

const taskChevronCollapsedStyle: React.CSSProperties = {
  transform: 'rotate(-90deg)',
}

const taskNameStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  fontWeight: 600,
  color: 'var(--text-primary)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  flexShrink: 1,
}

const taskStatusStyle: React.CSSProperties = {
  flexShrink: 0,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
}

const taskCountStyle: React.CSSProperties = {
  flexShrink: 0,
  fontSize: 'var(--text-xs)',
  lineHeight: 1.6,
  padding: '0 6px',
  borderRadius: 999,
  color: 'var(--accent-blue)',
  background: 'var(--accent-blue-dim)',
}

const matchesStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 1,
  paddingLeft: 10,
}

const matchRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 8,
  padding: '1px 6px',
  borderRadius: 'var(--radius-sm)',
  cursor: 'pointer',
  fontFamily: 'var(--font-mono)',
  fontSize: 'var(--text-xs)',
  lineHeight: 1.6,
  color: 'var(--text-secondary)',
  minWidth: 0,
}

const matchMetaStyle: React.CSSProperties = {
  flexShrink: 0,
  minWidth: 84,
  textAlign: 'right',
  color: 'var(--text-muted)',
  userSelect: 'none',
  whiteSpace: 'nowrap',
}

const matchTextStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-all',
  overflow: 'hidden',
}

const matchHighlightStyle: React.CSSProperties = {
  background: 'color-mix(in srgb, var(--accent-blue) 30%, transparent)',
  color: 'var(--text-primary)',
  fontWeight: 600,
  borderRadius: 2,
  padding: '0 1px',
}