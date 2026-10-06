export type MarkdownHeadingLevel = 1 | 2 | 3 | 4 | 5 | 6

export type MarkdownHeading = {
  id: string
  level: MarkdownHeadingLevel
  text: string
  line: number
}

export function matchMarkdownHeading(line: string): { level: MarkdownHeadingLevel; text: string } | null {
  const match = /^(#{1,6})\s+(.*)$/.exec(line)
  if (!match) return null
  const level = match[1].length
  if (level < 1 || level > 6) return null
  const text = match[2].trim()
  if (!text) return null
  return {
    level: level as MarkdownHeadingLevel,
    text,
  }
}

/** 标题文本 → 锚点 slug(小写、空白折叠为 -、剔除行内标记符号;中文原样保留)。 */
export function slugifyMarkdownHeadingText(text: string): string {
  return text
    .toLowerCase()
    .replace(/[`*_[\]()>#+.!-]/g, ' ')
    .replace(/\s+/g, '-')
    .replace(/^-+|-+$/g, '')
}

export function buildMarkdownHeadingId(text: string, index: number): string {
  const slug = slugifyMarkdownHeadingText(text)
  return slug ? `md-heading-${index}-${slug}` : `md-heading-${index}`
}

export function parseMarkdownHeadings(content: string): MarkdownHeading[] {
  // 归一化 CRLF/CR 行尾：避免 matchMarkdownHeading 因行尾残留 \r 而全部失效（heading 解析失败）。
  const lines = content.replace(/\r\n?/g, '\n').split('\n')
  const headings: MarkdownHeading[] = []
  let inCodeBlock = false

  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index]
    if (line.startsWith('```')) {
      inCodeBlock = !inCodeBlock
      continue
    }
    if (inCodeBlock) continue
    const heading = matchMarkdownHeading(line)
    if (!heading) continue
    headings.push({
      id: buildMarkdownHeadingId(heading.text, headings.length),
      level: heading.level,
      text: heading.text,
      line: index,
    })
  }

  return headings
}

/**
 * 把链接锚点(#后的片段,如「已缓解项」「hard-guarantees」)解析为标题 id。
 *
 * 匹配顺序:
 * 1. 片段(或其 percent 解码形式)恰为完整标题 id(复制自大纲的形态);
 * 2. 片段(或其解码形式)的 slug 与标题文本的 slug 等值——文档作者手写锚点
 *    通常即标题的 GitHub 风格 slug,与 buildMarkdownHeadingId 同一规则。
 * 找不到返回 null(调用方自行提示或忽略)。
 */
export function resolveMarkdownHeadingAnchor(headings: MarkdownHeading[], anchor: string): string | null {
  const trimmed = anchor.trim()
  if (!trimmed) return null
  const candidates: string[] = [trimmed]
  try {
    const decoded = decodeURIComponent(trimmed)
    if (decoded !== trimmed) candidates.push(decoded)
  } catch {
    /* 非法 percent 编码序列:按原文参与匹配 */
  }
  for (const candidate of candidates) {
    const exact = headings.find((heading) => heading.id === candidate)
    if (exact) return exact.id
  }
  for (const candidate of candidates) {
    const slug = slugifyMarkdownHeadingText(candidate)
    if (!slug) continue
    const hit = headings.find((heading) => slugifyMarkdownHeadingText(heading.text) === slug)
    if (hit) return hit.id
  }
  return null
}
