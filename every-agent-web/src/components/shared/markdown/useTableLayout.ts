import React from 'react'

/**
 * 表格布局引擎(Excel/AG Grid 式):
 * - 列宽:内容理想宽(每列最宽单元格)钳 (64px, 容器宽 20%] → 总宽不足容器时
 *   富余优先补给被上限钳掉的列(按缺口比例),仍有富余再按内容占比分;
 *   超出容器则横向滚动;用户手调列 px 覆盖(其余列不动,Excel 式);
 * - 行高:内容默认上限 96px(约 4 行)裁剪,用户拖行边界覆盖;
 * - 手调数据仅存 React state(内存态),刷新/卸载即失。
 *
 * 交互(表格外把手栏,不侵入单元格):
 * - 表头单击切换的是**浮动工具栏**(复制内容/调整表格),桌面端悬停表格亦显示工具栏;
 * - 工具栏「调整表格」按钮切换把手栏(= 旧版表头单击行为);工具栏不可见时把手栏连带收起;
 * - 把手栏:顶栏每列右缘一个 ↔ 把手(TableBlock 渲染),拖动调列宽,双击重置;
 *   左栏每行底缘一个 ⇕ 把手,拖动调行高,双击重置;
 * - 点击表格的非表头区域(单元格/表外)与表外区域自动收起工具栏与把手栏(capture 监听);
 * - 拖动期间 pointer capture 跟踪;把手几何由 useTableGeometry 实测对齐。
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

/** 列宽上限(ch):单列过宽严重伤可读性,超出的内容换行。60ch ≈ 30 个中文字。 */
const MAX_COLUMN_CH = 60
/** 单元格左右内边距折算(10px 12px × 2 ≈ 24px,按 ch 近似)。 */
const COLUMN_PAD_CH = 3

/** 行高初始上限(px,约 4 行正文):超出裁剪,用户可拖行边界放大。 */
export const MAX_ROW_PX = 96

/** 手调列宽下限(px):拖动把手能缩到的最小宽;120px ≈ 6 个中文字(14px 字号),再窄不可读。
 *  只约束用户手调——自动分配的短内容列按内容收紧,不受此限(见 MIN_COMPACT_COL_PX)。 */
export const MIN_COL_PX = 120

/** 自动分配的绝对下限(px):内容理想宽不足此值的列按内容收紧,只保 64px 兜底
 *  (≈ 3 个中文字 + 内边距)。短内容列(如"默认值"列只有 true/30)若被通用
 *  下限垫肥,会凭空挤占长内容列的宽度——换行可读性下限只对"内容会被换行的列"
 *  有意义,内容本就一行放得下的列再垫底限纯属浪费。 */
const MIN_COMPACT_COL_PX = 64

/** 每列理想宽(ch 单位,内容口径):max 单元格视觉长 + 内边距,封顶 60ch。
 *  不设 ch 下限:短内容列按内容收紧即可(15ch 下限会把 true/30 这类列凭空垫肥)。 */
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
  return maxLens.map((maxLen) => Math.min(MAX_COLUMN_CH, maxLen + COLUMN_PAD_CH))
}

/**
 * 兜底列宽(ch 字符串,含 100vw 钳制):容器宽度/字体度量未测得时使用
 * (SSR、首帧),声明式由浏览器解析。
 */
export function computeFallbackColumnWidths(headers: string[], rows: string[][]): string[] {
  return idealColumnCh(headers, rows).map((ch) => `min(${ch}ch, 100vw)`)
}

/**
 * 初始列宽分配(px 数值,主路径),两段式:
 * 1. 每列内容理想宽 rawPx = idealCh × 1ch 实测 px,钳 (MIN_COMPACT_COL_PX, 容器宽 20%]
 *    ——短内容列按内容收紧(不被 120px 通用下限垫肥);
 * 2. Σ钳制宽 < 容器宽 → 富余宽度两段分配:
 *    a. 优先按缺口(理想宽 − 钳制宽)补给被 20% 上限钳掉的列——它们的 content
 *       还没放完,最需要宽度;缺口全补平后仍有富余,再按理想宽占比分给所有列
 *       (内容多的列多吸收,短内容列保持紧凑);放大后单列可超 20%;
 * 3. Σ钳制宽 ≥ 容器宽 → 维持钳制宽,表格横向滚动。
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
  const raw = idealColumnCh(headers, rows).map((ch) => ch * chPx)
  const capped = raw.map((px) => Math.max(MIN_COMPACT_COL_PX, Math.min(maxCol, px)))

  const sum = capped.reduce((acc, value) => acc + value, 0)
  if (sum >= containerWidth) {
    return capped.map((value) => Math.round(value))
  }

  let slack = containerWidth - sum
  // 第一段:按缺口补给被上限钳掉的列(理想宽没放完的列优先吃富余)。
  const deficit = raw.map((px, index) => Math.max(0, px - capped[index]))
  const totalDeficit = deficit.reduce((acc, value) => acc + value, 0)
  let widths = capped
  if (totalDeficit > 0) {
    const fill = Math.min(1, slack / totalDeficit)
    widths = capped.map((value, index) => value + deficit[index] * fill)
    slack -= totalDeficit * fill
  }
  // 第二段:缺口补平后仍有富余(Σ理想宽 < 容器宽),按理想宽占比分配——
  // 内容多的列多吸收,短内容列保持紧凑。raw 恒 ≥ 3ch > 0,无除零。
  if (slack > 0) {
    const totalRaw = raw.reduce((acc, value) => acc + value, 0)
    widths = widths.map((value, index) => value + (slack * raw[index]) / totalRaw)
  }
  return widths.map((value) => Math.round(value))
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

/** 表格调整回调(经 context 注入,表格外把手栏消费)。 */
export type TableResizeController = {
  /** 列宽把手按下:以当前列宽(originWidthPx,实测口径)为原点开始拖动。 */
  beginColumnResize: (columnIndex: number, originWidthPx: number, event: React.PointerEvent<HTMLElement>) => void
  /** 行高把手按下:以当前行高(originHeightPx,实测口径)为原点开始拖动。 */
  beginRowResize: (rowIndex: number, originHeightPx: number, event: React.PointerEvent<HTMLElement>) => void
  /** 双击把手:重置该列/行为自动。 */
  resetColumn: (columnIndex: number) => void
  resetRow: (rowIndex: number) => void
  /** 拖动进行中的 move/up/cancel(挂到把手元素,capture 后事件都来这)。 */
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
  /** 表外把手栏可见性:默认隐藏;经工具栏「调整表格」按钮切换,点表外非表头区域收起。 */
  handlesVisible: boolean
  setHandlesVisible: (visible: boolean) => void
  /** 表格外浮动工具栏可见性:点击表头固定(pinned)或桌面端悬停(hovering)。 */
  toolbarVisible: boolean
  /** 工具栏固定态:表头单击切换;false 且无悬停时工具栏隐藏并连带收起把手栏。 */
  toolbarPinned: boolean
  setToolbarPinned: (pinned: boolean) => void
  /** 悬停态:仅 hover 能力设备由 TableBlock 挂载 pointerenter/leave 驱动。 */
  setToolbarHovering: (hovering: boolean) => void
}

/**
 * 悬停能力检测(桌面鼠标口径):hover + fine pointer 才挂工具栏悬停显示;
 * 触屏/笔设备无 hover 语义,只保留表头点击切换。SSR 无 window 时恒 false。
 */
export function canHoverPointer(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false
  try {
    return window.matchMedia('(hover: hover) and (pointer: fine)').matches
  } catch {
    return false
  }
}

export function useTableLayout(): TableLayoutController {
  const wrapRef = React.useRef<HTMLDivElement | null>(null)
  const [containerWidth, setContainerWidth] = React.useState(0)
  const [chPx, setChPx] = React.useState(0)
  const [columnOverrides, setColumnOverrides] = React.useState(() => new Map<number, number>())
  const [rowOverrides, setRowOverrides] = React.useState(() => new Map<number, number>())
  const [handlesVisible, setHandlesVisible] = React.useState(false)
  // 把手可见性镜像(ref 形态供全局监听同步读取,避免闭包陈旧)。
  const handlesVisibleRef = React.useRef(false)
  // 工具栏:pinned(表头单击固定) + hovering(桌面悬停),二者任一即显示。
  const [toolbarPinned, setToolbarPinned] = React.useState(false)
  const [toolbarHovering, setToolbarHovering] = React.useState(false)
  // 固定态镜像(ref 形态供全局监听同步读取,避免闭包陈旧)。
  const toolbarPinnedRef = React.useRef(false)
  const dragRef = React.useRef<DragState | null>(null)
  const toolbarVisible = toolbarPinned || toolbarHovering

  const showHandles = React.useCallback((visible: boolean) => {
    handlesVisibleRef.current = visible
    setHandlesVisible(visible)
  }, [])

  const pinToolbar = React.useCallback((pinned: boolean) => {
    toolbarPinnedRef.current = pinned
    setToolbarPinned(pinned)
  }, [])

  // 工具栏不可见时把手栏连带收起(把手栏是工具栏「调整表格」的子模式,
  // 入口只此一处;初始挂载时二者均为隐藏,无额外渲染)。
  React.useEffect(() => {
    if (!toolbarVisible) showHandles(false)
  }, [toolbarVisible, showHandles])

  // 收起规则:点击落在表格的非表头区域(单元格/表外)即隐藏工具栏与把手栏。
  // 表头点击由 Th 的 onClick 切换工具栏(覆盖本规则,时序在后);把手栏自身
  // (.md-table-gutter)与工具栏(.md-table-toolbar)内的点击(拖动把手、按按钮)不收起。
  // capture 阶段监听,不与链接点击等原生行为冲突。
  React.useEffect(() => {
    const onPointerDown = (event: PointerEvent) => {
      if (!handlesVisibleRef.current && !toolbarPinnedRef.current) return
      const target = event.target as HTMLElement | null
      if (!target) return
      if (target.closest('.md-table-gutter, .md-table-toolbar')) return
      const wrap = wrapRef.current
      const th = target.closest('th')
      if (wrap && th && wrap.contains(th)) return
      if (toolbarPinnedRef.current) pinToolbar(false)
      showHandles(false)
    }
    window.addEventListener('pointerdown', onPointerDown, true)
    return () => window.removeEventListener('pointerdown', onPointerDown, true)
  }, [pinToolbar, showHandles])

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

  const beginColumnResize = React.useCallback((columnIndex: number, originWidthPx: number, event: React.PointerEvent<HTMLElement>) => {
    if (event.button !== 0) return
    const host = event.currentTarget
    dragRef.current = {
      kind: 'column',
      index: columnIndex,
      originPx: originWidthPx,
      startPoint: event.clientX,
      pointerId: event.pointerId,
    }
    host.setPointerCapture(event.pointerId)
    event.preventDefault()
  }, [])

  const beginRowResize = React.useCallback((rowIndex: number, originHeightPx: number, event: React.PointerEvent<HTMLElement>) => {
    if (event.button !== 0) return
    const host = event.currentTarget
    dragRef.current = {
      kind: 'row',
      index: rowIndex,
      originPx: originHeightPx,
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
    handlesVisible,
    setHandlesVisible: showHandles,
    toolbarVisible,
    toolbarPinned,
    setToolbarPinned: pinToolbar,
    setToolbarHovering,
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

// ───────────────────────── 表格几何实测 ─────────────────────────

/** 表格渲染几何(实测口径):供表格外把手栏对齐与拖动原点。 */
export type TableGeometry = {
  /** 表头(thead)总高(px)。 */
  headerHeight: number
  /** tbody 各行高(px,索引与行对齐)。 */
  rowHeights: number[]
  /** 各列实测宽(px,表头单元格口径;border-collapse 折算后的真实值)。 */
  colWidths: number[]
  /** 表格整体渲染宽(px)。 */
  tableWidth: number
}

const EMPTY_TABLE_GEOMETRY: TableGeometry = { headerHeight: 0, rowHeights: [], colWidths: [], tableWidth: 0 }

function sameTableGeometry(a: TableGeometry, b: TableGeometry): boolean {
  if (a.headerHeight !== b.headerHeight || a.tableWidth !== b.tableWidth) return false
  if (a.rowHeights.length !== b.rowHeights.length || a.colWidths.length !== b.colWidths.length) return false
  return a.rowHeights.every((v, i) => v === b.rowHeights[i])
    && a.colWidths.every((v, i) => v === b.colWidths[i])
}

export type TableGeometryController = {
  /** 实测几何;空(rowHeights/colWidths 空) = 未测得(SSR、无 RO 环境) → 把手栏不渲染。 */
  geometry: TableGeometry
  /** 顶栏列把手平移层 ref(随 wrap 横向滚动命令式平移,不触发 React 渲染)。 */
  colHandleLayerRef: React.MutableRefObject<HTMLDivElement | null>
}

/**
 * 实测表格几何:thead 高、各行高、各列宽、表宽。ResizeObserver 跟踪
 * (列宽/行高手调、窗口缩放、字体就绪、内容变化都会触发重测),setGeometry
 * 前做相等判定避免测量→渲染循环。无 ResizeObserver 的环境(SSR)退化为
 * 空几何——把手栏整体不渲染,与旧版无把手表现一致。
 *
 * @param wrapRef 表格包裹层(滚动容器,与布局引擎/平移共用)。
 * @param rescanKey 行/列数变化时重扫观测目标(内容编辑会重建行/列)。
 */
export function useTableGeometry(
  wrapRef: React.MutableRefObject<HTMLDivElement | null>,
  rescanKey: string,
): TableGeometryController {
  const [geometry, setGeometry] = React.useState<TableGeometry>(EMPTY_TABLE_GEOMETRY)
  const colHandleLayerRef = React.useRef<HTMLDivElement | null>(null)

  React.useLayoutEffect(() => {
    const wrap = wrapRef.current
    if (!wrap || typeof ResizeObserver === 'undefined') return
    const measure = () => {
      const table = wrap.querySelector('table')
      if (!table) return
      const next: TableGeometry = {
        headerHeight: table.querySelector('thead')?.getBoundingClientRect().height ?? 0,
        rowHeights: Array.from(table.querySelectorAll('tbody tr'))
          .map((row) => row.getBoundingClientRect().height),
        colWidths: Array.from(table.querySelectorAll('thead th, thead td'))
          .map((cell) => cell.getBoundingClientRect().width),
        tableWidth: table.getBoundingClientRect().width,
      }
      setGeometry((prev) => (sameTableGeometry(prev, next) ? prev : next))
    }
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(wrap)
    const table = wrap.querySelector('table')
    if (table) {
      observer.observe(table)
      table.querySelectorAll('tr, thead th, thead td').forEach((element) => observer.observe(element))
    }
    return () => observer.disconnect()
  }, [rescanKey, wrapRef])

  // 顶栏把手层横向滚动跟随:命令式平移(与吸顶表头同思路)。
  React.useEffect(() => {
    const wrap = wrapRef.current
    if (!wrap) return
    const sync = () => {
      const layer = colHandleLayerRef.current
      if (layer) layer.style.transform = `translateX(${-wrap.scrollLeft}px)`
    }
    sync()
    wrap.addEventListener('scroll', sync, { passive: true })
    return () => wrap.removeEventListener('scroll', sync)
  }, [wrapRef])

  return { geometry, colHandleLayerRef }
}
