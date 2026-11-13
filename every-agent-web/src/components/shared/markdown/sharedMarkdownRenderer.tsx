import React from 'react'
import type { Components, ExtraProps } from 'react-markdown'
import type { Element, ElementContent } from 'hast'
import CodeBlock from './CodeBlock'
import { escapeTableCellText, resolveCellRange, isTouchDoubleTap, type MarkdownTableCellEditRequest, type TouchTap } from './markdownTableEdit'
import { useTablePan } from './useTablePan'
import { usePinnedTableHeader } from './usePinnedTableHeader'
import {
  useTableLayout,
  useTableGeometry,
  allocateColumnWidths,
  applyColumnOverrides,
  computeFallbackColumnWidths,
  measureColumnIdealWidths,
  MAX_ROW_PX,
  MIN_COL_PX,
  canHoverPointer,
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
 *
 * 注意:react-markdown 在进入 table 组件前就已完成 components 映射,
 * children 里的 th/td 已是 Th/Td 组件元素而非宿主标签元素——按字符串
 * 'th'/'td' 匹配永远不成立,必须按注入的组件引用匹配(否则索引注入
 * 静默失效,列宽/行高把手全部不出现)。
 */
function injectTableCellIndices(
  children: React.ReactNode,
  cellTypes: { headerCell: unknown; bodyCell: unknown },
): React.ReactNode {
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
                const isHeaderCell = cell.type === cellTypes.headerCell
                const isBodyCell = cell.type === cellTypes.bodyCell
                if (!isHeaderCell && !isBodyCell) return cell
                const indexProps: TableCellIndexProps = isBody
                  ? { __rowIndex: rowIndex }
                  : { __columnIndex: columnIndex++ }
                // tbody 行的 td 也带列索引(备用),thead 的 th 不带行索引。
                return cloneWith(cell, isBody && isBodyCell
                  ? { ...indexProps, __columnIndex: columnIndex++ }
                  : indexProps)
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


/** 单元格编辑弹窗容器:视口居中(水平+垂直),整框封顶 100vh-24px,
 * 内容超高时 textarea 内部滚动,底部按钮永不出屏。宽度按列宽自适应注入。 */
const cellDialogStyle: React.CSSProperties = {
  position: 'fixed',
  left: '50%',
  top: '50%',
  transform: 'translate(-50%, -50%)',
  maxHeight: 'calc(100vh - 24px)',
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
  maxHeight: 'min(60vh, calc(100vh - 220px))',
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

/** 浮层内 textarea 自由增高(无格高约束,封顶由 CSS maxHeight 钳制)。 */
function autoGrowTextArea(element: HTMLTextAreaElement | null): void {
  if (!element) return
  element.style.height = 'auto'
  element.style.height = `${element.scrollHeight}px`
}

/** 弹窗宽度下限/占位(px):窄列时编辑区仍保有舒展宽度。 */
const CELL_DIALOG_MIN_WIDTH = 420

/** 行高上限内容容器:td 是 table-cell,max-height 对其无效(CSS 规范),
 * 行高限制须作用于 td 内的块级内容层。超出以 -webkit-line-clamp 按行截断,
 * 末行尾显示省略号;行数 = 上限px ÷ 实测行高(见 Td)。 */
const rowClampStyle = (maxPx: number, lineHeightPx: number): React.CSSProperties => ({
  display: '-webkit-box',
  WebkitBoxOrient: 'vertical',
  WebkitLineClamp: Math.max(1, Math.floor(maxPx / lineHeightPx)),
  overflow: 'hidden',
})


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

// ───────────────────── 表格外把手栏(行高/列宽) ─────────────────────

/** 顶栏(列宽把手)高度与左栏(行高把手)宽度(px)。 */
const COL_GUTTER_PX = 18
const ROW_GUTTER_PX = 20
/** 把手命中区尺寸(px):命中区大于 9px 图标,便于精确抓取。 */
const COL_HANDLE_W = 18
const ROW_HANDLE_H = 18

/** 把手栏外框:左栏(行高)+ 右列(顶栏 + 表格)。margin 沿用原 tableWrap 间距。 */
const tableResizeFrameStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'stretch',
}

/** 左栏:每行一个 ⇕ 把手,槽高与表格行实测高对齐(顶部让位顶栏高度)。 */
const rowGutterStyle: React.CSSProperties = {
  width: ROW_GUTTER_PX,
  flexShrink: 0,
  paddingTop: COL_GUTTER_PX,
}

/** 顶栏:裁剪视口(宽=表格区),内部平移层随横向滚动移动。 */
const colGutterClipStyle: React.CSSProperties = {
  height: COL_GUTTER_PX,
  overflow: 'hidden',
  position: 'relative',
}

/** 行把手:跨坐在行底缘(槽内绝对定位,居中)。 */
const rowHandleStyle: React.CSSProperties = {
  position: 'absolute',
  left: '50%',
  bottom: -Math.floor(ROW_HANDLE_H / 2),
  transform: 'translateX(-50%)',
  width: 16,
  height: ROW_HANDLE_H,
}

/** 列把手:跨坐在列右缘(平移层内绝对定位,left 按列边界计算)。 */
const colHandleStyle: React.CSSProperties = {
  position: 'absolute',
  top: '50%',
  transform: 'translateY(-50%)',
  width: COL_HANDLE_W,
  height: 16,
}

// ───────────────────── 表格外浮动工具栏 ─────────────────────

/** 工具栏高度(px):无把手栏时跨坐表格上缘(一半在上、一半盖住表头内边距区)。 */
const TABLE_TOOLBAR_H = 24
/** 复制结果反馈驻留时长(ms)。 */
const TOOLBAR_FEEDBACK_MS = 1600

/** 工具栏容器:绝对定位于表格框右上,不占布局(悬停/点击表头时由 TableBlock 渲染)。 */
const tableToolbarStyle: React.CSSProperties = {
  position: 'absolute',
  right: 0,
  display: 'flex',
  alignItems: 'center',
  gap: 2,
  height: TABLE_TOOLBAR_H,
  padding: '0 2px',
  borderRadius: 'var(--radius-md)',
  border: '1px solid var(--border-light)',
  background: 'var(--bg-secondary)',
  boxShadow: '0 4px 12px rgba(0, 0, 0, 0.18)',
  zIndex: 22,
}

/** hast 表格矩阵 → GFM 源码兜底重建(源 offset 缺失时使用;内联语法已退化为纯文本)。 */
function buildGfmTableSource(headers: string[], rows: string[][]): string {
  const escapeCell = (text: string) => text.replace(/\|/g, '\\|').replace(/\r?\n/g, ' ')
  const columnCount = Math.max(1, headers.length, ...rows.map((row) => row.length))
  const padRow = (cells: string[]) => Array.from(
    { length: columnCount },
    (_, index) => escapeCell(cells[index] ?? ''),
  )
  return [
    `| ${padRow(headers).join(' | ')} |`,
    `| ${Array.from({ length: columnCount }, () => '---').join(' | ')} |`,
    ...rows.map((row) => `| ${padRow(row).join(' | ')} |`),
  ].join('\n')
}

/** 剪贴板写入:优先异步 Clipboard API(需安全上下文),不可用/失败回退 execCommand 同步复制。 */
async function writeClipboardText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // 落入 execCommand 兜底。
  }
  try {
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.setAttribute('readonly', '')
    textarea.style.cssText = 'position:fixed;top:0;left:0;opacity:0;pointer-events:none'
    document.body.appendChild(textarea)
    textarea.select()
    const copied = document.execCommand('copy')
    textarea.remove()
    return copied
  } catch {
    return false
  }
}

/**
 * 表格源 markdown:源文本 offset 切片优先(保留内联语法原文,聊天场景由
 * MarkdownDisplay 注入 sourceText),切片条件不满足(position 缺失、未注入
 * sourceText)时按矩阵重建纯文本 GFM。
 */
function resolveTableSourceMarkdown(
  node: Element | undefined,
  sourceText: string | undefined,
  headers: string[],
  rows: string[][],
): string {
  const start = node?.position?.start?.offset
  const end = node?.position?.end?.offset
  if (sourceText != null && start != null && end != null && start >= 0 && start < end && end <= sourceText.length) {
    return sourceText.slice(start, end).replace(/\s+$/, '')
  }
  return buildGfmTableSource(headers, rows)
}

/**
 * 表格外浮动工具栏(绝对定位于表格框右上,不占布局):
 * - 复制内容:整表 markdown 源码写入剪贴板,按钮短暂切换「已复制/复制失败」反馈;
 * - 调整表格:切换表外把手栏(列宽 ↔ / 行高 ⇕),即旧版「单击表头」的调整入口。
 * 可见性由 TableBlock 驱动(表头单击固定 + 桌面端悬停),此处只承载操作。
 */
function TableToolbar(props: {
  node: Element | undefined
  sourceText: string | undefined
  headers: string[]
  rows: string[][]
  layout: TableLayoutController
  /** 把手栏当前实际可见(按钮高亮;把手栏在表格上方时工具栏整体让位上移)。 */
  handlesActive: boolean
  style?: React.CSSProperties
}) {
  const { node, sourceText, headers, rows, layout, handlesActive, style } = props
  const [feedback, setFeedback] = React.useState<'idle' | 'copied' | 'failed'>('idle')
  const feedbackTimer = React.useRef<number>(0)
  React.useEffect(() => () => window.clearTimeout(feedbackTimer.current), [])

  const handleCopy = React.useCallback(() => {
    const text = resolveTableSourceMarkdown(node, sourceText, headers, rows)
    void writeClipboardText(text).then((copied) => {
      setFeedback(copied ? 'copied' : 'failed')
      window.clearTimeout(feedbackTimer.current)
      feedbackTimer.current = window.setTimeout(() => setFeedback('idle'), TOOLBAR_FEEDBACK_MS)
    })
  }, [headers, node, rows, sourceText])

  const toggleHandles = React.useCallback(() => {
    layout.setHandlesVisible(!handlesActive)
  }, [handlesActive, layout])

  return (
    <div className="md-table-toolbar" role="toolbar" aria-label="表格工具栏" style={{ ...tableToolbarStyle, ...style }}>
      <button
        type="button"
        className="md-table-toolbar-button"
        onClick={handleCopy}
        title="复制表格内容(Markdown 源码)"
      >
        {feedback === 'copied' ? '✓ 已复制' : feedback === 'failed' ? '✗ 复制失败' : '⧉ 复制内容'}
      </button>
      <button
        type="button"
        className={`md-table-toolbar-button${handlesActive ? ' md-table-toolbar-button--active' : ''}`}
        onClick={toggleHandles}
        aria-pressed={handlesActive}
        title="调整表格(列宽/行高)"
      >
        ⇔ 调整表格
      </button>
    </div>
  )
}

/** 单元格编辑弹窗互斥注册表:后开的弹窗让先开的提交收口(同一时刻至多一弹窗)。 */
const cellDialogClosers = new Set<() => void>()

/**
 * 表格单元格编辑态(th/td 共用):双击进入弹窗编辑,Ctrl+Enter/「保存」提交,Esc/「取消」关闭。
 * 弹窗不随失焦或点击外部自动收口(显式操作才生效);双击其他单元格时旧弹窗自动提交收口。
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
  /** 弹窗宽度(px):按列宽自适应,打开瞬间测量一次;位置由 CSS 视口居中。 */
  const [dialogWidth, setDialogWidth] = React.useState(0)
  /** 宿主 td/th ref:弹窗宽度测量基准。 */
  const hostRef = React.useRef<HTMLTableCellElement | null>(null)
  const dialogInputRef = React.useRef<HTMLTextAreaElement | null>(null)
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
    // 弹窗互斥:先让别处已开的编辑弹窗提交收口,避免叠层(本格的 closer
    // 在 expanded 生效后才注册,此时尚未登记,不会自己关自己)。
    cellDialogClosers.forEach((close) => close())
    // 双击 = 直接弹层编辑:所有单元格统一弹窗交互,超高内容天然获得完整编辑区。
    // 宽度按列宽自适应(下限 420px,上限 80vw);测量在切换编辑态前同步执行
    // (此时 DOM 仍是原单元格,矩形有效)。位置由 CSS 视口居中,无需几何计算。
    const host = hostRef.current?.getBoundingClientRect() ?? null
    setDialogWidth(Math.round(Math.min(
      Math.max(host?.width ?? 0, CELL_DIALOG_MIN_WIDTH),
      window.innerWidth * 0.8,
    )))
    setDraft(original)
    setEditing(true)
    setExpanded(true)
    requestAnimationFrame(() => {
      const element = dialogInputRef.current
      if (element) {
        autoGrowTextArea(element)
        // 聚焦但不全选:全选态下随手一敲会整段覆盖原内容;光标置于末尾便于追加。
        element.focus()
        const end = element.value.length
        element.setSelectionRange(end, end)
      }
    })
  }, [original])

  const cancelEdit = React.useCallback(() => {
    setExpanded(false)
    setEditing(false)
    setDialogWidth(0)
  }, [])

  const commitEdit = React.useCallback(() => {
    // 编辑只有浮层一种形态:提交即整单收口(in-cell 已移除)。
    setExpanded(false)
    setEditing(false)
    setDialogWidth(0)
    if (range == null || onTableCellEdit == null) return
    const next = escapeTableCellText(draft)
    if (next === range.original) return
    onTableCellEdit({ start: range.start, end: range.end, original: range.original, next })
  }, [draft, onTableCellEdit, range])

  const handleDraftChange = React.useCallback((event: React.ChangeEvent<HTMLTextAreaElement>) => {
    setDraft(event.target.value)
  }, [])

  // 弹窗互斥登记:本格弹窗打开期间向注册表挂一个"提交收口"闭包,供其他单元格
  // beginEdit 时调用(保留"切到别的单元格 = 保存旧编辑"预期,也避免弹窗叠层)。
  // 注意不因失焦/点击弹窗外收口——误触外部(点别处、切窗口)不应丢编辑现场,
  // 只有 Esc/「取消」/Ctrl+Enter/「保存」显式操作才收口。
  React.useEffect(() => {
    if (!expanded) return
    const close = () => commitEdit()
    cellDialogClosers.add(close)
    return () => { cellDialogClosers.delete(close) }
  }, [commitEdit, expanded])

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
  // 视口居中由 cellDialogStyle(left/top 50% + translate)承担;宽度按列宽注入。
  const editor = editing && expanded && dialogWidth > 0 && typeof document !== 'undefined' ? (
    createPortal(
      <div
        style={{ ...cellDialogStyle, width: dialogWidth }}
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            event.preventDefault()
            cancelEdit()
          } else if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
            event.preventDefault()
            commitEdit()
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
          <button type="button" onClick={cancelEdit} style={cellDialogCancelButtonStyle}>
            取消
          </button>
          <button type="button" onClick={commitEdit} style={cellDialogSaveButtonStyle}>
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
    // 列理想宽以**实测**为主口径(克隆真实单元格量宽,自适应阅读字体/字号/
    // 缩放):挂载与字体就绪后测得并替换启发式估算(估算在比例字体上系统性
    // 偏宽——空格实际 ~0.3ch、CJK ≈ 1.8ch,而非估算的 1ch/2ch,这正是
    // "内容没撑满单元格"的根因)。node 引用在同一 parse 内稳定,作重测依赖。
    const [measuredIdealPx, setMeasuredIdealPx] = React.useState<number[]>([])
    React.useLayoutEffect(() => {
      const wrap = layout.wrapRef.current
      if (!wrap) return
      const apply = () => {
        const next = measureColumnIdealWidths(wrap)
        setMeasuredIdealPx((prev) => (
          next.length === prev.length && next.every((px, index) => px === prev[index]) ? prev : next
        ))
      }
      apply()
      if (document.fonts?.ready == null) return
      let cancelled = false
      void document.fonts.ready.then(() => {
        if (!cancelled) apply()
      })
      return () => { cancelled = true }
    }, [layout.wrapRef, node])

    const baseWidths = React.useMemo(
      () => allocateColumnWidths(headers, rows, layout.containerWidth, layout.chPx, measuredIdealPx),
      [headers, layout.chPx, layout.containerWidth, measuredIdealPx, rows],
    )
    const fallbackWidths = React.useMemo(
      () => (baseWidths.length === 0 ? computeFallbackColumnWidths(headers, rows) : []),
      [baseWidths.length, headers, rows],
    )

    // colgroup 为在流表与吸顶克隆共用:手调列用覆盖 px(两种口径都生效,
    // 未测得容器宽的 ch 兜底下也能手调),其余走初始分配(px)或 ch 兜底。
    const colWidthStyles = React.useMemo(() => {
      if (baseWidths.length > 0) {
        return applyColumnOverrides(baseWidths, layout.columnOverrides).map((px) => `${px}px`)
      }
      return fallbackWidths.map((ch, index) => {
        const override = layout.columnOverrides.get(index)
        return override != null ? `${override}px` : ch
      })
    }, [baseWidths, fallbackWidths, layout.columnOverrides])
    const colgroup = colWidthStyles.length > 0 ? (
      <colgroup>
        {colWidthStyles.map((width, index) => (
          <col key={`col-${index}`} style={{ width }} />
        ))}
      </colgroup>
    ) : null

    // 注入列/行索引(th→__columnIndex、td→__rowIndex):Td 的行高上限
    // (rowOverrides)需要知道自己属于哪一行。克隆 thead(吸顶)复用注入后的同一份。
    // Th/Td 为 buildMarkdownComponents 闭包常量(每次构建固定),随 children 一起
    // 进入 memo 即可;children 不变时二者引用也不变。
    const injectedChildren = React.useMemo(
      () => injectTableCellIndices(children, { headerCell: Th, bodyCell: Td }),
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

    // 表格外把手栏几何:实测行高/列宽/表头高(把手对齐与拖动原点);
    // 行列数变化(内容编辑)时重扫观测目标。
    const rescanKey = `${headers.length}|${rows.length}`
    const { geometry, colHandleLayerRef } = useTableGeometry(layout.wrapRef, rescanKey)
    // 列边界(累计列宽):顶栏 ↔ 把手的落点(每列右缘)。
    const colBoundaries = React.useMemo(() => {
      const boundaries: number[] = []
      let x = 0
      for (const width of geometry.colWidths) {
        x += width
        boundaries.push(x)
      }
      return boundaries
    }, [geometry.colWidths])
    // 把手栏渲染条件:几何已测得且用户已唤出(默认隐藏;无 RO 环境
    // 如 SSR 整栏不渲染,表格回到原生形态)。
    const guttersReady = layout.handlesVisible
      && (geometry.rowHeights.length > 0 || geometry.colWidths.length > 0)

    // 悬停显示工具栏:仅 hover 能力设备(桌面鼠标);触屏/笔只保留表头点击切换。
    const [hoverCapable] = React.useState(canHoverPointer)
    const handleFramePointerEnter = React.useCallback(() => layout.setToolbarHovering(true), [layout])
    const handleFramePointerLeave = React.useCallback(() => layout.setToolbarHovering(false), [layout])

    return (
      <TableResizeContext.Provider value={layout}>
        <HeaderEditingContext.Provider value={headerEditingTracker}>
          <div
            {...sourceLineData(node)}
            style={{ ...tableResizeFrameStyle, position: 'relative', margin: s.tableWrap.margin }}
            {...(hoverCapable ? {
              onPointerEnter: handleFramePointerEnter,
              onPointerLeave: handleFramePointerLeave,
            } : null)}
          >
            {/* 浮动工具栏(绝对定位表格框右上,不占布局):复制内容 + 调整表格。
                把手栏可见时整体让位上移,避开顶栏右缘的列宽把手。 */}
            {layout.toolbarVisible ? (
              <TableToolbar
                node={node}
                sourceText={sourceText}
                headers={headers}
                rows={rows}
                layout={layout}
                handlesActive={guttersReady}
                style={{
                  top: guttersReady
                    ? -(COL_GUTTER_PX + TABLE_TOOLBAR_H / 2 + 2)
                    : -(TABLE_TOOLBAR_H / 2 - 1),
                }}
              />
            ) : null}
            {/* 行高把手栏(表外左栏):每行底缘一个 ⇕,槽高与表格行实测高对齐。 */}
            {guttersReady ? (
              <div className="md-table-gutter" style={rowGutterStyle}>
                <div style={{ height: geometry.headerHeight }} />
                {geometry.rowHeights.map((height, rowIndex) => (
                  <div key={`row-${rowIndex}`} style={{ position: 'relative', height }}>
                    <span
                      role="separator"
                      aria-label="调整行高"
                      className="md-table-gutter-handle md-table-gutter-handle--row"
                      style={rowHandleStyle}
                      title="拖动调整行高，双击重置"
                      onPointerDown={(event) => { layout.beginRowResize(rowIndex, height, event) }}
                      onPointerMove={layout.dragHandlers.onPointerMove}
                      onPointerUp={layout.dragHandlers.onPointerUp}
                      onPointerCancel={layout.dragHandlers.onPointerCancel}
                      onDoubleClick={(event) => {
                        event.preventDefault()
                        layout.resetRow(rowIndex)
                      }}
                    >
                      ⇕
                    </span>
                  </div>
                ))}
              </div>
            ) : null}
            <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
              {/* 列宽把手栏(表外顶栏):每列右缘一个 ↔,平移层随横向滚动移动。 */}
              {guttersReady ? (
                <div className="md-table-gutter" style={colGutterClipStyle}>
                  <div
                    ref={colHandleLayerRef}
                    style={{ position: 'relative', width: geometry.tableWidth || '100%', height: '100%' }}
                  >
                    {colBoundaries.map((boundary, columnIndex) => (
                      <span
                        key={`col-${columnIndex}`}
                        role="separator"
                        aria-label="调整列宽"
                        className="md-table-gutter-handle md-table-gutter-handle--col"
                        style={{
                          ...colHandleStyle,
                          left: Math.max(0, Math.min(boundary - COL_HANDLE_W / 2, geometry.tableWidth - COL_HANDLE_W)),
                        }}
                        title="拖动调整列宽，双击重置"
                        onPointerDown={(event) => {
                          layout.beginColumnResize(
                            columnIndex,
                            geometry.colWidths[columnIndex] || MIN_COL_PX,
                            event,
                          )
                        }}
                        onPointerMove={layout.dragHandlers.onPointerMove}
                        onPointerUp={layout.dragHandlers.onPointerUp}
                        onPointerCancel={layout.dragHandlers.onPointerCancel}
                        onDoubleClick={(event) => {
                          event.preventDefault()
                          layout.resetColumn(columnIndex)
                        }}
                      >
                        ↔
                      </span>
                    ))}
                  </div>
                </div>
              ) : null}
              <div
                ref={wrapRef}
                {...pan.handlers}
                style={{
                  ...s.tableWrap,
                  margin: 0,
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
            </div>
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
                      重新包一层让克隆表头的单击切换把手同样生效。 */}
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
    const { children, align, node } = props
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

    // 单击表头 = 切换表外浮动工具栏(复制内容/调整表格);双击仍编辑单元格。
    const handleClick = React.useCallback(() => {
      if (resize == null || cell.editing) return
      resize.setToolbarPinned(!resize.toolbarPinned)
    }, [cell.editing, resize])

    return (
      <th
        ref={cell.hostRef}
        {...rest}
        style={{
          ...s.th,
          textAlign: (align ?? node?.properties?.align ?? 'left') as React.CSSProperties['textAlign'],
        }}
        onClick={handleClick}
        onDoubleClick={cell.editable && !cell.editing ? cell.beginEdit : undefined}
        title={cell.editable ? '点击显示/隐藏工具栏；双击编辑单元格' : '点击显示/隐藏工具栏'}
      >
        {children}
        {cell.editor}
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
    /** 行高上限:用户手调(表外左栏 ⇕ 把手)优先,默认 MAX_ROW_PX;编辑态同样生效。 */
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

    // 行高调整走表外左栏 ⇕ 把手,单元格自身只保留触屏双击编辑与内容截断。
    return (
      <td
        ref={cell.hostRef}
        {...rest}
        style={{
          ...s.td,
          textAlign: (align ?? node?.properties?.align) as React.CSSProperties['textAlign'],
        }}
        onPointerDown={cell.touchHandlers.onPointerDown}
        onPointerUp={cell.touchHandlers.onPointerUp}
        onDoubleClick={cell.editable && !cell.editing ? cell.beginEdit : undefined}
        title={plainText || undefined}
      >
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
