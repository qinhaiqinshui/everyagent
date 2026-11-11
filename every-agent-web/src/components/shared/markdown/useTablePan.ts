import React from 'react'

/**
 * 大表格空格手型平移(space-pan):横向溢出的大表格上按住 Space,光标变抓手,
 * 按住左键拖动即可上下左右平移滚动——长表格浏览不丢表头、宽表格免拖滚动条。
 *
 * 交互模型(Figma 式临时手型,文本选择优先):
 * - 默认(不按空格):完全原生行为——I-beam 光标、拖动即选择文本、复制粘贴照常;
 * - 按住 Space 且指针悬停在大表格上:光标变 grab,拖动 = 平移(上下左右),
 *   平移中 grab 抓取态;松开空格(手势结束后)恢复原生;
 * - 空格仅当指针悬停在表格上时才被接管(阻止空格翻页),其余场合空格保持原生;
 * - 平移确认阈值(6px):按住空格后单击/双击(单元格编辑)不受影响;
 * - 焦点在输入框/CodeMirror 等可编辑区域时空格是打字,绝不接管;
 * - 触摸/笔输入不平移(原生手势滚动);链接、输入框等交互元素上按下不平移。
 */

/** 平移确认阈值(px):按下后位移超过该值才判定为拖动,小于则保留点击/双击。 */
const PAN_THRESHOLD_PX = 6

/** 空格手型模式下按下也不接管平移的交互元素(保持原生点击/输入)。 */
const INTERACTIVE_SELECTOR = 'a, input, textarea, button, [contenteditable], .cm-editor'

/** 焦点元素处于可编辑态(空格是打字)时,空格手型不接管。 */
function isEditableFocus(): boolean {
  const active = document.activeElement
  if (!active) return false
  return active.closest(INTERACTIVE_SELECTOR) != null
}

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
  /** 平移进行中(抓手抓取光标)。 */
  panning: boolean
  /** 空格手型激活(按住空格且悬停本表格):显示 grab 光标,拖动即平移。 */
  spacePan: boolean
  handlers: {
    onPointerDown: React.PointerEventHandler<HTMLDivElement>
    onPointerMove: React.PointerEventHandler<HTMLDivElement>
    onPointerUp: React.PointerEventHandler<HTMLDivElement>
    onPointerCancel: React.PointerEventHandler<HTMLDivElement>
    onPointerEnter: React.PointerEventHandler<HTMLDivElement>
    onPointerLeave: React.PointerEventHandler<HTMLDivElement>
  }
}

export function useTablePan(): TablePanController {
  const wrapRef = React.useRef<HTMLDivElement | null>(null)
  const [pannable, setPannable] = React.useState(false)
  const [panning, setPanning] = React.useState(false)
  const [spacePan, setSpacePan] = React.useState(false)
  const stateRef = React.useRef<PanState | null>(null)
  const spaceDownRef = React.useRef(false)
  const hoverRef = React.useRef(false)

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

  // 空格临时手型:按住且悬停本表格(或平移进行中)时激活;窗口失焦复位。
  React.useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== ' ' || event.repeat) return
      if (isEditableFocus()) return // 空格在输入场景是打字,绝不接管。
      spaceDownRef.current = true
      // 仅当指针悬停在本表格上时接管空格(阻止空格翻页);其余场合空格保持原生。
      if (hoverRef.current) {
        event.preventDefault()
        setSpacePan(true)
      }
    }
    const onKeyUp = (event: KeyboardEvent) => {
      if (event.key !== ' ') return
      spaceDownRef.current = false
      // 平移进行中保持手型到本次手势结束,不打断正在拖动的用户。
      if (!stateRef.current?.active) setSpacePan(false)
    }
    const onBlur = () => {
      spaceDownRef.current = false
      setSpacePan(false)
    }
    window.addEventListener('keydown', onKeyDown)
    window.addEventListener('keyup', onKeyUp)
    window.addEventListener('blur', onBlur)
    return () => {
      window.removeEventListener('keydown', onKeyDown)
      window.removeEventListener('keyup', onKeyUp)
      window.removeEventListener('blur', onBlur)
    }
  }, [])

  /** 指针进入表格:空格已按住时即刻切手型。 */
  const onPointerEnter: React.PointerEventHandler<HTMLDivElement> = () => {
    hoverRef.current = true
    if (spaceDownRef.current) setSpacePan(true)
  }

  /** 指针离开表格:手型随之取消(平移进行中除外,不打断手势)。 */
  const onPointerLeave: React.PointerEventHandler<HTMLDivElement> = () => {
    hoverRef.current = false
    if (!stateRef.current?.active) setSpacePan(false)
  }

  const onPointerDown: React.PointerEventHandler<HTMLDivElement> = (event) => {
    if (!pannable || panning) return
    if (event.button !== 0) return
    // 默认(空格未按住)不平移——拖动即原生文本选择,选择复制优先。
    if (!spaceDownRef.current) return
    if (event.pointerType !== 'mouse') return // 触摸/笔走原生手势滚动。
    // 链接、输入框(单元格编辑)等交互元素上按下保持原生行为。
    const target = event.target as HTMLElement | null
    if (target?.closest(INTERACTIVE_SELECTOR)) return
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
      setSpacePan(true)
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
    // 手势结束:空格仍按住且指针仍在表格上则保持手型,否则恢复原生选择模式。
    if (!(spaceDownRef.current && hoverRef.current)) setSpacePan(false)
  }

  return {
    wrapRef,
    pannable,
    panning,
    spacePan,
    handlers: {
      onPointerDown,
      onPointerMove,
      onPointerUp: endPan,
      onPointerCancel: endPan,
      onPointerEnter,
      onPointerLeave,
    },
  }
}
