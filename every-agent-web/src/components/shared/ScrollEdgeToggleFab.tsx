import React from 'react'
import IconButton from '@/components/shared/ui/IconButton'

type ScrollTargetRef = React.RefObject<HTMLElement | null>

export type ScrollEdgeToggleFabProps = {
  scrollTargetRefs: ScrollTargetRef[]
  margin?: number
  size?: number
}

const DEFAULT_MARGIN = 20
const DEFAULT_SIZE = 23
const TOP_THRESHOLD = 24

export default function ScrollEdgeToggleFab({
  scrollTargetRefs,
  margin = DEFAULT_MARGIN,
  size = DEFAULT_SIZE,
}: ScrollEdgeToggleFabProps) {
  const [scrollable, setScrollable] = React.useState(false)
  const [atTop, setAtTop] = React.useState(true)

  const getTargets = React.useCallback(() => {
    return scrollTargetRefs
      .map((ref) => ref.current)
      .filter((node): node is HTMLElement => Boolean(node))
  }, [scrollTargetRefs])

  const updateScrollState = React.useCallback(() => {
    const targets = getTargets()
    if (targets.length === 0) {
      setScrollable(false)
      setAtTop(true)
      return
    }
    const nextScrollable = targets.some((node) => node.scrollHeight > node.clientHeight + 4)
    const nextAtTop = targets.every((node) => node.scrollTop <= TOP_THRESHOLD)
    setScrollable(nextScrollable)
    setAtTop(nextAtTop)
  }, [getTargets])

  React.useEffect(() => {
    updateScrollState()
    const targets = getTargets()
    const cleanups: Array<() => void> = []

    targets.forEach((node) => {
      const handleScroll = () => updateScrollState()
      node.addEventListener('scroll', handleScroll, { passive: true })
      cleanups.push(() => node.removeEventListener('scroll', handleScroll))
    })

    // 重新计算可滚动/是否在顶状态：被滚动容器尺寸变化时同步刷新可见性。
    const handleWindowResize = () => updateScrollState()
    window.addEventListener('resize', handleWindowResize, { passive: true })
    cleanups.push(() => window.removeEventListener('resize', handleWindowResize))

    if (typeof ResizeObserver !== 'undefined') {
      const resizeObserver = new ResizeObserver(() => updateScrollState())
      targets.forEach((node) => resizeObserver.observe(node))
      cleanups.push(() => resizeObserver.disconnect())
    }

    return () => {
      cleanups.forEach((cleanup) => cleanup())
    }
  }, [getTargets, scrollTargetRefs, updateScrollState])

  const scrollTargetsToTop = React.useCallback(() => {
    getTargets().forEach((node) => {
      if (typeof node.scrollTo === 'function') {
        node.scrollTo({ top: 0, behavior: 'smooth' })
        return
      }
      node.scrollTop = 0
    })
  }, [getTargets])

  const scrollTargetsToBottom = React.useCallback(() => {
    getTargets().forEach((node) => {
      const nextTop = Math.max(0, node.scrollHeight - node.clientHeight)
      if (typeof node.scrollTo === 'function') {
        node.scrollTo({ top: nextTop, behavior: 'smooth' })
        return
      }
      node.scrollTop = nextTop
    })
  }, [getTargets])

  const handleClick = React.useCallback(() => {
    if (atTop) {
      scrollTargetsToBottom()
      return
    }
    scrollTargetsToTop()
  }, [atTop, scrollTargetsToBottom, scrollTargetsToTop])

  if (!scrollable) {
    return null
  }

  return (
    <IconButton
      variant="ghost"
      aria-label={atTop ? '滚动到底部' : '滚动到顶部'}
      icon={<span style={fabIconStyle}>{atTop ? '↓' : '↑'}</span>}
      onClick={handleClick}
      style={{
        ...fabStyle,
        width: size,
        height: size,
        right: margin,
        bottom: margin,
      }}
      title={atTop ? '点击滚动到底部' : '点击滚动到顶部'}
    />
  )
}

const fabStyle: React.CSSProperties = {
  position: 'absolute',
  zIndex: 20,
  border: '1px solid color-mix(in srgb, var(--accent-blue) 28%, var(--border))',
  borderRadius: 999,
  background: 'color-mix(in srgb, var(--bg-secondary) 90%, var(--accent-blue-dim) 10%)',
  color: 'var(--text-primary)',
  boxShadow: '0 12px 30px rgba(0, 0, 0, 0.22)',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  userSelect: 'none',
  backdropFilter: 'blur(8px)',
}

const fabIconStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  lineHeight: 1,
  fontWeight: 700,
}
