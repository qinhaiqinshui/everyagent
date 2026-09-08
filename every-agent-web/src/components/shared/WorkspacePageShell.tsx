import React from 'react'

export type WorkspacePageShellHandle = {
  getBodyElement: () => HTMLDivElement | null
  isAutoFollowEnabled: () => boolean
  scrollToBottom: (behavior?: ScrollBehavior, force?: boolean) => void
  scrollElementIntoView: (element: HTMLElement, behavior?: ScrollBehavior, force?: boolean, margin?: number) => void
}

export type WorkspacePageShellProps = {
  header?: React.ReactNode
  children: React.ReactNode
  bodyRef?: React.Ref<HTMLDivElement>
  shellRef?: React.Ref<WorkspacePageShellHandle>
  shellClassName?: string
  headerClassName?: string
  bodyClassName?: string
  bodyInnerClassName?: string
  bodyPadding?: React.CSSProperties['padding']
  shellStyle?: React.CSSProperties
  headerStyle?: React.CSSProperties
  bodyStyle?: React.CSSProperties
  bodyInnerStyle?: React.CSSProperties
  enableAutoFollow?: boolean
}

function getScrollMetrics(target: HTMLElement) {
  return {
    scrollTop: target.scrollTop,
    clientHeight: target.clientHeight,
    scrollHeight: target.scrollHeight,
  }
}

function isScrollTargetNearBottom(target: HTMLElement, threshold = 24): boolean {
  const { scrollTop, clientHeight, scrollHeight } = getScrollMetrics(target)
  return scrollTop + clientHeight >= scrollHeight - threshold
}

function isAnchorVisibleWithinContainer(
  anchorRect: DOMRect,
  containerRect: DOMRect,
  margin = 12,
): boolean {
  return anchorRect.top >= containerRect.top + margin
    && anchorRect.bottom <= containerRect.bottom - margin
}

/**
 * 工作区页面通用壳组件。
 * 用于统一“顶部固定，内容区独立滚动”的页面结构。
 */
export default function WorkspacePageShell({
  header,
  children,
  bodyRef,
  shellRef,
  shellClassName,
  headerClassName,
  bodyClassName,
  bodyInnerClassName,
  bodyPadding = 16,
  shellStyle,
  headerStyle,
  bodyStyle,
  bodyInnerStyle,
  enableAutoFollow = false,
}: WorkspacePageShellProps) {
  const internalBodyRef = React.useRef<HTMLDivElement | null>(null)
  const autoFollowEnabledRef = React.useRef(enableAutoFollow)
  const scrollPendingRef = React.useRef(false)

  React.useEffect(() => {
    autoFollowEnabledRef.current = enableAutoFollow
  }, [enableAutoFollow])

  const setBodyRefs = React.useCallback((node: HTMLDivElement | null) => {
    internalBodyRef.current = node
    if (!bodyRef) return
    if (typeof bodyRef === 'function') {
      bodyRef(node)
      return
    }
    ;(bodyRef as React.MutableRefObject<HTMLDivElement | null>).current = node
  }, [bodyRef])

  React.useEffect(() => {
    const node = internalBodyRef.current
    if (!node || !enableAutoFollow) return

    const handleScroll = () => {
      autoFollowEnabledRef.current = isScrollTargetNearBottom(node)
    }

    handleScroll()
    node.addEventListener('scroll', handleScroll, { passive: true })
    return () => {
      node.removeEventListener('scroll', handleScroll)
    }
  }, [enableAutoFollow])

  const scrollToBottom = React.useCallback((behavior: ScrollBehavior = 'auto', force = false) => {
    const node = internalBodyRef.current
    if (!node) return
    if (!force && !autoFollowEnabledRef.current) return
    if (scrollPendingRef.current) return

    scrollPendingRef.current = true
    requestAnimationFrame(() => {
      scrollPendingRef.current = false
      const current = internalBodyRef.current
      if (!current) return
      if (!force && !autoFollowEnabledRef.current) return
      current.scrollTo({
        top: current.scrollHeight,
        behavior,
      })
    })
  }, [])

  const scrollElementIntoView = React.useCallback((
    element: HTMLElement,
    behavior: ScrollBehavior = 'auto',
    force = false,
    margin = 12,
  ) => {
    const scrollContainer = internalBodyRef.current
    if (!scrollContainer) return
    if (!force && !autoFollowEnabledRef.current) return

    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        const currentContainer = internalBodyRef.current
        if (!currentContainer) return
        if (!force && !autoFollowEnabledRef.current) return

        const containerRect = currentContainer.getBoundingClientRect()
        const anchorRect = element.getBoundingClientRect()

        if (isAnchorVisibleWithinContainer(anchorRect, containerRect, margin)) {
          return
        }

        let nextTop = currentContainer.scrollTop
        if (anchorRect.top < containerRect.top + margin) {
          nextTop += anchorRect.top - (containerRect.top + margin)
        } else if (anchorRect.bottom > containerRect.bottom - margin) {
          nextTop += anchorRect.bottom - (containerRect.bottom - margin)
        }

        currentContainer.scrollTo({
          top: Math.max(0, nextTop),
          behavior,
        })
      })
    })
  }, [])

  React.useImperativeHandle(shellRef, () => ({
    getBodyElement: () => internalBodyRef.current,
    isAutoFollowEnabled: () => autoFollowEnabledRef.current,
    scrollToBottom,
    scrollElementIntoView,
  }), [scrollElementIntoView, scrollToBottom])

  return (
    <div className={shellClassName} style={{ ...shellBaseStyle, ...shellStyle }}>
      {header && (
        <div className={headerClassName} style={{ ...headerBaseStyle, ...headerStyle }}>
          {header}
        </div>
      )}
      <div ref={setBodyRefs} className={bodyClassName} style={{ ...bodyBaseStyle, ...bodyStyle }}>
        <div className={bodyInnerClassName} style={{ ...bodyInnerBaseStyle, padding: bodyPadding, ...bodyInnerStyle }}>
          {children}
        </div>
      </div>
    </div>
  )
}

/**
 * 页面外层容器样式。
 * 负责固定头部和可滚动主体的纵向分栏。
 */
const shellBaseStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
  background: 'var(--bg-primary)',
}

/**
 * 固定头部容器样式。
 * 始终停留在页面顶部，不参与内容区滚动。
 */
const headerBaseStyle: React.CSSProperties = {
  flexShrink: 0,
  minWidth: 0,
}

/**
 * 主滚动容器样式。
 * 页面主体内容统一在这个容器内滚动。
 */
const bodyBaseStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  minWidth: 0,
  overflowY: 'auto',
  overflowX: 'hidden',
  // 阻止内部滚动容器越界回弹向视口链式传递，避免移动端嵌套滚动时页面闪烁。
  overscrollBehavior: 'contain',
}

/**
 * 主体内容内层容器样式。
 * 用于统一控制内容区的间距和布局。
 */
const bodyInnerBaseStyle: React.CSSProperties = {
  minHeight: '100%',
  boxSizing: 'border-box',
  display: 'flex',
  flexDirection: 'column',
}
