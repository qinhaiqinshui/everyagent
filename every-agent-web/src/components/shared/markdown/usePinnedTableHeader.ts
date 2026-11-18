import React from 'react'
import { findVerticalScrollTarget } from './useTablePan'

/**
 * 表头吸顶(滚动钉住):长表格滚动阅读时,表头钉在阅读容器顶部;表格整体
 * 滚出可视区域后自动还原并卸载浮动表头。
 *
 * 为什么不用 CSS position:sticky:表格 wrap 是横向滚动容器(overflow-x:auto),
 * sticky 会被最近的滚动容器(wrap 本身)接管——wrap 不垂直滚动,垂直吸顶失效。
 * 故采用 JS 浮动表头:钉住时经 portal 在 body 渲染表头克隆(复用原表列宽
 * colgroup + tableLayout:fixed,几何完全一致),横向跟随 wrap.scrollLeft。
 *
 * 吸顶条件(shouldPinHeader):
 * - 表头底缘已滚出容器顶(原表头不可见,需要替身);
 * - 表格底缘仍在容器顶之下(表格还没滚完;表格滚出后替身随之卸载)。
 */

/** 吸顶激活时的几何快照(视口坐标)。 */
export type PinnedHeaderMetrics = {
  /** 阅读容器顶缘的视口 y(浮动表头的 fixed top)。 */
  top: number
  /** 表格 wrap 左缘的视口 x。 */
  left: number
  /** wrap 客户区宽(浮动表头可见宽,超出部分裁剪)。 */
  width: number
  /** 原表实际渲染宽(px,克隆表宽度,保证列几何一致)。 */
  tableWidth: number
  /** 表头高(px,判定表头是否已滚出)。 */
  headerHeight: number
  /** 吸顶瞬间的横向滚动偏移(克隆初始平移)。 */
  scrollLeft: number
}

/**
 * 纯判定:是否需要吸顶。
 * @param wrapTop      表格 wrap 顶缘视口 y
 * @param wrapBottom   表格 wrap 底缘视口 y
 * @param scrollerTop  阅读容器顶缘视口 y(吸顶线)
 * @param headerHeight 表头高
 */
export function shouldPinHeader(
  wrapTop: number,
  wrapBottom: number,
  scrollerTop: number,
  headerHeight: number,
): boolean {
  const theadBottom = wrapTop + Math.max(headerHeight, 0)
  return theadBottom < scrollerTop && wrapBottom > scrollerTop
}

export type PinnedTableHeaderController = {
  /** 吸顶几何快照;null = 不吸顶(常态)。 */
  pinned: PinnedHeaderMetrics | null
  /** 克隆内容的平移层 ref(横向滚动跟随,命令式更新避免高频 setState)。 */
  innerRef: React.MutableRefObject<HTMLDivElement | null>
}

/**
 * @param wrapRef 表格包裹层 ref(与 useTablePan 共用同一 wrap)。
 * @param enabled false 时彻底关闭并卸载浮动表头(如表头单元格编辑中)。
 */
export function usePinnedTableHeader(
  wrapRef: React.MutableRefObject<HTMLDivElement | null>,
  enabled: boolean,
): PinnedTableHeaderController {
  const [pinned, setPinned] = React.useState<PinnedHeaderMetrics | null>(null)
  const innerRef = React.useRef<HTMLDivElement | null>(null)
  const rafRef = React.useRef(0)

  React.useEffect(() => {
    if (!enabled) {
      setPinned(null)
      return
    }
    const wrap = wrapRef.current
    if (!wrap || typeof ResizeObserver === 'undefined') return
    const scroller = findVerticalScrollTarget(wrap)
    if (!scroller) return

    const evaluate = () => {
      rafRef.current = 0
      const w = wrapRef.current
      if (!w) return
      const table = w.querySelector('table')
      const thead = w.querySelector('thead')
      const headerHeight = thead?.offsetHeight ?? 0
      const wrapRect = w.getBoundingClientRect()
      const scrollerRect = scroller.getBoundingClientRect()
      if (!table || !shouldPinHeader(wrapRect.top, wrapRect.bottom, scrollerRect.top, headerHeight)) {
        setPinned(null)
        return
      }
      const next: PinnedHeaderMetrics = {
        top: scrollerRect.top,
        left: wrapRect.left,
        width: w.clientWidth,
        tableWidth: table.offsetWidth,
        headerHeight,
        scrollLeft: w.scrollLeft,
      }
      setPinned((prev) => (
        prev
          && prev.top === next.top
          && prev.left === next.left
          && prev.width === next.width
          && prev.tableWidth === next.tableWidth
          && prev.headerHeight === next.headerHeight
          && prev.scrollLeft === next.scrollLeft
          ? prev
          : next
      ))
    }
    const scheduleEvaluate = () => {
      if (!rafRef.current) rafRef.current = requestAnimationFrame(evaluate)
    }

    // 垂直滚动(容器)、几何变化(表格/容器尺寸)都会改变吸顶判定;横向滚动单独走平移。
    scroller.addEventListener('scroll', scheduleEvaluate, { passive: true })
    window.addEventListener('resize', scheduleEvaluate)
    const observer = new ResizeObserver(scheduleEvaluate)
    observer.observe(wrap)
    const table = wrap.querySelector('table')
    if (table) observer.observe(table)
    observer.observe(scroller)
    scheduleEvaluate()

    return () => {
      scroller.removeEventListener('scroll', scheduleEvaluate)
      window.removeEventListener('resize', scheduleEvaluate)
      observer.disconnect()
      if (rafRef.current) {
        cancelAnimationFrame(rafRef.current)
        rafRef.current = 0
      }
      setPinned(null)
    }
  }, [enabled, wrapRef])

  // 横向滚动跟随:命令式平移克隆内容,不触发 React 渲染。
  React.useEffect(() => {
    if (!enabled) return
    const wrap = wrapRef.current
    if (!wrap) return
    const syncScrollLeft = () => {
      const w = wrapRef.current
      const inner = innerRef.current
      if (w && inner) inner.style.transform = `translateX(${-w.scrollLeft}px)`
    }
    wrap.addEventListener('scroll', syncScrollLeft, { passive: true })
    return () => wrap.removeEventListener('scroll', syncScrollLeft)
  }, [enabled, wrapRef])

  return { pinned, innerRef }
}
