/**
 * 通用搜索结果树原语 · 命中行高亮组件。
 *
 * buildLineSegments / MAX_LINE_LENGTH / TRUNCATE_KEEP 此前在 SearchResultsTree
 * 与 TaskSearchResultsTree 各抄一份(文件结果树与任务结果树同款截断策略),本文件
 * 单点化:输入行正文 + 首命中定位(matchIndex / matchText),输出「普通段 + 命中
 * 片段 <mark>」的展示文本序列。行为约定:
 * - 一行内命中片段的**所有**出现位置均高亮(旧实现只高亮 matchIndex 处的第一个,
 *   这是本组件唯一的预期增强);单命中渲染与旧实现逐字符一致;
 * - matchIndex 缺失(-1)或与行内容对不上、matchText 为空 → 整行不高亮(退化为
 *   旧实现的无命中渲染);
 * - 行超长(> MAX_LINE_LENGTH)时中间截断:有命中定位则围绕首个命中片段保留前后
 *   各 TRUNCATE_KEEP 字符(截断窗口内的其余命中同样高亮),无命中则保留首尾各
 *   TRUNCATE_KEEP 字符,两端以省略号示意。
 */
import React from 'react'

/** 单行最长展示字符数,超出时中间截断(优先围绕命中片段保留上下文)。 */
export const MAX_LINE_LENGTH = 250
/** 中间截断时命中片段前后各保留的字符数。 */
export const TRUNCATE_KEEP = 110

/** 展示文本段:hit = true 的段渲染为高亮 <mark>。 */
export interface MatchLineSegment {
  text: string
  hit: boolean
}

/** 首命中定位是否与行内容对得上(对不上则整行不高亮,与旧实现一致)。 */
function hasAnchoredMatch(line: string, matchIndex: number, matchText: string): boolean {
  return matchIndex >= 0
    && matchText.length > 0
    && matchIndex + matchText.length <= line.length
    && line.slice(matchIndex, matchIndex + matchText.length) === matchText
}

/** 行内 matchText 的全部出现位置 [start, end)(互不重叠,按位置序)。 */
function findAllOccurrences(line: string, matchText: string): Array<[number, number]> {
  const ranges: Array<[number, number]> = []
  let from = 0
  while (from + matchText.length <= line.length) {
    const index = line.indexOf(matchText, from)
    if (index < 0) break
    ranges.push([index, index + matchText.length])
    from = index + matchText.length
  }
  return ranges
}

/** 按命中区间把文本拆成普通段 + 高亮段(区间之间的普通文本原样保留)。 */
function segmentsFromRanges(text: string, ranges: Array<[number, number]>): MatchLineSegment[] {
  const segments: MatchLineSegment[] = []
  let cursor = 0
  for (const [start, end] of ranges) {
    if (start > cursor) segments.push({ text: text.slice(cursor, start), hit: false })
    segments.push({ text: text.slice(start, end), hit: true })
    cursor = end
  }
  if (cursor < text.length) segments.push({ text: text.slice(cursor), hit: false })
  return segments
}

/**
 * 命中行 → 展示文本段序列(单点实现,两棵结果树共用)。
 * 输入为行正文 + 首命中定位;多命中全高亮见文件头注释。
 */
export function buildLineSegments(
  line: string,
  matchIndex: number,
  matchText: string,
): MatchLineSegment[] {
  const anchored = hasAnchoredMatch(line, matchIndex, matchText)

  if (line.length <= MAX_LINE_LENGTH) {
    if (!anchored) return [{ text: line, hit: false }]
    return segmentsFromRanges(line, findAllOccurrences(line, matchText))
  }

  if (anchored) {
    // 超长且有命中定位:围绕首个命中片段截断,保留前后各 TRUNCATE_KEEP 字符。
    const hitStart = matchIndex
    const hitEnd = matchIndex + matchText.length
    const start = Math.max(0, hitStart - TRUNCATE_KEEP)
    const end = Math.min(line.length, hitEnd + TRUNCATE_KEEP)
    const visible = line.slice(start, end)
    return [
      ...(start > 0 ? [{ text: '…', hit: false }] : []),
      ...segmentsFromRanges(visible, findAllOccurrences(visible, matchText)),
      ...(end < line.length ? [{ text: '…', hit: false }] : []),
    ]
  }

  // 超长且无可定位命中:保留首尾各 TRUNCATE_KEEP 字符,中间以省略号示意。
  return [
    { text: `${line.slice(0, TRUNCATE_KEEP)} … `, hit: false },
    { text: line.slice(Math.max(line.length - TRUNCATE_KEEP, TRUNCATE_KEEP + 5)), hit: false },
  ]
}

export interface HighlightedMatchLineProps {
  /** 行正文(已含上下文)。 */
  line: string
  /** 首命中在行内的起始列(0-based;-1 或与行内容对不上 = 整行不高亮)。 */
  matchIndex: number
  /** 命中片段文本(行内所有出现位置均高亮;空串 = 不高亮)。 */
  matchText: string
}

/** 命中行文本:普通段 + 命中片段 <mark>(一行内全部命中均高亮)。 */
export default function HighlightedMatchLine({ line, matchIndex, matchText }: HighlightedMatchLineProps) {
  const segments = React.useMemo(
    () => buildLineSegments(line, matchIndex, matchText),
    [line, matchIndex, matchText],
  )
  return (
    <>
      {segments.map((segment, index) =>
        segment.hit ? (
          <mark key={index} style={matchHighlightStyle}>{segment.text}</mark>
        ) : (
          segment.text
        ),
      )}
    </>
  )
}

/** 命中片段高亮样式(单点)。 */
const matchHighlightStyle: React.CSSProperties = {
  background: 'color-mix(in srgb, var(--accent-blue) 30%, transparent)',
  color: 'var(--text-primary)',
  fontWeight: 600,
  borderRadius: 2,
  padding: '0 1px',
}
