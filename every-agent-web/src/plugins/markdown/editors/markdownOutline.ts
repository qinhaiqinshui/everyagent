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

export function buildMarkdownHeadingId(text: string, index: number): string {
  const slug = text
    .toLowerCase()
    .replace(/[`*_[\]()>#+.!-]/g, ' ')
    .replace(/\s+/g, '-')
    .replace(/^-+|-+$/g, '')
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
