import React from 'react'
import {
  searchWorkspaceContent,
  type WorkspaceContentSearchResult,
} from '@/query/workspaceContentSearch'
import { workspaceGateway } from '@/platform/fs/workspaceGateway'
import { walkWorkspaceFiles } from './walkWorkspaceFiles'

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
}

const REGEX_META_PATTERN = /[.*+?^${}()|[\]\\]/g

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
 * - 查询代际取消：每次发起/取消递增 generation ref，旧查询经 shouldStop 回调中断，
 *   结果落地前核对代际，过期丢弃（不覆盖新查询或取消后的状态）；
 * - 数据源：walkWorkspaceFiles 枚举 + workspaceGateway.readTextFile 直读（UI 专用，
 *   不走 AI 权限网关）。
 */
export function useWorkspaceSearch() {
  const [status, setStatus] = React.useState<WorkspaceSearchStatus>('idle')
  const [result, setResult] = React.useState<WorkspaceContentSearchResult | null>(null)
  const [error, setError] = React.useState('')
  /** 非法正则标记：面板据此给输入框加红框（正则修正前不触发搜索）。 */
  const [regexInvalid, setRegexInvalid] = React.useState(false)
  /** 查询代际：发起/取消时递增，用于中断旧查询与丢弃过期结果。 */
  const generationRef = React.useRef(0)
  /** 结果镜像：cancel 回到「已有结果展示态」时需要读取最新结果，用 ref 旁路闭包。 */
  const resultRef = React.useRef<WorkspaceContentSearchResult | null>(result)
  resultRef.current = result

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
      const nextResult = await searchWorkspaceContent({
        // rootPath 传工作区根（空串）：walkFiles 输出已归一为工作区相对路径，
        // glob 过滤（files to include/exclude）与结果展示都以工作区相对路径为基准（对标 VSCode）。
        rootPath: '',
        regex: built.regex,
        matchMode: 'content',
        walkFiles: async () => {
          const paths = await walkWorkspaceFiles(
            options.workspaceRoot,
            options.rootPath,
            options.includeInternalFiles ?? false,
          )
          // 业务绝对路径（前导 `/`）归一为工作区相对路径。
          return paths.map((path) => path.replace(/^\/+/, ''))
        },
        readFileText: (filePath) => workspaceGateway.readTextFile(options.workspaceRoot, filePath),
        includePatterns: options.includePatterns,
        excludePatterns: options.excludePatterns,
        shouldStop: () => generationRef.current !== generation,
      })
      if (generationRef.current !== generation) {
        // 过期查询（已取消 / 已被新查询取代），丢弃结果。
        return
      }
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
   * 取消当前搜索：递增代际让在途查询的 shouldStop 生效（结果丢弃），
   * 状态回到已有结果的展示态（有结果 → done，无结果 → idle）。
   */
  const cancel = React.useCallback(() => {
    generationRef.current += 1
    setStatus((current) => {
      if (current !== 'searching') return current
      return resultRef.current ? 'done' : 'idle'
    })
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
  }
}
