/**
 * git 插件内部侧边栏滚动区域（自宿主 components/shared/SidebarScrollArea
 * 完整复制，插件不引用宿主模块）。
 */
import React from 'react'

export type SidebarScrollAreaProps = {
  children: React.ReactNode
  style?: React.CSSProperties
  className?: string
  thumbClassName?: string
  viewportRef?: React.Ref<HTMLDivElement>
  onScroll?: (event: React.UIEvent<HTMLDivElement>) => void
}

const HIDE_DELAY = 1000
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
