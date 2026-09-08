import React from 'react'

type LineNumberedSourceViewProps = {
  content: string
  editable?: boolean
  value?: string
  onChange?: (nextValue: string) => void
  wrapLines: boolean
  onWrapLinesChange?: (nextValue: boolean) => void
  lineNumber?: number
  lineLocateRequestedAt?: number
  onLineLocateApplied?: () => void
  scrollTargetRef?: React.MutableRefObject<HTMLDivElement | HTMLTextAreaElement | null>
  lineHeight?: number
  fontSize?: number
  fontFamily?: string
  padding?: string
  readonlyClassName?: string
  /** 文件内查找正则（带 g 标志）；可编辑态用它在 textarea 背后渲染高亮叠层。 */
  findRegex?: RegExp | null
  /** 当前命中序号（0-based），标亮「当前」匹配。 */
  findActiveIndex?: number
  /** 是否启用查找高亮叠层。 */
  findEnabled?: boolean
}

export default function LineNumberedSourceView({
  content,
  editable = false,
  value,
  onChange,
  wrapLines,
  onWrapLinesChange,
  lineNumber,
  lineLocateRequestedAt,
  onLineLocateApplied,
  scrollTargetRef,
  lineHeight = 1.8,
  fontSize = 14,
  fontFamily = '"Cascadia Code", "Fira Code", Consolas, monospace',
  padding = '20px 0',
  readonlyClassName,
  findRegex,
  findActiveIndex = 0,
  findEnabled = false,
}: LineNumberedSourceViewProps) {
  const highlightRef = React.useRef<HTMLDivElement | null>(null)
  // 仅可编辑态 + 查找打开时渲染高亮叠层（只读态由外层 useHighlightMatches 负责）。
  const showFindHighlight = editable && Boolean(findEnabled && findRegex)
  const displayContent = editable ? (value ?? '') : content
  const lines = React.useMemo(() => splitLines(displayContent), [displayContent])
  const gutterRef = React.useRef<HTMLDivElement | null>(null)
  const scrollRef = React.useRef<HTMLDivElement | HTMLTextAreaElement | null>(null)
  const measureRootRef = React.useRef<HTMLDivElement | null>(null)
  const [contentWidth, setContentWidth] = React.useState(0)
  const [visualLineHeights, setVisualLineHeights] = React.useState<number[]>([])
  const pendingLocateRef = React.useRef<number | null>(null)
  const baseLineHeightPx = lineHeight * fontSize
  const lineCount = Math.max(1, lines.length)
  const maxVisibleLineNumber = Math.max(lineCount, lineNumber ?? 0)
  const lineNumberWidth = `${Math.max(2, String(maxVisibleLineNumber).length + 1)}ch`

  // 每行在自动换行后实际占用的像素高度（未换行时等于单行高）。
  const lineOffsets = React.useMemo(() => {
    const offsets: number[] = []
    let currentOffset = 0
    for (let index = 0; index < lines.length; index += 1) {
      offsets.push(currentOffset)
      currentOffset += visualLineHeights[index] ?? baseLineHeightPx
    }
    return offsets
  }, [baseLineHeightPx, lines.length, visualLineHeights])

  // 每行折行后占几个「视觉行」：用于把行号拆成首行数字 + 后续空行号。
  const visualLineCounts = React.useMemo(
    () => visualLineHeights.map((height) => Math.max(1, Math.round(height / baseLineHeightPx))),
    [baseLineHeightPx, visualLineHeights],
  )

  // 按视觉行展开的行号块：逻辑行首行显示数字，自动换行产生的后续视觉行留空、不计数。
  // 渲染为单个 <pre> 的文本序列（行号 + 子行空行），避免超大文件为每行建一个 DOM 节点。
  // 当前行号包 <span> 单独着色，保留「定位行高亮」能力。
  const gutterNodes = React.useMemo(() => {
    const nodes: React.ReactNode[] = []
    for (let index = 0; index < lines.length; index += 1) {
      const count = visualLineCounts[index] ?? 1
      const isActiveLine = lineNumber === index + 1
      if (isActiveLine) {
        nodes.push(<span key={`num-${index}`} style={gutterActiveLineStyle}>{index + 1}</span>)
      } else {
        nodes.push(String(index + 1))
      }
      for (let sub = 1; sub < count; sub += 1) {
        // 自动换行产生的后续视觉行：空行号占一行。
        nodes.push('\n')
      }
      if (index < lines.length - 1) {
        nodes.push('\n')
      }
    }
    return nodes
  }, [lineNumber, lines.length, visualLineCounts])

  // 折行测量镜像层只渲染「确实会折行」的行：用 canvas.measureText 逐行预筛文本宽度，
  // 宽度超过正文可用宽度的行才进入测量层实测高度，其余行直接按单行高处理。
  // 等宽字体下 measureText 与浏览器排版一致，判定精确；空白断行等场景下预筛偏保守（多测无害）。
  const needMeasureRows = React.useMemo(() => {
    if (!wrapLines || contentWidth <= 0) return null
    const context = getMeasureContext()
    if (!context) return null
    context.font = `${fontSize}px ${fontFamily}`
    const threshold = contentWidth - parseHorizontalPadding(padding)
    const rows: number[] = []
    for (let index = 0; index < lines.length; index += 1) {
      const text = lines[index]
      if (text && context.measureText(text).width > threshold) {
        rows.push(index)
      }
    }
    return rows
  }, [contentWidth, fontFamily, fontSize, lines, padding, wrapLines])

  React.useLayoutEffect(() => {
    const node = scrollRef.current
    if (!node) return

    const updateWidth = () => {
      setContentWidth(node.clientWidth)
    }

    updateWidth()

    if (typeof ResizeObserver !== 'undefined') {
      const resizeObserver = new ResizeObserver(updateWidth)
      resizeObserver.observe(node)
      return () => {
        resizeObserver.disconnect()
      }
    }

    window.addEventListener('resize', updateWidth, { passive: true })
    return () => {
      window.removeEventListener('resize', updateWidth)
    }
  }, [editable, wrapLines])

  React.useEffect(() => {
    if (lineLocateRequestedAt == null || lineNumber == null) return
    pendingLocateRef.current = lineNumber
    if (wrapLines && onWrapLinesChange) {
      onWrapLinesChange(false)
      return
    }
    scrollToLine(scrollRef.current, gutterRef.current, lineNumber, lineOffsets)
    onLineLocateApplied?.()
  }, [baseLineHeightPx, lineLocateRequestedAt, lineNumber, lineOffsets, onWrapLinesChange, wrapLines])

  React.useEffect(() => {
    if (pendingLocateRef.current == null) return
    scrollToLine(scrollRef.current, gutterRef.current, pendingLocateRef.current, lineOffsets)
    pendingLocateRef.current = null
    onLineLocateApplied?.()
  }, [baseLineHeightPx, contentWidth, lineOffsets, wrapLines])

  React.useLayoutEffect(() => {
    // 未换行 / 容器未就绪 / 没有折行行时：所有行按单行高处理，不建测量镜像层。
    if (!wrapLines || contentWidth <= 0 || needMeasureRows === null || needMeasureRows.length === 0) {
      setVisualLineHeights(() => lines.map(() => baseLineHeightPx))
      return
    }

    const root = measureRootRef.current
    if (!root) return

    // 只对预筛出的折行行读实际高度；其余行保持单行高。
    // 保留精确浮点高度，不要取整：行高通常为小数（如 25.2px），
    // 逐行 round 会让 gutter 总高相对内容持续下漂，文件越长错位越明显。
    const nextHeights = lines.map(() => baseLineHeightPx)
    needMeasureRows.forEach((rowIndex, j) => {
      const child = root.children[j] as HTMLElement | undefined
      if (!child) return
      const height = child.getBoundingClientRect().height
      nextHeights[rowIndex] = Math.max(1, height)
    })

    setVisualLineHeights((current) => (areNumberArraysEqual(current, nextHeights) ? current : nextHeights))
  }, [baseLineHeightPx, contentWidth, lines, wrapLines, displayContent, fontFamily, fontSize, lineHeight, padding, needMeasureRows])

  const measureNode =
    wrapLines && contentWidth > 0 && needMeasureRows !== null && needMeasureRows.length > 0 ? (
      <div
        ref={measureRootRef}
        aria-hidden="true"
        style={{
          ...measureRootStyle,
          ...sharedWrapStyles(true),
          width: contentWidth,
          padding,
          fontSize: fontSize,
          lineHeight,
          fontFamily,
        }}
      >
        {needMeasureRows.map((rowIndex) => (
          <div
            key={rowIndex}
            style={{
              ...measureLineStyle,
              fontSize: fontSize,
              lineHeight,
              fontFamily,
            }}
          >
            {lines[rowIndex] || '\u200b'}
          </div>
        ))}
      </div>
    ) : null

  if (editable) {
    return (
      <div style={{ ...rootStyle, background: inputBackground }}>
        <div ref={gutterRef} style={{ ...gutterStyle, minWidth: lineNumberWidth, padding }}>
          <pre style={{ ...gutterPreStyle, fontSize: fontSize, lineHeight, fontFamily }}>{gutterNodes}</pre>
        </div>
        <div style={editorAreaStyle}>
          {showFindHighlight && (
            <div
              ref={highlightRef}
              aria-hidden="true"
              data-find-overlay=""
              style={{
                ...highlightLayerStyle,
                padding,
                ...sharedWrapStyles(wrapLines),
                fontSize: fontSize,
                lineHeight,
                fontFamily,
              }}
            >
              <pre style={{ ...highlightPreStyle, ...sharedWrapStyles(wrapLines) }}>
                {buildFindHighlightNodes(displayContent, findRegex ?? null, findActiveIndex)}
              </pre>
            </div>
          )}
          <textarea
            ref={(node) => {
              scrollRef.current = node
              if (scrollTargetRef) {
                scrollTargetRef.current = node
              }
            }}
            value={value ?? ''}
            onChange={(event) => onChange?.(event.target.value)}
            onScroll={(event) => {
              syncGutter(event.currentTarget, gutterRef.current)
              syncHighlightScroll(event.currentTarget, highlightRef.current)
            }}
            wrap={wrapLines ? 'soft' : 'off'}
            spellCheck={false}
            style={{
              ...editorStyle,
              position: 'absolute',
              top: 0,
              left: 0,
              right: 0,
              bottom: 0,
              flex: 'none',
              width: 'auto',
              height: 'auto',
              padding,
              ...sharedWrapStyles(wrapLines),
              overflowX: wrapLines ? 'hidden' : 'auto',
              fontSize: fontSize,
              lineHeight,
              fontFamily,
              background: showFindHighlight ? 'transparent' : inputBackground,
              // 与高亮叠层同步预留滚动条槽位，避免内容超长时两侧 soft-wrap 换行宽度不同。
              scrollbarGutter: 'stable',
            }}
          />
          {measureNode}
        </div>
      </div>
    )
  }

  return (
    <div style={rootStyle}>
      <div ref={gutterRef} style={{ ...gutterStyle, minWidth: lineNumberWidth, padding }}>
        <pre style={{ ...gutterPreStyle, fontSize: fontSize, lineHeight, fontFamily }}>{gutterNodes}</pre>
      </div>
      <div
        ref={(node) => {
          scrollRef.current = node
          if (scrollTargetRef) {
            scrollTargetRef.current = node
          }
        }}
        onScroll={(event) => syncGutter(event.currentTarget, gutterRef.current)}
        className={readonlyClassName}
        style={{
          ...readonlyScrollStyle,
          padding,
          overflowX: wrapLines ? 'hidden' : 'auto',
        }}
      >
        <pre
          style={{
            ...readonlyPreStyle,
            ...sharedWrapStyles(wrapLines),
            fontSize: fontSize,
            lineHeight,
            fontFamily,
          }}
        >
          {content}
        </pre>
      </div>
      {measureNode}
    </div>
  )
}

function syncGutter(source: HTMLElement, gutter: HTMLDivElement | null) {
  if (!gutter) return
  gutter.scrollTop = source.scrollTop
}

/** 把可编辑高亮叠层（位于 textarea 背后）的滚动对齐到 textarea，保证命中标记始终压在正文字下。 */
function syncHighlightScroll(
  source: HTMLTextAreaElement,
  layer: HTMLDivElement | null,
) {
  if (!layer) return
  layer.scrollTop = source.scrollTop
  layer.scrollLeft = source.scrollLeft
}

function scrollToLine(
  source: HTMLElement | HTMLTextAreaElement | null,
  gutter: HTMLDivElement | null,
  lineNumber: number,
  lineOffsets: number[],
) {
  if (!source) return
  const targetTop = Math.max(0, Math.round((lineOffsets[lineNumber - 1] ?? 0) - 24))
  if ('scrollTo' in source && typeof source.scrollTo === 'function') {
    source.scrollTo({ top: targetTop, behavior: 'smooth' })
  } else {
    source.scrollTop = targetTop
  }
  if (gutter) {
    gutter.scrollTop = targetTop
  }
}

function splitLines(content: string): string[] {
  const normalized = content.replace(/\r\n/g, '\n')
  return normalized ? normalized.split('\n') : ['']
}

/** 复用的 canvas 2d context（measureText 预筛折行行用），避免反复创建 canvas。 */
let sharedMeasureContext: CanvasRenderingContext2D | null = null

function getMeasureContext(): CanvasRenderingContext2D | null {
  if (!sharedMeasureContext) {
    sharedMeasureContext = document.createElement('canvas').getContext('2d')
  }
  return sharedMeasureContext
}

/** 解析 CSS padding 简写，返回左右方向合计像素（px）；无法解析时返回 0。 */
function parseHorizontalPadding(padding: string): number {
  const parts = padding.trim().split(/\s+/).map((part) => parseFloat(part)).filter((value) => !Number.isNaN(value))
  if (parts.length === 0) return 0
  if (parts.length === 1) return parts[0] * 2
  if (parts.length === 2) return parts[1] * 2
  if (parts.length === 3) return parts[1] * 2
  return parts[1] + parts[3]
}

/** 可编辑高亮叠层单次渲染最多包裹的 <mark> 数，避免超大文件撑爆 DOM。 */
const FIND_MAX_MARKS = 2000

/** 普通命中高亮样式（叠层文字透明，仅背景透出到 textarea 之下）。 */
const FIND_MARK_BASE: React.CSSProperties = { background: 'var(--accent-amber-dim)', borderRadius: 2, padding: 0 }
/** 当前命中高亮样式（强调色，压在光标附近当前命中处）。 */
const FIND_MARK_ACTIVE: React.CSSProperties = { background: 'var(--accent-amber)', borderRadius: 2, padding: 0 }

/**
 * 由源文本 + 查找正则构造高亮叠层节点：命中片段包成 <mark>，其余为纯文本。
 * 叠层文字整体透明（由外层 highlightLayerStyle 设置），只让 <mark> 背景透到 textarea 之下，
 * 因此与 textarea 自身文字叠加后呈现「高亮」效果，且不影响编辑。
 * 返回 React 节点数组（无命中时回退为单段纯文本）。
 */
function buildFindHighlightNodes(
  text: string,
  regex: RegExp | null,
  activeIndex: number,
): React.ReactNode {
  if (!regex) return text
  const global = new RegExp(regex.source, regex.flags.includes('g') ? regex.flags : `${regex.flags}g`)
  const nodes: React.ReactNode[] = []
  let last = 0
  let produced = 0
  let key = 0
  let match: RegExpExecArray | null
  while ((match = global.exec(text)) !== null) {
    if (produced >= FIND_MAX_MARKS) break
    const start = match.index
    if (start > last) {
      nodes.push(text.slice(last, start))
    }
    const isActive = produced === activeIndex
    nodes.push(
      <mark key={`find-${key}`} style={isActive ? FIND_MARK_ACTIVE : FIND_MARK_BASE}>
        {match[0]}
      </mark>,
    )
    produced += 1
    key += 1
    last = start + match[0].length
    // 防止零宽匹配死循环。
    if (match.index === global.lastIndex) {
      global.lastIndex += 1
    }
  }
  if (last < text.length) {
    nodes.push(text.slice(last))
  }
  return nodes
}

function areNumberArraysEqual(left: number[], right: number[]): boolean {
  if (left.length !== right.length) return false
  return left.every((value, index) => value === right[index])
}

// 测量节点与真实渲染节点（<pre> / <textarea>）必须共用同一份换行样式，
// 否则两侧对长行的软换行视觉行数不同，gutter 行号会相对内容逐行累积漂移。
// 移动端屏幕窄、软换行频繁，差异被放大，表现为「越往下行号越对不上」。
function sharedWrapStyles(wrap: boolean): React.CSSProperties {
  return wrap
    ? { whiteSpace: 'pre-wrap', wordBreak: 'break-word', overflowWrap: 'anywhere' }
    : { whiteSpace: 'pre', wordBreak: 'normal', overflowWrap: 'normal' }
}

const inputBackground = 'var(--bg-secondary)'

const inactiveLineNumberColor = 'color-mix(in srgb, var(--text-muted) 50%, transparent)'

/** 行号槽位：单 <pre> 承载全部行号文本（含折行子行空行），超大文件不逐行建 DOM。 */
const gutterPreStyle: React.CSSProperties = {
  margin: 0,
  boxSizing: 'border-box',
  whiteSpace: 'pre',
  overflow: 'hidden',
  textAlign: 'right',
  color: inactiveLineNumberColor,
  userSelect: 'none',
  paddingRight: 14,
}

/** 当前定位行号：单 <pre> 内的行内 <span> 着色（userSelect 不继承自 pre，需自带以被查找高亮跳过）。 */
const gutterActiveLineStyle: React.CSSProperties = {
  color: 'var(--accent-blue)',
  fontWeight: 600,
  userSelect: 'none',
}

const rootStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  minWidth: 0,
  display: 'flex',
  overflow: 'hidden',
  position: 'relative',
}

/** 可编辑区定位容器：文本框与高亮叠层都绝对铺满它，叠层在文本框之下。 */
const editorAreaStyle: React.CSSProperties = {
  position: 'relative',
  flex: 1,
  minWidth: 0,
  minHeight: 0,
}

/** 高亮叠层：与 textarea 共用字体 / 行高 / 内边距 / 换行，文字透明，命中处以 <mark> 背景透出。 */
const highlightLayerStyle: React.CSSProperties = {
  position: 'absolute',
  inset: 0,
  overflow: 'hidden',
  pointerEvents: 'none',
  boxSizing: 'border-box',
  background: inputBackground,
  color: 'transparent',
  margin: 0,
  // 与 textarea 同步预留滚动条槽位：当内容超长出现垂直滚动条时，
  // 两侧内容区宽度保持一致，避免 soft-wrap 换行点不同导致高亮 Y 方向错位。
  scrollbarGutter: 'stable',
}

const highlightPreStyle: React.CSSProperties = {
  margin: 0,
  boxSizing: 'border-box',
}

const gutterStyle: React.CSSProperties = {
  flexShrink: 0,
  overflow: 'hidden',
  background: inputBackground,
  color: 'var(--text-muted)',
  fontFamily: '"Cascadia Code", "Fira Code", Consolas, monospace',
  boxSizing: 'border-box',
}

const editorStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  minHeight: 0,
  width: '100%',
  height: '100%',
  border: 'none',
  outline: 'none',
  resize: 'none',
  background: inputBackground,
  color: 'var(--text-primary)',
  boxSizing: 'border-box',
  overflowY: 'auto',
}

const readonlyScrollStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  minHeight: 0,
  overflowY: 'auto',
  boxSizing: 'border-box',
}

const readonlyPreStyle: React.CSSProperties = {
  margin: 0,
  color: 'var(--text-primary)',
  boxSizing: 'border-box',
}

const measureRootStyle: React.CSSProperties = {
  position: 'absolute',
  left: 0,
  top: 0,
  visibility: 'hidden',
  pointerEvents: 'none',
  zIndex: -1,
  boxSizing: 'border-box',
  overflow: 'hidden',
}

const measureLineStyle: React.CSSProperties = {
  width: '100%',
  boxSizing: 'border-box',
}
