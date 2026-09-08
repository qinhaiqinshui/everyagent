import React from 'react'

/**
 * 横向滚动区域：内容超出时横向滚动，原生滚动条始终隐藏，
 * 仅当用户滚动时在底部显示一条自定义滚动指示条，停止滚动后自动淡出。
 *
 * 用法：把需要横向滚动的一组子元素包裹起来，
 * `<HScrollArea className="...">{...子元素...}</HScrollArea>`。
 * 子元素按 flex 行排列（不换行），空间不足时横向滚动，不影响同级其它元素布局。
 */
export type HScrollAreaProps = {
  children: React.ReactNode
  /** 透传给最外层容器的 className。 */
  className?: string
}

/** 停止滚动后到指示条淡出之间的等待时长（毫秒）。 */
const HIDE_DELAY = 800

/** 指示条最小宽度（像素），避免内容极长时缩成不可见的细线。 */
const MIN_THUMB_WIDTH = 24

export default function HScrollArea({ children, className }: HScrollAreaProps) {
  const viewportRef = React.useRef<HTMLDivElement>(null)
  const hideTimerRef = React.useRef<number | null>(null)

  const [visible, setVisible] = React.useState(false)
  const [scrollable, setScrollable] = React.useState(false)
  const [thumbWidth, setThumbWidth] = React.useState(0)
  const [thumbLeft, setThumbLeft] = React.useState(0)

  const scheduleHide = React.useCallback(() => {
    if (hideTimerRef.current !== null) {
      window.clearTimeout(hideTimerRef.current)
    }
    hideTimerRef.current = window.setTimeout(() => {
      setVisible(false)
    }, HIDE_DELAY)
  }, [])

  const updateMetrics = React.useCallback(() => {
    const viewport = viewportRef.current
    if (!viewport) {
      return
    }
    const { scrollWidth, clientWidth, scrollLeft } = viewport
    const overflow = scrollWidth - clientWidth
    if (overflow <= 1) {
      setScrollable(false)
      setVisible(false)
      setThumbWidth(0)
      setThumbLeft(0)
      return
    }
    setScrollable(true)
    const ratio = clientWidth / scrollWidth
    const nextThumbWidth = Math.max(MIN_THUMB_WIDTH, Math.floor(clientWidth * ratio))
    const maxThumbOffset = clientWidth - nextThumbWidth
    const nextThumbLeft = overflow > 0 ? Math.round((scrollLeft / overflow) * maxThumbOffset) : 0
    setThumbWidth(nextThumbWidth)
    setThumbLeft(nextThumbLeft)
  }, [])

  const handleScroll = React.useCallback(() => {
    updateMetrics()
    setVisible(true)
    scheduleHide()
  }, [updateMetrics, scheduleHide])

  React.useEffect(() => {
    const viewport = viewportRef.current
    if (!viewport) {
      return
    }
    updateMetrics()
    // 观察视口尺寸变化（面板宽度调整）与内容层（胶囊增减）变化，及时刷新指示条。
    const resizeObserver = new ResizeObserver(() => {
      updateMetrics()
    })
    resizeObserver.observe(viewport)
    const content = viewport.firstElementChild
    if (content) {
      resizeObserver.observe(content)
    }
    return () => {
      resizeObserver.disconnect()
      if (hideTimerRef.current !== null) {
        window.clearTimeout(hideTimerRef.current)
      }
    }
  }, [updateMetrics])

  const wrapperClassName = [
    'h-scroll-area',
    visible ? 'is-visible' : '',
    className ?? '',
  ]
    .filter(Boolean)
    .join(' ')

  return (
    <div className={wrapperClassName}>
      <div ref={viewportRef} className="h-scroll-area__viewport" onScroll={handleScroll}>
        <div className="h-scroll-area__content">{children}</div>
      </div>
      {scrollable ? (
        <div className="h-scroll-area__track" aria-hidden>
          <div
            className="h-scroll-area__thumb"
            style={{ width: thumbWidth, transform: `translateX(${thumbLeft}px)` }}
          />
        </div>
      ) : null}
    </div>
  )
}
