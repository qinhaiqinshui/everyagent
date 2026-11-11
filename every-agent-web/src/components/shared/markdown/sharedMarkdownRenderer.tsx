import React from 'react'
import type { Components, ExtraProps } from 'react-markdown'
import type { Element, ElementContent } from 'hast'
import CodeBlock from './CodeBlock'
import { escapeTableCellText, resolveCellRange, isTouchDoubleTap, type MarkdownTableCellEditRequest, type TouchTap } from './markdownTableEdit'
import { useTablePan } from './useTablePan'
import { usePinnedTableHeader } from './usePinnedTableHeader'
import { createPortal } from 'react-dom'
import './markdown.css'

/**
 * 表头单元格编辑上报通道:表头有单元格进入编辑时通知所属 TableBlock,
 * 吸顶浮动表头(克隆)与在流表头二者的编辑态不共享,编辑期间关闭吸顶,
 * 避免克隆表头内容与正在编辑的单元格不一致。仅表头(th)需要;正文(td)
 * 不被克隆,编辑与吸顶互不影响。
 */
type HeaderEditingTracker = {
  onChange: (editing: boolean) => void
}

const HeaderEditingContext = React.createContext<HeaderEditingTracker | null>(null)

export type MarkdownVariant = 'preview' | 'display'

export type MarkdownComponentOptions = {
  variant: MarkdownVariant
  /** 段落/列表/引用的换行模式：true=自动换行(pre-wrap)，false=不换行(pre)。 */
  wrapLines?: boolean
  /** 大纲跳转：根据 hast 节点的起始行号(0-based)解析标题 id。仅 preview 场景需要。 */
  resolveHeadingId?: (line: number) => string | undefined
  /** 图片渲染器；缺省时回退为原生 <img>。仅 preview 场景需要工作区上下文图片。 */
  renderImage?: (src: string, alt: string) => React.ReactNode
  /**
   * 链接点击接管：返回 true 表示已处理，渲染器拦截默认导航（改 hash / 新开标签页）。
   * 仅无修饰键的左键单击回调（中键 / Ctrl 等修饰键保留浏览器原生行为，便于复制链接、新开标签）。
   * 缺省时保持默认行为：页内锚点禁用跳转，其余链接按外部链接新窗口打开。
   */
  onLinkClick?: (href: string) => boolean
  /** 源 markdown 文本:表格单元格编辑的 offset 定位基准。缺省时表格单元格只读。 */
  sourceText?: string
  /** 表格单元格编辑提交(offset 区间替换源文本)。缺省时表格单元格只读。 */
  onTableCellEdit?: (request: MarkdownTableCellEditRequest) => void
}

/** 统计列宽前剥离内联 markdown 语法（图片取 alt），避免语法符号干扰字符数占比。 */
function stripMarkdownInlineSyntax(text: string): string {
  return text
    .replace(/!\[([^\]]*)\]\([^)]+\)/g, '$1')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
}

/** CJK/全角类字符集:这些字符渲染宽度约为拉丁字符 2 倍,列宽估算按 2 计。 */
const WIDE_CHAR = /[\u1100-\u115f\u2e80-\u303f\u3041-\u33ff\u3400-\u4dbf\u4e00-\u9fff\ua000-\ua4cf\uac00-\ud7a3\uf900-\ufaff\ufe30-\ufe4f\uff00-\uff60\uffe0-\uffe6]/

/** 单元格视觉长度:CJK/全角计 2,其余计 1(与 ch 单位的宽度比例近似)。 */
function visualCellLength(text: string): number {
  let length = 0
  for (const char of text) {
    length += WIDE_CHAR.test(char) ? 2 : 1
  }
  return length
}

/** 列宽下限(ch):短列(如二字表头)也能舒适容纳内容与内边距,不至于被压扁。 */
const MIN_COLUMN_CH = 10
/** 列宽上限(ch):单列过宽严重伤可读性,超出的内容换行。60ch ≈ 30 个中文字。 */
const MAX_COLUMN_CH = 60
/** 单元格左右内边距折算(10px 12px × 2 ≈ 24px ≈ 3ch,随字号等比缩放取近似值)。 */
const COLUMN_PAD_CH = 3

/**
 * 按列内容计算列宽(ch 单位):每列取内容最多的那个单元格的视觉长度。
 *
 * 与旧版「全列字符总量占比百分比」不同,max 口径保证内容最多的单元格
 * 决定本列宽度——长内容列获得足够横向空间而不是被挤压换行。
 * 搭配 tableLayout:fixed + width:100%:表格至少撑满容器,列宽总和超出
 * 容器时表格更宽、由外层横向滚动。
 * 每列 clamp 到 [MIN_COLUMN_CH, MAX_COLUMN_CH],防止列无限宽/被压扁;
 * 列很多时总和仍可超容器(横向滚动承载),不再回退压缩下限。
 * 另有「单列 ≤ 屏幕宽度」的最终钳制,由渲染层 col 元素
 * width: min(Nch, 100vw) 声明式完成(见 TableBlock colgroup),此处不管。
 */
export function computeTableColumnWidths(headers: string[], rows: string[][]): string[] {
  const columnCount = headers.length
  if (columnCount === 0) return []

  const maxLens = new Array<number>(columnCount).fill(0)
  const measure = (text: string, index: number) => {
    if (index >= columnCount) return
    const length = visualCellLength(stripMarkdownInlineSyntax(text))
    if (length > maxLens[index]) maxLens[index] = length
  }
  headers.forEach(measure)
  rows.forEach((row) => row.forEach(measure))

  return maxLens.map((maxLen) => {
    const width = Math.min(MAX_COLUMN_CH, Math.max(MIN_COLUMN_CH, maxLen + COLUMN_PAD_CH))
    return `${width}ch`
  })
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
  // 表格 width:100% + tableLayout:fixed 下,表格宽度取 max(容器宽, Σ列宽):
  // 列内容不多时撑满容器,列宽总和超出容器时表格更宽,由本层出横向滚动条。
  overflowX: 'auto',
  overflowY: 'hidden',
  // 移动端:禁用双击缩放(与单元格双击编辑冲突),保留滑动滚动手势与捏合缩放;
  // 横向滚动到边后惯性不再向祖先链传导(页面横向本就不可滚)。
  touchAction: 'manipulation',
  overscrollBehaviorX: 'contain',
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

/**
 * 文件阅读区（preview 变体）正文色：跟随 `--reading-fg`，使阅读类颜色主题
 * （只覆盖 `--reading-bg`/`--reading-fg`）能整体生效，而不是只有底色变。
 * 聊天（display 变体）不使用它，保持 `--text-*`，不受阅读主题影响。
 */
const READING_FG = 'var(--reading-fg)'
const READING_FG_DIM = 'color-mix(in srgb, var(--reading-fg) 78%, transparent)'
const READING_FG_FAINT = 'color-mix(in srgb, var(--reading-fg) 60%, transparent)'
const READING_FG_SOFT = 'color-mix(in srgb, var(--reading-fg) 74%, transparent)'

const previewHeadingStyles: Record<number, React.CSSProperties> = {
  1: { margin: '24px 0 12px', fontSize: 'var(--reading-font-2xl)', fontWeight: 600, lineHeight: 1.3, color: READING_FG, borderBottom: '1px solid var(--border)', paddingBottom: 8 },
  2: { margin: '20px 0 10px', fontSize: 'var(--reading-font-xl)', fontWeight: 600, lineHeight: 1.35, color: READING_FG },
  3: { margin: '16px 0 8px', fontSize: 'var(--reading-font-md)', fontWeight: 600, lineHeight: 1.4, color: READING_FG },
  4: { margin: '14px 0 6px', fontSize: 'var(--reading-font-sm)', fontWeight: 600, color: READING_FG },
  5: { margin: '12px 0 4px', fontSize: 'var(--reading-font-sm)', fontWeight: 600, color: READING_FG_DIM },
  6: { margin: '10px 0 4px', fontSize: 'var(--reading-font-xs)', fontWeight: 700, color: READING_FG_FAINT, letterSpacing: '0.02em' },
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
    // 段落/列表项不写字号 → 继承 .md-root 的 --editor-font-size（随设置缩放）。
    paragraph: { ...baseParagraphStyle, lineHeight: 1.8, color: READING_FG, marginBottom: 4 },
    blockquote: { ...baseBlockquoteStyle, margin: '8px 0', color: READING_FG_SOFT, fontSize: 'var(--reading-font-sm)', background: 'var(--bg-tertiary)' },
    list: { margin: '6px 0 8px', padding: 0, paddingLeft: '1.4em', listStyleType: 'disc', whiteSpace: 'normal' },
    listItem: { lineHeight: 1.6, color: READING_FG, marginBottom: 2 },
    tableWrap: { ...baseTableWrapStyle, margin: '12px 0 16px', background: 'var(--bg-table)' },
    table: baseTableStyle,
    th: { ...baseThStyle, fontSize: 'var(--reading-font-xs)', color: READING_FG, background: 'color-mix(in srgb, var(--bg-table) 92%, var(--text-muted))' },
    td: { ...baseTdStyle, fontSize: 'var(--reading-font-sm)', color: READING_FG },
    hr: { ...baseHrStyle, margin: '16px 0', borderTopColor: 'var(--border)' },
    code: { background: 'var(--bg-tertiary)', padding: '1px 5px', borderRadius: 4, color: READING_FG, fontSize: 'var(--reading-font-xs)', fontFamily: 'var(--font-mono)' },
    strong: { fontWeight: 700, color: READING_FG },
    heading: previewHeadingStyles,
  },
  display: {
    paragraph: { ...baseParagraphStyle, fontSize: 'var(--text-sm)', lineHeight: 1.8, color: 'var(--text-secondary)', margin: 0 },
    blockquote: { ...baseBlockquoteStyle, margin: '4px 0', color: 'var(--text-secondary)', fontSize: 'var(--text-sm)', lineHeight: 1.75 },
    list: { margin: '2px 0 2px 18px', padding: 0, paddingLeft: 2, listStyleType: 'disc', whiteSpace: 'normal' },
    listItem: { fontSize: 'var(--text-sm)', lineHeight: 1.75, color: 'var(--text-secondary)', marginBottom: 4 },
    tableWrap: { ...baseTableWrapStyle, margin: '6px 0', border: '1px solid color-mix(in srgb, var(--border-light) 85%, transparent)', background: 'var(--bg-table)' },
    table: baseTableStyle,
    th: { ...baseThStyle, lineHeight: 1.6, color: 'var(--text-primary)', background: 'color-mix(in srgb, var(--bg-table) 92%, var(--text-muted))', borderBottomColor: 'var(--border-light)' },
    td: { ...baseTdStyle, lineHeight: 1.75, color: 'var(--text-secondary)', borderTopColor: 'color-mix(in srgb, var(--border-light) 78%, transparent)' },
    hr: { ...baseHrStyle, margin: '8px 0' },
    code: { background: 'var(--bg-tertiary)', padding: '1px 5px', borderRadius: 4, color: 'var(--text-primary)', fontSize: 'var(--text-xs)', fontFamily: 'var(--font-mono)' },
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

/** 表格单元格编辑输入框样式(编辑态下嵌入 td/th,宽度吃满单元格)。 */
const cellInputStyle: React.CSSProperties = {
  display: 'block',
  width: '100%',
  minWidth: 0,
  boxSizing: 'border-box',
  padding: '5px 8px',
  borderRadius: 'var(--radius-sm, 4px)',
  border: '1px solid var(--accent-blue, var(--border))',
  background: 'var(--bg-secondary)',
  color: 'var(--text-primary)',
  font: 'inherit',
  lineHeight: 1.6,
  outline: 'none',
  // 表格 wrap 的 grab 光标会继承到单元格;编辑态显式恢复文本光标。
  cursor: 'text',
  // iOS Safari:输入框字号 < 16px 时聚焦会强制放大视口;取「继承字号与 16px 的较大者」。
  fontSize: 'max(16px, 1em)',
  // 随内容自动增高(宽度吃满列宽);封顶 40vh 后出竖向滚动条,长内容编辑不撑爆表格。
  resize: 'none',
  overflowX: 'hidden',
  overflowY: 'auto',
  maxHeight: '40vh',
}

/** textarea 高度自适应内容(单行起步,随换行/软换行增长)。 */
function autoGrowTextArea(element: HTMLTextAreaElement | null): void {
  if (!element) return
  element.style.height = 'auto'
  element.style.height = `${element.scrollHeight}px`
}

/** 吸顶浮动表头容器样式(position:fixed + 几何由吸顶快照注入)。 */
const floatHeaderBoxStyle: React.CSSProperties = {
  position: 'fixed',
  zIndex: 20,
  overflow: 'hidden',
  boxSizing: 'border-box',
  border: '1px solid var(--border-light)',
  borderRadius: 'var(--radius-md)',
  background: 'var(--bg-table)',
  boxShadow: '0 4px 12px rgba(0, 0, 0, 0.18)',
}

/**
 * 表格单元格编辑态(th/td 共用):双击进入编辑,Enter/blur 提交,Esc 取消。
 * 编辑初值取源 markdown 原始片段(hast position offset 切片),用户改的就是源码,
 * 内联语法(`**粗体**` 等)原样保留;提交经 escapeTableCellText 转义 `|` 与换行。
 * 源定位(offset)缺失(如部分 rehype-raw 产物)或未注入提交回调时只读。
 * 进入编辑:桌面走原生 dblclick;触屏/笔双击(tap-tap)自行判定——移动端
 * dblclick 派发不可靠(iOS Safari 常缺失),配合 wrap 的 touch-action:manipulation
 * 抑制系统双击缩放。
 */
function useTableCellEditor(
  node: Element | undefined,
  sourceText: string | undefined,
  onTableCellEdit: ((request: MarkdownTableCellEditRequest) => void) | undefined,
) {
  const [editing, setEditing] = React.useState(false)
  const [draft, setDraft] = React.useState('')
  const cancelledRef = React.useRef(false)
  const inputRef = React.useRef<HTMLTextAreaElement | null>(null)
  // 触屏/笔轻触轨迹:按下落点(抬起时判位移,滚动/长滑不算轻触)与上一次轻触时空坐标。
  const touchDownRef = React.useRef<{ x: number; y: number } | null>(null)
  const lastTapRef = React.useRef<TouchTap | null>(null)

  // hast 单元格区间含边界分隔符(前导 `|`、行尾 `|` 与空白),先规范化为纯内容区间,
  // 否则编辑初值开头出现 `|`,提交替换会吞掉分隔符导致相邻单元格合并。
  const range = React.useMemo(() => {
    const startOffset = node?.position?.start?.offset
    const endOffset = node?.position?.end?.offset
    if (sourceText == null || startOffset == null || endOffset == null) return null
    return resolveCellRange(sourceText, startOffset, endOffset)
  }, [node, sourceText])
  const original = range?.original ?? null

  const editable = original != null && onTableCellEdit != null

  const beginEdit = React.useCallback(() => {
    if (original == null) return
    cancelledRef.current = false
    setDraft(original)
    setEditing(true)
  }, [original])

  const cancelEdit = React.useCallback(() => {
    cancelledRef.current = true
    setEditing(false)
  }, [])

  const commitEdit = React.useCallback(() => {
    setEditing(false)
    if (cancelledRef.current) return
    if (range == null || onTableCellEdit == null) return
    const next = escapeTableCellText(draft)
    if (next === range.original) return
    onTableCellEdit({ start: range.start, end: range.end, original: range.original, next })
  }, [draft, onTableCellEdit, range])

  React.useEffect(() => {
    if (!editing) return
    const element = inputRef.current
    if (!element) return
    autoGrowTextArea(element)
    element.focus()
    element.select()
  }, [editing])

  const handleDraftChange = React.useCallback((event: React.ChangeEvent<HTMLTextAreaElement>) => {
    setDraft(event.target.value)
    autoGrowTextArea(event.target)
  }, [])

  /** 触屏/笔按下:记录落点(仅非 mouse 指针)。 */
  const handleCellPointerDown = React.useCallback((event: React.PointerEvent<HTMLElement>) => {
    if (event.pointerType === 'mouse') return
    touchDownRef.current = { x: event.clientX, y: event.clientY }
  }, [])

  /** 触屏/笔抬起:位移小(轻触而非滚动)且与上次轻触构成 tap-tap → 进入编辑。 */
  const handleCellPointerUp = React.useCallback((event: React.PointerEvent<HTMLElement>) => {
    if (event.pointerType === 'mouse') return // 桌面双击走原生 dblclick。
    if (!event.isPrimary) return // 多指手势(捏合缩放等)不算轻触。
    const down = touchDownRef.current
    touchDownRef.current = null
    if (down == null) return
    if (!editable || editing) return
    // 按下→抬起位移过大 = 滚动手势,不是轻触。
    if (Math.hypot(event.clientX - down.x, event.clientY - down.y) > 8) return
    // 链接/输入框等交互元素上的轻触保持原生行为。
    if ((event.target as HTMLElement | null)?.closest('a, input, textarea, button, [contenteditable]')) return
    const tap: TouchTap = { time: event.timeStamp, x: event.clientX, y: event.clientY }
    if (isTouchDoubleTap(lastTapRef.current, tap)) {
      lastTapRef.current = null
      beginEdit()
    } else {
      lastTapRef.current = tap
    }
  }, [beginEdit, editable, editing])

  const touchHandlers = {
    onPointerDown: handleCellPointerDown,
    onPointerUp: handleCellPointerUp,
  }

  const editor = editing ? (
    <textarea
      ref={inputRef}
      rows={1}
      value={draft}
      onChange={handleDraftChange}
      onBlur={commitEdit}
      onKeyDown={(event) => {
        if (event.key === 'Enter' && !event.shiftKey) {
          // Enter 提交;Shift+Enter 允许软换行(提交时折叠为空格,单元格内不容纳换行)。
          event.preventDefault()
          commitEdit()
        } else if (event.key === 'Escape') {
          event.preventDefault()
          cancelEdit()
        }
      }}
      style={cellInputStyle}
    />
  ) : null

  return { editable, editing, beginEdit, editor, touchHandlers }
}

function buildWrapStyle(wrapLines: boolean | undefined): React.CSSProperties {
  return wrapLines === false
    ? { whiteSpace: 'pre', wordBreak: 'normal', overflowWrap: 'normal' }
    : { whiteSpace: 'normal', wordBreak: 'break-word', overflowWrap: 'anywhere' }
}

/** 仅无修饰键的左键单击才接管链接导航；中键/修饰键保留浏览器原生行为。 */
function isPlainLeftClick(event: React.MouseEvent<HTMLAnchorElement>): boolean {
  return event.button === 0
    && !event.metaKey
    && !event.ctrlKey
    && !event.shiftKey
    && !event.altKey
}

/**
 * 块级元素源行号标注(1-based,与 CodeMirror 行号同基准),
 * 供 markdown 预览滚动同步(markdownScrollSync)按行定位预览块。
 * hast 节点缺失 position(如部分 rehype-raw 产物)时返回空。
 */
function sourceLineData(node: Element | undefined): {
  'data-source-line'?: number
  'data-source-line-end'?: number
} {
  const start = node?.position?.start?.line
  const end = node?.position?.end?.line
  if (start == null) return {}
  return end != null && end >= start
    ? { 'data-source-line': start, 'data-source-line-end': end }
    : { 'data-source-line': start }
}

export function buildMarkdownComponents(options: MarkdownComponentOptions): Components {
  const { variant, wrapLines = true, resolveHeadingId, renderImage, onLinkClick, sourceText, onTableCellEdit } = options
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
        { ...rest, id, 'data-markdown-heading-id': id, ...sourceLineData(node), style: s.heading[level] },
        children,
      )
    }
    return Heading
  }

  const PreBlock = ({ node, children }: IntrinsicProps<'pre'>) => {
    const childArray = React.Children.toArray(children)
    const codeEl = childArray.find(React.isValidElement) as
      | React.ReactElement<{ className?: string; children?: React.ReactNode }>
      | undefined
    const className = codeEl?.props?.className
    const lang = /language-([\w-]+)/.exec(className ?? '')?.[1]
    const text = extractReactText(codeEl ? codeEl.props.children : children).replace(/\n$/, '')
    const lineData = sourceLineData(node)
    return (
      <CodeBlock
        code={text}
        language={lang}
        variant={variant}
        style={{ margin: variant === 'preview' ? '10px 0' : '4px 0' }}
        sourceLine={lineData['data-source-line']}
        sourceLineEnd={lineData['data-source-line-end']}
      />
    )
  }

  const TableBlock = ({ node, children }: IntrinsicProps<'table'>) => {
    const { headers, rows } = extractTableMatrix(node)
    const widths = computeTableColumnWidths(headers, rows)
    // Ctrl 手型平移:默认原生选择复制;按住 Ctrl 变抓手,拖动平移(仅大表格)。
    const pan = useTablePan()
    const panCursor = pan.panning
      ? 'grabbing'
      : pan.ctrlPan && pan.pannable ? 'grab' : undefined

    // 表头吸顶:表头滚出视野且表格仍跨越阅读容器顶时,钉一个同列宽的表头克隆。
    // 表头单元格编辑期间关闭(克隆与在流编辑态不共享,避免内容不一致)。
    const headerEditingCountRef = React.useRef(0)
    const [headerEditing, setHeaderEditing] = React.useState(false)
    const headerEditingTracker = React.useMemo<HeaderEditingTracker>(() => ({
      onChange: (editing: boolean) => {
        headerEditingCountRef.current = Math.max(0, headerEditingCountRef.current + (editing ? 1 : -1))
        setHeaderEditing(headerEditingCountRef.current > 0)
      },
    }), [])
    const pin = usePinnedTableHeader(pan.wrapRef, variant === 'preview' && !headerEditing)

    // 从 react-markdown 的 children 中取在流 thead 元素克隆渲染(同列宽同单元格组件)。
    const theadElement = React.useMemo(() => (
      React.Children.toArray(children).find(
        (child): child is React.ReactElement => React.isValidElement(child) && child.type === 'thead',
      ) ?? null
    ), [children])

    // colgroup 为在流表与吸顶克隆共用:列宽按内容计算(ch),再经 min(…,100vw)
    // 把单列上限钳到屏幕宽度——窄屏/超大字号下单列不超屏,免横向滚动。
    const colgroup = widths.length > 0 ? (
      <colgroup>
        {widths.map((width, index) => (
          <col key={`col-${index}`} style={{ width: `min(${width}, 100vw)` }} />
        ))}
      </colgroup>
    ) : null

    return (
      <HeaderEditingContext.Provider value={headerEditingTracker}>
        <div
          ref={pan.wrapRef}
          {...sourceLineData(node)}
          {...pan.handlers}
          style={{
            ...s.tableWrap,
            ...(panCursor != null ? { cursor: panCursor } : null),
            ...(pan.panning ? { userSelect: 'none' as const } : null),
          }}
          title={pan.pannable ? '按住 Ctrl 拖动平移表格；直接拖动可选择复制文本' : undefined}
        >
          <table style={s.table}>
            {colgroup}
            {children}
          </table>
        </div>
        {pin.pinned && theadElement && typeof document !== 'undefined'
          ? createPortal(
            <div
              aria-hidden="true"
              style={{
                ...floatHeaderBoxStyle,
                top: pin.pinned.top,
                left: pin.pinned.left,
                width: pin.pinned.width,
              }}
            >
              <div
                ref={pin.innerRef}
                style={{
                  width: pin.pinned.tableWidth,
                  transform: `translateX(${-pin.pinned.scrollLeft}px)`,
                }}
              >
                <table style={{ ...s.table, width: pin.pinned.tableWidth }}>
                  {colgroup}
                  {theadElement}
                </table>
              </div>
            </div>,
            document.body,
          )
          : null}
      </HeaderEditingContext.Provider>
    )
  }

  const Th = (props: IntrinsicProps<'th'>) => {
    const { children, align, node } = props
    const rest = stripNode(props)
    delete rest.align
    const cell = useTableCellEditor(node, sourceText, onTableCellEdit)
    // 表头单元格编辑态上报:编辑期间所属表格关闭表头吸顶(克隆表头与在流编辑态不共享)。
    const headerEditingTracker = React.useContext(HeaderEditingContext)
    React.useEffect(() => {
      if (!headerEditingTracker || !cell.editing) return
      headerEditingTracker.onChange(true)
      return () => headerEditingTracker.onChange(false)
    }, [cell.editing, headerEditingTracker])
    return (
      <th
        {...rest}
        {...cell.touchHandlers}
        onDoubleClick={cell.editable && !cell.editing ? cell.beginEdit : undefined}
        title={cell.editable ? '双击编辑单元格' : undefined}
        style={{
          ...s.th,
          textAlign: (align ?? node?.properties?.align ?? 'left') as React.CSSProperties['textAlign'],
          ...(cell.editable && !cell.editing ? { cursor: 'text' } : null),
          ...(cell.editing ? { padding: 3 } : null),
        }}
      >
        {cell.editing ? cell.editor : children}
      </th>
    )
  }

  const Td = (props: IntrinsicProps<'td'>) => {
    const { children, align, node } = props
    const rest = stripNode(props)
    delete rest.align
    const cell = useTableCellEditor(node, sourceText, onTableCellEdit)
    return (
      <td
        {...rest}
        {...cell.touchHandlers}
        onDoubleClick={cell.editable && !cell.editing ? cell.beginEdit : undefined}
        title={cell.editable ? '双击编辑单元格' : undefined}
        style={{
          ...s.td,
          textAlign: (align ?? node?.properties?.align) as React.CSSProperties['textAlign'],
          ...(cell.editable && !cell.editing ? { cursor: 'text' } : null),
          ...(cell.editing ? { padding: 3 } : null),
        }}
      >
        {cell.editing ? cell.editor : children}
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
      const { children, node } = props
      const rest = stripNode(props)
      return <p {...rest} {...sourceLineData(node)} style={{ ...s.paragraph, ...wrap }}>{children}</p>
    },
    blockquote: (props: IntrinsicProps<'blockquote'>) => {
      const { children, node } = props
      const rest = stripNode(props)
      return <blockquote {...rest} {...sourceLineData(node)} style={{ ...s.blockquote, ...wrap }}>{children}</blockquote>
    },
    ul: (props: IntrinsicProps<'ul'>) => {
      const { children, node } = props
      const rest = stripNode(props)
      return <ul {...rest} {...sourceLineData(node)} style={s.list}>{children}</ul>
    },
    ol: (props: IntrinsicProps<'ol'>) => {
      const { children, node } = props
      const rest = stripNode(props)
      return <ol {...rest} {...sourceLineData(node)} style={{ ...s.list, listStyleType: 'decimal' }}>{children}</ol>
    },
    li: (props: IntrinsicProps<'li'>) => {
      const { children, className, node } = props
      const rest = stripNode(props)
      const lineData = sourceLineData(node)
      const isTaskItem = typeof className === 'string' && className.includes('task-list-item')
      if (!isTaskItem) {
        return (
          <li {...rest} {...lineData} style={{ ...s.listItem, ...wrap }}>
            {children}
          </li>
        )
      }
      // GFM 任务列表项：checkbox 由 input 渲染器统一替换为 ⬜/✅，
      // 此处只负责去掉项目符号并按 flex 排列（紧凑列表时 checkbox 是 li 直接子节点，
      // 松散列表时 checkbox 包在 li > p 内，两种情况都成立）。
      return (
        <li
          {...rest}
          {...lineData}
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
          {children}
        </li>
      )
    },
    input: (props: IntrinsicProps<'input'>) => {
      const { type, checked } = props
      const rest = stripNode(props)
      // GFM 任务列表 checkbox：原生 disabled 样式是灰框灰勾，替换为 ⬜/✅ emoji。
      if (type === 'checkbox') {
        return <span style={taskCheckedStyle}>{checked ? '✅' : '⬜'}</span>
      }
      return <input {...rest} />
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
      const { node } = props
      const rest = stripNode(props)
      return <hr {...rest} {...sourceLineData(node)} style={s.hr} />
    },
    a: (props: IntrinsicProps<'a'>) => {
      const { children, href } = props
      const rest = stripNode(props)
      // 页内锚点链接(如目录 #xxx)：一律拦截浏览器默认跳转(改 hash 会扰动 SPA 路由)，
      // 是否真正导航由 onLinkClick 决定(未提供时点击无动作，与旧行为一致——导航由大纲面板承担)。
      if (typeof href === 'string' && href.startsWith('#')) {
        return (
          <a
            {...rest}
            href={href}
            style={{ ...baseLinkStyle, cursor: onLinkClick ? 'pointer' : 'text' }}
            onClick={(event) => {
              event.preventDefault()
              if (!onLinkClick || !isPlainLeftClick(event)) return
              onLinkClick(href)
            }}
          >
            {children}
          </a>
        )
      }
      return (
        <a
          {...rest}
          href={href}
          target="_blank"
          rel="noopener noreferrer nofollow"
          style={baseLinkStyle}
          onClick={(event) => {
            if (!onLinkClick) return
            if (typeof href !== 'string' || !href || !isPlainLeftClick(event)) return
            // 接管方(如工作区文件链接)已处理时拦截默认新开标签页；未处理(返回 false)走浏览器默认。
            if (onLinkClick(href)) event.preventDefault()
          }}
        >
          {children}
        </a>
      )
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
