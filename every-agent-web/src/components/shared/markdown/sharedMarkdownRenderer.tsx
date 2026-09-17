import React from 'react'
import type { Components, ExtraProps } from 'react-markdown'
import type { Element, ElementContent } from 'hast'
import CodeBlock from './CodeBlock'
import './markdown.css'

export type MarkdownVariant = 'preview' | 'display'

export type MarkdownComponentOptions = {
  variant: MarkdownVariant
  /** 段落/列表/引用的换行模式：true=自动换行(pre-wrap)，false=不换行(pre)。 */
  wrapLines?: boolean
  /** 大纲跳转：根据 hast 节点的起始行号(0-based)解析标题 id。仅 preview 场景需要。 */
  resolveHeadingId?: (line: number) => string | undefined
  /** 图片渲染器；缺省时回退为原生 <img>。仅 preview 场景需要工作区上下文图片。 */
  renderImage?: (src: string, alt: string) => React.ReactNode
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

/** 递归收集 hast 节点的纯文本内容。 */
function collectHastText(node: ElementContent | undefined): string {
  if (!node) return ''
  if (node.type === 'text') return node.value
  if (node.type === 'element') return node.children.map(collectHastText).join('')
  return ''
}

/** 从 hast table 节点提取表头与数据行纯文本，供列宽计算使用。 */
function extractTableMatrix(node: Element | undefined): { headers: string[]; rows: string[][] } {
  const headers: string[] = []
  const rows: string[][] = []
  if (!node) return { headers, rows }

  for (const section of node.children) {
    if (section.type !== 'element') continue
    if (section.tagName === 'thead') {
      for (const tr of section.children) {
        if (tr.type !== 'element' || tr.tagName !== 'tr') continue
        for (const cell of tr.children) {
          if (cell.type === 'element' && (cell.tagName === 'th' || cell.tagName === 'td')) {
            headers.push(collectHastText(cell))
          }
        }
      }
    } else if (section.tagName === 'tbody') {
      for (const tr of section.children) {
        if (tr.type !== 'element' || tr.tagName !== 'tr') continue
        const row: string[] = []
        for (const cell of tr.children) {
          if (cell.type === 'element' && (cell.tagName === 'th' || cell.tagName === 'td')) {
            row.push(collectHastText(cell))
          }
        }
        if (row.length > 0) rows.push(row)
      }
    }
  }
  return { headers, rows }
}

/** 递归收集 React 节点树的纯文本（用于代码块文本提取）。 */
function extractReactText(node: React.ReactNode): string {
  if (node == null || node === false || node === true) return ''
  if (typeof node === 'string' || typeof node === 'number') return String(node)
  if (React.isValidElement(node)) {
    return extractReactText(node.props.children as React.ReactNode)
  }
  if (Array.isArray(node)) return node.map(extractReactText).join('')
  return ''
}

// ───────────────────────── 样式 ─────────────────────────

const baseParagraphStyle: React.CSSProperties = {
  margin: 0,
}

const baseBlockquoteStyle: React.CSSProperties = {
  padding: '8px 12px',
  borderLeft: '3px solid color-mix(in srgb, var(--bg-tertiary) 75%, #000)',
  borderRadius: '0 var(--radius-md) var(--radius-md) 0',
}

const baseTableWrapStyle: React.CSSProperties = {
  width: '100%',
  border: '1px solid var(--border-light)',
  borderRadius: 'var(--radius-md)',
}

const baseTableStyle: React.CSSProperties = {
  width: '100%',
  borderCollapse: 'collapse',
  tableLayout: 'fixed',
}

const baseThStyle: React.CSSProperties = {
  padding: '10px 12px',
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  verticalAlign: 'top',
  borderBottom: '1px solid var(--border)',
  wordBreak: 'break-word',
  overflowWrap: 'anywhere',
}

const baseTdStyle: React.CSSProperties = {
  padding: '10px 12px',
  fontSize: 'var(--text-sm)',
  lineHeight: 1.7,
  verticalAlign: 'top',
  borderTop: '1px solid var(--border-light)',
  wordBreak: 'break-word',
  overflowWrap: 'anywhere',
}

const baseHrStyle: React.CSSProperties = {
  width: '100%',
  border: 'none',
  borderTop: '1px solid var(--border-light)',
}

const baseLinkStyle: React.CSSProperties = {
  color: 'var(--accent-blue, var(--text-link, #3b82f6))',
  textDecoration: 'none',
  wordBreak: 'break-word',
}

const taskCheckedStyle: React.CSSProperties = {
  flexShrink: 0,
  fontSize: '1em',
  lineHeight: 1.3,
  marginTop: 1,
}

const taskUncheckedStyle: React.CSSProperties = {
  flexShrink: 0,
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  width: '1.2em',
  height: '1.2em',
  marginTop: 2,
  borderRadius: 3,
  border: '1.5px solid var(--border-strong, #888)',
  boxSizing: 'border-box',
}

const previewHeadingStyles: Record<number, React.CSSProperties> = {
  1: { margin: '24px 0 12px', fontSize: 'var(--text-2xl)', fontWeight: 600, lineHeight: 1.3, color: 'var(--text-primary)', borderBottom: '1px solid var(--border)', paddingBottom: 8 },
  2: { margin: '20px 0 10px', fontSize: 'var(--text-xl)', fontWeight: 600, lineHeight: 1.35, color: 'var(--text-primary)' },
  3: { margin: '16px 0 8px', fontSize: 'var(--text-md)', fontWeight: 600, lineHeight: 1.4, color: 'var(--text-primary)' },
  4: { margin: '14px 0 6px', fontSize: 'var(--text-sm)', fontWeight: 600, color: 'var(--text-primary)' },
  5: { margin: '12px 0 4px', fontSize: 'var(--text-sm)', fontWeight: 600, color: 'var(--text-secondary)' },
  6: { margin: '10px 0 4px', fontSize: 'var(--text-xs)', fontWeight: 700, color: 'var(--text-muted)', letterSpacing: '0.02em' },
}

const displayHeadingStyles: Record<number, React.CSSProperties> = {
  1: { margin: '0 0 8px', fontSize: 'var(--text-xl)', fontWeight: 700, lineHeight: 1.35, color: 'var(--text-primary)' },
  2: { margin: '4px 0 8px', fontSize: 'var(--text-lg)', fontWeight: 700, lineHeight: 1.4, color: 'var(--text-primary)' },
  3: { margin: '4px 0 6px', fontSize: 'var(--text-base)', fontWeight: 700, lineHeight: 1.45, color: 'var(--text-primary)' },
  4: { margin: '4px 0 4px', fontSize: 'var(--text-sm)', fontWeight: 700, color: 'var(--text-primary)' },
  5: { margin: '4px 0 2px', fontSize: 'var(--text-xs)', fontWeight: 700, color: 'var(--text-secondary)' },
  6: { margin: '4px 0 2px', fontSize: 'var(--text-xs)', fontWeight: 700, color: 'var(--text-muted)' },
}

const variantStyles: Record<MarkdownVariant, {
  paragraph: React.CSSProperties
  blockquote: React.CSSProperties
  list: React.CSSProperties
  listItem: React.CSSProperties
  tableWrap: React.CSSProperties
  table: React.CSSProperties
  th: React.CSSProperties
  td: React.CSSProperties
  hr: React.CSSProperties
  code: React.CSSProperties
  strong: React.CSSProperties
  heading: Record<number, React.CSSProperties>
}> = {
  preview: {
    paragraph: { ...baseParagraphStyle, fontSize: 'var(--text-base)', lineHeight: 1.8, color: 'var(--text-primary)', marginBottom: 4 },
    blockquote: { ...baseBlockquoteStyle, margin: '8px 0', color: 'var(--text-secondary)', fontSize: 'var(--text-sm)', background: 'var(--bg-tertiary)' },
    list: { margin: '6px 0 8px', padding: 0, paddingLeft: '1.4em', listStyleType: 'disc' },
    listItem: { fontSize: 'var(--text-base)', lineHeight: 1.6, color: 'var(--text-primary)', marginBottom: 2 },
    tableWrap: { ...baseTableWrapStyle, margin: '12px 0 16px', background: 'var(--bg-primary)' },
    table: baseTableStyle,
    th: { ...baseThStyle, color: 'var(--text-primary)', background: 'var(--bg-secondary)' },
    td: { ...baseTdStyle, color: 'var(--text-primary)' },
    hr: { ...baseHrStyle, margin: '16px 0', borderTopColor: 'var(--border)' },
    code: { background: 'var(--bg-tertiary)', padding: '1px 5px', borderRadius: 3, fontSize: 'var(--text-xs)', fontFamily: 'var(--font-mono)' },
    strong: { fontWeight: 700, color: 'var(--text-primary)' },
    heading: previewHeadingStyles,
  },
  display: {
    paragraph: { ...baseParagraphStyle, fontSize: 'var(--text-sm)', lineHeight: 1.8, color: 'var(--text-secondary)', margin: 0 },
    blockquote: { ...baseBlockquoteStyle, margin: '4px 0', color: 'var(--text-secondary)', fontSize: 'var(--text-sm)', lineHeight: 1.75 },
    list: { margin: '2px 0 2px 18px', padding: 0, paddingLeft: 2, listStyleType: 'disc' },
    listItem: { fontSize: 'var(--text-sm)', lineHeight: 1.75, color: 'var(--text-secondary)', marginBottom: 4 },
    tableWrap: { ...baseTableWrapStyle, margin: '6px 0', border: '1px solid color-mix(in srgb, var(--border-light) 85%, transparent)', background: 'color-mix(in srgb, var(--bg-tertiary) 48%, transparent)' },
    table: { ...baseTableStyle, background: 'color-mix(in srgb, var(--bg-tertiary) 48%, transparent)' },
    th: { ...baseThStyle, lineHeight: 1.6, color: 'var(--text-primary)', background: 'color-mix(in srgb, var(--bg-tertiary) 82%, transparent)', borderBottomColor: 'var(--border-light)' },
    td: { ...baseTdStyle, lineHeight: 1.75, color: 'var(--text-secondary)', borderTopColor: 'color-mix(in srgb, var(--border-light) 78%, transparent)' },
    hr: { ...baseHrStyle, margin: '8px 0' },
    code: { padding: '1px 5px', borderRadius: 5, color: 'var(--text-primary)', fontSize: 'var(--text-xs)', fontFamily: 'var(--font-mono)', border: '1px solid color-mix(in srgb, var(--border-light) 80%, transparent)' },
    strong: { fontWeight: 700, color: 'var(--text-primary)' },
    heading: displayHeadingStyles,
  },
}

// ───────────────────────── 组件工厂 ─────────────────────────

type IntrinsicProps<Tag extends keyof JSX.IntrinsicElements> = JSX.IntrinsicElements[Tag] & ExtraProps

/** 从 react-markdown 传入的 props 中剥离 node，避免泄漏到 DOM 属性。 */
function stripNode<T extends object>(props: T): Omit<T, 'node'> {
  const { node, ...rest } = props as Record<string, unknown>
  void node
  return rest as Omit<T, 'node'>
}

function buildWrapStyle(wrapLines: boolean | undefined): React.CSSProperties {
  return wrapLines === false
    ? { whiteSpace: 'pre', wordBreak: 'normal', overflowWrap: 'normal' }
    : { whiteSpace: 'normal', wordBreak: 'break-word', overflowWrap: 'anywhere' }
}

export function buildMarkdownComponents(options: MarkdownComponentOptions): Components {
  const { variant, wrapLines = true, resolveHeadingId, renderImage } = options
  const s = variantStyles[variant]
  const wrap = buildWrapStyle(wrapLines)

  const makeHeading = (level: number) => {
    const Heading = (props: IntrinsicProps<'h1'>) => {
      const { children } = props
      const { node } = props
      const rest = stripNode(props)
      const line = node?.position?.start?.line
      const id = line != null ? resolveHeadingId?.(line - 1) : undefined
      return React.createElement(
        `h${level}`,
        { ...rest, id, 'data-markdown-heading-id': id, style: s.heading[level] },
        children,
      )
    }
    return Heading
  }

  const PreBlock = ({ children }: IntrinsicProps<'pre'>) => {
    const childArray = React.Children.toArray(children)
    const codeEl = childArray.find(React.isValidElement) as
      | React.ReactElement<{ className?: string; children?: React.ReactNode }>
      | undefined
    const className = codeEl?.props?.className
    const lang = /language-([\w-]+)/.exec(className ?? '')?.[1]
    const text = extractReactText(codeEl ? codeEl.props.children : children).replace(/\n$/, '')
    return <CodeBlock code={text} language={lang} variant={variant} style={{ margin: variant === 'preview' ? '10px 0' : '4px 0' }} />
  }

  const TableBlock = ({ node, children }: IntrinsicProps<'table'>) => {
    const { headers, rows } = extractTableMatrix(node)
    const widths = computeTableColumnWidths(headers, rows)
    return (
      <div style={s.tableWrap}>
        <table style={s.table}>
          {widths.length > 0 && (
            <colgroup>
              {widths.map((width, index) => (
                <col key={`col-${index}`} style={{ width }} />
              ))}
            </colgroup>
          )}
          {children}
        </table>
      </div>
    )
  }

  const Th = (props: IntrinsicProps<'th'>) => {
    const { children, align, node } = props
    const rest = stripNode(props)
    delete rest.align
    return (
      <th {...rest} style={{ ...s.th, textAlign: (align ?? node?.properties?.align ?? 'left') as React.CSSProperties['textAlign'] }}>
        {children}
      </th>
    )
  }

  const Td = (props: IntrinsicProps<'td'>) => {
    const { children, align, node } = props
    const rest = stripNode(props)
    delete rest.align
    return (
      <td {...rest} style={{ ...s.td, textAlign: (align ?? node?.properties?.align) as React.CSSProperties['textAlign'] }}>
        {children}
      </td>
    )
  }

  const components: Components = {
    h1: makeHeading(1),
    h2: makeHeading(2),
    h3: makeHeading(3),
    h4: makeHeading(4),
    h5: makeHeading(5),
    h6: makeHeading(6),
    p: (props: IntrinsicProps<'p'>) => {
      const { children } = props
      const rest = stripNode(props)
      return <p {...rest} style={{ ...s.paragraph, ...wrap }}>{children}</p>
    },
    blockquote: (props: IntrinsicProps<'blockquote'>) => {
      const { children } = props
      const rest = stripNode(props)
      return <blockquote {...rest} style={{ ...s.blockquote, ...wrap }}>{children}</blockquote>
    },
    ul: (props: IntrinsicProps<'ul'>) => {
      const { children } = props
      const rest = stripNode(props)
      return <ul {...rest} style={s.list}>{children}</ul>
    },
    ol: (props: IntrinsicProps<'ol'>) => {
      const { children } = props
      const rest = stripNode(props)
      return <ol {...rest} style={{ ...s.list, listStyleType: 'decimal' }}>{children}</ol>
    },
    li: (props: IntrinsicProps<'li'>) => {
      const { children, className } = props
      const rest = stripNode(props)
      const isTaskItem = typeof className === 'string' && className.includes('task-list-item')
      if (!isTaskItem) {
        return (
          <li {...rest} style={{ ...s.listItem, ...wrap }}>
            {children}
          </li>
        )
      }
      // GFM 任务列表项：react-markdown 渲染成 <input type="checkbox" disabled checked>，
      // 替换为彩色 ✓/◻ 图标，过滤掉原生 checkbox。
      const childArray = React.Children.toArray(children)
      const checkboxEl = childArray.find(
        (child) => React.isValidElement(child) && (child as React.ReactElement<{ type?: string }>).props?.type === 'checkbox',
      ) as React.ReactElement<{ checked?: boolean }> | undefined
      const isChecked = checkboxEl?.props?.checked === true
      const contentNodes = childArray.filter((child) => child !== checkboxEl)
      return (
        <li
          {...rest}
          style={{
            ...s.listItem,
            ...wrap,
            listStyleType: 'none',
            paddingLeft: 0,
            display: 'flex',
            alignItems: 'flex-start',
            gap: 6,
          }}
        >
          <span style={isChecked ? taskCheckedStyle : taskUncheckedStyle}>
            {isChecked ? '✅' : ''}
          </span>
          <span style={{ flex: 1, minWidth: 0 }}>
            {contentNodes}
          </span>
        </li>
      )
    },
    pre: PreBlock,
    code: (props: IntrinsicProps<'code'>) => {
      const { children } = props
      const rest = stripNode(props)
      return <code {...rest} style={s.code}>{children}</code>
    },
    table: TableBlock,
    th: Th,
    td: Td,
    hr: (props: IntrinsicProps<'hr'>) => {
      const rest = stripNode(props)
      return <hr {...rest} style={s.hr} />
    },
    a: (props: IntrinsicProps<'a'>) => {
      const { children, href } = props
      const rest = stripNode(props)
      return <a {...rest} href={href} target="_blank" rel="noopener noreferrer nofollow" style={baseLinkStyle}>{children}</a>
    },
    strong: (props: IntrinsicProps<'strong'>) => {
      const { children } = props
      const rest = stripNode(props)
      return <strong {...rest} style={s.strong}>{children}</strong>
    },
    img: (props: IntrinsicProps<'img'>) => {
      const { src, alt } = props
      const rest = stripNode(props)
      if (renderImage && typeof src === 'string') {
        return <>{renderImage(src, alt ?? '')}</>
      }
      return <img {...rest} src={src} alt={alt} loading="lazy" style={{ maxWidth: '100%', height: 'auto' }} />
    },
  }

  return components
}
