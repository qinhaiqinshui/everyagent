/**
 * 文件内查找（Ctrl+F）的纯逻辑工具。
 *
 * 查找始终基于「文件源文本」：计数 / 上一处 / 下一处都以源文本匹配为准，
 * 这样无论编辑器以何种方式渲染（纯文本 <pre>、JSON 格式化视图、Markdown 预览），
 * 用户看到的「x / N」都是相对文件内容的稳定值。
 * 视图层（useHighlightMatches）再尽力把命中渲染为高亮，二者解耦。
 */

/** 转义正则元字符，使查找按字面量匹配。 */
export function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

export type SourceMatch = {
  /** 命中在全文中的字符偏移。 */
  offset: number
  /** 命中长度（字符数）。 */
  length: number
  /** 命中所在行号（1-based）。 */
  line: number
}

export type SourceMatchResult = {
  /** 命中详情（最多收集 cap 条，供导航 / 滚动使用）。 */
  items: SourceMatch[]
  /** 命中总数（不受 cap 限制）。 */
  total: number
}

/**
 * 在全文中按正则收集命中。
 *
 * @param cap items 数组上限，避免超大文件构造过多对象；total 仍为真实总数。
 */
export function computeSourceMatches(
  content: string,
  regex: RegExp,
  cap = 2000,
): SourceMatchResult {
  const items: SourceMatch[] = []
  // 重置 lastIndex，保证多次调用结果稳定（外部 regex 可能带 g 标志）。
  const globalRegex = new RegExp(regex.source, regex.flags.includes('g') ? regex.flags : `${regex.flags}g`)
  let total = 0
  let lastIndex = 0
  let line = 1
  let match: RegExpExecArray | null
  while ((match = globalRegex.exec(content)) !== null) {
    total += 1
    if (items.length < cap) {
      const offset = match.index
      // 自 lastIndex 起统计换行，得到命中所在行号。
      for (let i = lastIndex; i < offset; i += 1) {
        if (content.charCodeAt(i) === 10) line += 1
      }
      lastIndex = offset
      items.push({ offset, length: match[0].length, line })
    }
    // 防止零宽匹配导致死循环。
    if (match.index === globalRegex.lastIndex) {
      globalRegex.lastIndex += 1
    }
  }
  return { items, total }
}

/**
 * 由源文本查询构造查找正则。查询为空或非法时返回 null。
 */
export function buildFindRegex(query: string, caseSensitive: boolean): RegExp | null {
  if (!query) return null
  try {
    return new RegExp(escapeRegExp(query), caseSensitive ? 'g' : 'gi')
  } catch {
    return null
  }
}

/**
 * 把替换词中的反向引用展开为实际匹配内容（与 JS 原生 String.replace 语义一致）：
 * $& 整段匹配 / $1..$9 捕获组 / $` 前缀 / $' 后缀 / $$ 字面量 $。
 * 因替换词由调用方字面传入，未捕获的组按空串处理。
 */
export function expandReplacement(match: RegExpExecArray, replacement: string): string {
  return replacement.replace(/\$(\$|&|`|'|\d{1,2})/g, (escaped, token: string) => {
    if (token === '$') return '$'
    if (token === '&') return match[0]
    if (token === '`') return (match.input ?? '').slice(0, match.index ?? 0)
    if (token === "'") return (match.input ?? '').slice((match.index ?? 0) + match[0].length)
    const groupIndex = Number(token)
    if (groupIndex >= 1 && groupIndex < match.length) {
      return match[groupIndex] ?? ''
    }
    return escaped
  })
}

/** 把正则规范化为带 g 标志的副本（不修改传入对象）。 */
function asGlobal(regex: RegExp): RegExp {
  return new RegExp(regex.source, regex.flags.includes('g') ? regex.flags : `${regex.flags}g`)
}

/** 仅替换第 nth 个命中（0-based）；nth 越界时原样返回。 */
export function replaceNthMatch(
  content: string,
  regex: RegExp,
  replacement: string,
  nth: number,
): string {
  const globalRegex = asGlobal(regex)
  const matches = Array.from(content.matchAll(globalRegex))
  if (nth < 0 || nth >= matches.length) return content
  const match = matches[nth]
  const start = match.index ?? 0
  const end = start + match[0].length
  return content.slice(0, start) + expandReplacement(match, replacement) + content.slice(end)
}

/** 替换全部命中（基于初始匹配集合，不对替换产生的新文本二次匹配）。 */
export function replaceAllMatches(
  content: string,
  regex: RegExp,
  replacement: string,
): string {
  const globalRegex = asGlobal(regex)
  const matches = Array.from(content.matchAll(globalRegex))
  if (matches.length === 0) return content
  let result = ''
  let cursor = 0
  for (const match of matches) {
    const start = match.index ?? cursor
    result += content.slice(cursor, start)
    result += expandReplacement(match, replacement)
    cursor = start + match[0].length
  }
  result += content.slice(cursor)
  return result
}
