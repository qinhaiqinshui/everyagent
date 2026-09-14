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

/** 表格样式覆盖项：允许调用方在共享默认样式基础上按场景（编辑器预览 / 聊天消息）覆盖。 */
export type MarkdownTableStyles = {
  wrap?: React.CSSProperties
  table?: React.CSSProperties
  headerCell?: React.CSSProperties
  bodyCell?: React.CSSProperties
}

export type MarkdownTableProps = {
  headers: string[]
  alignments: MarkdownTableAlignment[]
  rows: string[][]
  inlineOptions?: MarkdownInlineRenderOptions
  styles?: MarkdownTableStyles
}

/** 统计列宽前剥离内联 markdown 语法（图片取 alt），避免语法符号干扰字符数占比。 */
function stripMarkdownInlineSyntax(text: string): string {
  return text
    .replace(/!\[([^\]]*)\]\([^)]+\)/g, '$1')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
}

/**
 * 按内容字符数比例计算各列宽度百分比。
 * 算法：每列所有行（含表头）文本字符数之和 ÷ 所有列总字符数 = 该列宽度占比；
 * 设最小列宽下限，防止内容极少的列被压扁，结果归一化保证总和为 100%。
 */
export function computeTableColumnWidths(headers: string[], rows: string[][]): string[] {
  const columnCount = headers.length
  if (columnCount === 0) return []

  const totals = new Array<number>(columnCount).fill(0)
  headers.forEach((header, index) => {
    totals[index] += stripMarkdownInlineSyntax(header).length
  })
  rows.forEach((row) => {
    for (let index = 0; index < columnCount; index += 1) {
      totals[index] += stripMarkdownInlineSyntax(row[index] ?? '').length
    }
  })

  const grandTotal = totals.reduce((sum, value) => sum + value, 0)
  if (grandTotal === 0) {
    return headers.map(() => `${(100 / columnCount).toFixed(2)}%`)
  }

  // 列很多时缩小下限，保证下限本身不会超过可分配总宽。
  const minPercent = Math.min(8, 100 / columnCount)
  const widths = new Array<number>(columnCount).fill(0)
  let remaining = 100
  let remainingWeight = 0

  totals.forEach((total, index) => {
    const percent = (total / grandTotal) * 100
    if (percent < minPercent) {
      widths[index] = minPercent
      remaining -= minPercent
    } else {
      remainingWeight += total
    }
  })
  totals.forEach((total, index) => {
    if (widths[index] === 0 && remainingWeight > 0) {
      widths[index] = (total / remainingWeight) * remaining
    }
  })

  return widths.map((width) => `${width.toFixed(2)}%`)
}

/**
 * 共享 Markdown 表格渲染组件（MarkdownPreview / MarkdownDisplay 共用）。
 * 使用 tableLayout: fixed + colgroup 按内容字符数占比分配列宽，
 * 内容超宽时在单元格内自动换行，不再撑破容器或横向滚动。
 */
export function MarkdownTable({ headers, alignments, rows, inlineOptions, styles }: MarkdownTableProps) {
  const columnWidths = computeTableColumnWidths(headers, rows)
  return (
    <div style={{ ...markdownTableWrapStyle, ...styles?.wrap }}>
      <table style={{ ...markdownTableStyle, ...styles?.table }}>
        <colgroup>
          {columnWidths.map((width, index) => (
            <col key={`col-${index}`} style={{ width }} />
          ))}
        </colgroup>
        <thead>
          <tr>
            {headers.map((cell, index) => (
              <th
                key={`th-${index}`}
                style={{
                  ...markdownTableHeaderCellStyle,
                  ...styles?.headerCell,
                  textAlign: alignments[index] ?? 'left',
                }}
              >
                {renderMarkdownInline(cell, inlineOptions ?? {}, `th-${index}-`)}
              </th>
            ))}
          </tr>
        </thead>
        {rows.length > 0 && (
          <tbody>
            {rows.map((row, rowIndex) => (
              <tr key={`tr-${rowIndex}`}>
                {headers.map((_, cellIndex) => (
                  <td
                    key={`td-${rowIndex}-${cellIndex}`}
                    style={{
                      ...markdownTableBodyCellStyle,
                      ...styles?.bodyCell,
                      textAlign: alignments[cellIndex] ?? 'left',
                    }}
                  >
                    {renderMarkdownInline(row[cellIndex] ?? '', inlineOptions ?? {}, `td-${rowIndex}-${cellIndex}-`)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        )}
      </table>
    </div>
  )
}

const markdownTableWrapStyle: React.CSSProperties = {
  width: '100%',
  marginTop: 12,
  marginBottom: 16,
  border: '1px solid var(--border-light)',
  borderRadius: 'var(--radius-md)',
  background: 'var(--bg-primary)',
}

const markdownTableStyle: React.CSSProperties = {
  width: '100%',
  borderCollapse: 'collapse',
  tableLayout: 'fixed',
}

const markdownTableHeaderCellStyle: React.CSSProperties = {
  padding: '10px 12px',
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  color: 'var(--text-primary)',
  background: 'var(--bg-secondary)',
  borderBottom: '1px solid var(--border)',
  verticalAlign: 'top',
  wordBreak: 'break-word',
  overflowWrap: 'anywhere',
}

const markdownTableBodyCellStyle: React.CSSProperties = {
  padding: '10px 12px',
  verticalAlign: 'top',
  fontSize: 'var(--text-sm)',
  lineHeight: 1.7,
  color: 'var(--text-primary)',
  borderTop: '1px solid var(--border-light)',
  wordBreak: 'break-word',
  overflowWrap: 'anywhere',
}
