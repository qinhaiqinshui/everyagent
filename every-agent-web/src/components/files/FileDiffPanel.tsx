import React from 'react'
import type { TaskFileChange } from '@/types'
import { useResponsiveViewport } from '@/hooks/useResponsiveViewport'
import { buildLineDiff, buildSideBySideRows, type SideBySideDiffRow } from '@/utils/textDiff';
import { Button } from '@/components/shared/ui'
import { useWorkspaceShell } from '@/components/app/WorkspaceShellContext'
import { useAppUi } from '@/components/app/AppUiContext'
import { workspaceRegistry } from '@/hub/workspaceRegistry'
import { workspaceGateway } from '@/platform/fs/workspaceGateway'
import { FilesIcon } from '@/components/icon'
import './fileDiff.css'

type FileDiffPanelProps = {
  /** 当前要展示的文件差异。 */
  fileChange: TaskFileChange
  /** 差异所属的工作区根(「打开文件」定位用;缺省时回退注册表首项)。 */
  workspaceRoot?: string
}

type InlineDiffPart = {
  /** 片段文本。 */
  text: string
  /** 是否属于变更高亮片段。 */
  changed: boolean
}

type UnifiedDiffRow = {
  /** 行唯一键。 */
  key: string
  /** 行变更类型。 */
  type: 'context' | 'added' | 'removed' | 'changed'
  /** 左侧行号。 */
  leftLineNumber: number | null
  /** 右侧行号。 */
  rightLineNumber: number | null
  /** 左侧完整文本。 */
  leftContent: string
  /** 右侧完整文本。 */
  rightContent: string
  /** 左侧行内高亮。 */
  leftParts: InlineDiffPart[]
  /** 右侧行内高亮。 */
  rightParts: InlineDiffPart[]
}

/**
 * 文件差异展示面板。
 * 自定义实现 VSCode 风格的整文件 diff，避免第三方通用 diff 组件把改词误显示成错位。
 */
export default function FileDiffPanel({ fileChange, workspaceRoot: diffWorkspaceRoot }: FileDiffPanelProps) {
  const { isMobile } = useResponsiveViewport()
  const { openGlobalFileTab } = useWorkspaceShell()
  const { showToast } = useAppUi()
  const [viewType, setViewType] = React.useState<'unified' | 'split'>(isMobile ? 'unified' : 'split')

  React.useEffect(() => {
    if (isMobile) {
      setViewType('unified')
    }
  }, [isMobile])

  const diffRows = React.useMemo(() => buildDiffRows(fileChange), [fileChange])

  const handleOpenFileInTab = React.useCallback(async () => {
    // 优先用差异所属工作区根精确落位;diff 来源未携带时回退注册表首项。
    const workspaceRoot = diffWorkspaceRoot ?? workspaceRegistry.primaryRoot()
    if (!workspaceRoot) {
      showToast('暂无可用的工作区，无法打开文件。', 'error')
      return
    }
    // 差异可能是已删除文件:磁盘上已无该路径,直接打开会进一个报 NOT_FOUND 的空标签。
    // 先校验存在性,缺失时给可读提示,避免用户看到原始 RPC 错误。
    const stat = await workspaceGateway.stat(workspaceRoot, fileChange.filePath).catch(() => null)
    if (!stat || stat.isDirectory) {
      showToast(`文件已不存在于工作区（可能已被删除），无法打开：${fileChange.filePath}`, 'error')
      return
    }
    openGlobalFileTab({
      workspaceRoot,
      filePath: fileChange.filePath,
    }, { mode: 'readwrite' })
  }, [diffWorkspaceRoot, fileChange.filePath, openGlobalFileTab, showToast])

  if (diffRows.length === 0) {
    return (
      <div className="file-diff-panel">
        <div className="file-diff-panel__empty">当前没有可展示的差异。</div>
      </div>
    )
  }

  return (
    <div className="file-diff-panel">
      <div className="file-diff-panel__toolbar">
        <Button
          variant="ghost"
          size="sm"
          className="file-diff-panel__open-file"
          title="在新标签页打开文件"
          aria-label="在新标签页打开文件"
          onClick={handleOpenFileInTab}
        >
          <FilesIcon size={14} />
          <span className="file-diff-panel__open-file-label">打开文件</span>
        </Button>
        <div className="file-diff-panel__view-toggle" role="group" aria-label="差异视图切换">
          <Button
            variant="ghost"
            size="sm"
            className={`file-diff-panel__view-button${viewType === 'unified' ? ' is-active' : ''}`}
            aria-pressed={viewType === 'unified'}
            onClick={() => setViewType('unified')}
          >
            单列
          </Button>
          <Button
            variant="ghost"
            size="sm"
            className={`file-diff-panel__view-button${viewType === 'split' ? ' is-active' : ''}`}
            aria-pressed={viewType === 'split'}
            onClick={() => setViewType('split')}
            disabled={isMobile}
          >
            并排
          </Button>
        </div>
      </div>
      <div className="file-diff-panel__body">
        {viewType === 'split'
          ? <SplitDiffTable rows={diffRows} />
          : <UnifiedDiffTable rows={diffRows} />}
      </div>
    </div>
  )
}

type SplitDiffTableProps = {
  /** 当前文件的 diff 行。 */
  rows: UnifiedDiffRow[]
}

/**
 * 左右分栏 diff 表格。
 */
function SplitDiffTable({ rows }: SplitDiffTableProps) {
  return (
    <div className="file-diff-panel__table file-diff-panel__table--split" role="table" aria-label="并排文件差异">
      {rows.map((row) => (
        <div
          key={row.key}
          className={`file-diff-row file-diff-row--${row.type}`}
          role="row"
        >
          <DiffCell
            side="left"
            type={row.type}
            lineNumber={row.leftLineNumber}
            content={row.leftContent}
            parts={row.leftParts}
          />
          <DiffCell
            side="right"
            type={row.type}
            lineNumber={row.rightLineNumber}
            content={row.rightContent}
            parts={row.rightParts}
          />
        </div>
      ))}
    </div>
  )
}

type UnifiedDiffTableProps = {
  /** 当前文件的 diff 行。 */
  rows: UnifiedDiffRow[]
}

/**
 * 单列 diff 表格。
 * 移动端保留完整文件顺序，同时把变更行拆成旧值/新值两条展示。
 */
function UnifiedDiffTable({ rows }: UnifiedDiffTableProps) {
  return (
    <div className="file-diff-panel__table file-diff-panel__table--unified" role="table" aria-label="单列文件差异">
      {rows.flatMap((row) => {
        if (row.type === 'changed') {
          return [
            (
              <UnifiedDiffLine
                key={`${row.key}:removed`}
                marker="-"
                type="removed"
                lineNumber={row.leftLineNumber}
                content={row.leftContent}
                parts={row.leftParts}
              />
            ),
            (
              <UnifiedDiffLine
                key={`${row.key}:added`}
                marker="+"
                type="added"
                lineNumber={row.rightLineNumber}
                content={row.rightContent}
                parts={row.rightParts}
              />
            ),
          ]
        }

        if (row.type === 'removed') {
          return [
            (
              <UnifiedDiffLine
                key={row.key}
                marker="-"
                type="removed"
                lineNumber={row.leftLineNumber}
                content={row.leftContent}
                parts={row.leftParts}
              />
            ),
          ]
        }

        if (row.type === 'added') {
          return [
            (
              <UnifiedDiffLine
                key={row.key}
                marker="+"
                type="added"
                lineNumber={row.rightLineNumber}
                content={row.rightContent}
                parts={row.rightParts}
              />
            ),
          ]
        }

        return [
          (
            <UnifiedDiffLine
              key={row.key}
              marker=" "
              type="context"
              lineNumber={row.rightLineNumber ?? row.leftLineNumber}
              content={row.rightContent}
              parts={row.rightParts}
            />
          ),
        ]
      })}
    </div>
  )
}

type DiffCellProps = {
  /** 当前单元格位于左侧还是右侧。 */
  side: 'left' | 'right'
  /** 行变更类型。 */
  type: UnifiedDiffRow['type']
  /** 行号。 */
  lineNumber: number | null
  /** 行文本。 */
  content: string
  /** 行内高亮片段。 */
  parts: InlineDiffPart[]
}

/**
 * 并排视图的单侧单元格。
 */
function DiffCell({ side, type, lineNumber, content, parts }: DiffCellProps) {
  const sideState = resolveSplitCellState(side, type, lineNumber)
  return (
    <div
      className={[
        'file-diff-cell',
        `file-diff-cell--${side}`,
        `file-diff-cell--${sideState}`,
        lineNumber == null ? 'file-diff-cell--empty' : '',
      ].filter(Boolean).join(' ')}
      role="cell"
    >
      <span className="file-diff-cell__line-number">{lineNumber ?? ''}</span>
      <span className="file-diff-cell__marker">{sideState === 'added' ? '+' : sideState === 'removed' ? '-' : ''}</span>
      <span className="file-diff-cell__content">
        {lineNumber == null ? '' : renderInlineParts(parts, content)}
      </span>
    </div>
  )
}

type UnifiedDiffLineProps = {
  /** 当前行前缀标记。 */
  marker: ' ' | '+' | '-'
  /** 行类型。 */
  type: 'context' | 'added' | 'removed'
  /** 行号。 */
  lineNumber: number | null
  /** 行文本。 */
  content: string
  /** 行内高亮。 */
  parts: InlineDiffPart[]
}

/**
 * 单列视图的单行。
 */
function UnifiedDiffLine({ marker, type, lineNumber, content, parts }: UnifiedDiffLineProps) {
  return (
    <div className={`file-diff-unified-line file-diff-unified-line--${type}`} role="row">
      <span className="file-diff-unified-line__line-number">{lineNumber ?? ''}</span>
      <span className="file-diff-unified-line__marker">{marker}</span>
      <span className="file-diff-unified-line__content">{renderInlineParts(parts, content)}</span>
    </div>
  )
}

/**
 * 把文件内容转成稳定的 VSCode 风格 diff 行。
 */
function buildDiffRows(fileChange: TaskFileChange): UnifiedDiffRow[] {
  const diffLines = buildLineDiff(fileChange.beforeContent ?? '', fileChange.afterContent ?? '')
  const rows = buildSideBySideRows(diffLines)

  if (rows.length > 0) {
    return rows.map((row, index) => mapSideBySideRowToUnifiedRow(row, index))
  }

  const fallbackLines = normalizeLines(fileChange.afterContent ?? '')
  return fallbackLines.map((line, index) => ({
    key: `fallback:${index + 1}`,
    type: 'context',
    leftLineNumber: index + 1,
    rightLineNumber: index + 1,
    leftContent: line,
    rightContent: line,
    leftParts: [{ text: line, changed: false }],
    rightParts: [{ text: line, changed: false }],
  }))
}

/**
 * 把 side-by-side diff 行转换为统一视图模型。
 */
function mapSideBySideRowToUnifiedRow(row: SideBySideDiffRow, index: number): UnifiedDiffRow {
  if (row.type === 'changed') {
    const inlineDiff = buildInlineDiffParts(row.leftContent, row.rightContent)
    return {
      key: `changed:${index}:${row.leftLineNumber ?? 'n'}:${row.rightLineNumber ?? 'n'}`,
      type: 'changed',
      leftLineNumber: row.leftLineNumber,
      rightLineNumber: row.rightLineNumber,
      leftContent: row.leftContent,
      rightContent: row.rightContent,
      leftParts: inlineDiff.leftParts,
      rightParts: inlineDiff.rightParts,
    }
  }

  if (row.type === 'removed') {
    return {
      key: `removed:${index}:${row.leftLineNumber ?? 'n'}`,
      type: 'removed',
      leftLineNumber: row.leftLineNumber,
      rightLineNumber: row.rightLineNumber,
      leftContent: row.leftContent,
      rightContent: row.rightContent,
      leftParts: [{ text: row.leftContent, changed: true }],
      rightParts: [],
    }
  }

  if (row.type === 'added') {
    return {
      key: `added:${index}:${row.rightLineNumber ?? 'n'}`,
      type: 'added',
      leftLineNumber: row.leftLineNumber,
      rightLineNumber: row.rightLineNumber,
      leftContent: row.leftContent,
      rightContent: row.rightContent,
      leftParts: [],
      rightParts: [{ text: row.rightContent, changed: true }],
    }
  }

  return {
    key: `context:${index}:${row.rightLineNumber ?? row.leftLineNumber ?? 'n'}`,
    type: 'context',
    leftLineNumber: row.leftLineNumber,
    rightLineNumber: row.rightLineNumber,
    leftContent: row.leftContent,
    rightContent: row.rightContent,
    leftParts: [{ text: row.leftContent, changed: false }],
    rightParts: [{ text: row.rightContent, changed: false }],
  }
}

/**
 * 解析并排视图单元格应使用的状态。
 * changed 行会在左右两侧分别显示删除态和新增态。
 */
function resolveSplitCellState(
  side: 'left' | 'right',
  type: UnifiedDiffRow['type'],
  lineNumber: number | null,
): 'context' | 'added' | 'removed' | 'empty' {
  if (lineNumber == null) {
    return 'empty'
  }
  if (type === 'changed') {
    return side === 'left' ? 'removed' : 'added'
  }
  if (type === 'removed' || type === 'added') {
    return type
  }
  return 'context'
}

/**
 * 渲染行内高亮片段。
 */
function renderInlineParts(parts: InlineDiffPart[], fallbackContent: string): React.ReactNode {
  const effectiveParts = parts.length > 0 ? parts : [{ text: fallbackContent, changed: false }]
  return effectiveParts.map((part, index) => (
    <span
      key={`${index}:${part.text}`}
      className={part.changed ? 'file-diff-inline file-diff-inline--changed' : 'file-diff-inline'}
    >
      {part.text || '\u00A0'}
    </span>
  ))
}

/**
 * 构建同一行内的词级 diff。
 * 优先按“单词 + 分隔符”切分，保证配置项、引号和空格也能稳定显示。
 */
function buildInlineDiffParts(leftText: string, rightText: string): {
  leftParts: InlineDiffPart[]
  rightParts: InlineDiffPart[]
} {
  const leftTokens = tokenizeInlineText(leftText)
  const rightTokens = tokenizeInlineText(rightText)
  const tokenDiff = buildLineDiff(leftTokens.join('\n'), rightTokens.join('\n'))

  const leftParts: InlineDiffPart[] = []
  const rightParts: InlineDiffPart[] = []

  tokenDiff.forEach((line) => {
    if (line.type === 'context') {
      leftParts.push({ text: line.content, changed: false })
      rightParts.push({ text: line.content, changed: false })
      return
    }
    if (line.type === 'removed') {
      leftParts.push({ text: line.content, changed: true })
      return
    }
    rightParts.push({ text: line.content, changed: true })
  })

  return {
    leftParts: mergeInlineParts(leftParts),
    rightParts: mergeInlineParts(rightParts),
  }
}

/**
 * 把文本切成可用于行内 diff 的 token。
 * 保留空白和标点，避免高亮时把结构字符丢掉。
 */
function tokenizeInlineText(text: string): string[] {
  const tokens = text.match(/(\s+|[A-Za-z0-9_]+|[^\sA-Za-z0-9_])/g)
  return tokens && tokens.length > 0 ? tokens : [text]
}

/**
 * 合并相邻且状态相同的片段，避免渲染过碎。
 */
function mergeInlineParts(parts: InlineDiffPart[]): InlineDiffPart[] {
  if (parts.length === 0) {
    return []
  }

  const merged: InlineDiffPart[] = []
  parts.forEach((part) => {
    const previous = merged[merged.length - 1]
    if (previous && previous.changed === part.changed) {
      previous.text += part.text
      return
    }
    merged.push({ ...part })
  })
  return merged
}

/**
 * 规范化文本为逐行数组。
 */
function normalizeLines(content: string): string[] {
  const normalized = content.replace(/\r\n/g, '\n')
  if (!normalized) {
    return []
  }
  return normalized.split('\n')
}
