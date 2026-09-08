import React from 'react'
import { Button } from '@/components/shared/ui'
import type {
  WorkspaceContentSearchFileResult,
  WorkspaceContentSearchHit,
  WorkspaceContentSearchResult,
} from '@/query/workspaceContentSearch'

export interface FileSearchResultsPanelProps {
  /** 搜索结果。 */
  result: WorkspaceContentSearchResult
  /** 点击某条命中时回调，由宿主负责打开对应文件标签。 */
  onOpenHit: (filePath: string, hit: WorkspaceContentSearchHit) => void
}

/**
 * 工作区内容搜索结果面板。
 * 渲染「文件 → 命中行（行号 + 片段 + 前后上下文）」，点击命中项可跳转打开文件。
 */
export default function FileSearchResultsPanel({ result, onOpenHit }: FileSearchResultsPanelProps) {
  if (result.files.length === 0) {
    return <div style={emptyStyle}>未找到匹配内容。</div>
  }

  return (
    <div style={listStyle}>
      {result.files.map((file) => (
        <FileResultRow key={file.path} file={file} onOpenHit={onOpenHit} />
      ))}
      {result.truncated ? (
        <div style={truncatedStyle}>命中数过多，仅展示前 {result.matchCount} 处，请收紧正则后再试。</div>
      ) : null}
    </div>
  )
}

function FileResultRow({
  file,
  onOpenHit,
}: {
  file: WorkspaceContentSearchFileResult
  onOpenHit: (filePath: string, hit: WorkspaceContentSearchHit) => void
}) {
  const fileName = file.path.split('/').pop() ?? file.path
  const matches = file.matches ?? []

  return (
    <div style={fileGroupStyle}>
      <div style={fileHeaderStyle} title={file.path}>{fileName}</div>
      <div style={matchesStyle}>
        {matches.map((hit) => (
          <Button
            key={hit.lineNumber}
            type="button"
            onClick={() => onOpenHit(file.path, hit)}
            style={hitButtonStyle}
            title={`${file.path}:${hit.lineNumber}`}
          >
            {hit.before?.map((ctx) => (
              <div key={`b${ctx.lineNumber}`} style={contextLineStyle}>
                <span style={lineNumberStyle}>{ctx.lineNumber}</span>
                <span style={lineTextStyle}>{ctx.line}</span>
              </div>
            ))}
            <div style={hitLineStyle}>
              <span style={hitLineNumberStyle}>{hit.lineNumber}</span>
              <span style={hitTextStyle}>{hit.line}</span>
            </div>
            {hit.after?.map((ctx) => (
              <div key={`a${ctx.lineNumber}`} style={contextLineStyle}>
                <span style={lineNumberStyle}>{ctx.lineNumber}</span>
                <span style={lineTextStyle}>{ctx.line}</span>
              </div>
            ))}
          </Button>
        ))}
      </div>
    </div>
  )
}

const listStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
  maxHeight: 360,
  overflowY: 'auto',
}

const emptyStyle: React.CSSProperties = {
  padding: '8px 4px',
  color: 'var(--text-muted)',
  fontSize: 'var(--text-xs)',
}

const truncatedStyle: React.CSSProperties = {
  padding: '6px 4px',
  color: 'var(--text-muted)',
  fontSize: 'var(--text-xs)',
}

const fileGroupStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
}

const fileHeaderStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  color: 'var(--text-secondary)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

const matchesStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
}

const hitButtonStyle: React.CSSProperties = {
  textAlign: 'left',
  cursor: 'pointer',
  border: '1px solid var(--border-light)',
  borderRadius: 'var(--radius-sm)',
  background: 'var(--bg-secondary)',
  padding: '4px 6px',
  display: 'flex',
  flexDirection: 'column',
  justifyContent: 'flex-start',
  gap: 1,
  color: 'inherit',
  fontFamily: '"Cascadia Code", "Fira Code", Consolas, monospace',
  fontSize: 'var(--text-xs)',
  lineHeight: 1.5,
}

const hitLineStyle: React.CSSProperties = {
  display: 'flex',
  gap: 8,
  background: 'color-mix(in srgb, var(--accent-blue) 12%, transparent)',
  borderRadius: 2,
  padding: '1px 4px',
}

const contextLineStyle: React.CSSProperties = {
  display: 'flex',
  gap: 8,
  padding: '1px 4px',
  opacity: 0.6,
}

const lineNumberStyle: React.CSSProperties = {
  flexShrink: 0,
  minWidth: 28,
  color: 'var(--text-muted)',
  textAlign: 'right',
  userSelect: 'none',
}

const hitLineNumberStyle: React.CSSProperties = {
  ...lineNumberStyle,
  color: 'var(--accent-blue)',
  fontWeight: 700,
}

const lineTextStyle: React.CSSProperties = {
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-all',
  color: 'var(--text-muted)',
}

const hitTextStyle: React.CSSProperties = {
  ...lineTextStyle,
  color: 'var(--text-primary)',
  fontWeight: 600,
}
