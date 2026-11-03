import React from 'react'
import type { TaskFileChange } from '@/task/types'
import { useResponsiveViewport } from '@/hooks/useResponsiveViewport'
import {
  buildLineDiff,
  buildRollbackBlocks,
  buildRollbackReplacePair,
  buildSideBySideRows,
  type DiffLine,
  type RollbackBlock,
  type SideBySideDiffRow,
} from '@/utils/textDiff';
import { Button } from '@/components/shared/ui'
import { useWorkspaceShell } from '@/components/app/WorkspaceShellContext'
import { useAppUi } from '@/components/app/AppUiContext'
import { App } from 'antd'
import { UndoOutlined } from '@ant-design/icons'
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
 *
 * 回滚(反向 update_file):工具栏「回滚全部变更」整体把差异新内容替换回旧内容;
 * 每个连续变更块首行悬浮「回滚此块」按钮,只回滚该处。定位纯靠内容(worker
 * fs.replaceRaw 行尾宽容唯一匹配),不依赖行号;该处已被其他修改 → 定位不再命中
 * → 回滚失败(绝不盲改)。
 */
export default function FileDiffPanel({ fileChange, workspaceRoot: diffWorkspaceRoot }: FileDiffPanelProps) {
  const { isMobile } = useResponsiveViewport()
  const { openGlobalFileTab } = useWorkspaceShell()
  const { showToast } = useAppUi()
  const { modal } = App.useApp()
  const [viewType, setViewType] = React.useState<'unified' | 'split'>(isMobile ? 'unified' : 'split')
  const [restoring, setRestoring] = React.useState(false)
  const [rollingBackAll, setRollingBackAll] = React.useState(false)
  /** 正在回滚的变更块序号(null=无在途块回滚)。 */
  const [pendingHunkIndex, setPendingHunkIndex] = React.useState<number | null>(null)
  /** 已成功回滚的变更块序号集合(渲染为「已回滚」置灰态)。 */
  const [rolledBackHunks, setRolledBackHunks] = React.useState<ReadonlySet<number>>(() => new Set())

  React.useEffect(() => {
    if (isMobile) {
      setViewType('unified')
    }
  }, [isMobile])

  const diffLines = React.useMemo(
    () => buildLineDiff(fileChange.beforeContent ?? '', fileChange.afterContent ?? ''),
    [fileChange],
  )
  const diffRows = React.useMemo(() => buildDiffRows(diffLines, fileChange), [diffLines, fileChange])
  /** 变更块(回滚单元)切分:与下方 hunkFirstRowKeyToIndex(行视图切分)按序号一一对应。 */
  const rollbackBlocks = React.useMemo(() => buildRollbackBlocks(diffLines), [diffLines])

  /**
   * 行视图上的块切分(非 context 行的最大连续段,同一 diffLines 推导 → 与
   * rollbackBlocks 严格同构):块首行 rowKey → 块序号(按钮挂载行)。
   */
  const hunkFirstRowKeyToIndex = React.useMemo(() => {
    const map = new Map<string, number>()
    let start = -1
    diffRows.forEach((row, index) => {
      const isChange = row.type !== 'context'
      if (isChange && start < 0) start = index
      if (!isChange && start >= 0) {
        map.set(diffRows[start].key, map.size)
        start = -1
      }
    })
    if (start >= 0) {
      map.set(diffRows[start].key, map.size)
    }
    return map
  }, [diffRows])

  /** 回滚目标工作区(优先差异所属根,缺省回退注册表首项;null=不可回滚)。 */
  const rollbackWorkspaceRoot = diffWorkspaceRoot ?? workspaceRegistry.primaryRoot()
  /** 回滚能力:非二进制、非删除变更、差异新内容非空(空内容无处定位)。 */
  const canRollback = Boolean(
    !fileChange.binary
    && fileChange.changeType !== 'deleted'
    && (fileChange.afterContent ?? '').length > 0,
  )
  const isCreated = fileChange.changeType === 'created'

  const handleOpenFileInTab = React.useCallback(async () => {
    // 优先用差异所属工作区根精确落位;diff 来源未携带时回退注册表首项。
    const workspaceRoot = diffWorkspaceRoot ?? workspaceRegistry.primaryRoot()
    if (!workspaceRoot) {
      showToast('暂无可用的工作区，无法打开文件。', 'error')
      return
    }
    // 差异可能是已删除文件:磁盘上已无该路径,直接打开会进一个报 NOT_FOUND 的空标签。
    // 先校验存在性,缺失时给可读提示,避免用户看到原始 RPC 错误。
    // 工作区外绝对路径也经 fs.statRaw 校验(不经沙箱,用户操作非 AI 工具)。
    const stat = await workspaceGateway.statRaw(workspaceRoot, fileChange.filePath).catch(() => null)
    if (!stat || stat.isDirectory) {
      showToast(`文件已不存在于工作区（可能已被删除），无法打开：${fileChange.filePath}`, 'error')
      return
    }
    openGlobalFileTab({ workspaceRoot, filePath: fileChange.filePath }, { mode: 'readwrite' })
  }, [diffWorkspaceRoot, fileChange.filePath, openGlobalFileTab, showToast])

  /**
   * 恢复此版本:把工作区文件覆盖为 diff 内容。仅 allowRestore 显示;二进制禁用。
   * 恢复目标:常规文件 = 该提交版本(after);deleted 文件 = 删除前版本(before),写回即重新创建该文件。
   */
  const isDeleted = fileChange.changeType === 'deleted'
  const restoreContent = isDeleted
    ? (fileChange.beforeContent ?? '')
    : (fileChange.afterContent ?? '')
  const canRestore = Boolean(
    fileChange.allowRestore
    && !fileChange.binary
    && restoreContent.length > 0,
  )
  const handleRestore = React.useCallback(() => {
    if (!canRestore) return
    const workspaceRoot = diffWorkspaceRoot ?? workspaceRegistry.primaryRoot()
    if (!workspaceRoot) {
      showToast('暂无可用的工作区，无法恢复。', 'error')
      return
    }
    modal.confirm({
      title: '恢复此版本',
      content: isDeleted
        ? `该提交删除了文件 ${fileChange.filePath}，将按删除前的内容重新创建该文件，此操作不可撤销。确定恢复？`
        : `将把工作区文件 ${fileChange.filePath} 的内容覆盖为该提交版本，此操作不可撤销。确定恢复？`,
      okText: '恢复',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        setRestoring(true)
        try {
          await workspaceGateway.writeTextFileRaw(workspaceRoot, fileChange.filePath, restoreContent)
          showToast(isDeleted ? '已恢复被删除的文件' : '已恢复此版本', 'success')
        } catch (restoreError) {
          showToast(restoreError instanceof Error ? restoreError.message : String(restoreError), 'error')
        } finally {
          setRestoring(false)
        }
      },
    })
  }, [canRestore, isDeleted, diffWorkspaceRoot, fileChange.filePath, restoreContent, modal, showToast])

  /**
   * 回滚全部变更(反向 update_file):oldContent=差异新内容(after)、newContent=差异旧内容
   * (before),worker 行尾宽容定位唯一出现后原位替换。文件在差异生成后被其他修改 → 定位
   * 不再命中 → 回滚失败(宁可失败也不盲改)。created 变更带 deleteWhenEmpty:内容仍是
   * 新建时全文则删除文件(真正撤销「新建」),否则按内容部分回滚。
   */
  const handleRollbackAll = React.useCallback(() => {
    if (!canRollback) return
    const workspaceRoot = rollbackWorkspaceRoot
    if (!workspaceRoot) {
      showToast('暂无可用的工作区，无法回滚。', 'error')
      return
    }
    modal.confirm({
      title: '回滚全部变更',
      content: isCreated
        ? `将撤销对新建文件 ${fileChange.filePath} 的更改：若文件内容仍是新建时的内容则删除该文件；若此后已被修改，回滚将失败。此操作不可撤销，确定回滚？`
        : `将把 ${fileChange.filePath} 中此差异的新内容整体替换回旧内容（按内容定位，不依赖行号）；若文件此后已被其他修改导致内容对不上，回滚将失败。此操作不可撤销，确定回滚？`,
      okText: '回滚',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        setRollingBackAll(true)
        try {
          const result = await workspaceGateway.replaceTextRaw(
            workspaceRoot,
            fileChange.filePath,
            fileChange.afterContent ?? '',
            fileChange.beforeContent ?? '',
            { deleteWhenEmpty: isCreated },
          )
          showToast(result.deleted ? '已回滚并删除新建的文件' : '已回滚全部变更', 'success')
        } catch (rollbackError) {
          showToast(rollbackError instanceof Error ? rollbackError.message : String(rollbackError), 'error')
        } finally {
          setRollingBackAll(false)
        }
      },
    })
  }, [canRollback, isCreated, rollbackWorkspaceRoot, fileChange, modal, showToast])

  /**
   * 回滚单个变更块:search=块新文本(可带上下文行)、replacement=块旧文本(同上下文)。
   * 定位「不唯一」时自动扩大上下文重试(上下文行编码了变更的真实位置,扩位即消歧);
   * 其余失败(典型「未找到」=该处已被其他修改)立即中止。
   */
  const handleRollbackHunk = React.useCallback(async (hunkIndex: number) => {
    const workspaceRoot = rollbackWorkspaceRoot
    if (!workspaceRoot) {
      showToast('暂无可用的工作区，无法回滚。', 'error')
      return
    }
    const block: RollbackBlock | undefined = rollbackBlocks[hunkIndex]
    if (!block) return
    setPendingHunkIndex(hunkIndex)
    try {
      let lastError: unknown = null
      let attempted = false
      for (let context = 0; context <= MAX_ROLLBACK_CONTEXT_LINES; context += 1) {
        const pair = buildRollbackReplacePair(diffLines, block, context)
        if (!pair) continue
        attempted = true
        try {
          await workspaceGateway.replaceTextRaw(workspaceRoot, fileChange.filePath, pair.search, pair.replacement)
          setRolledBackHunks((prev) => new Set(prev).add(hunkIndex))
          showToast('已回滚该处变更', 'success')
          return
        } catch (error) {
          lastError = error
          if (error instanceof Error && error.message.includes('不唯一') && context < MAX_ROLLBACK_CONTEXT_LINES) {
            continue
          }
          throw error
        }
      }
      throw attempted
        ? (lastError instanceof Error ? lastError : new Error('回滚失败'))
        : new Error('该处变更无法按内容定位（缺少上下文锚点），回滚失败')
    } catch (error) {
      showToast(error instanceof Error ? error.message : String(error), 'error')
    } finally {
      setPendingHunkIndex(null)
    }
  }, [diffLines, rollbackBlocks, rollbackWorkspaceRoot, fileChange.filePath, showToast])

  /** 单块回滚按钮状态。 */
  const hunkButtonState = React.useCallback((hunkIndex: number) => ({
    pending: pendingHunkIndex === hunkIndex,
    done: rolledBackHunks.has(hunkIndex),
    disabled: !canRollback || !rollbackWorkspaceRoot || rollingBackAll,
  }), [pendingHunkIndex, rolledBackHunks, canRollback, rollbackWorkspaceRoot, rollingBackAll])

  /** 变更块首行渲染的回滚动作(按钮 / 已回滚标记)。 */
  const renderHunkAction = React.useCallback((hunkIndex: number) => (
    <HunkRollbackButton
      hunkIndex={hunkIndex}
      state={hunkButtonState(hunkIndex)}
      onRollback={handleRollbackHunk}
    />
  ), [hunkButtonState, handleRollbackHunk])

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
        {fileChange.allowRestore ? (
          <Button
            variant="ghost"
            size="sm"
            className="file-diff-panel__open-file"
            title={canRestore ? (isDeleted ? '恢复此版本（按删除前内容重新创建文件）' : '恢复此版本（覆盖当前工作区文件）') : '二进制文件不可恢复'}
            aria-label="恢复此版本"
            disabled={!canRestore || restoring}
            onClick={handleRestore}
          >
            <FilesIcon size={14} />
            <span className="file-diff-panel__open-file-label">{restoring ? '恢复中…' : '恢复此版本'}</span>
          </Button>
        ) : null}
        {canRollback ? (
          <Button
            variant="ghost"
            size="sm"
            className="file-diff-panel__open-file"
            title={rollbackWorkspaceRoot
              ? (isCreated
                ? '回滚全部变更（文件内容仍是新建时内容则删除文件；已被修改则失败）'
                : '回滚全部变更（按内容定位整体替换回旧内容；已被其他修改则失败）')
              : '暂无可用的工作区，无法回滚'}
            aria-label="回滚全部变更"
            disabled={!rollbackWorkspaceRoot || restoring || rollingBackAll}
            onClick={handleRollbackAll}
          >
            <UndoOutlined />
            <span className="file-diff-panel__open-file-label">{rollingBackAll ? '回滚中…' : '回滚全部变更'}</span>
          </Button>
        ) : null}
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
          ? <SplitDiffTable rows={diffRows} hunkFirstRowKeyToIndex={hunkFirstRowKeyToIndex} renderHunkAction={renderHunkAction} />
          : <UnifiedDiffTable rows={diffRows} hunkFirstRowKeyToIndex={hunkFirstRowKeyToIndex} renderHunkAction={renderHunkAction} />}
      </div>
    </div>
  )
}

/** 单块回滚定位不唯一时,自动扩大上下文行的最大行数(通常 1-2 行即可消歧)。 */
const MAX_ROLLBACK_CONTEXT_LINES = 3

type HunkRollbackButtonProps = {
  /** 变更块序号。 */
  hunkIndex: number
  /** 按钮状态(pending 在途 / done 已回滚 / disabled 不可用)。 */
  state: { pending: boolean; done: boolean; disabled: boolean }
  /** 点击回滚。 */
  onRollback: (hunkIndex: number) => void
}

/**
 * 单个变更块的回滚按钮(悬浮于块首行,hover 显示;触屏设备常显)。
 */
function HunkRollbackButton({ hunkIndex, state, onRollback }: HunkRollbackButtonProps) {
  if (state.done) {
    return (
      <span className="file-diff-hunk-rolledback" title="该处变更已回滚">
        已回滚
      </span>
    )
  }
  return (
    <button
      type="button"
      className="file-diff-hunk-rollback"
      title="回滚此变更：用旧内容替换新内容（按内容定位，不依赖行号）；若该处已被其他修改则回滚失败"
      disabled={state.disabled || state.pending}
      onClick={() => onRollback(hunkIndex)}
    >
      <UndoOutlined />
      <span>{state.pending ? '回滚中…' : '回滚此块'}</span>
    </button>
  )
}

type SplitDiffTableProps = {
  /** 当前文件的 diff 行。 */
  rows: UnifiedDiffRow[]
  /** 块首行 rowKey → 变更块序号(按钮挂载行)。 */
  hunkFirstRowKeyToIndex: Map<string, number>
  /** 变更块回滚动作渲染器。 */
  renderHunkAction: (hunkIndex: number) => React.ReactNode
}

/**
 * 左右分栏 diff 表格。
 */
function SplitDiffTable({ rows, hunkFirstRowKeyToIndex, renderHunkAction }: SplitDiffTableProps) {
  return (
    <div className="file-diff-panel__table file-diff-panel__table--split" role="table" aria-label="并排文件差异">
      {rows.map((row) => {
        const hunkIndex = hunkFirstRowKeyToIndex.get(row.key)
        return (
          <div
            key={row.key}
            className={`file-diff-row file-diff-row--${row.type}${hunkIndex !== undefined ? ' file-diff-row--hunk-start' : ''}`}
            role="row"
          >
            {hunkIndex !== undefined ? renderHunkAction(hunkIndex) : null}
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
        )
      })}
    </div>
  )
}

type UnifiedDiffTableProps = {
  /** 当前文件的 diff 行。 */
  rows: UnifiedDiffRow[]
  /** 块首行 rowKey → 变更块序号(按钮挂载行)。 */
  hunkFirstRowKeyToIndex: Map<string, number>
  /** 变更块回滚动作渲染器。 */
  renderHunkAction: (hunkIndex: number) => React.ReactNode
}

/**
 * 单列 diff 表格。
 * 移动端保留完整文件顺序，同时把变更行拆成旧值/新值两条展示。
 */
function UnifiedDiffTable({ rows, hunkFirstRowKeyToIndex, renderHunkAction }: UnifiedDiffTableProps) {
  return (
    <div className="file-diff-panel__table file-diff-panel__table--unified" role="table" aria-label="单列文件差异">
      {rows.flatMap((row) => {
        // 变更块首行按钮挂在块首 row 的第一条渲染行上(changed 行挂旧值行)
        const hunkIndex = hunkFirstRowKeyToIndex.get(row.key)
        const hunkAction = hunkIndex !== undefined ? renderHunkAction(hunkIndex) : null

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
                hunkAction={hunkAction}
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
                hunkAction={hunkAction}
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
                hunkAction={hunkAction}
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
              hunkAction={hunkAction}
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
  /** 该行是变更块首行时携带的回滚动作(悬浮按钮)。 */
  hunkAction?: React.ReactNode
}

/**
 * 单列视图的单行。
 */
function UnifiedDiffLine({ marker, type, lineNumber, content, parts, hunkAction }: UnifiedDiffLineProps) {
  return (
    <div
      className={`file-diff-unified-line file-diff-unified-line--${type}${hunkAction != null ? ' file-diff-unified-line--hunk-start' : ''}`}
      role="row"
    >
      {hunkAction != null ? hunkAction : null}
      <span className="file-diff-unified-line__line-number">{lineNumber ?? ''}</span>
      <span className="file-diff-unified-line__marker">{marker}</span>
      <span className="file-diff-unified-line__content">{renderInlineParts(parts, content)}</span>
    </div>
  )
}

/**
 * 把文件内容转成稳定的 VSCode 风格 diff 行。
 */
function buildDiffRows(diffLines: DiffLine[], fileChange: TaskFileChange): UnifiedDiffRow[] {
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
