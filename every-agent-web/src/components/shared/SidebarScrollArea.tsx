import React from 'react'

/**
 * 侧边栏自定义滚动区域。
 *
 * 行为：
 * - 原生浏览器滚动条始终隐藏（内容仍可通过滚轮 / 触控板 / 触摸 / 键盘正常滚动）。
 * - 自定义滚动条滑块默认完全透明（不显示），即便内容溢出也不显示。
 * - 仅当用户滚动时显示滑块；停止滚动一段时长后自动淡出隐藏。
 * - 滑块可拖拽，拖拽过程中保持可见。
 *
 * 用法：把原本 `overflowY: 'auto'` 的滚动容器替换为
 * `<SidebarScrollArea style={原滚动容器样式}>{...内容...}</SidebarScrollArea>`。
 * 原样式（flex / minHeight / padding / gap 等）会被应用到内部内容层，布局保持不变。
 */
export type SidebarScrollAreaProps = {
  children: React.ReactNode
  /** 透传给内部内容层的样式（保留原面板的 padding / gap 等布局样式）。 */
  style?: React.CSSProperties
  /** 透传给最外层容器的 className。 */
  className?: string
  /** 透传给自定义滑块的 className。 */
  thumbClassName?: string
  /** 视口元素引用（供调用方做触底续拉、程序化滚动等）。 */
  viewportRef?: React.Ref<HTMLDivElement>
  /** 视口滚动回调（链式透传，供调用方做触底续拉等）。 */
  onScroll?: (event: React.UIEvent<HTMLDivElement>) => void
}

/**
 * 停止滚动后到滑块淡出之间的等待时长（毫秒）。
 */
const HIDE_DELAY = 1000

/**
 * 滑块最小高度（像素），避免内容极长时滑块缩成不可点中的细线。
 */
const MIN_THUMB_HEIGHT = 24

export default function SidebarScrollArea({
  children,
  style,
  className,
  thumbClassName,
  viewportRef,
  onScroll,
}: SidebarScrollAreaProps) {
  const innerViewportRef = React.useRef<HTMLDivElement | null>(null)
  const contentRef = React.useRef<HTMLDivElement | null>(null)
  const hideTimerRef = React.useRef<number | null>(null)
  const draggingRef = React.useRef(false)
  const dragStartPointerYRef = React.useRef(0)
  const dragStartScrollTopRef = React.useRef(0)

  const [thumbHeight, setThumbHeight] = React.useState(0)
  const [thumbTop, setThumbTop] = React.useState(0)
  const [visible, setVisible] = React.useState(false)
  const [scrollable, setScrollable] = React.useState(false)
  const [dragging, setDragging] = React.useState(false)

  const scheduleHide = React.useCallback(() => {
    if (hideTimerRef.current !== null) {
      window.clearTimeout(hideTimerRef.current)
    }
    hideTimerRef.current = window.setTimeout(() => {
      if (!draggingRef.current) {
        setVisible(false)
      }
    }, HIDE_DELAY)
  }, [])

  /** 合并内部视口 ref 与调用方透传的 viewportRef(函数 ref 或对象 ref)。 */
  const setViewportRef = React.useCallback((node: HTMLDivElement | null) => {
    innerViewportRef.current = node
    if (typeof viewportRef === 'function') {
      viewportRef(node)
    } else if (viewportRef) {
      ;(viewportRef as React.MutableRefObject<HTMLDivElement | null>).current = node
    }
  }, [viewportRef])

  const updateMetrics = React.useCallback(() => {
    const viewport = innerViewportRef.current
    if (!viewport) {
      return
    }
    const { scrollHeight, clientHeight, scrollTop } = viewport
    const overflow = scrollHeight - clientHeight
    if (overflow <= 1) {
      setScrollable(false)
      setVisible(false)
      setThumbHeight(0)
      setThumbTop(0)
      return
    }
    setScrollable(true)
    const ratio = clientHeight / scrollHeight
    const nextThumbHeight = Math.max(MIN_THUMB_HEIGHT, Math.floor(clientHeight * ratio))
    const maxThumbOffset = clientHeight - nextThumbHeight
    const nextThumbTop = overflow > 0 ? Math.round((scrollTop / overflow) * maxThumbOffset) : 0
    setThumbHeight(nextThumbHeight)
    setThumbTop(nextThumbTop)
  }, [])

  const handleScroll = React.useCallback(() => {
    updateMetrics()
    setVisible(true)
    scheduleHide()
  }, [updateMetrics, scheduleHide])

  React.useEffect(() => {
    const viewport = innerViewportRef.current
    const content = contentRef.current
    if (!viewport || !content) {
      return
    }
    updateMetrics()
    const resizeObserver = new ResizeObserver(() => {
      updateMetrics()
    })
    // 观察视口（尺寸变化，如侧边栏高度调整）与内容层（内容增减，如展开树）。
    resizeObserver.observe(viewport)
    resizeObserver.observe(content)
    return () => {
      resizeObserver.disconnect()
      if (hideTimerRef.current !== null) {
        window.clearTimeout(hideTimerRef.current)
      }
    }
  }, [updateMetrics])

  const handleThumbPointerDown = React.useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      event.preventDefault()
      event.stopPropagation()
      const viewport = innerViewportRef.current
      if (!viewport) {
        return
      }
      draggingRef.current = true
      setDragging(true)
      setVisible(true)
      if (hideTimerRef.current !== null) {
        window.clearTimeout(hideTimerRef.current)
      }
      dragStartPointerYRef.current = event.clientY
      dragStartScrollTopRef.current = viewport.scrollTop

      const handlePointerMove = (moveEvent: PointerEvent) => {
        const nextViewport = innerViewportRef.current
        if (!nextViewport) {
          return
        }
        const overflow = nextViewport.scrollHeight - nextViewport.clientHeight
        if (overflow <= 0) {
          return
        }
        const trackTravel = nextViewport.clientHeight - thumbHeight
        if (trackTravel <= 0) {
          return
        }
        const deltaY = moveEvent.clientY - dragStartPointerYRef.current
        const scrollDelta = (deltaY / trackTravel) * overflow
        nextViewport.scrollTop = dragStartScrollTopRef.current + scrollDelta
      }

      const handlePointerUp = () => {
        draggingRef.current = false
        setDragging(false)
        window.removeEventListener('pointermove', handlePointerMove)
        window.removeEventListener('pointerup', handlePointerUp)
        scheduleHide()
      }

      window.addEventListener('pointermove', handlePointerMove)
      window.addEventListener('pointerup', handlePointerUp)
    },
    [thumbHeight, scheduleHide],
  )

  const wrapperClassName = [
    'sidebar-scroll-area',
    visible ? 'is-visible' : '',
    dragging ? 'is-dragging' : '',
    className ?? '',
  ]
    .filter(Boolean)
    .join(' ')

  return (
    <div className={wrapperClassName} style={wrapperStyle}>
      <div
        ref={setViewportRef}
        className="sidebar-scroll-area__viewport"
        style={viewportExtraStyle}
        onScroll={(event) => {
          handleScroll()
          onScroll?.(event)
        }}
      >
        <div ref={contentRef} className="sidebar-scroll-area__content" style={style}>
          {children}
        </div>
      </div>
      {scrollable ? (
        <div className="sidebar-scroll-area__track" aria-hidden>
          <div
            className={['sidebar-scroll-area__thumb', thumbClassName ?? ''].filter(Boolean).join(' ')}
            style={{ height: thumbHeight, transform: `translateY(${thumbTop}px)` }}
            onPointerDown={handleThumbPointerDown}
          />
        </div>
      ) : null}
    </div>
  )
}

const wrapperStyle: React.CSSProperties = {
  position: 'relative',
  flex: '1 1 auto',
  minWidth: 0,
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
}

const viewportExtraStyle: React.CSSProperties = {
  flex: '1 1 auto',
  minWidth: 0,
  minHeight: 0,
  overflowY: 'auto',
  overflowX: 'hidden',
}
