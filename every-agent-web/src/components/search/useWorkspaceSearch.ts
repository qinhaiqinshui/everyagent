/**
 * 统一搜索状态机（`useWorkspaceSearch`）。
 *
 * - 状态：idle / searching / done / error，结果为统一扁平模型 `UnifiedSearchResult`；
 * - 入参：`kinds` 数组（缺省 = 不传 = 全部已注册类型）、`filters` 不透明袋（键约定
 *   `${kind}.${field}`）；**不传 maxResults**（上限由各 provider 自定）；
 * - 查询代际取消：每次发起/取消递增 generation ref，结果落地前核对代际、过期丢弃；
 * - 非法正则预检：面板据当前过滤字段声明推导 `regex`，本 Hook 对 `pattern` 做非法
 *   正则预检（非法时红框提示、不发起）；漏检时 worker 侧正则异常也会以 rpc.err 返回；
 * - 摘要文案按统一模型生成（「N 个结果 · M 个文件（/任务）」等），不再读 `result.files`。
 */
import React from 'react'
import { search, type UnifiedSearchResult } from '@/query/search'

/** 搜索执行状态：未搜索 / 搜索中 / 完成 / 出错。 */
export type WorkspaceSearchStatus = 'idle' | 'searching' | 'done' | 'error'

/** 一次统一搜索请求的参数。 */
export interface WorkspaceSearchRunOptions {
  /** 工作区根（worker 机器绝对路径）。 */
  workspace: string
  /** 搜索词。 */
  pattern: string
  /** 可选数据过滤：只搜这些 kind；缺省/不传 = 全部已注册类型。 */
  kinds?: string[]
  /** 不透明过滤袋（键约定 `${kind}.${field}`）。 */
  filters?: Record<string, unknown>
  /** 命中词是否按正则解释（仅用于非法正则预检；由面板从过滤字段声明推导）。 */
  regex?: boolean
}

const REGEX_META_PATTERN = /[.*+?^${}()|[\]\\]/g

/**
 * 编译搜索正则（仅用于**非法正则预检**，不参与实际匹配——匹配在 worker 侧完成）。
 * 非正则模式按字面量转义元字符；全字匹配包 `\b(?:...)\b`；非敏感附 `i`。
 */
export function buildWorkspaceSearchRegExp(
  pattern: string,
  options: { useRegex: boolean; caseSensitive?: boolean; wholeWord?: boolean },
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

/** 统一结果 → 摘要文案（「N 个结果 · M 个文件（/任务）」+ 截断说明）。 */
function summarizeResult(result: UnifiedSearchResult): string {
  const total = result.items.length
  if (result.items.length > 0 && result.items.every((item) => item.kind === 'file-name')) {
    return `${total} 个文件${result.truncated ? '（已达上限，结果被截断）' : ''}`
  }
  const files = new Set<string>()
  const tasks = new Set<string>()
  for (const item of result.items) {
    if (item.kind === 'file-content' || item.kind === 'file-name') {
      const path = typeof item.path === 'string' ? item.path : ''
      if (path) files.add(path)
    } else if (item.kind === 'task') {
      const taskId = typeof item.taskId === 'string' ? item.taskId : ''
      if (taskId) tasks.add(taskId)
    }
  }
  const parts = [`${total} 个结果`]
  if (files.size > 0) parts.push(`${files.size} 个文件`)
  if (tasks.size > 0) parts.push(`${tasks.size} 个任务`)
  const suffix = result.truncated ? '（已达上限，结果被截断）' : ''
  return `${parts.join(' · ')}${suffix}`
}

/**
 * 工作区统一搜索状态 Hook。
 */
export function useWorkspaceSearch() {
  const [status, setStatus] = React.useState<WorkspaceSearchStatus>('idle')
  const [result, setResult] = React.useState<UnifiedSearchResult | null>(null)
  const [error, setError] = React.useState('')
  /** 非法正则标记：面板据此给输入框加红框（正则修正前不触发搜索）。 */
  const [regexInvalid, setRegexInvalid] = React.useState(false)
  /** 查询代际：发起/取消时递增，用于中断旧查询与丢弃过期结果。 */
  const generationRef = React.useRef(0)
  /** 结果镜像：cancel 回到「已有结果展示态」时需要读取最新结果，用 ref 旁路闭包。 */
  const resultRef = React.useRef<UnifiedSearchResult | null>(result)
  resultRef.current = result

  const run = React.useCallback(async (options: WorkspaceSearchRunOptions) => {
    const pattern = options.pattern.trim()
    if (!pattern) {
      setRegexInvalid(false)
      setError('请输入搜索内容')
      setStatus('error')
      return
    }
    // 非法正则预检：仅当命中词按正则解释时才校验（字面量模式恒合法）。
    if (options.regex) {
      const built = buildWorkspaceSearchRegExp(pattern, { useRegex: true })
      if (built.error !== undefined) {
        setRegexInvalid(true)
        setError(`正则表达式非法：${built.error}`)
        setStatus('error')
        return
      }
    }
    setRegexInvalid(false)
    setError('')
    const generation = generationRef.current + 1
    generationRef.current = generation
    setStatus('searching')
    try {
      const nextResult = await search({
        workspace: options.workspace,
        pattern,
        kinds: options.kinds,
        filters: options.filters,
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
   * 重置为初始 idle 态（清结果/错误/非法正则标记，并作废在途查询）：
   * 搜索面板切换类型/工作区绑定时清旧结果用。
   */
  const reset = React.useCallback(() => {
    generationRef.current += 1
    setResult(null)
    setError('')
    setRegexInvalid(false)
    setStatus('idle')
  }, [])

  /** 摘要文案：搜索中提示 / 完成后由统一模型 summarize 生成。 */
  const summary = React.useMemo(() => {
    if (status === 'searching') {
      return '搜索中…'
    }
    if (status !== 'done' || !result) {
      return ''
    }
    return summarizeResult(result)
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