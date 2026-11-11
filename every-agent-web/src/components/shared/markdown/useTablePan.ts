import React from 'react'

/**
 * 大表格手型平移(handy-pan):横向溢出的表格显示抓手光标,按住左键拖动即可
 * 上下左右平移滚动——解决宽表格拖滚动条不友好的问题。
 *
 * 与文本选择的共存策略:
 * - 拖动位移超过阈值(6px)才判定为平移:单击/双击(单元格编辑)不受影响;
 * - 确认平移时清除浏览器拖选产生的选区并临时禁选(user-select:none),
 *   拖动语义唯一归属平移;
 * - 按住 Alt 拖动 = 临时切回文本选择(不平移、光标恢复),复制能力保留;
 * - 无横向溢出的普通表格不启用手型,选择行为与原生完全一致;
 * - 触摸/笔输入不平移(原生手势滚动;wrap 的 touch-action:manipulation 保证滑动
 *   流畅、禁用系统双击缩放,为单元格触屏双击编辑让路);
 * - 链接、输入框等交互元素上按下不平移。
 */

/** 平移确认阈值(px):按下后位移超过该值才判定为拖动,小于则保留点击/双击。 */
const PAN_THRESHOLD_PX = 6

type PanState = {
  pointerId: number
  startX: number
  startY: number
  /** 拖动起始时 wrap 的 scrollLeft(绝对式回写,避免增量漂移)。 */
  scrollLeft: number
  /** 垂直平移目标(最近可垂直滚动祖先,即阅读容器);无则垂直拖动无效果。 */
  verticalTarget: HTMLElement | null
  /** verticalTarget 拖动起始 scrollTop。 */
  verticalStartTop: number
  /** 是否已确认平移(超过阈值并接管指针)。 */
  active: boolean
}

/** 沿祖先链找垂直方向可滚动的元素(表格 wrap 自身 overflow-y hidden,垂直滚动在外层)。 */
export function findVerticalScrollTarget(element: HTMLElement): HTMLElement | null {
  let parent: HTMLElement | null = element.parentElement
  while (parent && parent !== document.body) {
    if (parent.scrollHeight > parent.clientHeight + 1) {
      const overflowY = window.getComputedStyle(parent).overflowY
      if (overflowY === 'auto' || overflowY === 'scroll') return parent
    }
    parent = parent.parentElement
  }
  return null
}

export type TablePanController = {
  /** 表格包裹层 ref(平移滚动与溢出检测的载体)。 */
  wrapRef: React.MutableRefObject<HTMLDivElement | null>
  /** 表格横向溢出(可平移)时为 true——仅大表格启用手型。 */
  pannable: boolean
  /** 平移进行中(抓手按下光标 + 禁选)。 */
  panning: boolean
  /** Alt 按住(临时文本选择模式,光标恢复默认)。 */
  altSelect: boolean
  handlers: {
    onPointerDown: React.PointerEventHandler<HTMLDivElement>
    onPointerMove: React.PointerEventHandler<HTMLDivElement>
    onPointerUp: React.PointerEventHandler<HTMLDivElement>
    onPointerCancel: React.PointerEventHandler<HTMLDivElement>
  }
}

export function useTablePan(): TablePanController {
  const wrapRef = React.useRef<HTMLDivElement | null>(null)
  const [pannable, setPannable] = React.useState(false)
  const [panning, setPanning] = React.useState(false)
  const [altSelect, setAltSelect] = React.useState(false)
  const stateRef = React.useRef<PanState | null>(null)

  // 横向溢出检测:容器或表格尺寸变化时重判(窗口缩放、列宽/内容变化都能感知)。
  React.useEffect(() => {
    const wrap = wrapRef.current
    if (!wrap || typeof ResizeObserver === 'undefined') return
    const update = () => {
      setPannable(wrap.scrollWidth > wrap.clientWidth + 1)
    }
    update()
    const observer = new ResizeObserver(update)
    observer.observe(wrap)
    const table = wrap.querySelector('table')
    if (table) observer.observe(table)
    return () => observer.disconnect()
  }, [])

  // Alt 按住/松开:临时切换文本选择模式。窗口失焦时重置,避免 Alt 状态滞留。
  React.useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Alt') setAltSelect(true)
    }
    const onKeyUp = (event: KeyboardEvent) => {
      if (event.key === 'Alt') setAltSelect(false)
    }
    const onBlur = () => setAltSelect(false)
    window.addEventListener('keydown', onKeyDown)
    window.addEventListener('keyup', onKeyUp)
    window.addEventListener('blur', onBlur)
    return () => {
      window.removeEventListener('keydown', onKeyDown)
      window.removeEventListener('keyup', onKeyUp)
      window.removeEventListener('blur', onBlur)
    }
  }, [])

  const onPointerDown: React.PointerEventHandler<HTMLDivElement> = (event) => {
    if (!pannable || panning) return
    if (event.button !== 0) return
    if (event.altKey) return // Alt 按住 = 文本选择,不平移。
    if (event.pointerType !== 'mouse') return // 触摸/笔走原生手势滚动。
    // 链接、输入框(单元格编辑)等交互元素上按下保持原生行为。
    const target = event.target as HTMLElement | null
    if (target?.closest('a, input, textarea, button, [contenteditable]')) return
    const wrap = wrapRef.current
    if (!wrap) return
    const verticalTarget = findVerticalScrollTarget(wrap)
    stateRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      scrollLeft: wrap.scrollLeft,
      verticalTarget,
      verticalStartTop: verticalTarget?.scrollTop ?? 0,
      active: false,
    }
  }

  const onPointerMove: React.PointerEventHandler<HTMLDivElement> = (event) => {
    const state = stateRef.current
    if (!state || state.pointerId !== event.pointerId) return
    const wrap = wrapRef.current
    if (!wrap) return
    const dx = event.clientX - state.startX
    const dy = event.clientY - state.startY
    if (!state.active) {
      if (Math.abs(dx) < PAN_THRESHOLD_PX && Math.abs(dy) < PAN_THRESHOLD_PX) return
      // 确认平移:接管指针(拖出表格仍跟踪)、清除拖选残迹、禁选防扩散。
      state.active = true
      setPanning(true)
      wrap.setPointerCapture(event.pointerId)
      window.getSelection()?.removeAllRanges()
    }
    wrap.scrollLeft = state.scrollLeft - dx
    if (state.verticalTarget) {
      state.verticalTarget.scrollTop = state.verticalStartTop - dy
    }
  }

  const endPan: React.PointerEventHandler<HTMLDivElement> = (event) => {
    const state = stateRef.current
    if (!state || state.pointerId !== event.pointerId) return
    stateRef.current = null
    if (state.active) {
      setPanning(false)
      const wrap = wrapRef.current
      if (wrap?.hasPointerCapture(event.pointerId)) {
        wrap.releasePointerCapture(event.pointerId)
      }
    }
  }

  return {
    wrapRef,
    pannable,
    panning,
    altSelect,
    handlers: {
      onPointerDown,
      onPointerMove,
      onPointerUp: endPan,
      onPointerCancel: endPan,
    },
  }
}
