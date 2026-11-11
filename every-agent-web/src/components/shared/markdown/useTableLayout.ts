import React from 'react'

/**
 * 表格布局引擎(Excel/AG Grid 式):
 * - 列宽:内容理想宽(每列最宽单元格)钳制 [64px, 容器宽 20%] → 总宽不足容器时
 *   同比放大撑满,超出则横向滚动;用户手调列 px 覆盖(其余列不动,Excel 式);
 * - 行高:内容默认上限 96px(约 4 行)裁剪,用户拖行边界覆盖;
 * - 手调数据仅存 React state(内存态),刷新/卸载即失。
 *
 * 交互(th/td 边缘热区,无需额外把手元素):
 * - th 右缘 5px 拖动 = 调列宽;td/th 底缘 4px 拖动 = 调行高;
 * - 双击热区 = 重置该列/行为自动;拖动期间 pointer capture 跟踪。
 */

/** CJK/全角类字符集:渲染宽度约为拉丁字符 2 倍,列宽估算按 2 计。 */
const WIDE_CHAR = /[\u1100-\u115f\u2e80-\u303f\u3041-\u33ff\u3400-\u4dbf\u4e00-\u9fff\ua000-\ua4cf\uac00-\ud7a3\uf900-\ufaff\ufe30-\ufe4f\uff00-\uff60\uffe0-\uffe6]/

/** 单元格视觉长度:CJK/全角计 2,其余计 1(与 ch 单位的宽度比例近似)。 */
function visualCellLength(text: string): number {
  let length = 0
  for (const char of text) {
    length += WIDE_CHAR.test(char) ? 2 : 1
  }
  return length
}

/** 统计列宽前剥离内联 markdown 语法（图片取 alt），避免语法符号干扰字符数占比。 */
function stripMarkdownInlineSyntax(text: string): string {
  return text
    .replace(/!\[([^\]]*)\]\([^)]+\)/g, '$1')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/`([^`]+)`/g, '$1')
}

/** 列宽下限(ch):短列(如二字表头)也能舒适容纳内容与内边距,不至于被压扁。 */
const MIN_COLUMN_CH = 10
/** 列宽上限(ch):单列过宽严重伤可读性,超出的内容换行。60ch ≈ 30 个中文字。 */
const MAX_COLUMN_CH = 60
/** 单元格左右内边距折算(10px 12px × 2 ≈ 24px,按 ch 近似)。 */
const COLUMN_PAD_CH = 3

/** 行高初始上限(px,约 4 行正文):超出裁剪,用户可拖行边界放大。 */
export const MAX_ROW_PX = 96

/** 列宽下限(px):手调与分配共用的绝对下限,防止列被拖没。 */
export const MIN_COL_PX = 64

/** 每列理想宽(ch 单位,内容口径):max 单元格视觉长 + 内边距,钳 [10ch, 60ch]。 */
function idealColumnCh(headers: string[], rows: string[][]): number[] {
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
  return maxLens.map((maxLen) => Math.min(MAX_COLUMN_CH, Math.max(MIN_COLUMN_CH, maxLen + COLUMN_PAD_CH)))
}

/**
 * 兜底列宽(ch 字符串,含 100vw 钳制):容器宽度/字体度量未测得时使用
 * (SSR、首帧),声明式由浏览器解析。
 */
export function computeFallbackColumnWidths(headers: string[], rows: string[][]): string[] {
  return idealColumnCh(headers, rows).map((ch) => `min(${ch}ch, 100vw)`)
}

/**
 * 初始列宽分配(px 数值,主路径):
 * 1. 每列理想宽 = idealCh × 1ch 实测 px,钳 [MIN_COL_PX, 容器宽 20%];
 * 2. Σ理想宽 < 容器宽 → 同比放大撑满(比例不变;放大后单列可超 20%,
 *    否则永远填不满——20% 上限只约束内容估算法,不约束撑满);
 * 3. Σ理想宽 ≥ 容器宽 → 维持理想宽,表格横向滚动。
 * containerWidth / chPx 无效时返回 [](调用方走 ch 兜底)。
 */
export function allocateColumnWidths(
  headers: string[],
  rows: string[][],
  containerWidth: number,
  chPx: number,
): number[] {
  const columnCount = headers.length
  if (columnCount === 0) return []
  if (!Number.isFinite(containerWidth) || containerWidth <= 0) return []
  if (!Number.isFinite(chPx) || chPx <= 0) return []

  const maxCol = Math.max(MIN_COL_PX, containerWidth * 0.2)
  const ideal = idealColumnCh(headers, rows).map((ch) =>
    Math.min(maxCol, Math.max(MIN_COL_PX, ch * chPx)))

  const sum = ideal.reduce((acc, value) => acc + value, 0)
  if (sum < containerWidth) {
    const scale = containerWidth / sum
    return ideal.map((value) => Math.round(value * scale))
  }
  return ideal.map((value) => Math.round(value))
}

/** 用户手调列宽合并(px):手调列用覆盖值,其余列维持初始分配不动(Excel 式)。 */
export function applyColumnOverrides(base: number[], overrides: Map<number, number>): number[] {
  return base.map((width, index) => overrides.get(index) ?? width)
}

/** 测量 1ch 的像素宽(以 wrap 的字体为准;失败回退 0.5em 近似值)。 */
function measureChPx(host: HTMLElement): number {
  const probe = document.createElement('span')
  probe.style.cssText = 'position:absolute;visibility:hidden;width:1ch;white-space:nowrap'
  host.appendChild(probe)
  const px = probe.getBoundingClientRect().width
  probe.remove()
  if (px > 0) return px
  const fontSize = Number.parseFloat(window.getComputedStyle(host).fontSize || '14')
  return fontSize / 2
}

type DragState = {
  kind: 'column' | 'row'
  index: number
  originPx: number
  startPoint: number
  pointerId: number
}

/** 表格调整回调(经 context 注入 Th/Td)。 */
export type TableResizeController = {
  /** th 右缘热区按下:开始列宽拖动。 */
  beginColumnResize: (columnIndex: number, event: React.PointerEvent<HTMLElement>) => void
  /** th/td 底缘热区按下:开始行高拖动。 */
  beginRowResize: (rowIndex: number, event: React.PointerEvent<HTMLElement>) => void
  /** 双击热区:重置该列/行为自动。 */
  resetColumn: (columnIndex: number) => void
  resetRow: (rowIndex: number) => void
  /** 拖动进行中的 move/up/cancel(挂到热区宿主元素,capture 后事件都来这)。 */
  dragHandlers: {
    onPointerMove: (event: React.PointerEvent<HTMLElement>) => void
    onPointerUp: (event: React.PointerEvent<HTMLElement>) => void
    onPointerCancel: (event: React.PointerEvent<HTMLElement>) => void
  }
}

export type TableLayoutController = TableResizeController & {
  /** 表格包裹层 ref(测量载体,与 useTablePan 共用)。 */
  wrapRef: React.MutableRefObject<HTMLDivElement | null>
  /** 容器宽度(px,ResizeObserver 维护;0=未测得)。 */
  containerWidth: number
  /** 1ch 实测像素宽(0=未测得)。 */
  chPx: number
  /** 手调列宽(列 idx → px)。 */
  columnOverrides: Map<number, number>
  /** 手调行高(行 idx → max-height px)。 */
  rowOverrides: Map<number, number>
}

/** 列宽热区宽(px):th 右缘命中即列拖动。 */
export const COLUMN_EDGE_PX = 5
/** 行高热区高(px):td/th 底缘命中即行拖动。 */
export const ROW_EDGE_PX = 4

export function useTableLayout(): TableLayoutController {
  const wrapRef = React.useRef<HTMLDivElement | null>(null)
  const [containerWidth, setContainerWidth] = React.useState(0)
  const [chPx, setChPx] = React.useState(0)
  const [columnOverrides, setColumnOverrides] = React.useState(() => new Map<number, number>())
  const [rowOverrides, setRowOverrides] = React.useState(() => new Map<number, number>())
  const dragRef = React.useRef<DragState | null>(null)

  // 容器宽度:ResizeObserver 持续跟踪(窗口缩放/布局变化)。
  React.useEffect(() => {
    const wrap = wrapRef.current
    if (!wrap || typeof ResizeObserver === 'undefined') return
    const update = () => setContainerWidth(wrap.clientWidth)
    update()
    const observer = new ResizeObserver(update)
    observer.observe(wrap)
    return () => observer.disconnect()
  }, [])

  // 1ch 度量:挂载后测一次,字体就绪后重测(webfont 晚加载会改变字宽)。
  React.useLayoutEffect(() => {
    const wrap = wrapRef.current
    if (!wrap) return
    setChPx(measureChPx(wrap))
    if (document.fonts?.ready == null) return
    let cancelled = false
    void document.fonts.ready.then(() => {
      if (!cancelled) setChPx(measureChPx(wrap))
    })
    return () => {
      cancelled = true
    }
  }, [])

  const beginColumnResize = React.useCallback((columnIndex: number, event: React.PointerEvent<HTMLElement>) => {
    if (event.button !== 0) return
    const host = event.currentTarget
    dragRef.current = {
      kind: 'column',
      index: columnIndex,
      originPx: host.getBoundingClientRect().width,
      startPoint: event.clientX,
      pointerId: event.pointerId,
    }
    host.setPointerCapture(event.pointerId)
    event.preventDefault()
  }, [])

  const beginRowResize = React.useCallback((rowIndex: number, event: React.PointerEvent<HTMLElement>) => {
    if (event.button !== 0) return
    const host = event.currentTarget
    dragRef.current = {
      kind: 'row',
      index: rowIndex,
      originPx: host.getBoundingClientRect().height,
      startPoint: event.clientY,
      pointerId: event.pointerId,
    }
    host.setPointerCapture(event.pointerId)
    event.preventDefault()
  }, [])

  const onPointerMove = React.useCallback((event: React.PointerEvent<HTMLElement>) => {
    const drag = dragRef.current
    if (!drag || drag.pointerId !== event.pointerId) return
    const delta = drag.kind === 'column'
      ? event.clientX - drag.startPoint
      : event.clientY - drag.startPoint
    const next = Math.max(drag.kind === 'column' ? MIN_COL_PX : 24, Math.round(drag.originPx + delta))
    if (drag.kind === 'column') {
      setColumnOverrides((prev) => new Map(prev).set(drag.index, next))
    } else {
      setRowOverrides((prev) => new Map(prev).set(drag.index, next))
    }
  }, [])

  const endDrag = React.useCallback((event: React.PointerEvent<HTMLElement>) => {
    const drag = dragRef.current
    if (!drag || drag.pointerId !== event.pointerId) return
    dragRef.current = null
    const host = event.currentTarget
    if (host.hasPointerCapture?.(event.pointerId)) {
      host.releasePointerCapture(event.pointerId)
    }
  }, [])

  const resetColumn = React.useCallback((columnIndex: number) => {
    setColumnOverrides((prev) => {
      if (!prev.has(columnIndex)) return prev
      const next = new Map(prev)
      next.delete(columnIndex)
      return next
    })
  }, [])

  const resetRow = React.useCallback((rowIndex: number) => {
    setRowOverrides((prev) => {
      if (!prev.has(rowIndex)) return prev
      const next = new Map(prev)
      next.delete(rowIndex)
      return next
    })
  }, [])

  return {
    wrapRef,
    containerWidth,
    chPx,
    columnOverrides,
    rowOverrides,
    beginColumnResize,
    beginRowResize,
    resetColumn,
    resetRow,
    dragHandlers: {
      onPointerMove,
      onPointerUp: endDrag,
      onPointerCancel: endDrag,
    },
  }
}
