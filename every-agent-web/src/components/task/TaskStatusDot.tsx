import React from 'react'
import type { TaskStatus } from '@/types'
import {
  AlertTriangleIcon,
  CheckIcon,
  CircleIcon,
  StopIcon,
} from '../shared/AppGlyphs'
import { InlineSpinner } from '@/components/shared/ui'

/**
 * 任务状态点。复用原 `TaskChatHeader` 的配色与图标逻辑，供标题栏标签等处内联渲染。
 */
export default function TaskStatusDot({
  status,
  size = 16,
}: {
  /** 任务状态。 */
  status: TaskStatus
  /** 圆点直径（px）。 */
  size?: number
}) {
  const isRunning = status === 'running'
  const iconSize = Math.max(8, Math.round(size * 0.66))
  return (
    <span className="task-status-dot" style={buildStatusDotStyle(status, isRunning, size)}>
      {isRunning
        ? <InlineSpinner size={iconSize} />
        : formatStatusIcon(status, iconSize)}
    </span>
  )
}

function formatStatusIcon(status: TaskStatus, size: number): React.ReactNode {
  switch (status) {
    case 'completed': return <CheckIcon size={size} />
    case 'stopped': return <StopIcon size={Math.max(8, size - 1)} />
    case 'error': return <AlertTriangleIcon size={size} />
    default: return <CircleIcon size={size} />
  }
}

export function buildStatusDotStyle(
  status: TaskStatus,
  isRunning: boolean,
  size: number,
): React.CSSProperties {
  const tone = status === 'completed'
    ? { color: 'var(--accent-green)', background: 'var(--accent-green-dim)' }
    : status === 'error'
      ? { color: 'var(--accent-red)', background: 'var(--accent-red-dim)' }
      : status === 'stopped'
        ? { color: 'var(--accent-red)', background: 'var(--accent-red-dim)' }
        : status === 'running' || isRunning
          ? { color: 'var(--accent-blue)', background: 'var(--accent-blue-dim)' }
          : { color: 'var(--text-muted)', background: 'var(--bg-tertiary)' }

  return {
    width: size,
    height: size,
    ...tone,
  }
}
