import React from 'react'

export type MarkdownTableAlignment = 'left' | 'center' | 'right'

/** 内联渲染选项：兼容原 styles 字段（strong/inlineCode），新增图片渲染器。 */
export type MarkdownInlineRenderOptions = {
  strong?: React.CSSProperties
  inlineCode?: React.CSSProperties
  /**
   * 图片语法（![alt](src)）渲染器；未提供时该语法以原文文本降级显示，
   * 保证无工作区上下文（如聊天消息里的 Markdown）也不会丢失内容。
   */
  renderImage?: (src: string, alt: string, key: string) => React.ReactNode
}

export function renderMarkdownInline(
  text: string,
  options: MarkdownInlineRenderOptions = {},
  keyPrefix = '',
): React.ReactNode[] {
  const nodes: React.ReactNode[] = []
  const pattern = /(!\[[^\]]*\]\([^)]+\)|\*\*[^*]+\*\*|`[^`]+`)/g
  let lastIndex = 0
  let match: RegExpExecArray | null
  let key = 0

  while ((match = pattern.exec(text)) !== null) {
    if (match.index > lastIndex) {
      nodes.push(text.slice(lastIndex, match.index))
    }

    const token = match[0]
    if (token.startsWith('![')) {
      const imageMatch = /^!\[([^\]]*)\]\(([^)]+)\)$/.exec(token)
      if (imageMatch && options.renderImage) {
        nodes.push(options.renderImage(imageMatch[1], imageMatch[2].trim(), `${keyPrefix}image-${key++}`))
      } else {
        // 无图片渲染器时降级为原文，不丢信息。
        nodes.push(token)
      }
    } else if (token.startsWith('**') && token.endsWith('**')) {
      nodes.push(
        <strong key={`${keyPrefix}strong-${key++}`} style={options.strong}>
          {token.slice(2, -2)}
        </strong>,
      )
    } else if (token.startsWith('`') && token.endsWith('`')) {
      nodes.push(
        <code key={`${keyPrefix}code-${key++}`} style={options.inlineCode}>
          {token.slice(1, -1)}
        </code>,
      )
    }

    lastIndex = match.index + token.length
  }

  if (lastIndex < text.length) {
    nodes.push(text.slice(lastIndex))
  }

  return nodes
}

export function renderMarkdownInlineWithBreaks(
  lines: string[],
  options: MarkdownInlineRenderOptions = {},
): React.ReactNode[] {
  const nodes: React.ReactNode[] = []
  lines.forEach((line, index) => {
    if (index > 0) {
      nodes.push(<br key={`br-${index}`} />)
    }
    nodes.push(...renderMarkdownInline(line, options, `line-${index}-`))
  })
  return nodes
}

export function isMarkdownTableHeaderLine(line: string): boolean {
  const cells = splitMarkdownTableRow(line)
  return cells.length > 0
    && cells.some((cell) => cell.length > 0)
    && hasUnescapedPipe(line)
}

export function isMarkdownTableSeparatorLine(line: string): boolean {
  const cells = splitMarkdownTableRow(line)
  return cells.length > 0
    && cells.every((cell) => /^:?-{3,}:?$/.test(cell.replace(/\s+/g, '')))
}

export function isMarkdownTableDataLine(line: string): boolean {
  return hasUnescapedPipe(line) && splitMarkdownTableRow(line).length > 0
}

export function parseMarkdownTableAlignments(line: string): MarkdownTableAlignment[] {
  return splitMarkdownTableRow(line).map((cell) => {
    const normalized = cell.replace(/\s+/g, '')
    const startsWithColon = normalized.startsWith(':')
    const endsWithColon = normalized.endsWith(':')

    if (startsWithColon && endsWithColon) return 'center'
    if (endsWithColon) return 'right'
    return 'left'
  })
}

export function splitMarkdownTableRow(line: string): string[] {
  const normalized = line.trim().replace(/^\|/, '').replace(/\|$/, '')
  const cells: string[] = []
  let current = ''
  let escaped = false

  for (const char of normalized) {
    if (escaped) {
      current += char
      escaped = false
      continue
    }

    if (char === '\\') {
      escaped = true
      continue
    }

    if (char === '|') {
      cells.push(current.trim())
      current = ''
      continue
    }

    current += char
  }

  if (escaped) {
    current += '\\'
  }
  cells.push(current.trim())

  return cells
}

function hasUnescapedPipe(line: string): boolean {
  let escaped = false

  for (const char of line) {
    if (escaped) {
      escaped = false
      continue
    }
    if (char === '\\') {
      escaped = true
      continue
    }
    if (char === '|') {
      return true
    }
  }

  return false
}
