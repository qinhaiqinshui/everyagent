import React from 'react'
import {
  isMarkdownTableDataLine,
  isMarkdownTableHeaderLine,
  isMarkdownTableSeparatorLine,
  parseMarkdownTableAlignments,
  renderMarkdownInline,
  renderMarkdownInlineWithBreaks,
  splitMarkdownTableRow,
} from './markdown/sharedMarkdownRenderer'

export default function MarkdownDisplay({ content }: { content: string }) {
  const lines = normalizeMarkdownContent(content).split('\n')
  const elements: React.ReactNode[] = []
  let paragraphBuffer: string[] = []
  let codeBuffer = ''
  let inCodeBlock = false
  let sequence = 0

  const flushParagraph = () => {
    if (paragraphBuffer.length === 0) return
    elements.push(
      <p key={`p-${sequence++}`} style={paragraphStyle}>
        {renderMarkdownInlineWithBreaks(paragraphBuffer, inlineRenderStyles)}
      </p>,
    )
    paragraphBuffer = []
  }

  const flushCode = () => {
    if (!codeBuffer) return
    elements.push(
      <pre key={`code-${sequence++}`} style={codeBlockStyle}>
        <code>{codeBuffer}</code>
      </pre>,
    )
    codeBuffer = ''
  }

  const flushBlocks = () => {
    flushParagraph()
  }

  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index]

    if (line.startsWith('```')) {
      flushBlocks()
      if (inCodeBlock) {
        flushCode()
        inCodeBlock = false
      } else {
        inCodeBlock = true
      }
      continue
    }

    if (inCodeBlock) {
      codeBuffer += `${line}\n`
      continue
    }

    if (isMarkdownTableHeaderLine(line) && index + 1 < lines.length && isMarkdownTableSeparatorLine(lines[index + 1])) {
      flushBlocks()

      const headerCells = splitMarkdownTableRow(line)
      const alignments = parseMarkdownTableAlignments(lines[index + 1])
      const bodyRows: string[][] = []

      index += 2
      while (index < lines.length && isMarkdownTableDataLine(lines[index])) {
        bodyRows.push(splitMarkdownTableRow(lines[index]))
        index += 1
      }
      index -= 1

      elements.push(
        <TableBlock
          key={`table-${sequence++}`}
          headers={headerCells}
          alignments={alignments}
          rows={bodyRows}
        />,
      )
      continue
    }

    const listBlock = parseNestedList(lines, index)
    if (listBlock) {
      flushParagraph()
      elements.push(renderListNode(listBlock.node, 0, `list-root-${sequence++}`))
      index = listBlock.nextIndex - 1
      continue
    }

    if (!line.trim()) {
      flushBlocks()
      elements.push(<div key={`space-${sequence++}`} className="md-spacer" style={spacerStyle} />)
      continue
    }

    if (line.trim() === '---') {
      flushBlocks()
      elements.push(<hr key={`hr-${sequence++}`} style={hrStyle} />)
      continue
    }

    if (line.startsWith('### ')) {
      flushParagraph()
      elements.push(<h3 key={`h3-${sequence++}`} style={h3Style}>{renderMarkdownInline(line.slice(4), inlineRenderStyles)}</h3>)
      continue
    }

    if (line.startsWith('## ')) {
      flushParagraph()
      elements.push(<h2 key={`h2-${sequence++}`} style={h2Style}>{renderMarkdownInline(line.slice(3), inlineRenderStyles)}</h2>)
      continue
    }

    if (line.startsWith('# ')) {
      flushParagraph()
      elements.push(<h1 key={`h1-${sequence++}`} style={h1Style}>{renderMarkdownInline(line.slice(2), inlineRenderStyles)}</h1>)
      continue
    }

    if (line.startsWith('> ')) {
      flushParagraph()
      elements.push(
        <blockquote key={`quote-${sequence++}`} style={blockquoteStyle}>
          {renderMarkdownInline(line.slice(2), inlineRenderStyles)}
        </blockquote>,
      )
      continue
    }

    paragraphBuffer.push(line)
  }

  flushParagraph()
  if (inCodeBlock || codeBuffer) {
    flushCode()
  }

  // 仅裁剪界面渲染：去掉末尾连续的空行占位（height:6 的 spacer），
  // 避免 AI 输出末尾出现多余空白。落盘 content 不变。
  while (
    elements.length > 0 &&
    React.isValidElement(elements[elements.length - 1]) &&
    (elements[elements.length - 1] as React.ReactElement<Record<string, unknown>>).props.className === 'md-spacer'
  ) {
    elements.pop()
  }

  return <div style={rootStyle}>{elements}</div>
}

function TableBlock({
  headers,
  alignments,
  rows,
}: {
  headers: string[]
  alignments: Array<'left' | 'center' | 'right'>
  rows: string[][]
}) {
  return (
    <div style={tableWrapStyle}>
      <table style={tableStyle}>
        <thead>
          <tr>
            {headers.map((cell, index) => (
              <th
                key={`header-${index}`}
                style={{
                  ...tableHeaderCellStyle,
                  textAlign: alignments[index] ?? 'left',
                }}
              >
                {renderMarkdownInline(cell, inlineRenderStyles)}
              </th>
            ))}
          </tr>
        </thead>
        {rows.length > 0 ? (
          <tbody>
            {rows.map((row, rowIndex) => (
              <tr key={`row-${rowIndex}`}>
                {headers.map((_, cellIndex) => (
                  <td
                    key={`cell-${rowIndex}-${cellIndex}`}
                    style={{
                      ...tableCellStyle,
                      textAlign: alignments[cellIndex] ?? 'left',
                    }}
                  >
                    {renderMarkdownInline(row[cellIndex] ?? '', inlineRenderStyles)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        ) : null}
      </table>
    </div>
  )
}

type ListItemNode = {
  text: string
  childList: ListNode | null
}

type ListNode = {
  ordered: boolean
  items: ListItemNode[]
}

function parseNestedList(
  lines: string[],
  startIndex: number,
): { node: ListNode; nextIndex: number } | null {
  const itemRegex = /^(\s*)([-*+]|\d+[.)])\s+(.+?)\s*$/
  const firstMatch = itemRegex.exec(lines[startIndex])
  if (!firstMatch) return null

  const baseOrdered = /^\d/.test(firstMatch[2])
  const items: ListItemNode[] = []
  const stack: { indent: number; node: ListItemNode }[] = []
  let index = startIndex

  while (index < lines.length) {
    const line = lines[index]
    if (!line.trim()) break

    const match = itemRegex.exec(line)
    if (!match) break

    const indent = match[1].replace(/\t/g, '  ').length
    const ordered = /^\d/.test(match[2])
    const item: ListItemNode = { text: match[3], childList: null }

    while (stack.length > 0 && stack[stack.length - 1].indent >= indent) {
      stack.pop()
    }

    if (stack.length === 0) {
      items.push(item)
    } else {
      const parent = stack[stack.length - 1].node
      if (!parent.childList) {
        parent.childList = { ordered, items: [] }
      }
      parent.childList.items.push(item)
    }
    stack.push({ indent, node: item })
    index += 1
  }

  return { node: { ordered: baseOrdered, items }, nextIndex: index }
}

function buildListStyle(ordered: boolean, depth: number): React.CSSProperties {
  const base = ordered ? orderedListStyle : listStyle
  const marker = ordered ? 'decimal' : depth === 0 ? 'disc' : depth === 1 ? 'circle' : 'square'
  return {
    ...base,
    marginLeft: depth * 18 + 18,
    listStyleType: marker,
  }
}

function renderListNode(node: ListNode, depth: number, keyPrefix: string): React.ReactNode {
  const Tag = node.ordered ? 'ol' : 'ul'
  return (
    <Tag key={keyPrefix} style={buildListStyle(node.ordered, depth)}>
      {node.items.map((item, index) => (
        <li key={`${keyPrefix}-${index}`} style={listItemStyle}>
          {renderMarkdownInline(item.text, inlineRenderStyles)}
          {item.childList && renderListNode(item.childList, depth + 1, `${keyPrefix}-${index}-child`)}
        </li>
      ))}
    </Tag>
  )
}

function normalizeMarkdownContent(content: string): string {
  if (!content) return ''

  return content
    .replace(/\\r\\n/g, '\n')
    .replace(/\\n/g, '\n')
    .replace(/\r\n?/g, '\n')
}

const rootStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 0,
}

const h1Style: React.CSSProperties = {
  margin: '0 0 8px',
  fontSize: 'var(--text-xl)',
  lineHeight: 1.35,
  fontWeight: 700,
  color: 'var(--text-primary)',
}

const h2Style: React.CSSProperties = {
  margin: '4px 0 8px',
  fontSize: 'var(--text-lg)',
  lineHeight: 1.4,
  fontWeight: 700,
  color: 'var(--text-primary)',
}

const h3Style: React.CSSProperties = {
  margin: '4px 0 6px',
  fontSize: 'var(--text-base)',
  lineHeight: 1.45,
  fontWeight: 700,
  color: 'var(--text-primary)',
}

const paragraphStyle: React.CSSProperties = {
  margin: 0,
  fontSize: 'var(--text-sm)',
  lineHeight: 1.8,
  color: 'var(--text-secondary)',
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
}

const strongStyle: React.CSSProperties = {
  fontWeight: 700,
  color: 'var(--text-primary)',
}

const inlineCodeStyle: React.CSSProperties = {
  padding: '1px 5px',
  borderRadius: 5,
  color: 'var(--text-primary)',
  fontSize: 'var(--text-xs)',
  fontFamily: 'var(--font-mono)',
  border: '1px solid color-mix(in srgb, var(--border-light) 80%, transparent)',
}

const inlineRenderStyles = {
  strong: strongStyle,
  inlineCode: inlineCodeStyle,
}

const listStyle: React.CSSProperties = {
  margin: '2px 0 2px 18px',
  paddingLeft: 2,
  listStyleType: 'disc',
}

const orderedListStyle: React.CSSProperties = {
  ...listStyle,
  marginLeft: 22,
  listStyleType: 'decimal',
}

const listItemStyle: React.CSSProperties = {
  fontSize: 'var(--text-sm)',
  lineHeight: 1.75,
  color: 'var(--text-secondary)',
  marginBottom: 4,
}

const blockquoteStyle: React.CSSProperties = {
  margin: '4px 0',
  padding: '8px 12px',
  borderLeft: '3px solid color-mix(in srgb, var(--bg-tertiary) 75%, #000)',
  borderRadius: '0 var(--radius-md) var(--radius-md) 0',
  color: 'var(--text-secondary)',
  fontSize: 'var(--text-sm)',
  lineHeight: 1.75,
}

const codeBlockStyle: React.CSSProperties = {
  margin: '4px 0',
  padding: '12px 14px',
  borderRadius: 'var(--radius-md)',
  color: 'var(--text-primary)',
  fontSize: 'var(--text-xs)',
  lineHeight: 1.7,
  fontFamily: 'var(--font-mono)',
  whiteSpace: 'pre-wrap',
  overflowX: 'auto',
  border: '1px solid color-mix(in srgb, var(--border-light) 85%, transparent)',
}

const tableWrapStyle: React.CSSProperties = {
  width: '100%',
  overflowX: 'auto',
  margin: '6px 0',
  border: '1px solid color-mix(in srgb, var(--border-light) 85%, transparent)',
  borderRadius: 'var(--radius-md)',
}

const tableStyle: React.CSSProperties = {
  width: '100%',
  borderCollapse: 'collapse',
  background: 'color-mix(in srgb, var(--bg-tertiary) 48%, transparent)',
}

const tableHeaderCellStyle: React.CSSProperties = {
  padding: '10px 12px',
  fontSize: 'var(--text-xs)',
  lineHeight: 1.6,
  fontWeight: 700,
  color: 'var(--text-primary)',
  background: 'color-mix(in srgb, var(--bg-tertiary) 82%, transparent)',
  borderBottom: '1px solid var(--border-light)',
  whiteSpace: 'nowrap',
  verticalAlign: 'top',
}

const tableCellStyle: React.CSSProperties = {
  padding: '9px 12px',
  fontSize: 'var(--text-sm)',
  lineHeight: 1.75,
  color: 'var(--text-secondary)',
  borderTop: '1px solid color-mix(in srgb, var(--border-light) 78%, transparent)',
  verticalAlign: 'top',
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
}

const hrStyle: React.CSSProperties = {
  width: '100%',
  border: 'none',
  borderTop: '1px solid var(--border-light)',
  margin: '8px 0',
}

const spacerStyle: React.CSSProperties = {
  height: 6,
}
