import React from 'react'
import { gitGateway, GitNotInitializedError } from '@/platform/git/gitGateway'
import { InlineSpinner } from '@/components/shared/ui'
import { GitIcon } from '@/components/icon'

export interface GitHistoryCommit {
  id: string
  shortId: string
  author: string
  email?: string
  ts: number
  message: string
}

type GitHistoryPanelProps = {
  /** 所属工作区根(worker 机器绝对路径;git log 落对应工作区)。 */
  workspaceRoot: string
  /** 历史目标路径(工作区相对路径,空串 = 仓库级历史)。 */
  path: string
  /** 目标名称(用于头部展示)。 */
  name: string
}

/**
 * Git 历史标签页内容：进入后按路径懒加载提交历史列表（git log -- <path>）。
 * 仿 VSCode View History——直接消费原生 git 数据，仅展示，不内联 diff。
 */
export default function GitHistoryPanel({ workspaceRoot, path, name }: GitHistoryPanelProps) {
  const [commits, setCommits] = React.useState<GitHistoryCommit[]>([])
  const [loading, setLoading] = React.useState(true)
  const [error, setError] = React.useState('')

  React.useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError('')
    gitGateway.log(workspaceRoot, 50, path || undefined)
      .then((rows) => {
        if (cancelled) return
        setCommits(rows)
      })
      .catch((loadError) => {
        if (cancelled) return
        if (loadError instanceof GitNotInitializedError) {
          setError('此工作区不是 Git 仓库')
        } else {
          setError(loadError instanceof Error ? loadError.message : String(loadError))
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [workspaceRoot, path])

  return (
    <div style={panelStyle}>
      <div style={headerStyle}>
        <span style={headerIconStyle}><GitIcon size={15} /></span>
        <span style={headerTitleStyle} title={path || workspaceRoot}>{name}</span>
        {path ? <span style={headerPathStyle} title={path}>{path}</span> : null}
      </div>
      {loading ? (
        <div style={statusStyle}>
          <InlineSpinner size={14} color="currentColor" trackColor="transparent" />
          正在加载提交历史…
        </div>
      ) : error ? (
        <div style={statusStyle}>{error}</div>
      ) : commits.length === 0 ? (
        <div style={statusStyle}>该路径暂无提交历史</div>
      ) : (
        <div style={listStyle}>
          {commits.map((commit) => (
            <div key={commit.id} style={rowStyle}>
              <span style={shortIdStyle}>{commit.shortId || commit.id.slice(0, 8)}</span>
              <span style={messageStyle} title={commit.message}>{commit.message || '(无提交说明)'}</span>
              <span style={metaStyle} title={commit.author}>
                {commit.author || commit.email || ''}
              </span>
              <span style={timeStyle}>{formatCommitTime(commit.ts)}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

function formatCommitTime(ts: number): string {
  if (!ts || Number.isNaN(ts)) return ''
  const date = new Date(ts)
  const pad = (num: number) => String(num).padStart(2, '0')
  const year = date.getFullYear()
  const month = pad(date.getMonth() + 1)
  const day = pad(date.getDate())
  const hours = pad(date.getHours())
  const minutes = pad(date.getMinutes())
  return `${year}-${month}-${day} ${hours}:${minutes}`
}

const panelStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  height: '100%',
  minWidth: 0,
  minHeight: 0,
  background: 'var(--bg-primary)',
}

const headerStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  flexShrink: 0,
  padding: '10px 12px',
  borderBottom: '1px solid var(--border-light)',
}

const headerIconStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  color: 'var(--text-muted)',
  flexShrink: 0,
}

const headerTitleStyle: React.CSSProperties = {
  fontSize: 'var(--text-sm)',
  fontWeight: 700,
  color: 'var(--text-primary)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  flexShrink: 0,
  maxWidth: '40%',
}

const headerPathStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  fontFamily: '"Cascadia Code", "Fira Code", Consolas, monospace',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  minWidth: 0,
  flex: 1,
}

const statusStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 8,
  padding: '12px 14px',
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
}

const listStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  overflowY: 'auto',
  display: 'flex',
  flexDirection: 'column',
  padding: '4px 0',
}

const rowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  padding: '6px 14px',
  fontSize: 'var(--text-xs)',
  minWidth: 0,
}

const shortIdStyle: React.CSSProperties = {
  flexShrink: 0,
  fontFamily: '"Cascadia Code", "Fira Code", Consolas, monospace',
  color: 'var(--accent-blue)',
  fontWeight: 600,
}

const messageStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  color: 'var(--text-primary)',
}

const metaStyle: React.CSSProperties = {
  flexShrink: 0,
  maxWidth: '22%',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  color: 'var(--text-secondary)',
}

const timeStyle: React.CSSProperties = {
  flexShrink: 0,
  fontFamily: '"Cascadia Code", "Fira Code", Consolas, monospace',
  color: 'var(--text-muted)',
}
