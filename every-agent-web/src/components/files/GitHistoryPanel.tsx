import React from 'react'
import { gitGateway, GitNotInitializedError, type GitCommitFileChange } from '@/platform/git/gitGateway'
import { InlineSpinner } from '@/components/shared/ui'
import { GitIcon } from '@/components/icon'
import { useWorkspaceShell } from '@/components/app/WorkspaceShellContext'
import { normalizeWorkspaceRelativePath } from '@/platform/fs/pathUtils'

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

/** 变更状态徽标(对齐 SCM 面板配色)。 */
const CHANGE_BADGE: Record<GitCommitFileChange['changeType'], { letter: string; tone: string; label: string }> = {
  created: { letter: 'A', tone: 'var(--accent-green)', label: '新增' },
  updated: { letter: 'M', tone: 'var(--accent-yellow, #d7a000)', label: '修改' },
  deleted: { letter: 'D', tone: 'var(--accent-red)', label: '删除' },
}

/**
 * Git 历史标签页内容：进入后按路径懒加载提交历史列表（git log -- <path>）。
 * 点击提交行内联展开该提交的变更文件清单（git.show），再点文件打开 diff 标签页。
 * 单文件历史(path 指向文件)展开后把当前文件置顶高亮。
 */
export default function GitHistoryPanel({ workspaceRoot, path, name }: GitHistoryPanelProps) {
  const { openDiffTab } = useWorkspaceShell()
  const [commits, setCommits] = React.useState<GitHistoryCommit[]>([])
  const [loading, setLoading] = React.useState(true)
  const [error, setError] = React.useState('')
  /** 当前展开详情的提交 id(最多展开一条);null = 全部折叠。 */
  const [expandedCommitId, setExpandedCommitId] = React.useState<string | null>(null)
  /** 已展开提交的详细数据缓存(首次展开拉取,折叠再展开复用)。 */
  const [detailCache, setDetailCache] = React.useState<Record<string, GitCommitFileChange[]>>({})
  /** 详情加载中提交 id。 */
  const [detailLoadingId, setDetailLoadingId] = React.useState<string | null>(null)
  /** 详情加载错误消息(按提交 id 存)。 */
  const [detailError, setDetailError] = React.useState<Record<string, string>>({})

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

  React.useEffect(() => {
    // 切换历史目标时清空展开详情与缓存
    setExpandedCommitId(null)
    setDetailCache({})
    setDetailError({})
  }, [workspaceRoot, path])

  /** 展开/折叠提交详情:展开时首次调用 git.show 并缓存。 */
  const handleToggleCommit = React.useCallback((commit: GitHistoryCommit) => {
    setDetailError((prev) => {
      const next = { ...prev }
      delete next[commit.id]
      return next
    })
    setExpandedCommitId((current) => {
      const nextId = current === commit.id ? null : commit.id
      if (nextId && !detailCache[commit.id]) {
        setDetailLoadingId(commit.id)
        gitGateway.showCommit(workspaceRoot, commit.id)
          .then((result) => {
            setDetailCache((cache) => ({ ...cache, [commit.id]: result.files }))
          })
          .catch((showError) => {
            setDetailError((prev) => ({
              ...prev,
              [commit.id]: showError instanceof Error ? showError.message : String(showError),
            }))
          })
          .finally(() => setDetailLoadingId(null))
      }
      return nextId
    })
  }, [detailCache, workspaceRoot])

  /** 打开某提交变更文件的 diff 标签(仅 Git 历史详情,allowRestore=true 显示「恢复此版本」)。 */
  const handleOpenFileDiff = React.useCallback((commit: GitHistoryCommit, file: GitCommitFileChange) => {
    if (file.binary) return
    const slashIndex = file.path.lastIndexOf('/')
    const fileName = slashIndex >= 0 ? file.path.slice(slashIndex + 1) : file.path
    openDiffTab({
      filePath: file.path,
      fileName,
      changeType: file.changeType,
      beforeContent: file.beforeContent ?? '',
      afterContent: file.afterContent ?? '',
      workspaceRoot,
      title: `Git ${commit.shortId}: ${file.path}`,
      binary: file.binary,
      allowRestore: true,
    })
  }, [openDiffTab, workspaceRoot])

  /** 当前查看的文件(单文件历史):path 非空时把该文件的变更项置顶高亮。 */
  const focusRelPath = path ? normalizeWorkspaceRelativePath(path) : ''

  /** 排序:单文件历史聚焦文件置顶,其余按 created/updated/deleted 排后(保持提交内直观顺序)。 */
  const sortFiles = React.useCallback((files: GitCommitFileChange[]): GitCommitFileChange[] => {
    if (!focusRelPath) return files
    const focused = files.filter((f) => f.path === focusRelPath)
    const rest = files.filter((f) => f.path !== focusRelPath)
    return [...focused, ...rest]
  }, [focusRelPath])

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
          {commits.map((commit) => {
            const expanded = expandedCommitId === commit.id
            const files = detailCache[commit.id]
            const detailErr = detailError[commit.id]
            return (
              <div key={commit.id}>
                <div
                  style={{ ...rowStyle, ...(expanded ? rowExpandedStyle : null), cursor: 'pointer' }}
                  title={expanded ? '折叠详情' : '查看该提交的变更详情'}
                  onClick={() => handleToggleCommit(commit)}
                >
                  <span style={shortIdStyle}>{commit.shortId || commit.id.slice(0, 8)}</span>
                  <span style={messageStyle} title={commit.message}>{commit.message || '(无提交说明)'}</span>
                  <span style={metaStyle} title={commit.author}>{commit.author || commit.email || ''}</span>
                  <span style={timeStyle}>{formatCommitTime(commit.ts)}</span>
                  <span style={chevronStyle}>{expanded ? '▾' : '▸'}</span>
                </div>
                {expanded ? (
                  detailLoadingId === commit.id ? (
                    <div style={detailStatusStyle}>
                      <InlineSpinner size={12} color="currentColor" trackColor="transparent" /> 正在加载变更文件…
                    </div>
                  ) : detailErr ? (
                    <div style={detailStatusStyle}>{detailErr}</div>
                  ) : files && files.length === 0 ? (
                    <div style={detailStatusStyle}>该提交没有文件变更。</div>
                  ) : files ? (
                    <div style={fileListStyle}>
                      {sortFiles(files).map((file) => {
                        const badge = CHANGE_BADGE[file.changeType]
                        const isFocus = Boolean(focusRelPath && file.path === focusRelPath)
                        const disabled = file.binary
                        return (
                          <div
                            key={file.path}
                            style={{ ...fileRowStyle, ...(isFocus ? fileRowFocusStyle : null) }}
                            title={
                              disabled
                                ? '二进制文件'
                                : `查看 ${file.path} 的变更(点击打开 diff)`
                            }
                            onClick={() => !disabled && handleOpenFileDiff(commit, file)}
                          >
                            <span style={{ ...fileBadgeStyle, background: badge.tone }}>{badge.letter}</span>
                            <span style={filePathStyle}>{file.path}</span>
                            {isFocus ? <span style={focusTagStyle}>当前文件</span> : null}
                            {disabled ? <span style={binaryTagStyle}>二进制</span> : null}
                          </div>
                        )
                      })}
                    </div>
                  ) : null
                ) : null}
              </div>
            )
          })}
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

const rowExpandedStyle: React.CSSProperties = {
  background: 'var(--bg-secondary)',
}

const chevronStyle: React.CSSProperties = {
  flexShrink: 0,
  width: 14,
  textAlign: 'center',
  color: 'var(--text-muted)',
  fontSize: 11,
}

const detailStatusStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  padding: '6px 14px 6px 34px',
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
}

const fileListStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  padding: '2px 14px 8px 34px',
}

const fileRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '4px 8px',
  fontSize: 'var(--text-xs)',
  minWidth: 0,
  borderRadius: 'var(--radius-sm)',
  cursor: 'pointer',
}

const fileRowFocusStyle: React.CSSProperties = {
  background: 'var(--accent-blue-dim)',
}

const fileBadgeStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  width: 16,
  height: 16,
  borderRadius: 3,
  fontSize: 11,
  fontWeight: 700,
  color: '#fff',
  flexShrink: 0,
}

const filePathStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  color: 'var(--text-primary)',
  fontFamily: '"Cascadia Code", "Fira Code", Consolas, monospace',
}

const focusTagStyle: React.CSSProperties = {
  flexShrink: 0,
  fontSize: 11,
  color: 'var(--accent-blue)',
  fontWeight: 700,
}

const binaryTagStyle: React.CSSProperties = {
  flexShrink: 0,
  fontSize: 11,
  color: 'var(--text-muted)',
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
