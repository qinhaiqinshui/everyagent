import React from 'react'
import { cn } from '@/components/shared/ui/cn'

export interface OverlayScrollbarProps {
  /** 指向可横向滚动的容器元素（滚动条覆盖在其之上） */
  targetRef: React.RefObject<HTMLElement | null>
  /** 覆盖条距容器左右边缘的内缩距离（px），默认 6 */
  offset?: number
  /** 停止交互后自动隐藏的延迟（ms），默认 1200 */
  hideDelayMs?: number
  /** thumb 最小宽度（px），默认 24 */
  thumbMinWidth?: number
  /**
   * 内容版本号。当内容增删（如标签数变化）导致 scrollWidth 改变、但容器尺寸未变时，
   * 传入会变化的 version 可触发重新测量。传 workspaceTabs.length 之类即可。
   */
  version?: number | string
  className?: string
  /** 附加在 thumb 上的类名（用于定制外观） */
  thumbClassName?: string
  /** 溢出后是否常驻显示（不自动隐藏）。标题栏等需要常驻抓手提示的场景用。 */
  persistent?: boolean
  /** 溢出后默认隐藏，鼠标移入滚动容器(targetRef)时才显示、移出隐藏。用于标题栏等场景。 */
  hoverReveal?: boolean
}

/**
 * 覆盖式横向滚动指示条：
 * - 隐藏原生滚动条，改为浮在滚动容器上方的细指示条（pointer-events: none 不拦截内容点击）；
 * - 滚动 / 内容变化时淡入，停止交互 hideDelayMs 后淡出；
 * - 指示条 thumb 可拖拽，按「屏幕位移 ×(最大滚动/轨道行程)」换算 scrollLeft。
 *
 * 使用要求：本组件渲染的覆盖层采用 position:absolute，必须置于一个
 * position:relative 且恰好包裹滚动区域的祖先容器内（与滚动容器同级）。
 */
export default function OverlayScrollbar({
  targetRef,
  offset = 6,
  hideDelayMs = 1200,
  thumbMinWidth = 24,
  version,
  className,
  thumbClassName,
  persistent = false,
  hoverReveal = false,
}: OverlayScrollbarProps) {
  const [scroll, setScroll] = React.useState({
    overflowing: false,
    thumbLeft: 0,
    thumbWidth: 0,
    visible: false,
  })
  const hideTimer = React.useRef<number | null>(null)
  const draggingRef = React.useRef(false)
  const [hovered, setHovered] = React.useState(false)
  const [dragging, setDragging] = React.useState(false)
  const dragRef = React.useRef<{
    pointerStartX: number
    scrollStartLeft: number
    ratio: number
  } | null>(null)

  const scheduleHide = React.useCallback(() => {
    if (persistent || hoverReveal) return
    if (hideTimer.current !== null) window.clearTimeout(hideTimer.current)
    hideTimer.current = window.setTimeout(() => {
      setScroll((s) => ({ ...s, visible: false }))
    }, hideDelayMs)
  }, [hideDelayMs, persistent, hoverReveal])

  const measure = React.useCallback(() => {
    const el = targetRef.current
    if (!el) return
    const { scrollWidth, clientWidth, scrollLeft } = el
    const overflowing = scrollWidth > clientWidth + 1
    const trackWidth = Math.max(0, clientWidth - offset * 2)
    const thumbWidth = Math.max(
      thumbMinWidth,
      (clientWidth / Math.max(1, scrollWidth)) * trackWidth,
    )
    const maxScroll = scrollWidth - clientWidth
    const thumbLeft =
      maxScroll > 0 ? (scrollLeft / maxScroll) * (trackWidth - thumbWidth) : 0
    if (hoverReveal) {
      // hover 显示模式：只更新尺寸/位置，可见性由 hover/drag 状态决定。
      setScroll((s) => ({ ...s, overflowing, thumbLeft, thumbWidth }))
      return
    }
    setScroll({ overflowing, thumbLeft, thumbWidth, visible: true })
    // 拖拽期间不调度隐藏，避免闪烁；persistent 模式常显。
    if (draggingRef.current || persistent) return
    scheduleHide()
  }, [targetRef, offset, thumbMinWidth, scheduleHide, hoverReveal])

  // hoverReveal 模式：全局监听鼠标位置，判断是否位于滚动容器(targetRef)内控制显示。
  // 不依赖目标元素自身的事件派发（标题栏 tabs-strip 是 app-region:drag 区域，hover 事件不可靠）。
  React.useEffect(() => {
    if (!hoverReveal) return
    const onMove = (e: MouseEvent) => {
      const el = targetRef.current
      if (!el) return
      const rect = el.getBoundingClientRect()
      const inside =
        e.clientX >= rect.left && e.clientX <= rect.right &&
        e.clientY >= rect.top && e.clientY <= rect.bottom
      setHovered(inside)
    }
    window.addEventListener('mousemove', onMove, { passive: true })
    return () => window.removeEventListener('mousemove', onMove)
  }, [hoverReveal, targetRef])

  // 监听目标滚动容器的 scroll 与尺寸变化
  React.useEffect(() => {
    const el = targetRef.current
    if (!el) return
    const onScroll = () => measure()
    el.addEventListener('scroll', onScroll, { passive: true })
    const ro = new ResizeObserver(() => measure())
    ro.observe(el)
    measure()
    return () => {
      el.removeEventListener('scroll', onScroll)
      ro.disconnect()
      if (hideTimer.current !== null) window.clearTimeout(hideTimer.current)
    }
  }, [targetRef, measure])

  // 内容版本变化（如标签增删）时重新测量
  React.useEffect(() => {
    measure()
  }, [version, measure])

  const handlePointerDown = React.useCallback(
    (e: React.PointerEvent<HTMLDivElement>) => {
      const el = targetRef.current
      if (!el) return
      e.preventDefault()
      e.stopPropagation()
      const { scrollWidth, clientWidth } = el
      const maxScroll = scrollWidth - clientWidth
      const trackWidth = Math.max(0, clientWidth - offset * 2)
      const thumbWidth = Math.max(
        thumbMinWidth,
        (clientWidth / Math.max(1, scrollWidth)) * trackWidth,
      )
      const trackTravel = Math.max(1, trackWidth - thumbWidth)
      draggingRef.current = true
      setDragging(true)
      dragRef.current = {
        pointerStartX: e.clientX,
        scrollStartLeft: el.scrollLeft,
        ratio: maxScroll / trackTravel,
      }
      if (hideTimer.current !== null) window.clearTimeout(hideTimer.current)
      e.currentTarget.setPointerCapture(e.pointerId)
      document.body.style.userSelect = 'none'
    },
    [targetRef, offset, thumbMinWidth],
  )

  const handlePointerMove = React.useCallback(
    (e: React.PointerEvent<HTMLDivElement>) => {
      const drag = dragRef.current
      const el = targetRef.current
      if (!drag || !el) return
      const deltaX = e.clientX - drag.pointerStartX
      el.scrollLeft = Math.max(0, drag.scrollStartLeft + deltaX * drag.ratio)
      // scroll 事件会触发 measure 同步 thumb 位置
    },
    [targetRef],
  )

  const handlePointerUp = React.useCallback(
    (e: React.PointerEvent<HTMLDivElement>) => {
      if (!dragRef.current) return
      dragRef.current = null
      draggingRef.current = false
      setDragging(false)
      try {
        e.currentTarget.releasePointerCapture(e.pointerId)
      } catch {
        /* noop */
      }
      document.body.style.userSelect = ''
      scheduleHide()
    },
    [scheduleHide],
  )

  // 最终可见性：hoverReveal 模式由「溢出 && (hover 或拖拽中)」决定；其余模式沿用 scroll.visible。
  const visible = hoverReveal
    ? scroll.overflowing && (hovered || dragging)
    : scroll.visible && scroll.overflowing

  return (
    <div
      className={cn('overlay-scrollbar', className)}
      aria-hidden="true"
      style={{
        left: offset,
        right: offset,
        opacity: visible ? 1 : 0,
      }}
    >
      <div
        className={cn('overlay-scrollbar__thumb', thumbClassName)}
        style={{
          width: scroll.thumbWidth,
          transform: `translateX(${scroll.thumbLeft}px)`,
          // 仅在「确实溢出」且「当前处于可见态」时才接收指针事件；
          // 否则 thumb 会以透明状态继续拦截点击（如标题栏底部整条区域），
          // 导致标签无法点击、鼠标变成抓手。
          pointerEvents: visible ? 'auto' : 'none',
        }}
        onPointerDown={handlePointerDown}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        onPointerCancel={handlePointerUp}
      />
    </div>
  )
}
