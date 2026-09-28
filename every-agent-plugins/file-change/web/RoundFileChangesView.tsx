import { useRef, useState } from 'react'
import type { RoundTailPanelProps } from '@everyagent/plugin-api'
import { CaretDownOutlined, CaretRightOutlined } from '@ant-design/icons'
import { getPluginContext } from './pluginRuntime'
import './roundFileChanges.css'

/** 文件变更摘要（rounds.jsonl 每轮 fileChanges 项，轻量级）。 */
interface RoundFileChangeSummary {
  filePath: string
  fileName: string
  changeType: 'created' | 'updated' | 'deleted'
  saveCount: number
}

/** 文件变更全文项。 */
interface TaskFileChangeFull {
  filePath: string
  fileName: string
  changeType: 'created' | 'updated' | 'deleted'
  saveCount: number
  beforeContent: string
  afterContent: string
}

/** task.fileChanges rpc.ok 应答。 */
interface TaskFileChangesResult {
  changes: TaskFileChangeFull[]
}

/** changeType 与 git 状态字母、中文含义的映射(A=Added/新建,M=Modified/修改,D=Deleted/删除)。 */
const CHANGE_GIT_LETTER: Record<
  RoundFileChangeSummary['changeType'],
  { letter: string; title: string }
> = {
  created: { letter: 'A', title: '新建 (Added)' },
  updated: { letter: 'M', title: '修改 (Modified)' },
  deleted: { letter: 'D', title: '删除 (Deleted)' },
}

/**
 * 归一为「完整业务路径」(带前导 / 的 '/a/b/c',与资源树行路径/文件标签 id 同一形态)。
 * worker 新版落盘的 filePath 已是锚定后的完整路径;旧数据是模型给的原始裸/相对路径
 * (根级文件与 fileName 同串,导致「路径」列退化为文件名),此处兜底补前导 / 并词法消解
 * '.'/'..' 段。Windows 盘符 / UNC 绝对路径(工作区外授权写)原样保留。
 * workspaceGateway 入参对该形态与相对形态都接受(normalize 时剥前导 /),diff 标签
 * 的 stat/read/定位不受影响。
 */
function toDisplayPath(filePath: string): string {
  const normalized = filePath.trim().replace(/\\/g, '/')
  if (!normalized) {
    return ''
  }
  if (/^[A-Za-z]:[\\/]/.test(normalized) || normalized.startsWith('//')) {
    return normalized
  }
  const segments: string[] = []
  for (const segment of normalized.split('/')) {
    if (!segment || segment === '.') continue
    if (segment === '..') {
      segments.pop()
      continue
    }
    segments.push(segment)
  }
  return `/${segments.join('/')}`
}

/**
 * 轮末文件变更视图:渲染该轮文件变更轻量摘要行(文件名 + 完整路径 + A/M/D 标签),
 * 点击某行时按 roundId 经 task.fileChanges 拉取全文(含 beforeContent/afterContent),
 * 再开 diff 标签对比。同一轮的全文按 roundId 缓存,同轮多次点击不重复 RPC;
 * 拉取失败时仅记 warn 忽略(轻提示即可,不阻断线程浏览)。
 */
export default function RoundFileChangesView({
  taskId,
  roundId,
  workerId,
  workspaceRoot,
  round,
}: RoundTailPanelProps) {
  const changes = ((round as { fileChanges?: RoundFileChangeSummary[] })?.fileChanges) ?? []

  /** 默认折叠:只显示「文件变更」汇总头,点击展开文件行列表。 */
  const [open, setOpen] = useState(false)
  /** roundId → 该轮已拉取的全文变更列表(避免同轮多次点击重复 RPC)。 */
  const fullCacheRef = useRef<Map<string, TaskFileChangeFull[]>>(new Map())

  if (changes.length === 0) return null

  const openDiff = async (summary: RoundFileChangeSummary) => {
    let fullChanges = fullCacheRef.current.get(roundId)
    if (!fullChanges) {
      const ctx = getPluginContext()
      try {
        const result = await ctx.sdk.rpc(
          workerId ?? ctx.sdk.workerId,
          'task.fileChanges',
          { taskId, roundId },
        ) as TaskFileChangesResult
        fullChanges = result.changes ?? []
        fullCacheRef.current.set(roundId, fullChanges)
      } catch (error) {
        console.warn(`[RoundFileChangesView] 拉取第 ${roundId} 轮文件变更全文失败(${taskId}):`, error)
        return
      }
    }
    const full = fullChanges.find((c) => c.filePath === summary.filePath)
    if (!full) return
    const displayPath = toDisplayPath(full.filePath)
    const ctx = getPluginContext()
    ctx.ui.openDiffTab({
      filePath: displayPath,
      fileName: full.fileName,
      changeType: full.changeType === 'created' ? 'created' : 'updated',
      beforeContent: full.beforeContent,
      afterContent: full.afterContent,
      title: `变更对比：${full.fileName}`,
      workspaceRoot,
    })
  }

  return (
    <div className="task-file-changes">
      <button
        type="button"
        className="task-file-changes__toggle"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
        title={open ? '收起文件变更' : '展开文件变更'}
      >
        <span className="task-file-changes__toggle-icon">
          {open ? <CaretDownOutlined /> : <CaretRightOutlined />}
        </span>
        <span className="task-file-changes__toggle-label">文件变更</span>
        <span className="task-file-changes__toggle-count">{changes.length}</span>
      </button>
      {open ? (
        <div className="task-file-changes__list">
          {changes.map((change) => {
            const displayPath = toDisplayPath(change.filePath)
            return (
              <button
                key={`${taskId}:${roundId}:${displayPath}`}
                type="button"
                className="task-file-changes__row"
                onClick={() => void openDiff(change)}
              >
                <span className="task-file-changes__name">{change.fileName}</span>
                {/* 前缀 LRM(零宽强 LTR 字符):路径列 direction:rtl(长路径从左截断显尾),
                    前导 '/' 是中性字符会被 RTL 段落排到行尾(显示成 'foo.md/'),LRM 使整段
                    解析为单一 LTR 渲染串;title 供截断时 hover 查看完整路径。 */}
                <span className="task-file-changes__path" title={displayPath}>
                  {'\u200E' + displayPath}
                </span>
                <span
                  className={`task-file-changes__tag task-file-changes__tag--${change.changeType}`}
                  title={CHANGE_GIT_LETTER[change.changeType].title}
                >
                  {CHANGE_GIT_LETTER[change.changeType].letter}
                </span>
              </button>
            )
          })}
        </div>
      ) : null}
    </div>
  )
}
