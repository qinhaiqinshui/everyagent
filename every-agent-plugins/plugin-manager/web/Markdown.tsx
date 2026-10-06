/**
 * 轻量 Markdown 渲染器（插件自带，不引宿主模块 / 第三方依赖）。
 *
 * 服务扩展详情页 README 区：覆盖 readme 常用语法子集——
 * 标题 / 段落 / 粗体 / 斜体 / 删除线 / 行内代码 / 链接 / 图片 /
 * 围栏代码块 / 引用 / 无序+有序列表（含缩进嵌套）/ 分隔线 / 表格。
 *
 * 图片经 `resolveImage` 异步解析（插件目录内相对路径 → plugin.asset data URL，
 * http(s)/data URL 原样返回），未提供时 http(s) 直出、相对路径不渲染图。
 * 文本节点全部经 React 渲染（自动转义），无 innerHTML 注入面。
 */
import React from 'react'

/** 图片 src 解析器：返回可直接用于 <img src> 的 URL（null = 放弃渲染）。 */
export type MarkdownImageResolver = (src: string, alt: string) => Promise<string | null>

interface MarkdownProps {
  source: string
  /** 可选：相对路径图片解析器（如插件目录内资源 → base64 data URL）。 */
  resolveImage?: MarkdownImageResolver
}

// ── 行内解析 ────────────────────────────────────────────────────────────────

/** 行内元素识别顺序：图片 → 链接 → 行内代码 → 粗体 → 斜体 → 删除线。 */
const INLINE_RE = /!\[([^\]]*)\]\(([^)\s]+)\)|\[([^\]]+)\]\(([^)\s]+)\)|`([^`]+)`|\*\*([^*]+)\*\*|__([^_]+)__|~~([^~]+)~~|\*([^*\n]+)\*|_([^_\n]+)_/g

/**
 * 渲染行内 markdown 片段为 React 节点数组。
 */
function renderInline(
  text: string,
  keyPrefix: string,
  resolveImage?: MarkdownImageResolver,
): React.ReactNode[] {
  const nodes: React.ReactNode[] = []
  let last = 0
  let m: RegExpExecArray | null
  INLINE_RE.lastIndex = 0
  let i = 0
  while ((m = INLINE_RE.exec(text)) !== null) {
    if (m.index > last) {
      nodes.push(text.slice(last, m.index))
    }
    const key = `${keyPrefix}-i${i++}`
    if (m[2] !== undefined) {
      // ![alt](src)
      nodes.push(<MarkdownImage key={key} alt={m[1] ?? ''} src={m[2]} resolveImage={resolveImage} />)
    } else if (m[4] !== undefined) {
      // [text](href)
      const href = m[4]
      nodes.push(
        <a key={key} href={href} target="_blank" rel="noreferrer noopener" style={{ color: 'var(--accent-blue)' }}>
          {m[3]}
        </a>,
      )
    } else if (m[5] !== undefined) {
      nodes.push(
        <code key={key} style={inlineCodeStyle}>{m[5]}</code>,
      )
    } else if (m[6] !== undefined || m[7] !== undefined) {
      nodes.push(<strong key={key}>{m[6] ?? m[7]}</strong>)
    } else if (m[8] !== undefined) {
      nodes.push(<del key={key} style={{ opacity: 0.6 }}>{m[8]}</del>)
    } else {
      nodes.push(<em key={key}>{m[9] ?? m[10]}</em>)
    }
    last = m.index + m[0].length
  }
  if (last < text.length) {
    nodes.push(text.slice(last))
  }
  return nodes
}

const inlineCodeStyle: React.CSSProperties = {
  fontFamily: 'var(--font-mono, ui-monospace, SFMono-Regular, Consolas, monospace)',
  fontSize: '0.92em',
  background: 'var(--bg-tertiary)',
  border: '1px solid var(--border-light)',
  borderRadius: 4,
  padding: '1px 4px',
}

// ── 图片（异步 src 解析） ───────────────────────────────────────────────────

function MarkdownImage({
  alt,
  src,
  resolveImage,
}: {
  alt: string
  src: string
  resolveImage?: MarkdownImageResolver
}) {
  const [resolved, setResolved] = React.useState<string | null>(() =>
    /^https?:\/\//i.test(src) || src.startsWith('data:') ? src : null,
  )
  const [failed, setFailed] = React.useState(false)

  React.useEffect(() => {
    if (/^https?:\/\//i.test(src) || src.startsWith('data:')) {
      setResolved(src)
      return
    }
    if (!resolveImage) {
      setResolved(null)
      setFailed(true)
      return
    }
    let cancelled = false
    resolveImage(src, alt).then((url) => {
      if (cancelled) return
      if (url) {
        setResolved(url)
      } else {
        setFailed(true)
      }
    })
    return () => {
      cancelled = true
    }
  }, [src, alt, resolveImage])

  if (!resolved) {
    if (failed) {
      return <span style={{ color: 'var(--text-faint)' }}>[{alt || src}]</span>
    }
    return <span style={{ color: 'var(--text-faint)', fontSize: 12 }}>（加载图片…）</span>
  }
  return (
    <img
      src={resolved}
      alt={alt}
      loading="lazy"
      style={{ maxWidth: '100%', borderRadius: 6, border: '1px solid var(--border-light)' }}
    />
  )
}

// ── 块级解析 ────────────────────────────────────────────────────────────────

/** 无序/有序列表行：缩进空格数 + 序号（有序时）。 */
const LIST_RE = /^(\s*)([-*+]|\d+[.)])\s+(.*)$/

interface ListItemModel {
  indent: number
  text: string
  ordered: boolean
}

/** 列表树节点：同层兄弟 + 子列表。 */
interface ListGroup {
  items: Array<{ text: string; ordered: boolean; children: ListGroup[] }>
}

/**
 * 先把平铺列表行折叠为层级树（缩进每 2 空格一档），再递归渲染嵌套 <ul>/<ol>。
 */
function renderListItems(
  items: ListItemModel[],
  keyPrefix: string,
  resolveImage?: MarkdownImageResolver,
): React.ReactNode {
  const buildGroup = (start: number, baseIndent: number): { group: ListGroup; next: number } => {
    const group: ListGroup = { items: [] }
    let i = start
    while (i < items.length && items[i].indent >= baseIndent) {
      if (items[i].indent > baseIndent) {
        const { group: child, next } = buildGroup(i, items[i].indent)
        const last = group.items[group.items.length - 1]
        if (last) {
          last.children.push(child)
        } else {
          // 首项就深缩进：按独立子列表挂在空首项下，保证不丢内容
          group.items.push({ text: '', ordered: items[i].ordered, children: [child] })
        }
        i = next
        continue
      }
      group.items.push({ text: items[i].text, ordered: items[i].ordered, children: [] })
      i++
    }
    return { group, next: i }
  }

  const renderGroup = (group: ListGroup, key: string, parentOrdered: boolean): React.ReactNode => {
    // 层级标签：组内首个条目的 ordered 决定 ul/ol
    const ordered = group.items[0]?.ordered ?? parentOrdered
    const tag = ordered ? 'ol' : 'ul'
    return React.createElement(
      tag,
      {
        key,
        style: {
          margin: '0 0 10px',
          paddingLeft: 20,
          listStyle: ordered ? 'decimal' : 'disc',
        },
      },
      ...group.items.map((item, idx) =>
        React.createElement(
          'li',
          { key: `${key}-it${idx}`, style: { margin: '2px 0' } },
          renderInline(item.text, `${key}-it${idx}`, resolveImage),
          ...item.children.map((child, ci) => renderGroup(child, `${key}-it${idx}-c${ci}`, ordered)),
        ),
      ),
    )
  }

  const { group } = buildGroup(0, items[0].indent)
  return renderGroup(group, keyPrefix, false)
}

/**
 * Markdown 主组件：块级逐行解析 → 结构化渲染。
 */
export default function Markdown({ source, resolveImage }: MarkdownProps) {
  const lines = React.useMemo(() => (source ?? '').replace(/\r\n/g, '\n').split('\n'), [source])

  const blocks: React.ReactNode[] = []
  let i = 0
  let key = 0
  const nextKey = () => `md-b${key++}`

  while (i < lines.length) {
    const line = lines[i]

    // 空行
    if (!line.trim()) {
      i++
      continue
    }

    // 围栏代码块
    const fence = line.match(/^```(\w*)\s*$/)
    if (fence) {
      const lang = fence[1] || ''
      const codeLines: string[] = []
      i++
      while (i < lines.length && !/^```\s*$/.test(lines[i])) {
        codeLines.push(lines[i])
        i++
      }
      i++ // 跳过收口 ```
      blocks.push(
        <div key={nextKey()} style={codeBlockWrapperStyle}>
          {lang && <div style={codeLangStyle}>{lang}</div>}
          <pre style={preStyle}>
            <code>{codeLines.join('\n')}</code>
          </pre>
        </div>,
      )
      continue
    }

    // 标题
    const heading = line.match(/^(#{1,6})\s+(.*)$/)
    if (heading) {
      const level = heading[1].length
      blocks.push(
        React.createElement(
          `h${level}`,
          { key: nextKey(), style: headingStyle(level) },
          renderInline(heading[2], `h${key}`, resolveImage),
        ),
      )
      i++
      continue
    }

    // 分隔线
    if (/^(\s*)(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
      blocks.push(<hr key={nextKey()} style={hrStyle} />)
      i++
      continue
    }

    // 引用块（连续 > 行合并）
    if (/^\s*>/.test(line)) {
      const quoteLines: string[] = []
      while (i < lines.length && /^\s*>/.test(lines[i])) {
        quoteLines.push(lines[i].replace(/^\s*>\s?/, ''))
        i++
      }
      blocks.push(
        <blockquote key={nextKey()} style={blockquoteStyle}>
          {renderInline(quoteLines.join(' '), `q${key}`, resolveImage)}
        </blockquote>,
      )
      continue
    }

    // 列表
    if (LIST_RE.test(line)) {
      const items: ListItemModel[] = []
      while (i < lines.length) {
        const m = lines[i].match(LIST_RE)
        if (!m) {
          // 列表项的续行（缩进文本）并入上一项
          if (items.length > 0 && /^\s+\S/.test(lines[i])) {
            items[items.length - 1].text += ' ' + lines[i].trim()
            i++
            continue
          }
          break
        }
        items.push({
          indent: Math.floor(m[1].replace(/\t/g, '  ').length / 2),
          text: m[4],
          ordered: /\d/.test(m[2]),
        })
        i++
      }
      blocks.push(<React.Fragment key={nextKey()}>{renderListItems(items, `ls${key}`, resolveImage)}</React.Fragment>)
      continue
    }

    // 表格：当前行以 | 开头且下一行是分隔行
    if (line.trim().startsWith('|') && i + 1 < lines.length && /^\s*\|?[\s:|-]+\|?\s*$/.test(lines[i + 1]) && lines[i + 1].includes('-')) {
      const parseRow = (row: string): string[] =>
        row.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => c.trim())
      const header = parseRow(line)
      i += 2
      const rows: string[][] = []
      while (i < lines.length && lines[i].trim().startsWith('|')) {
        rows.push(parseRow(lines[i]))
        i++
      }
      blocks.push(
        <div key={nextKey()} style={{ overflowX: 'auto' }}>
          <table style={tableStyle}>
            <thead>
              <tr>
                {header.map((cell, ci) => (
                  <th key={ci} style={thStyle}>{renderInline(cell, `th${ci}`, resolveImage)}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((row, ri) => (
                <tr key={ri}>
                  {header.map((_, ci) => (
                    <td key={ci} style={tdStyle}>
                      {renderInline(row[ci] ?? '', `td${ri}-${ci}`, resolveImage)}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>,
      )
      continue
    }

    // 段落：连续非特殊行合并
    const paragraphLines: string[] = []
    while (
      i < lines.length
      && lines[i].trim()
      && !/^#{1,6}\s/.test(lines[i])
      && !/^```/.test(lines[i])
      && !/^\s*>/.test(lines[i])
      && !LIST_RE.test(lines[i])
      && !/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(lines[i])
    ) {
      paragraphLines.push(lines[i].trim())
      i++
    }
    blocks.push(
      <p key={nextKey()} style={paragraphStyle}>
        {renderInline(paragraphLines.join(' '), `p${key}`, resolveImage)}
      </p>,
    )
  }

  return <div style={rootStyle}>{blocks}</div>
}

// ── 样式（对齐宿主主题变量，深浅色自适应） ──────────────────────────────────

const rootStyle: React.CSSProperties = {
  fontSize: 13,
  lineHeight: 1.7,
  color: 'var(--text-primary)',
  wordBreak: 'break-word',
}

const paragraphStyle: React.CSSProperties = {
  margin: '0 0 10px',
}

function headingStyle(level: number): React.CSSProperties {
  const sizes: Record<number, number> = { 1: 20, 2: 17, 3: 15, 4: 14, 5: 13, 6: 13 }
  return {
    fontSize: sizes[level] ?? 14,
    fontWeight: 600,
    margin: `${level <= 2 ? 18 : 14}px 0 8px`,
    color: 'var(--text-primary)',
    lineHeight: 1.4,
  }
}

const hrStyle: React.CSSProperties = {
  border: 'none',
  borderTop: '1px solid var(--border)',
  margin: '14px 0',
}

const blockquoteStyle: React.CSSProperties = {
  margin: '0 0 10px',
  padding: '6px 12px',
  borderLeft: '3px solid var(--accent-blue)',
  background: 'var(--bg-soft)',
  borderRadius: 4,
  color: 'var(--text-secondary)',
}

const codeBlockWrapperStyle: React.CSSProperties = {
  position: 'relative',
  margin: '0 0 10px',
  border: '1px solid var(--border)',
  borderRadius: 6,
  background: 'var(--bg-sunken)',
  overflow: 'hidden',
}

const codeLangStyle: React.CSSProperties = {
  position: 'absolute',
  top: 4,
  right: 8,
  fontSize: 11,
  color: 'var(--text-faint)',
  userSelect: 'none',
}

const preStyle: React.CSSProperties = {
  margin: 0,
  padding: '10px 12px',
  overflowX: 'auto',
  fontFamily: 'var(--font-mono, ui-monospace, SFMono-Regular, Consolas, monospace)',
  fontSize: 12,
  lineHeight: 1.6,
}

const tableStyle: React.CSSProperties = {
  borderCollapse: 'collapse',
  width: '100%',
  margin: '0 0 10px',
  fontSize: 12.5,
  background: 'var(--bg-table)',
}

const thStyle: React.CSSProperties = {
  textAlign: 'left',
  padding: '6px 10px',
  borderBottom: '1px solid var(--border-strong)',
  fontWeight: 600,
  whiteSpace: 'nowrap',
}

const tdStyle: React.CSSProperties = {
  textAlign: 'left',
  padding: '6px 10px',
  borderBottom: '1px solid var(--border-light)',
  verticalAlign: 'top',
}
