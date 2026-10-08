/**
 * 文件搜索结果树(薄适配器):WorkspaceContentSearchResult → SearchResultGroup[]
 * 交给通用 SearchResultTreeView(resultTree/)渲染。
 * - 内容模式:一个文件一组(组头 = 文件图标 + 文件名 + 相对目录,折叠键 =
 *   文件路径),命中行 = 行号前缀 + 高亮正文,点击打开文件定位到行;
 * - 文件名模式(nameMode):每个命中即一个文件,渲染为扁平文件行(无折叠箭头与
 *   命中数徽章),点击直接打开文件。
 * 对外 props 与渲染结果保持不变(SearchPanel 接线零改动);高亮 / 截断 /
 * 折叠 / 组头样式等公共实现单点在 resultTree/。
 */
import React from 'react'
import { FileTypeIcon } from '../shared/FileTypeGlyphs'
import SearchResultTreeView, { resultTreeStyle } from './resultTree/SearchResultTreeView'
import type { SearchResultGroup } from './resultTree/model'
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
  /** 文件名搜索模式：每个命中即一个文件（无命中行），渲染为扁平文件行，点击直接打开文件。 */
  nameMode?: boolean
  /** 文件名模式下点击文件行：打开文件。 */
  onOpenFile?: (filePath: string) => void
}

/** 文件路径 → 文件名 + 相对目录(组头与文件名模式扁平行共用的拆分)。 */
function splitPath(path: string): { fileName: string; dirPath: string } {
  const slashIndex = path.lastIndexOf('/')
  return {
    fileName: slashIndex >= 0 ? path.slice(slashIndex + 1) : path,
    dirPath: slashIndex > 0 ? path.slice(0, slashIndex) : '',
  }
}

/** 文件结果 → 通用分组(组头 = 路径拆分 + 命中数;命中行前缀 = 行号,点击打开定位)。 */
function toFileGroups(
  result: WorkspaceContentSearchResult,
  onOpenHit: (filePath: string, hit: WorkspaceContentSearchHit) => void,
): SearchResultGroup[] {
  return result.files.map((file) => {
    const { fileName, dirPath } = splitPath(file.path)
    const matches = file.matches ?? []
    return {
      key: file.path,
      header: {
        title: file.path,
        name: fileName,
        detail: dirPath ? { text: dirPath, grow: true } : undefined,
        icon: { kind: 'file', fileName },
      },
      hits: matches.map((hit) => ({
        label: hit.line,
        line: hit.lineNumber,
        prefix: { text: String(hit.lineNumber), minWidth: 30 },
        matchIndex: hit.matchIndex ?? -1,
        matchText: hit.matchText ?? '',
        title: `${file.path}:${hit.lineNumber}`,
        onOpen: () => onOpenHit(file.path, hit),
      })),
    }
  })
}

/**
 * 搜索结果树:内容模式走通用结果树,文件名模式渲染扁平文件行列表。
 */
export default function SearchResultsTree({
  result,
  collapsedFiles,
  onToggleFile,
  onOpenHit,
  nameMode,
  onOpenFile,
}: SearchResultsTreeProps) {
  const groups = React.useMemo(() => toFileGroups(result, onOpenHit), [result, onOpenHit])

  if (nameMode && onOpenFile) {
    return (
      <div style={resultTreeStyle}>
        {result.files.map((file) => (
          <FileNameResultRow
            key={file.path}
            file={file}
            onOpenFile={onOpenFile}
          />
        ))}
      </div>
    )
  }

  return (
    <SearchResultTreeView
      groups={groups}
      collapsedKeys={collapsedFiles}
      onToggleGroup={onToggleFile}
    />
  )
}

/**
 * 文件名搜索结果行:扁平单行(文件图标 + 文件名 + 目录),点击直接打开文件。
 * 无折叠箭头与命中数徽章(每个命中即一个文件,无需展开子项)。
 */
function FileNameResultRow({
  file,
  onOpenFile,
}: {
  file: WorkspaceContentSearchFileResult
  onOpenFile: (filePath: string) => void
}) {
  const [hovered, setHovered] = React.useState(false)
  const { fileName, dirPath } = splitPath(file.path)

  return (
    <div
      role="button"
      tabIndex={0}
      title={file.path}
      style={{
        ...fileNameRowStyle,
        background: hovered ? 'var(--bg-hover)' : 'transparent',
      }}
      onClick={() => onOpenFile(file.path)}
      onKeyDown={(event) => {
        if (event.key === 'Enter') {
          event.preventDefault()
          onOpenFile(file.path)
        }
      }}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      <span style={fileIconStyle}>
        <FileTypeIcon fileName={fileName} size={14} />
      </span>
      <span style={fileNameStyle}>{fileName}</span>
      {dirPath ? <span style={fileDirStyle}>{dirPath}</span> : null}
    </div>
  )
}

const fileNameRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 5,
  minWidth: 0,
  padding: '3px 6px',
  borderRadius: 'var(--radius-sm)',
  cursor: 'pointer',
  userSelect: 'none',
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
