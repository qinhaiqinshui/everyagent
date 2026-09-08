import React from 'react'
import { buildFindRegex, computeSourceMatches, replaceAllMatches, replaceNthMatch, type SourceMatch } from './findInFile'

export type ContentFindState = {
  query: string
  /** 设置查询（同时重置命中索引到第一处）。 */
  setQuery: (next: string) => void
  caseSensitive: boolean
  toggleCaseSensitive: () => void
  /** 替换词。 */
  replaceQuery: string
  setReplaceQuery: (next: string) => void
  /** 命中总数（基于源文本）。 */
  count: number
  /** 当前命中的序号（0-based）。 */
  activeIndex: number
  /** 上一处 / 下一处，循环导航。 */
  next: () => void
  prev: () => void
  /** 替换当前命中（第 activeIndex 处），新内容经 onContentChange 写回。 */
  replace: () => void
  /** 替换全部命中，新内容经 onContentChange 写回。 */
  replaceAll: () => void
  /** 当前命中（用于编辑态 textarea 滚动等）。命中过多时可能为 null。 */
  activeMatch: SourceMatch | null
  /** 构造好的查找正则；查询为空 / 非法时为 null。 */
  regex: RegExp | null
  /** 清空查询（关闭查找条时调用）。 */
  clear: () => void
}

/**
 * 文件内查找状态：基于源文本维护查询、大小写、当前命中与计数。
 * 与渲染层解耦——只负责「命中在哪里」，不负责高亮。
 *
 * @param content 当前用于查找 / 替换的源文本。
 * @param onContentChange 替换时把新文本写回（只读态可传 noop，替换按钮随之禁用）。
 */
export function useContentFind(
  content: string,
  onContentChange: (next: string) => void,
): ContentFindState {
  const [query, setQueryState] = React.useState('')
  const [replaceQuery, setReplaceQueryState] = React.useState('')
  const [caseSensitive, setCaseSensitive] = React.useState(false)
  const [activeIndex, setActiveIndex] = React.useState(0)

  const regex = React.useMemo(() => buildFindRegex(query, caseSensitive), [query, caseSensitive])

  const matches = React.useMemo(
    () => (regex ? computeSourceMatches(content, regex) : { items: [], total: 0 }),
    [content, regex],
  )

  // 命中列表变化时，把 activeIndex 收敛到合法范围。
  React.useEffect(() => {
    if (matches.total === 0) {
      if (activeIndex !== 0) setActiveIndex(0)
      return
    }
    if (activeIndex >= matches.total) {
      setActiveIndex(0)
    }
  }, [activeIndex, matches.total])

  const setQuery = React.useCallback((next: string) => {
    setQueryState(next)
    setActiveIndex(0)
  }, [])

  const toggleCaseSensitive = React.useCallback(() => {
    setCaseSensitive((current) => !current)
    setActiveIndex(0)
  }, [])

  const setReplaceQuery = React.useCallback((next: string) => {
    setReplaceQueryState(next)
  }, [])

  const next = React.useCallback(() => {
    setActiveIndex((current) => {
      if (matches.total === 0) return 0
      return (current + 1) % matches.total
    })
  }, [matches.total])

  const prev = React.useCallback(() => {
    setActiveIndex((current) => {
      if (matches.total === 0) return 0
      return (current - 1 + matches.total) % matches.total
    })
  }, [matches.total])

  const replace = React.useCallback(() => {
    if (!regex || matches.total === 0) return
    const nextContent = replaceNthMatch(content, regex, replaceQuery, activeIndex)
    onContentChange(nextContent)
    // 保持 activeIndex：当前命中被替换后，后续命中整体前移，activeIndex 自然指向原下一处；
    // 若替换的是最后一处（activeIndex 超出新 total），上方收敛 effect 会归零（循环）。
  }, [content, regex, replaceQuery, activeIndex, matches.total, onContentChange])

  const replaceAll = React.useCallback(() => {
    if (!regex) return
    const nextContent = replaceAllMatches(content, regex, replaceQuery)
    onContentChange(nextContent)
    setActiveIndex(0)
  }, [content, regex, replaceQuery, onContentChange])

  const clear = React.useCallback(() => {
    setQueryState('')
    setActiveIndex(0)
  }, [])

  const activeMatch = matches.items[Math.min(activeIndex, matches.items.length - 1)] ?? null

  return React.useMemo(() => ({
    query,
    setQuery,
    caseSensitive,
    toggleCaseSensitive,
    replaceQuery,
    setReplaceQuery,
    count: matches.total,
    activeIndex,
    next,
    prev,
    replace,
    replaceAll,
    activeMatch,
    regex,
    clear,
  }), [query, setQuery, caseSensitive, toggleCaseSensitive, replaceQuery, setReplaceQuery, matches.total, activeIndex, next, prev, replace, replaceAll, activeMatch, regex, clear])
}
