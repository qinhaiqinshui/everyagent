import React from 'react'
import { ChevronDownIcon } from '../shared/AppGlyphs'
import { FileTypeIcon } from '../shared/FileTypeGlyphs'
import type {
  WorkspaceContentSearchFileResult,
  WorkspaceContentSearchHit,
  WorkspaceContentSearchResult,
} from '@/query/workspaceContentSearch'

export interface SearchResultsTreeProps {
  /** 搜索结果（按文件聚合）。 */
  result: WorkspaceContentSearchResult
  /** 处于折叠态的文件路径集合（不在集合内 = 展开），由面板统一持有（供折叠/展开全部）。 */
  collapsedFiles: ReadonlySet<string>
  /** 切换某文件分组的折叠态。 */
  onToggleFile: (filePath: string) => void
  /** 点击命中行：打开文件并定位到该行。 */
  onOpenHit: (filePath: string, hit: WorkspaceContentSearchHit) => void
}

/** 命中行三段式拆分：前段 + 高亮段 + 后段。 */
interface MatchSegments {
  prefix: string
  hit: string
  suffix: string
}

/** 单行最长展示字符数，超出时中间截断（优先围绕命中片段保留上下文）。 */
const MAX_LINE_LENGTH = 250
/** 中间截断时命中片段前后各保留的字符数。 */
const TRUNCATE_KEEP = 110

/**
 * 命中行 → 三段式展示文本。
 * 用 hit.matchIndex / hit.matchText 把行拆成「前段 + 高亮段 + 后段」；
 * matchIndex 缺失或与行内容对不上时整行不高亮（hit 为空串）。
 * 行超长（> MAX_LINE_LENGTH）时中间截断：有命中定位则围绕命中片段保留前后各
 * TRUNCATE_KEEP 字符，否则保留首尾各 TRUNCATE_KEEP 字符，两端以省略号示意。
 */
function buildLineSegments(line: string, hit: WorkspaceContentSearchHit): MatchSegments {
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

/**
 * 搜索结果树：按文件分组（折叠箭头 + 文件名 + 相对目录 + 命中数徽章），
 * 展开后逐行渲染「行号 | 前段 + 高亮命中片段 + 后段」，点击命中行打开文件定位。
 */
export default function SearchResultsTree({
  result,
  collapsedFiles,
  onToggleFile,
  onOpenHit,
}: SearchResultsTreeProps) {
  return (
    <div style={treeStyle}>
      {result.files.map((file) => (
        <FileResultGroup
          key={file.path}
          file={file}
          collapsed={collapsedFiles.has(file.path)}
          onToggle={() => onToggleFile(file.path)}
          onOpenHit={onOpenHit}
        />
      ))}
    </div>
  )
}

/** 单个文件分组：头部行（折叠切换 + 文件信息 + 命中数）+ 命中行列表。 */
function FileResultGroup({
  file,
  collapsed,
  onToggle,
  onOpenHit,
}: {
  file: WorkspaceContentSearchFileResult
  collapsed: boolean
  onToggle: () => void
  onOpenHit: (filePath: string, hit: WorkspaceContentSearchHit) => void
}) {
  const [hovered, setHovered] = React.useState(false)
  const slashIndex = file.path.lastIndexOf('/')
  const fileName = slashIndex >= 0 ? file.path.slice(slashIndex + 1) : file.path
  const dirPath = slashIndex > 0 ? file.path.slice(0, slashIndex) : ''
  const matches = file.matches ?? []

  return (
    <div style={fileGroupStyle}>
      <div
        role="button"
        tabIndex={0}
        title={file.path}
        aria-expanded={!collapsed}
        style={{
          ...fileHeaderStyle,
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
        <span style={fileChevronStyle}>
          <ChevronDownIcon size={13} style={collapsed ? fileChevronCollapsedStyle : undefined} />
        </span>
        <span style={fileIconStyle}>
          <FileTypeIcon fileName={fileName} size={14} />
        </span>
        <span style={fileNameStyle}>{fileName}</span>
        {dirPath ? <span style={fileDirStyle}>{dirPath}</span> : null}
        <span style={fileCountStyle}>{matches.length}</span>
      </div>
      {!collapsed ? (
        <div style={matchesStyle}>
          {matches.map((hit) => (
            <MatchLine
              key={`${file.path}:${hit.lineNumber}`}
              filePath={file.path}
              hit={hit}
              onOpenHit={onOpenHit}
            />
          ))}
        </div>
      ) : null}
    </div>
  )
}

/** 单条命中行：行号（右对齐灰字）+ 三段式文本（命中片段高亮背景），点击打开定位。 */
function MatchLine({
  filePath,
  hit,
  onOpenHit,
}: {
  filePath: string
  hit: WorkspaceContentSearchHit
  onOpenHit: (filePath: string, hit: WorkspaceContentSearchHit) => void
}) {
  const [hovered, setHovered] = React.useState(false)
  const segments = React.useMemo(() => buildLineSegments(hit.line, hit), [hit])

  return (
    <div
      role="button"
      tabIndex={0}
      title={`${filePath}:${hit.lineNumber}`}
      style={{
        ...matchRowStyle,
        background: hovered ? 'var(--bg-hover)' : 'transparent',
      }}
      onClick={() => onOpenHit(filePath, hit)}
      onKeyDown={(event) => {
        if (event.key === 'Enter') {
          event.preventDefault()
          onOpenHit(filePath, hit)
        }
      }}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      <span style={matchLineNumberStyle}>{hit.lineNumber}</span>
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

const fileGroupStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
  minWidth: 0,
}

const fileHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 5,
  minWidth: 0,
  padding: '3px 6px',
  borderRadius: 'var(--radius-sm)',
  cursor: 'pointer',
  userSelect: 'none',
}

const fileChevronStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  flexShrink: 0,
  color: 'var(--text-muted)',
}

const fileChevronCollapsedStyle: React.CSSProperties = {
  transform: 'rotate(-90deg)',
}

const fileIconStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  flexShrink: 0,
}

const fileNameStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  fontWeight: 600,
  color: 'var(--text-primary)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  flexShrink: 1,
}

const fileDirStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  flex: 1,
  minWidth: 0,
}

const fileCountStyle: React.CSSProperties = {
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

const matchLineNumberStyle: React.CSSProperties = {
  flexShrink: 0,
  minWidth: 30,
  textAlign: 'right',
  color: 'var(--text-muted)',
  userSelect: 'none',
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
