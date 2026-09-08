import React from 'react'
import {
  buildMarkdownHeadingId,
  matchMarkdownHeading,
  parseMarkdownHeadings,
  type MarkdownHeadingLevel,
} from './markdownOutline'
import {
  isMarkdownTableDataLine,
  isMarkdownTableHeaderLine,
  isMarkdownTableSeparatorLine,
  parseMarkdownTableAlignments,
  renderMarkdownInline,
  type MarkdownTableAlignment,
  splitMarkdownTableRow,
} from '@/components/shared/markdown/sharedMarkdownRenderer'

type MarkdownListItem = {
  text: string
  ordered: boolean
  children: MarkdownListItem[]
}

type MarkdownBlock =
  | {
    type: 'paragraph'
    key: string
    text: string
  }
  | {
    type: 'blockquote'
    key: string
    text: string
  }
  | {
    type: 'hr'
    key: string
  }
  | {
    type: 'spacer'
    key: string
  }
  | {
    type: 'code'
    key: string
    content: string
  }
  | {
    type: 'list'
    key: string
    ordered: boolean
    items: MarkdownListItem[]
  }
  | {
    type: 'table'
    key: string
    headers: string[]
    alignments: MarkdownTableAlignment[]
    rows: string[][]
  }

type MarkdownSection = {
  id: string
  level: MarkdownHeadingLevel
  text: string
  key: string
  blocks: MarkdownBlock[]
  children: MarkdownSection[]
}

type MarkdownDocument = {
  introBlocks: MarkdownBlock[]
  sections: MarkdownSection[]
}

export type MarkdownPreviewHandle = {
  revealHeading: (headingId: string) => boolean
}

type MarkdownPreviewProps = {
  content: string
  wrapLines?: boolean
}

export default React.forwardRef<MarkdownPreviewHandle, MarkdownPreviewProps>(function MarkdownPreview(
  { content, wrapLines = true },
  ref,
) {
  const headingIds = React.useMemo(() => {
    const nextHeadingIds = new Map<number, string>()
    parseMarkdownHeadings(content).forEach((heading) => {
      nextHeadingIds.set(heading.line, heading.id)
    })
    return nextHeadingIds
  }, [content])

  const documentTree = React.useMemo(
    () => parseMarkdownDocument(content, headingIds),
    [content, headingIds],
  )
  const [collapsedHeadingIds, setCollapsedHeadingIds] = React.useState<Set<string>>(() => new Set())

  React.useEffect(() => {
    setCollapsedHeadingIds((current) => {
      const validIds = new Set<string>()
      collectSectionIds(documentTree.sections, validIds)

      let hasChange = false
      const next = new Set<string>()
      current.forEach((id) => {
        if (validIds.has(id)) {
          next.add(id)
        } else {
          hasChange = true
        }
      })
      return hasChange ? next : current
    })
  }, [documentTree])

  const toggleSectionCollapsed = React.useCallback((headingId: string) => {
    setCollapsedHeadingIds((current) => {
      const next = new Set(current)
      if (next.has(headingId)) {
        next.delete(headingId)
      } else {
        next.add(headingId)
      }
      return next
    })
  }, [])

  const expandHeadingPath = React.useCallback((headingId: string): boolean => {
    const path = findHeadingPath(documentTree.sections, headingId)
    if (!path) return false
    if (path.length === 0) return true
    setCollapsedHeadingIds((current) => {
      const next = new Set(current)
      path.forEach((id) => next.delete(id))
      return next
    })
    return true
  }, [documentTree.sections])

  React.useImperativeHandle(ref, () => ({
    revealHeading: (headingId: string) => expandHeadingPath(headingId),
  }), [expandHeadingPath])

  return (
    <div style={rootStyle}>
      {documentTree.introBlocks.length > 0 && (
        <div style={sectionBodyStyle}>
          {documentTree.introBlocks.map((block) => renderBlock(block, 0, wrapLines))}
        </div>
      )}
      {documentTree.sections.map((section) => renderSection(section, 0, collapsedHeadingIds, toggleSectionCollapsed, wrapLines))}
    </div>
  )
})

function renderSection(
  section: MarkdownSection,
  depth: number,
  collapsedHeadingIds: Set<string>,
  onToggle: (headingId: string) => void,
  wrapLines: boolean,
): React.ReactNode {
  const isCollapsed = collapsedHeadingIds.has(section.id)
  const childDepth = depth + 1
  return (
    <section
      key={section.key}
      style={{
        ...sectionWrapStyle,
        marginLeft: `${depth * 18}px`,
      }}
    >
      {renderSectionHeading(section, isCollapsed, onToggle)}
      {!isCollapsed && (
        <div style={sectionBodyStyle}>
          {section.blocks.map((block) => renderBlock(block, childDepth, wrapLines))}
          {section.children.map((child) => renderSection(child, childDepth, collapsedHeadingIds, onToggle, wrapLines))}
        </div>
      )}
    </section>
  )
}

function renderSectionHeading(
  section: MarkdownSection,
  isCollapsed: boolean,
  onToggle: (headingId: string) => void,
): React.ReactNode {
  const headingContent = (
    <>
      <span style={headingToggleIconStyle}>{isCollapsed ? '▸' : '▾'}</span>
      <span>{renderMarkdownInline(section.text, inlineRenderStyles)}</span>
    </>
  )

  const buttonStyle = headingButtonStyles[section.level] ?? h3ButtonStyle

  return (
    <button
      type="button"
      id={section.id}
      data-markdown-heading-id={section.id}
      onClick={() => onToggle(section.id)}
      style={buttonStyle}
    >
      {headingContent}
    </button>
  )
}

function renderBlock(block: MarkdownBlock, depth: number, wrapLines: boolean): React.ReactNode {
  const blockOffset = `${depth * 18}px`

  if (block.type === 'paragraph') {
    return (
      <p key={block.key} style={{ ...buildParagraphStyle(wrapLines), marginLeft: blockOffset }}>
        {renderMarkdownInline(block.text, inlineRenderStyles)}
      </p>
    )
  }
  if (block.type === 'blockquote') {
    return (
      <blockquote key={block.key} style={{ ...buildBlockquoteStyle(wrapLines), marginLeft: blockOffset }}>
        {renderMarkdownInline(block.text, inlineRenderStyles)}
      </blockquote>
    )
  }
  if (block.type === 'hr') {
    return <hr key={block.key} style={{ ...hrStyle, marginLeft: blockOffset }} />
  }
  if (block.type === 'spacer') {
    return <div key={block.key} style={{ height: 8 }} />
  }
  if (block.type === 'code') {
    return (
      <pre key={block.key} style={{ ...codeBlockStyle, marginLeft: blockOffset }}>
        <code>{block.content}</code>
      </pre>
    )
  }
  if (block.type === 'list') {
    return renderListItems(block.items, block.ordered, 0, block.key, wrapLines)
  }
  return (
    <div key={block.key} style={{ ...tableWrapStyle, marginLeft: blockOffset }}>
      <table style={tableStyle}>
        <thead>
          <tr>
            {block.headers.map((cell, cellIndex) => (
              <th
                key={`${block.key}-th-${cellIndex}`}
                style={{
                  ...tableHeaderCellStyle,
                  textAlign: block.alignments[cellIndex] ?? 'left',
                }}
              >
                {renderMarkdownInline(cell, inlineRenderStyles)}
              </th>
            ))}
          </tr>
        </thead>
        {block.rows.length > 0 && (
          <tbody>
            {block.rows.map((row, rowIndex) => (
              <tr key={`${block.key}-tr-${rowIndex}`}>
                {block.headers.map((_, cellIndex) => (
                  <td
                    key={`${block.key}-td-${rowIndex}-${cellIndex}`}
                    style={{
                      ...tableBodyCellStyle,
                      textAlign: block.alignments[cellIndex] ?? 'left',
                    }}
                  >
                    {renderMarkdownInline(row[cellIndex] ?? '', inlineRenderStyles)}
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

function renderListItems(
  items: MarkdownListItem[],
  ordered: boolean,
  depth: number,
  keyPrefix: string,
  wrapLines: boolean,
): React.ReactNode {
  const marker = ordered ? 'decimal' : depth === 0 ? 'disc' : depth === 1 ? 'circle' : 'square'
  const ListTag = ordered ? 'ol' : 'ul'
  return (
    <ListTag
      key={keyPrefix}
      style={{
        ...ulStyle,
        marginLeft: `${depth * 18 + 20}px`,
        listStyleType: marker,
      }}
    >
      {items.map((item, index) => (
        <li key={`${keyPrefix}-${index}`} style={buildListItemStyle(wrapLines)}>
          {renderMarkdownInline(item.text, inlineRenderStyles)}
          {item.children.length > 0 && (
            renderListItems(item.children, item.ordered, depth + 1, `${keyPrefix}-${index}-child`, wrapLines)
          )}
        </li>
      ))}
    </ListTag>
  )
}

function parseMarkdownDocument(content: string, headingIds: Map<number, string>): MarkdownDocument {
  // 归一化 CRLF/CR 行尾：split('\n') 会残留 \r，导致 matchMarkdownHeading 的 $ 锚点对 CRLF 文件全部失效，
  // heading 全部回退到 paragraph 分支并显示 # 原文。与 MarkdownDisplay.normalizeMarkdownContent 行为对齐。
  const lines = content.replace(/\r\n?/g, '\n').split('\n')
  const introBlocks: MarkdownBlock[] = []
  const sections: MarkdownSection[] = []
  const sectionStack: MarkdownSection[] = []
  let codeBlockLines: string[] = []
  let codeBlockStartLine = -1
  let inCodeBlock = false
  let blockIndex = 0

  const appendBlock = (block: MarkdownBlock) => {
    const currentSection = sectionStack[sectionStack.length - 1]
    if (currentSection) {
      currentSection.blocks.push(block)
      return
    }
    introBlocks.push(block)
  }

  const flushCodeBlock = () => {
    appendBlock({
      type: 'code',
      key: `code-${codeBlockStartLine}-${blockIndex++}`,
      content: codeBlockLines.join('\n'),
    })
    codeBlockLines = []
    codeBlockStartLine = -1
  }

  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index]

    if (line.startsWith('```')) {
      if (inCodeBlock) {
        flushCodeBlock()
        inCodeBlock = false
      } else {
        inCodeBlock = true
        codeBlockStartLine = index
        codeBlockLines = []
      }
      continue
    }

    if (inCodeBlock) {
      codeBlockLines.push(line)
      continue
    }

    const listResult = parseListItems(lines, index)
    if (listResult) {
      appendBlock({
        type: 'list',
        key: `list-${index}-${blockIndex++}`,
        ordered: listResult.ordered,
        items: listResult.items,
      })
      index = listResult.nextIndex - 1
      continue
    }

    if (isMarkdownTableHeaderLine(line) && index + 1 < lines.length && isMarkdownTableSeparatorLine(lines[index + 1])) {
      const headerCells = splitMarkdownTableRow(line)
      const alignments = parseMarkdownTableAlignments(lines[index + 1])
      const bodyRows: string[][] = []
      let nextIndex = index + 2

      while (nextIndex < lines.length) {
        const nextLine = lines[nextIndex]
        if (!isMarkdownTableDataLine(nextLine)) {
          break
        }
        bodyRows.push(splitMarkdownTableRow(nextLine))
        nextIndex += 1
      }

      appendBlock({
        type: 'table',
        key: `table-${index}-${blockIndex++}`,
        headers: headerCells,
        alignments,
        rows: bodyRows,
      })
      index = nextIndex - 1
      continue
    }

    const heading = matchMarkdownHeading(line)
    if (heading) {
      const section: MarkdownSection = {
        id: headingIds.get(index) ?? buildMarkdownHeadingId(heading.text, index),
        level: heading.level,
        text: heading.text,
        key: `section-${index}`,
        blocks: [],
        children: [],
      }

      while (sectionStack.length > 0 && sectionStack[sectionStack.length - 1].level >= section.level) {
        sectionStack.pop()
      }

      const parent = sectionStack[sectionStack.length - 1]
      if (parent) {
        parent.children.push(section)
      } else {
        sections.push(section)
      }
      sectionStack.push(section)
      continue
    }

    if (line.startsWith('> ')) {
      appendBlock({
        type: 'blockquote',
        key: `blockquote-${index}-${blockIndex++}`,
        text: line.slice(2),
      })
      continue
    }

    if (line.startsWith('---')) {
      appendBlock({
        type: 'hr',
        key: `hr-${index}-${blockIndex++}`,
      })
      continue
    }

    if (!line.trim()) {
      appendBlock({
        type: 'spacer',
        key: `spacer-${index}-${blockIndex++}`,
      })
      continue
    }

    appendBlock({
      type: 'paragraph',
      key: `p-${index}-${blockIndex++}`,
      text: line,
    })
  }

  if (codeBlockLines.length > 0) {
    flushCodeBlock()
  }

  return {
    introBlocks,
    sections,
  }
}

function parseListItems(
  lines: string[],
  startIndex: number,
): { items: MarkdownListItem[]; ordered: boolean; nextIndex: number } | null {
  const listItemRegex = /^(\s*)([-*+]|\d+[.)])\s+(.+?)\s*$/
  const firstMatch = listItemRegex.exec(lines[startIndex])
  if (!firstMatch) return null

  const baseOrdered = /^\d/.test(firstMatch[2])
  const items: MarkdownListItem[] = []
  const stack: { indent: number; item: MarkdownListItem }[] = []
  let index = startIndex

  while (index < lines.length) {
    const line = lines[index]
    if (!line.trim()) break

    const match = listItemRegex.exec(line)
    if (!match) break

    const indent = match[1].replace(/\t/g, '  ').length
    const ordered = /^\d/.test(match[2])
    const item: MarkdownListItem = { text: match[3], ordered: false, children: [] }

    while (stack.length > 0 && stack[stack.length - 1].indent >= indent) {
      stack.pop()
    }

    if (stack.length === 0) {
      items.push(item)
    } else {
      const parent = stack[stack.length - 1].item
      if (parent.children.length === 0) {
        parent.ordered = ordered
      }
      parent.children.push(item)
    }
    stack.push({ indent, item })
    index += 1
  }

  return { items, ordered: baseOrdered, nextIndex: index }
}

function collectSectionIds(sections: MarkdownSection[], result: Set<string>) {
  sections.forEach((section) => {
    result.add(section.id)
    collectSectionIds(section.children, result)
  })
}

function findHeadingPath(sections: MarkdownSection[], headingId: string, trail: string[] = []): string[] | null {
  for (const section of sections) {
    if (section.id === headingId) {
      return trail
    }
    const nextPath = findHeadingPath(section.children, headingId, [...trail, section.id])
    if (nextPath) return nextPath
  }
  return null
}

const headingButtonBaseStyle: React.CSSProperties = {
  width: '100%',
  border: 'none',
  background: 'transparent',
  color: 'var(--text-primary)',
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: 0,
  textAlign: 'left',
  cursor: 'pointer',
}

const h1ButtonStyle: React.CSSProperties = {
  ...headingButtonBaseStyle,
  fontSize: 'var(--text-2xl)',
  fontWeight: 600,
  marginTop: 24,
  marginBottom: 12,
  paddingBottom: 8,
  borderBottom: '1px solid var(--border)',
}

const h2ButtonStyle: React.CSSProperties = {
  ...headingButtonBaseStyle,
  fontSize: 'var(--text-xl)',
  fontWeight: 600,
  marginTop: 20,
  marginBottom: 10,
}

const h3ButtonStyle: React.CSSProperties = {
  ...headingButtonBaseStyle,
  fontSize: 'var(--text-md)',
  fontWeight: 600,
  marginTop: 16,
  marginBottom: 8,
}

const h4ButtonStyle: React.CSSProperties = {
  ...headingButtonBaseStyle,
  fontSize: 'var(--text-sm)',
  fontWeight: 600,
  marginTop: 14,
  marginBottom: 6,
  color: 'var(--text-primary)',
}

const h5ButtonStyle: React.CSSProperties = {
  ...headingButtonBaseStyle,
  fontSize: 'var(--text-sm)',
  fontWeight: 600,
  marginTop: 12,
  marginBottom: 4,
  color: 'var(--text-secondary)',
}

const h6ButtonStyle: React.CSSProperties = {
  ...headingButtonBaseStyle,
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  marginTop: 10,
  marginBottom: 4,
  color: 'var(--text-muted)',
  letterSpacing: '0.02em',
}

const headingButtonStyles: Record<MarkdownHeadingLevel, React.CSSProperties> = {
  1: h1ButtonStyle,
  2: h2ButtonStyle,
  3: h3ButtonStyle,
  4: h4ButtonStyle,
  5: h5ButtonStyle,
  6: h6ButtonStyle,
}

const headingToggleIconStyle: React.CSSProperties = {
  flexShrink: 0,
  width: 12,
  color: 'var(--text-secondary)',
  fontSize: 'var(--text-xs)',
  lineHeight: 1,
}

const sectionWrapStyle: React.CSSProperties = {
  minWidth: 0,
}

const sectionBodyStyle: React.CSSProperties = {
  minWidth: 0,
}

function buildParagraphStyle(wrapLines: boolean): React.CSSProperties {
  return {
    ...pStyleBase,
    whiteSpace: wrapLines ? 'pre-wrap' : 'pre',
    wordBreak: wrapLines ? 'break-word' : 'normal',
    overflowWrap: wrapLines ? 'anywhere' : 'normal',
  }
}

const pStyleBase: React.CSSProperties = {
  fontSize: 'var(--text-base)',
  lineHeight: 1.8,
  color: 'var(--text-primary)',
  marginBottom: 4,
}

const strongStyle: React.CSSProperties = {
  fontWeight: 700,
  color: 'var(--text-primary)',
}

const inlineCodeStyle: React.CSSProperties = {
  background: 'var(--bg-tertiary)',
  padding: '1px 5px',
  borderRadius: 3,
  fontSize: 'var(--text-xs)',
  fontFamily: 'var(--font-mono)',
}

const inlineRenderStyles = {
  strong: strongStyle,
  inlineCode: inlineCodeStyle,
}

const hrStyle: React.CSSProperties = {
  border: 'none',
  borderTop: '1px solid var(--border)',
  margin: '16px 0',
}

function buildBlockquoteStyle(wrapLines: boolean): React.CSSProperties {
  return {
    ...blockquoteStyleBase,
    whiteSpace: wrapLines ? 'pre-wrap' : 'pre',
    wordBreak: wrapLines ? 'break-word' : 'normal',
    overflowWrap: wrapLines ? 'anywhere' : 'normal',
  }
}

const blockquoteStyleBase: React.CSSProperties = {
  borderLeft: '3px solid color-mix(in srgb, var(--bg-tertiary) 75%, #000)',
  padding: '6px 12px',
  margin: '8px 0',
  color: 'var(--text-secondary)',
  fontSize: 'var(--text-sm)',
  background: 'var(--bg-tertiary)',
  borderRadius: '0 var(--radius-md) var(--radius-md) 0',
}

const ulStyle: React.CSSProperties = {
  marginTop: 8,
  marginBottom: 10,
  padding: 0,
}

const tableWrapStyle: React.CSSProperties = {
  width: 'max-content',
  minWidth: '100%',
  marginTop: 12,
  marginBottom: 16,
  border: '1px solid var(--border-light)',
  borderRadius: 'var(--radius-md)',
  background: 'var(--bg-primary)',
}

const tableStyle: React.CSSProperties = {
  width: '100%',
  borderCollapse: 'collapse',
  minWidth: 360,
}

const tableHeaderCellStyle: React.CSSProperties = {
  padding: '10px 12px',
  textAlign: 'left',
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  color: 'var(--text-primary)',
  background: 'var(--bg-secondary)',
  borderBottom: '1px solid var(--border)',
  whiteSpace: 'nowrap',
}

const tableBodyCellStyle: React.CSSProperties = {
  padding: '10px 12px',
  textAlign: 'left',
  verticalAlign: 'top',
  fontSize: 'var(--text-sm)',
  lineHeight: 1.7,
  color: 'var(--text-primary)',
  borderTop: '1px solid var(--border-light)',
  wordBreak: 'break-word',
  overflowWrap: 'anywhere',
}

function buildListItemStyle(wrapLines: boolean): React.CSSProperties {
  return {
    ...liStyleBase,
    whiteSpace: wrapLines ? 'pre-wrap' : 'pre',
    wordBreak: wrapLines ? 'break-word' : 'normal',
    overflowWrap: wrapLines ? 'anywhere' : 'normal',
  }
}

const liStyleBase: React.CSSProperties = {
  fontSize: 'var(--text-base)',
  lineHeight: 1.8,
  color: 'var(--text-primary)',
}

const codeBlockStyle: React.CSSProperties = {
  background: 'var(--code-bg)',
  color: 'var(--code-text)',
  padding: '14px 18px',
  borderRadius: 'var(--radius-md)',
  fontSize: 'var(--text-sm)',
  lineHeight: 1.6,
  width: 'max-content',
  minWidth: '100%',
  marginTop: 10,
  marginBottom: 10,
  fontFamily: 'var(--font-mono)',
  whiteSpace: 'pre',
}

const rootStyle: React.CSSProperties = {
  width: '100%',
  minWidth: 0,
}
