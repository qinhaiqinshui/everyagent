import React from 'react'
import { Button } from '@/components/shared/ui'

/**
 * 桌面侧边栏允许的最小宽度。
 */
const DEFAULT_MIN_DESKTOP_SIZE = 220

/**
 * 桌面侧边栏允许的最大宽度。
 */
const DEFAULT_MAX_DESKTOP_SIZE = 560

/**
 * 移动端侧边栏允许的最小高度。
 */
const DEFAULT_MIN_MOBILE_SIZE = 220

/**
 * 桌面端侧边栏右侧拖动区域宽度。
 */
const DESKTOP_RESIZE_GUTTER_SIZE = 2

/**
 * 移动端侧边栏顶部拖动区域高度。
 */
const MOBILE_RESIZE_GUTTER_SIZE = 12

/**
 * 可拖拽侧边栏容器参数。
 */
export type ResizableSidebarContainerProps = {
  /** 当前是否处于移动端布局。 */
  isMobile: boolean
  /** 侧边栏当前是否展开。 */
  open: boolean
  /** 桌面端当前宽度。 */
  desktopSize: number
  /** 移动端当前高度。 */
  mobileSize: number
  /** 移动端当前允许的最大高度。 */
  mobileMaxSize: number
  /** 桌面端最小宽度。 */
  minDesktopSize?: number
  /** 桌面端最大宽度。 */
  maxDesktopSize?: number
  /** 移动端最小高度。 */
  minMobileSize?: number
  /** 桌面端尺寸变化回调。 */
  onDesktopSizeChange: (value: number) => void
  /** 移动端尺寸变化回调。 */
  onMobileSizeChange: (value: number) => void
  /** 容器内部内容。 */
  children: React.ReactNode
}

/**
 * 当前拖拽会话状态。
 */
type ResizeDragState = {
  /** 当前是否正在拖拽。 */
  active: boolean
  /** 当前拖拽方向。 */
  axis: 'horizontal' | 'vertical'
  /** 拖拽起点坐标。 */
  startPointer: number
  /** 拖拽开始时的尺寸。 */
  startSize: number
  /** 当前尺寸允许的最小值。 */
  minSize: number
  /** 当前尺寸允许的最大值。 */
  maxSize: number
}

/**
 * 通用可拖拽侧边栏容器。
 * 桌面端通过右侧拖拽条调整宽度，移动端通过顶部拖拽条调整高度。
 */
export default function ResizableSidebarContainer({
  isMobile,
  open,
  desktopSize,
  mobileSize,
  mobileMaxSize,
  minDesktopSize = DEFAULT_MIN_DESKTOP_SIZE,
  maxDesktopSize = DEFAULT_MAX_DESKTOP_SIZE,
  minMobileSize = DEFAULT_MIN_MOBILE_SIZE,
  onDesktopSizeChange,
  onMobileSizeChange,
  children,
}: ResizableSidebarContainerProps) {
  const dragStateRef = React.useRef<ResizeDragState>({
    active: false,
    axis: 'horizontal',
    startPointer: 0,
    startSize: desktopSize,
    minSize: minDesktopSize,
    maxSize: maxDesktopSize,
  })

  React.useEffect(() => {
    /**
     * 全局响应拖拽过程中的指针移动。
     */
    const handlePointerMove = (event: PointerEvent) => {
      const dragState = dragStateRef.current
      if (!dragState.active) {
        return
      }

      const delta = dragState.axis === 'horizontal'
        ? event.clientX - dragState.startPointer
        : dragState.startPointer - event.clientY
      const nextSize = clampSize(
        dragState.startSize + delta,
        dragState.minSize,
        dragState.maxSize,
      )

      if (dragState.axis === 'horizontal') {
        onDesktopSizeChange(nextSize)
        return
      }
      onMobileSizeChange(nextSize)
    }

    /**
     * 任意位置抬手都应结束当前拖拽。
     */
    const handlePointerUp = () => {
      dragStateRef.current.active = false
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
    }

    window.addEventListener('pointermove', handlePointerMove)
    window.addEventListener('pointerup', handlePointerUp)
    window.addEventListener('pointercancel', handlePointerUp)
    return () => {
      window.removeEventListener('pointermove', handlePointerMove)
      window.removeEventListener('pointerup', handlePointerUp)
      window.removeEventListener('pointercancel', handlePointerUp)
      document.body.style.cursor = ''
      document.body.style.userSelect = ''
    }
  }, [onDesktopSizeChange, onMobileSizeChange])

  /**
   * 启动桌面端横向拖拽。
   */
  const handleDesktopResizeStart = (event: React.PointerEvent<HTMLButtonElement>) => {
    if (isMobile || !open) {
      return
    }
    dragStateRef.current = {
      active: true,
      axis: 'horizontal',
      startPointer: event.clientX,
      startSize: desktopSize,
      minSize: minDesktopSize,
      maxSize: maxDesktopSize,
    }
    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'
    event.currentTarget.setPointerCapture(event.pointerId)
    event.preventDefault()
  }

  /**
   * 启动移动端纵向拖拽。
   */
  const handleMobileResizeStart = (event: React.PointerEvent<HTMLButtonElement>) => {
    if (!isMobile || !open) {
      return
    }
    dragStateRef.current = {
      active: true,
      axis: 'vertical',
      startPointer: event.clientY,
      startSize: mobileSize,
      minSize: minMobileSize,
      maxSize: Math.max(minMobileSize, mobileMaxSize),
    }
    document.body.style.cursor = 'row-resize'
    document.body.style.userSelect = 'none'
    event.currentTarget.setPointerCapture(event.pointerId)
    event.preventDefault()
  }

  const resolvedMobileSize = clampSize(mobileSize, minMobileSize, Math.max(minMobileSize, mobileMaxSize))
  const resolvedDesktopSize = clampSize(desktopSize, minDesktopSize, maxDesktopSize)

  return (
    <div
      className={`layout-sidebar-shell ${isMobile ? 'is-mobile' : 'is-desktop'}${open ? ' is-open' : ''}`}
      style={{
        ...sidebarShellStyle,
        flexDirection: isMobile ? 'column' : 'row',
        width: isMobile ? '100%' : (open ? `${resolvedDesktopSize + DESKTOP_RESIZE_GUTTER_SIZE}px` : 0),
        height: isMobile ? `${resolvedMobileSize}px` : '100%',
        minHeight: isMobile ? 0 : '100%',
        maxHeight: isMobile ? `${mobileMaxSize}px` : undefined,
        border: isMobile ? 'none' : undefined,
        opacity: open ? 1 : 0,
        pointerEvents: isMobile ? (open ? 'auto' : 'none') : 'auto',
        transition: isMobile
          ? 'opacity 180ms ease, transform 220ms cubic-bezier(0.22, 1, 0.36, 1), visibility 220ms ease, height 180ms ease'
          : 'width 220ms ease, opacity 180ms ease',
        willChange: isMobile ? 'opacity, transform, height' : 'width, opacity',
      }}
      aria-hidden={!open}
    >
      <div
        style={{
          ...sidebarSurfaceStyle,
          width: isMobile ? '100%' : `${resolvedDesktopSize}px`,
          border: isMobile ? '1px solid var(--border)' : undefined,
          borderRight: isMobile ? undefined : sidebarSurfaceStyle.borderRight,
          borderRadius: isMobile
            ? 'var(--radius-xl) var(--radius-xl) 0 0'
            : '0 var(--radius-xl) var(--radius-xl) 0',
        }}
      >
        {isMobile ? (
          <Button
            variant="ghost"
            aria-label="拖动调整侧边栏高度"
            onPointerDown={handleMobileResizeStart}
            style={mobileResizeHandleStyle}
          >
            <span style={mobileResizeGripStyle} />
          </Button>
        ) : null}
        <div style={sidebarContentStyle}>
          {children}
        </div>
      </div>
      {!isMobile && open ? (
        <Button
          variant="ghost"
          aria-label="拖动调整侧边栏宽度"
          onPointerDown={handleDesktopResizeStart}
          style={desktopResizeHandleStyle}
        >
          <span
            className="layout-sidebar-shell__desktop-resize-grip"
            style={desktopResizeGripStyle}
          />
        </Button>
      ) : null}
    </div>
  )
}

/**
 * 把尺寸限制在指定范围内。
 */
function clampSize(value: number, minValue: number, maxValue: number): number {
  return Math.min(maxValue, Math.max(minValue, value))
}

const sidebarShellStyle: React.CSSProperties = {
  display: 'flex',
  flexShrink: 0,
  position: 'relative',
  minWidth: 0,
  minHeight: 0,
  overflow: 'hidden',
}

const sidebarSurfaceStyle: React.CSSProperties = {
  position: 'relative',
  flexShrink: 0,
  minWidth: 0,
  minHeight: 0,
  height: '100%',
  overflow: 'hidden',
  background: 'var(--bg-secondary)',
  borderRight: '1px solid var(--border)',
}

const sidebarContentStyle: React.CSSProperties = {
  position: 'relative',
  flex: 1,
  minWidth: 0,
  minHeight: 0,
  width: '100%',
  height: '100%',
  overflow: 'hidden',
  display: 'flex',
  flexDirection: 'column',
}

const desktopResizeHandleStyle: React.CSSProperties = {
  position: 'relative',
  width: DESKTOP_RESIZE_GUTTER_SIZE,
  minWidth: DESKTOP_RESIZE_GUTTER_SIZE,
  height: '100%',
  border: 'none',
  borderRadius: 0,
  background: 'transparent',
  boxShadow: 'none',
  cursor: 'col-resize',
  padding: 0,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  flexShrink: 0,
  zIndex: 5,
}

const desktopResizeGripStyle: React.CSSProperties = {
  width: 1,
  height: 56,
  borderRadius: 999,
  background: 'color-mix(in srgb, var(--border-strong) 72%, transparent)',
  boxShadow: '0 0 0 1px color-mix(in srgb, var(--bg-secondary) 82%, transparent)',
}

const mobileResizeHandleStyle: React.CSSProperties = {
  width: '100%',
  minWidth: 0,
  minHeight: MOBILE_RESIZE_GUTTER_SIZE,
  height: MOBILE_RESIZE_GUTTER_SIZE,
  border: 'none',
  borderRadius: 0,
  background: 'color-mix(in srgb, var(--bg-secondary) 96%, transparent)',
  boxShadow: 'none',
  cursor: 'row-resize',
  padding: 0,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  flexShrink: 0,
}

const mobileResizeGripStyle: React.CSSProperties = {
  width: 42,
  height: 4,
  borderRadius: 999,
  background: 'color-mix(in srgb, var(--border-strong) 78%, transparent)',
}
