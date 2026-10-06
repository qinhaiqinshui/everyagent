import React from 'react'
import { type WorkspaceContentSearchResult } from '@/query/workspaceContentSearch'
import { workspaceGateway } from '@/platform/fs/workspaceGateway'
import { INTERNAL_DIR_NAMES, normalizeWorkspaceRelativePath } from '@/platform/fs/pathUtils'
import { searchTasksContent, type TaskContentSearchResult } from '@/query/taskContentSearch'

/** 搜索执行状态机：未搜索 / 搜索中 / 完成 / 出错。 */
export type WorkspaceSearchStatus = 'idle' | 'searching' | 'done' | 'error'

/** 一次搜索请求的全部参数（匹配选项 + 搜索范围）。 */
export interface WorkspaceSearchRunOptions {
  /** 搜索词。正则模式按正则解释，否则按字面量（内部转义正则元字符）。 */
  pattern: string
  /** 正则模式。 */
  useRegex: boolean
  /** 大小写敏感。 */
  caseSensitive: boolean
  /** 全字匹配（`\b(?:...)\b` 包裹）。 */
  wholeWord: boolean
  /** 包含 glob 串（逗号分隔，透传给 includePatterns）。 */
  includePatterns?: string
  /** 排除 glob 串（逗号分隔，透传给 excludePatterns）。 */
  excludePatterns?: string
  /** 搜索范围所属工作区根（worker 机器绝对路径）。 */
  workspaceRoot: string
  /** 搜索根路径（业务绝对形态；空串表示工作区根）。 */
  rootPath: string
  /** 是否枚举内部保留目录（默认 false，与资源管理器默认一致）。 */
  includeInternalFiles?: boolean
  /** 搜索目标：files = 工作区文件内容（默认），tasks = 任务内容。 */
  target?: 'files' | 'tasks'
  /** 匹配维度：content = 逐行搜内容（默认），name = 仅按文件名匹配（不读内容）。仅 files 模式生效。 */
  matchMode?: 'content' | 'name'
  /** 任务内容搜索目标 worker（仅 tasks 模式；files 模式忽略）。 */
  workerId?: string
  /** 任务所属工作区稳定 id（仅 tasks 模式；files 模式忽略）。 */
  workspaceId?: string
}

/** 搜索结果统一形状：文件内容搜索 / 任务内容搜索共用同一状态机（files 项语义随 target 不同）。 */
export type WorkspaceSearchResultShape = WorkspaceContentSearchResult | TaskContentSearchResult

const REGEX_META_PATTERN = /[.*+?^${}()|[\]\\]/g

/** 内部保留目录(如 .git)的逗号串:搜索路径追加进 excludeGlobs(rg --hidden 会进 .git)。 */
const INTERNAL_DIR_GLOBS = Array.from(INTERNAL_DIR_NAMES).join(',')

/**
 * 编译搜索正则。
 * - 非正则模式：pattern 按字面量转义正则元字符；
 * - 全字匹配：源码包一层 `\b(?:...)\b`；
 * - 大小写：非敏感时附 `i` 标志。
 * 返回 `{ regex }` 或 `{ error }`（非法正则，调用方不触发搜索）。
 */
export function buildWorkspaceSearchRegExp(
  pattern: string,
  options: { useRegex: boolean; caseSensitive: boolean; wholeWord: boolean },
): { regex: RegExp; error?: undefined } | { regex?: undefined; error: string } {
  let source = options.useRegex ? pattern : pattern.replace(REGEX_META_PATTERN, '\\$&')
  if (options.wholeWord) {
    source = `\\b(?:${source})\\b`
  }
  try {
    return { regex: new RegExp(source, options.caseSensitive ? undefined : 'i') }
  } catch (error) {
    return { error: error instanceof Error ? error.message : String(error) }
  }
}

/**
 * 工作区内容搜索状态 Hook（仿 VSCode 搜索面板的状态机）。
 *
 * - 状态：idle / searching / done / error，结果为算法层的 WorkspaceContentSearchResult；
 * - 查询代际取消：每次发起/取消递增 generation ref，结果落地前核对代际、过期丢弃
 *   （不覆盖新查询或取消后的状态）；rpc 层无现成取消机制（hub-client 只有超时与
 *   断连 failPending），在途查询同样按代际丢弃；
 * - 数据源：worker 内置 rg 的 fs.search（内容）/ fs.find（文件名），搜索范围
 *   （rootPath）经可选 path 参数下推给 rg（缺省整根）；前端不编译正则、不逐目录
 *   walk，不保留纯前端降级路径（需 worker ≥ fs.find 版本）。
 */
export function useWorkspaceSearch() {
  const [status, setStatus] = React.useState<WorkspaceSearchStatus>('idle')
  const [result, setResult] = React.useState<WorkspaceSearchResultShape | null>(null)
  const [error, setError] = React.useState('')
  /** 非法正则标记：面板据此给输入框加红框（正则修正前不触发搜索）。 */
  const [regexInvalid, setRegexInvalid] = React.useState(false)
  /** 查询代际：发起/取消时递增，用于中断旧查询与丢弃过期结果。 */
  const generationRef = React.useRef(0)
  /** 结果镜像：cancel 回到「已有结果展示态」时需要读取最新结果，用 ref 旁路闭包。 */
  const resultRef = React.useRef<WorkspaceSearchResultShape | null>(result)
  resultRef.current = result
  /** 最近完成搜索的匹配维度（摘要文案区分「结果/文件」）；与 setResult 同一轮更新，memo 以 result 变化触发重算。 */
  const resultMatchModeRef = React.useRef<'content' | 'name'>('content')

  const run = React.useCallback(async (options: WorkspaceSearchRunOptions) => {
    const pattern = options.pattern.trim()
    if (!pattern) {
      setRegexInvalid(false)
      setError('请输入搜索内容')
      setStatus('error')
      return
    }
    const built = buildWorkspaceSearchRegExp(pattern, options)
    if (built.error !== undefined) {
      setRegexInvalid(true)
      setError(`正则表达式非法：${built.error}`)
      setStatus('error')
      return
    }
    setRegexInvalid(false)
    setError('')
    const generation = generationRef.current + 1
    generationRef.current = generation
    setStatus('searching')
    try {
      // 任务内容搜索：worker 侧 task.search（rg + 后处理），不走文件 walk/fs.search。
      if ((options.target ?? 'files') === 'tasks') {
        if (!options.workerId) {
          setError('请先选择 worker')
          setStatus('error')
          return
        }
        if (!options.workspaceId) {
          setError('当前工作区不支持任务内容搜索（缺少 workspaceId）')
          setStatus('error')
          return
        }
        try {
          const taskResult = await searchTasksContent(options.workerId, {
            workspaceId: options.workspaceId,
            pattern,
            isRegex: options.useRegex,
            caseSensitive: options.caseSensitive,
            wholeWord: options.wholeWord,
            maxResults: 500,
          })
          if (generationRef.current !== generation) {
            return
          }
          setResult(taskResult)
          setStatus('done')
        } catch (taskError) {
          if (generationRef.current !== generation) {
            return
          }
          setResult(null)
          setError(taskError instanceof Error ? taskError.message : String(taskError))
          setStatus('error')
        }
        return
      }
      const matchMode = options.matchMode ?? 'content'
      // 文件搜索统一走 worker 内置 rg（架构 §7 契约表）：内容 = fs.search、文件名 =
      // fs.find；搜索范围（rootPath，业务绝对形态先归一为工作区相对路径）经可选 path
      // 参数下推给 rg（缺省整根），不再前端逐目录 walk（原路径中型仓库即数千次串行
      // fs.list RPC，且单个不可读条目会整树报错）。pattern 与开关原样透传，由 worker
      // 侧 rg / Java 正则语义解释（字面量 --fixed-strings / 全字 \b 包裹）；非法正则
      // 已在前面的预检拦下。includeInternalFiles=false 时内部保留目录（如 .git）追加
      // 进排除 glob（rg --hidden 会进 .git）。
      const searchParams = {
        pattern,
        isRegex: options.useRegex,
        caseSensitive: options.caseSensitive,
        wholeWord: options.wholeWord,
        includeGlobs: options.includePatterns,
        excludeGlobs: options.includeInternalFiles
          ? options.excludePatterns
          : [options.excludePatterns, INTERNAL_DIR_GLOBS].filter(Boolean).join(','),
        // 命中上限与内容搜索一致（文件名搜索按文件计）。
        maxResults: 1000,
        path: normalizeWorkspaceRelativePath(options.rootPath),
      }
      const nextResult: WorkspaceContentSearchResult = matchMode === 'name'
        ? await workspaceGateway.findNames(options.workspaceRoot, searchParams)
        : await workspaceGateway.search(options.workspaceRoot, searchParams)
      if (generationRef.current !== generation) {
        // 过期查询（已取消 / 已被新查询取代），丢弃结果。
        return
      }
      resultMatchModeRef.current = matchMode
      setResult(nextResult)
      setStatus('done')
    } catch (runError) {
      if (generationRef.current !== generation) {
        return
      }
      setResult(null)
      setError(runError instanceof Error ? runError.message : String(runError))
      setStatus('error')
    }
  }, [])

  /**
   * 取消当前搜索：递增代际作废在途查询（结果丢弃），
   * 状态回到已有结果的展示态（有结果 → done，无结果 → idle）。
   */
  const cancel = React.useCallback(() => {
    generationRef.current += 1
    setStatus((current) => {
      if (current !== 'searching') return current
      return resultRef.current ? 'done' : 'idle'
    })
  }, [])

  /**
   * 重置为初始 idle 态(清结果/错误/非法正则标记,并作废在途查询):
   * 搜索面板切换 worker/工作区绑定时清旧结果用。
   */
  const reset = React.useCallback(() => {
    generationRef.current += 1
    setResult(null)
    setError('')
    setRegexInvalid(false)
    setStatus('idle')
  }, [])

  /** 摘要文案：搜索中提示 / 完成后的「N 个结果 · M 个文件（截断说明）」。 */
  const summary = React.useMemo(() => {
    if (status === 'searching') {
      return '搜索中…'
    }
    if (status !== 'done' || !result) {
      return ''
    }
    const truncatedSuffix = result.truncated ? '（已达上限，结果被截断）' : ''
    // 文件名搜索：每个命中即一个文件（fs.find 结果项无 matches），摘要以「N 个文件」表述。
    if (resultMatchModeRef.current === 'name') {
      return `${result.files.length} 个文件${truncatedSuffix}`
    }
    return `${result.matchCount} 个结果 · ${result.files.length} 个文件${truncatedSuffix}`
  }, [result, status])

  return {
    status,
    result,
    error,
    regexInvalid,
    summary,
    run,
    cancel,
    reset,
  }
}
