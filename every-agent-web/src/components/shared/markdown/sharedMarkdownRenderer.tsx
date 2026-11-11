import React from 'react'
import type { Components, ExtraProps } from 'react-markdown'
import type { Element, ElementContent } from 'hast'
import CodeBlock from './CodeBlock'
import { escapeTableCellText, resolveCellRange, isTouchDoubleTap, type MarkdownTableCellEditRequest, type TouchTap } from './markdownTableEdit'
import { useTablePan } from './useTablePan'
import { usePinnedTableHeader } from './usePinnedTableHeader'
import {
  useTableLayout,
  allocateColumnWidths,
  applyColumnOverrides,
  computeFallbackColumnWidths,
  COLUMN_EDGE_PX,
  ROW_EDGE_PX,
  MAX_ROW_PX,
  type TableLayoutController,
} from './useTableLayout'
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

/** 表格调整(列宽/行高拖动)通道:TableBlock 的布局引擎经 context 下发 Th/Td。 */
const TableResizeContext = React.createContext<TableLayoutController | null>(null)

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

/** th/td 注入的内部索引 props(TableBlock 渲染期 cloneElement 注入,组件内消费后剥离)。 */
export type TableCellIndexProps = {
  /** 列索引(thead/tbody 的 tr 内按 th/td 出现顺序计数)。 */
  __columnIndex?: number
  /** 行索引(tbody 内按 tr 顺序计数)。 */
  __rowIndex?: number
}

/**
 * 渲染期向 react-markdown 的 table children 注入列/行索引:
 * thead/tbody > tr > th/td 逐层 cloneElement 加 __columnIndex/__rowIndex,
 * 供 Th/Td 的边缘拖动(调列宽/行高)定位目标。纯函数、渲染期执行;
 * 吸顶克隆的 thead 复用注入后的同一份元素。
 */
function injectTableCellIndices(children: React.ReactNode): React.ReactNode {
  type WithChildren = React.ReactElement<{ children?: React.ReactNode }>
  const cloneWith = (element: unknown, props: Record<string, unknown>) => (
    React.cloneElement(element as WithChildren, props)
  )
  const mapChildren = (node: React.ReactNode, fn: (child: React.ReactNode) => React.ReactNode) => (
    React.Children.map(React.isValidElement(node) ? (node as WithChildren).props.children : null, fn)
  )

  return React.Children.map(children, (section) => {
    if (!React.isValidElement(section)) return section
    if (section.type === 'thead' || section.type === 'tbody') {
      const isBody = section.type === 'tbody'
      return cloneWith(section, {
        children: React.Children.map(
          (section as WithChildren).props.children,
          (row, rowIndex) => {
            if (!React.isValidElement(row) || row.type !== 'tr') return row
            let columnIndex = 0
            return cloneWith(row, {
              children: mapChildren(row, (cell) => {
                if (!React.isValidElement(cell)) return cell
                if (cell.type === 'th' || cell.type === 'td') {
                  const indexProps: TableCellIndexProps = isBody
                    ? { __rowIndex: rowIndex }
                    : { __columnIndex: columnIndex++ }
                  // tbody 行的 td 也带列索引(备用),thead 的 th 不带行索引。
                  return cloneWith(cell, isBody && cell.type === 'td'
                    ? { ...indexProps, __columnIndex: columnIndex++ }
                    : indexProps)
                }
                return cell
              }),
            })
          },
        ),
      })
    }
    return section
  })
}

/** 从 react-markdown 传入的 props 中剥离 node，避免泄漏到 DOM 属性。 */
function stripNode<T extends object>(props: T): Omit<T, 'node'> {
  const { node, ...rest } = props as Record<string, unknown>
  void node
  return rest as Omit<T, 'node'>
}


/** 展开编辑浮层容器(定位几何由 computeExpandDialogGeometry 注入)。 */
const cellDialogStyle: React.CSSProperties = {
  position: 'fixed',
  zIndex: 100,
  boxSizing: 'border-box',
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
  padding: 12,
  borderRadius: 'var(--radius-md)',
  border: '1px solid var(--border)',
  background: 'var(--bg-secondary)',
  boxShadow: '0 8px 24px rgba(0, 0, 0, 0.2)',
}

const cellDialogAreaStyle: React.CSSProperties = {
  width: '100%',
  boxSizing: 'border-box',
  minHeight: 120,
  maxHeight: '60vh',
  padding: '8px 10px',
  borderRadius: 'var(--radius-sm, 4px)',
  border: '1px solid var(--border)',
  background: 'var(--bg-primary, var(--bg-secondary))',
  color: 'var(--text-primary)',
  font: 'inherit',
  fontSize: 'max(16px, 1em)',
  lineHeight: 1.6,
  resize: 'none',
  overflowY: 'auto',
  outline: 'none',
}

const cellDialogActionsStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  justifyContent: 'flex-end',
}

const cellDialogCountStyle: React.CSSProperties = {
  marginRight: 'auto',
  fontSize: 'var(--text-xs, 12px)',
  color: 'var(--text-muted, var(--text-secondary))',
}

const cellDialogButtonBaseStyle: React.CSSProperties = {
  padding: '5px 14px',
  borderRadius: 'var(--radius-sm, 4px)',
  border: '1px solid var(--border)',
  cursor: 'pointer',
  fontSize: 'var(--text-sm, 13px)',
}

const cellDialogCancelButtonStyle: React.CSSProperties = {
  ...cellDialogButtonBaseStyle,
  background: 'var(--bg-tertiary)',
  color: 'var(--text-secondary)',
}

const cellDialogSaveButtonStyle: React.CSSProperties = {
  ...cellDialogButtonBaseStyle,
  border: '1px solid var(--accent-blue, var(--border))',
  background: 'var(--accent-blue, var(--bg-tertiary))',
  color: '#fff',
}

/** 浮层查看态内容预览区(富内联渲染,长内容内部滚动)。 */
const cellDialogPreviewStyle: React.CSSProperties = {
  ...cellDialogAreaStyle,
  lineHeight: 1.8,
  whiteSpace: 'normal',
}

/** 表头列宽拖动把手:常显小图标(↔),悬停高亮;按住拖动调列宽,双击重置。 */
const columnHandleStyle: React.CSSProperties = {
  position: 'absolute',
  top: '50%',
  right: 0,
  transform: 'translateY(-50%)',
  width: 16,
  height: 22,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 10,
  lineHeight: 1,
  color: 'var(--text-muted, var(--text-secondary))',
  background: 'color-mix(in srgb, var(--bg-table) 85%, transparent)',
  border: '1px solid var(--border-light)',
  borderRadius: '4px 0 0 4px',
  cursor: 'col-resize',
  userSelect: 'none',
  touchAction: 'none',
  zIndex: 2,
}

/** 行高上限内容容器:td 是 table-cell,max-height 对其无效(CSS 规范),
 * 行高限制须作用于 td 内的块级内容层。超出以 -webkit-line-clamp 按行截断,
 * 末行尾显示省略号;行数 = 上限px ÷ 实测行高(见 Td)。 */
const rowClampStyle = (maxPx: number, lineHeightPx: number): React.CSSProperties => ({
  display: '-webkit-box',
  WebkitBoxOrient: 'vertical',
  WebkitLineClamp: Math.max(1, Math.floor(maxPx / lineHeightPx)),
  overflow: 'hidden',
})


/** 浮层内 textarea 自由增高(无格高约束,封顶 60vh 由 CSS maxHeight 承担)。 */
function autoGrowTextArea(element: HTMLTextAreaElement | null): void {
  if (!element) return
  element.style.height = 'auto'
  element.style.height = `${element.scrollHeight}px`
}

/** 单元格展开编辑浮层(Excel 公式栏展开式)的定位几何。 */
export type ExpandDialogGeometry = {
  left: number
  width: number
  placement: 'below' | 'above'
  /** placement=below 时的 fixed top(px)。 */
  top?: number
  /** placement=above 时的 fixed bottom(距视口底,px)。 */
  bottom?: number
}

/**
 * 计算展开浮层几何:宽 = max(列宽, 420px) 上限 80vw;水平钳制在视口内;
 * 优先锚在单元格下方,下方空间不足(<300px)且上方充足时翻上方,
 * 两侧都不足时兜底下方(fixed 层可超出视口,编辑短暂可接受)。
 */
export function computeExpandDialogGeometry(
  host: { left: number; top: number; bottom: number; width: number },
  viewport: { width: number; height: number },
  minWidth = 420,
): ExpandDialogGeometry {
  const margin = 8
  const width = Math.round(Math.min(Math.max(host.width, minWidth), viewport.width * 0.8))
  const left = Math.min(Math.max(host.left, margin), Math.max(margin, viewport.width - width - margin))
  const spaceBelow = viewport.height - host.bottom
  const placement: 'below' | 'above' = spaceBelow >= 300 || host.top < 300 ? 'below' : 'above'
  return placement === 'below'
    ? { left, width, placement, top: Math.round(host.bottom + margin) }
    : { left, width, placement, bottom: Math.round(viewport.height - host.top + margin) }
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
  /** 展开浮层打开:双击单元格直接弹层编辑(唯一编辑形态)。 */
  const [expanded, setExpanded] = React.useState(false)
  const cancelledRef = React.useRef(false)
  /** 宿主 td/th ref:浮层定位基准(锚定单元格矩形)。 */
  const hostRef = React.useRef<HTMLTableCellElement | null>(null)
  /** 浮层打开标记(ref 形态供 onBlur 同步读取,避免闭包陈旧)。 */
  const expandedRef = React.useRef(false)
  const dialogRef = React.useRef<HTMLDivElement | null>(null)
  const dialogInputRef = React.useRef<HTMLTextAreaElement | null>(null)
  /** 展示态限高容器 ref(自测溢出:scrollHeight > clientHeight 即超高)。 */
  const displayWrapRef = React.useRef<HTMLDivElement | null>(null)
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
    // 双击 = 直接弹层编辑(不再原地内嵌编辑框):所有单元格统一弹窗交互,
    // 超高内容天然获得完整编辑区,格高/行高不再受编辑影响。
    // 几何在切换编辑态前同步测量(此时 DOM 仍是原单元格,矩形有效)。
    const host = hostRef.current?.getBoundingClientRect() ?? null
    setDialogGeometry(host
      ? computeExpandDialogGeometry(
        { left: host.left, top: host.top, bottom: host.bottom, width: host.width },
        { width: window.innerWidth, height: window.innerHeight },
      )
      : null)
    cancelledRef.current = false
    setDraft(original)
    setEditing(true)
    expandedRef.current = true
    setExpanded(true)
    requestAnimationFrame(() => {
      const element = dialogInputRef.current
      if (element) {
        autoGrowTextArea(element)
        element.focus()
        element.select()
      }
    })
  }, [original])

  const cancelEdit = React.useCallback(() => {
    cancelledRef.current = true
    expandedRef.current = false
    setExpanded(false)
    setEditing(false)
    setDialogGeometry(null)
  }, [])

  const commitEdit = React.useCallback(() => {
    // 编辑只有浮层一种形态:提交即整单收口(in-cell 已移除)。
    expandedRef.current = false
    setExpanded(false)
    setEditing(false)
    setDialogGeometry(null)
    if (cancelledRef.current) return
    if (range == null || onTableCellEdit == null) return
    const next = escapeTableCellText(draft)
    if (next === range.original) return
    onTableCellEdit({ start: range.start, end: range.end, original: range.original, next })
  }, [draft, onTableCellEdit, range])

  const handleDraftChange = React.useCallback((event: React.ChangeEvent<HTMLTextAreaElement>) => {
    setDraft(event.target.value)
  }, [])

  /** 浮层内提交/取消:先收浮层再走原提交/取消路径。 */
  const submitFromDialog = React.useCallback(() => {
    expandedRef.current = false
    setExpanded(false)
    commitEdit()
  }, [commitEdit])

  const cancelFromDialog = React.useCallback(() => {
    expandedRef.current = false
    setExpanded(false)
    cancelEdit()
  }, [cancelEdit])

  // 浮层打开期间:点击浮层外任意处 = 提交当前草稿(与 Excel 点外部保存一致)。
  // 重新点击同一单元格不会误触:该 pointerdown 先落在外面,浮层已收口。
  React.useEffect(() => {
    if (!expanded) return
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target as Node | null
      if (target && dialogRef.current?.contains(target)) return
      commitEdit()
    }
    window.addEventListener('pointerdown', onPointerDown, true)
    return () => window.removeEventListener('pointerdown', onPointerDown, true)
  }, [commitEdit, expanded])

  // 浮层几何在打开瞬间按宿主单元格矩形计算一次;编辑短暂,窗口缩放不重算。
  // beginEdit 中同步测量(state 批处理 flush 前 DOM 仍是原单元格,矩形有效)。
  const [dialogGeometry, setDialogGeometry] = React.useState<ExpandDialogGeometry | null>(null)

  const handleDialogChange = React.useCallback((event: React.ChangeEvent<HTMLTextAreaElement>) => {
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

  // 编辑即浮层:双击单元格直接弹层编辑(portal 到 body),不再原地内嵌输入框。
  const editor = editing && expanded && dialogGeometry && typeof document !== 'undefined' ? (
    createPortal(
      <div
        ref={dialogRef}
        style={{
          ...cellDialogStyle,
          left: dialogGeometry.left,
          width: dialogGeometry.width,
          ...(dialogGeometry.placement === 'below'
            ? { top: dialogGeometry.top }
            : { bottom: dialogGeometry.bottom }),
        }}
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            event.preventDefault()
            cancelFromDialog()
          } else if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
            event.preventDefault()
            submitFromDialog()
          }
        }}
      >
        <textarea
          ref={dialogInputRef}
          value={draft}
          onChange={handleDialogChange}
          style={cellDialogAreaStyle}
        />
        <div style={cellDialogActionsStyle}>
          <span style={cellDialogCountStyle}>{draft.length} 字</span>
          <button type="button" onClick={cancelFromDialog} style={cellDialogCancelButtonStyle}>
            取消
          </button>
          <button type="button" onClick={submitFromDialog} style={cellDialogSaveButtonStyle}>
            保存
          </button>
        </div>
      </div>,
      document.body,
    )
  ) : null

  return { editable, editing, beginEdit, editor, hostRef, touchHandlers }
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
    // Ctrl 手型平移:默认原生选择复制;按住 Ctrl 变抓手,拖动平移(仅大表格)。
    const pan = useTablePan()
    const panCursor = pan.panning
      ? 'grabbing'
      : pan.ctrlPan && pan.pannable ? 'grab' : undefined

    // 表格布局引擎:初始列宽分配(内容理想宽 + 撑满放大)+ 用户手调列宽/行高(内存态)。
    const layout = useTableLayout()

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

    // 初始分配(容器宽/字体度量就绪后走 px 数值口径;未测得时 colgroup 用 ch 兜底)。
    const baseWidths = React.useMemo(
      () => allocateColumnWidths(headers, rows, layout.containerWidth, layout.chPx),
      [headers, layout.chPx, layout.containerWidth, rows],
    )
    const effectiveWidths = React.useMemo(
      () => (baseWidths.length > 0 ? applyColumnOverrides(baseWidths, layout.columnOverrides) : null),
      [baseWidths, layout.columnOverrides],
    )
    const fallbackWidths = React.useMemo(
      () => (baseWidths.length === 0 ? computeFallbackColumnWidths(headers, rows) : []),
      [baseWidths.length, headers, rows],
    )

    // colgroup 为在流表与吸顶克隆共用:手调覆盖优先(px),否则初始分配(px)或 ch 兜底。
    const colWidthStyles = React.useMemo(() => {
      if (effectiveWidths) return effectiveWidths.map((px) => `${px}px`)
      return fallbackWidths
    }, [effectiveWidths, fallbackWidths])
    const colgroup = colWidthStyles.length > 0 ? (
      <colgroup>
        {colWidthStyles.map((width, index) => (
          <col key={`col-${index}`} style={{ width }} />
        ))}
      </colgroup>
    ) : null

    // 注入列/行索引(th→__columnIndex、td→__rowIndex):Th/Td 的边缘热区拖动需要
    // 知道自己调整的是哪一列/哪一行。克隆 thead(吸顶)复用注入后的同一份。
    const injectedChildren = React.useMemo(
      () => injectTableCellIndices(children),
      [children],
    )
    const theadElement = React.useMemo(() => (
      React.Children.toArray(injectedChildren).find(
        (child): child is React.ReactElement => React.isValidElement(child) && child.type === 'thead',
      ) ?? null
    ), [injectedChildren])

    // wrap ref 双挂:平移(pan)与布局测量(layout)共用同一包裹层。
    const wrapRef = React.useCallback((element: HTMLDivElement | null) => {
      pan.wrapRef.current = element
      layout.wrapRef.current = element
    }, [pan.wrapRef, layout.wrapRef])

    return (
      <TableResizeContext.Provider value={layout}>
        <HeaderEditingContext.Provider value={headerEditingTracker}>
          <div
            ref={wrapRef}
            {...sourceLineData(node)}
            {...pan.handlers}
            style={{
              ...s.tableWrap,
              ...(panCursor != null ? { cursor: panCursor } : null),
              ...(pan.panning ? { userSelect: 'none' as const } : null),
            }}
            title={pan.pannable ? '按住 Ctrl 拖动平移表格；直接拖动可选择复制文本' : undefined}
          >
            <table className="md-table" style={s.table}>
              {colgroup}
              {injectedChildren}
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
                <table className="md-table" style={{ ...s.table, width: pin.pinned.tableWidth }}>
                  {colgroup}
                  {/* 克隆经 portal 落在 body 下,脱离 TableResizeContext.Provider 树;
                      重新包一层让克隆表头的列宽把手同样可拖。 */}
                  <TableResizeContext.Provider value={layout}>
                    {theadElement}
                  </TableResizeContext.Provider>
                </table>
              </div>
            </div>,
            document.body,
          )
          : null}
        </HeaderEditingContext.Provider>
      </TableResizeContext.Provider>
    )
  }

  const Th = (props: IntrinsicProps<'th'> & TableCellIndexProps) => {
    const { children, align, node, __columnIndex } = props
    const rest = stripNode(props)
    delete rest.align
    delete rest.__columnIndex
    delete rest.__rowIndex
    const cell = useTableCellEditor(node, sourceText, onTableCellEdit)
    const resize = React.useContext(TableResizeContext)
    // 表头单元格编辑态上报:编辑期间所属表格关闭表头吸顶(克隆表头与在流编辑态不共享)。
    const headerEditingTracker = React.useContext(HeaderEditingContext)
    React.useEffect(() => {
      if (!headerEditingTracker || !cell.editing) return
      headerEditingTracker.onChange(true)
      return () => headerEditingTracker.onChange(false)
    }, [cell.editing, headerEditingTracker])

    // 长按表头 350ms → 显示本列调宽把手(↔ 出现在该列右缘);再单击表头任意处收起。
    const [handleVisible, setHandleVisible] = React.useState(false)
    const pressTimerRef = React.useRef<number | null>(null)
    const clearPressTimer = React.useCallback(() => {
      if (pressTimerRef.current != null) {
        window.clearTimeout(pressTimerRef.current)
        pressTimerRef.current = null
      }
    }, [])
    React.useEffect(() => clearPressTimer, [clearPressTimer])

    const beginPress = React.useCallback((event: React.PointerEvent<HTMLElement>) => {
      if (resize == null || __columnIndex == null || cell.editing) {
        cell.touchHandlers.onPointerDown(event)
        return
      }
      clearPressTimer()
      pressTimerRef.current = window.setTimeout(() => {
        pressTimerRef.current = null
        setHandleVisible(true)
        // 触觉反馈(支持的设备):长按成功出把手。
        if (typeof navigator.vibrate === 'function') navigator.vibrate(10)
      }, 350)
      cell.touchHandlers.onPointerDown(event)
    }, [__columnIndex, cell.editing, cell.touchHandlers, clearPressTimer, resize])

    const handlePointerUp = (event: React.PointerEvent<HTMLElement>) => {
      clearPressTimer()
      resize?.dragHandlers.onPointerUp(event)
      cell.touchHandlers.onPointerUp(event)
    }

    const showHandle = handleVisible && resize != null && __columnIndex != null && !cell.editing

    return (
      <th
        ref={cell.hostRef}
        {...rest}
        style={{
          ...s.th,
          position: 'relative',
          ...(handleVisible ? { background: 'color-mix(in srgb, var(--accent-blue, #3b82f6) 12%, var(--bg-table))' } : null),
          textAlign: (align ?? node?.properties?.align ?? 'left') as React.CSSProperties['textAlign'],
        }}
        onPointerDown={beginPress}
        onPointerMove={resize?.dragHandlers.onPointerMove}
        onPointerUp={handlePointerUp}
        onPointerCancel={(event) => {
          clearPressTimer()
          resize?.dragHandlers.onPointerCancel(event)
        }}
        onDoubleClick={cell.editable && !cell.editing ? cell.beginEdit : undefined}
        title={cell.editable ? '长按显示列宽把手；双击编辑单元格' : '长按显示列宽把手'}
      >
        {children}
        {showHandle ? (
          <span
            role="separator"
            aria-label="调整列宽"
            style={columnHandleStyle}
            title="拖动调整列宽，双击重置"
            onPointerDown={(event) => {
              event.stopPropagation()
              resize!.beginColumnResize(__columnIndex!, event)
            }}
            onDoubleClick={(event) => {
              event.stopPropagation()
              event.preventDefault()
              resize!.resetColumn(__columnIndex!)
            }}
          >
            ↔
          </span>
        ) : null}
        {handleVisible ? (
          // 把手可见时,点击表头其他区域收起把手(不冒泡到 th 的长按逻辑)。
          <span
            aria-hidden="true"
            style={{ position: 'absolute', inset: 0, zIndex: 1, cursor: 'pointer' }}
            onPointerDown={(event) => {
              event.stopPropagation()
              setHandleVisible(false)
            }}
          />
        ) : null}
      </th>
    )
  }

  const Td = (props: IntrinsicProps<'td'> & TableCellIndexProps) => {
    const { children, align, node, __rowIndex } = props
    const rest = stripNode(props)
    delete rest.align
    delete rest.__columnIndex
    delete rest.__rowIndex
    const cell = useTableCellEditor(node, sourceText, onTableCellEdit)
    const resize = React.useContext(TableResizeContext)
    /** 行高上限:用户手调优先,默认 MAX_ROW_PX;编辑态同样生效(编辑框=格高)。 */
    const rowMaxHeight = resize && __rowIndex != null
      ? resize.rowOverrides.get(__rowIndex) ?? MAX_ROW_PX
      : MAX_ROW_PX
    const plainText = React.useMemo(() => collectHastText(node), [node])

    // 实测行高(px):行数 = 行高上限 ÷ 行高。line-clamp 按行截断,省略号才生效;
    // computedStyle 读到 "normal"(极少数)时回退 fontSize × 1.7(baseTdStyle 行高)。
    const [lineHeightPx, setLineHeightPx] = React.useState(24)
    React.useEffect(() => {
      const host = cell.hostRef.current
      if (!host) return
      const style = window.getComputedStyle(host)
      const lh = Number.parseFloat(style.lineHeight)
      const fs = Number.parseFloat(style.fontSize)
      const next = Number.isFinite(lh) && lh > 0
        ? lh
        : (Number.isFinite(fs) && fs > 0 ? fs * 1.7 : 24)
      setLineHeightPx((prev) => (Math.abs(prev - next) > 0.5 ? next : prev))
    }, [cell.hostRef])

    /** 命中底缘热区(行高拖动)。 */
    const hitRowEdge = (event: React.PointerEvent<HTMLElement>) => {
      const rect = event.currentTarget.getBoundingClientRect()
      return event.clientY - rect.top >= rect.height - ROW_EDGE_PX
    }

    const handlePointerDown = (event: React.PointerEvent<HTMLElement>) => {
      if (resize && __rowIndex != null && hitRowEdge(event)) {
        resize.beginRowResize(__rowIndex, event)
        return
      }
      cell.touchHandlers.onPointerDown(event)
    }

    return (
      <td
        ref={cell.hostRef}
        {...rest}
        style={{
          ...s.td,
          position: 'relative',
          textAlign: (align ?? node?.properties?.align) as React.CSSProperties['textAlign'],
        }}
        onPointerDown={handlePointerDown}
        onPointerMove={resize?.dragHandlers.onPointerMove}
        onPointerUp={(event) => {
          resize?.dragHandlers.onPointerUp(event)
          cell.touchHandlers.onPointerUp(event)
        }}
        onPointerCancel={resize?.dragHandlers.onPointerCancel}
        onDoubleClick={(event) => {
          if (resize && __rowIndex != null && hitRowEdge(event as unknown as React.PointerEvent<HTMLElement>)) {
            resize.resetRow(__rowIndex)
            return
          }
          if (cell.editable && !cell.editing) cell.beginEdit()
        }}
        title={plainText || undefined}
      >
        {cell.editing || resize == null || __rowIndex == null
          ? null
          : (
            <span
              role="separator"
              aria-label="调整行高"
              style={{
                position: 'absolute',
                left: '50%',
                bottom: 0,
                transform: 'translateX(-50%)',
                width: 22,
                height: 12,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 9,
                lineHeight: 1,
                color: 'var(--text-muted, var(--text-secondary))',
                cursor: 'row-resize',
                userSelect: 'none',
                touchAction: 'none',
                zIndex: 2,
              }}
              title="拖动调整行高，双击重置"
              onPointerDown={(event) => {
                event.stopPropagation()
                resize.beginRowResize(__rowIndex, event)
              }}
              onDoubleClick={(event) => {
                event.stopPropagation()
                event.preventDefault()
                resize.resetRow(__rowIndex)
              }}
            >
              ⇕
            </span>
          )}
        <div style={rowClampStyle(rowMaxHeight, lineHeightPx)}>{children}</div>
        {/* 编辑浮层(portal 到 body,不占位);编辑期间单元格保持原内容显示。 */}
        {cell.editor}
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
