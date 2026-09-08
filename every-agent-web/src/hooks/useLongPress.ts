import React, { useCallback, useRef } from 'react'

export type LongPressPoint = { x: number; y: number }

export type LongPressHandlers = {
  onPointerDown: (event: React.PointerEvent) => void
  onPointerMove: (event: React.PointerEvent) => void
  onPointerUp: (event: React.PointerEvent) => void
  onPointerLeave: (event: React.PointerEvent) => void
  /** 移动端阻止原生长按菜单；桌面端为空操作。展开到行元素即可。 */
  onContextMenu: (event: React.MouseEvent) => void
}

type UseLongPressOptions = {
  /** 长按触发阈值（毫秒），默认 500。 */
  delay?: number
  /** 仅在移动端挂监听；桌面端处理器为空操作。 */
  isMobile?: boolean
  /** 长按触发回调，传入被按下的行 DOM 节点与触摸点坐标。 */
  onLongPress: (target: HTMLElement, point: LongPressPoint) => void
  /** 触摸移动超过该像素阈值即取消（避免滚动误触），默认 10。 */
  moveTolerance?: number
}

/**
 * 移动端长按手势。
 *
 * 用法：把返回的 handlers 展开到行元素上；长按达 delay 后调用 onLongPress(node, point)。
 * 触发后用 wasLongPressed() 判断，以便在随后的 onClick 中抑制「打开项」等默认动作。
 *
 * 返回的 handlers 含 onContextMenu：移动端 preventDefault 抑制系统原生长按菜单，
 * 桌面端为空操作——消费者无需再单独写 onContextMenu。
 *
 * 仅当 isMobile 为 true 时启用，桌面端处理器直接空过。
 */
export function useLongPress({
  delay = 500,
  isMobile = true,
  onLongPress,
  moveTolerance = 10,
}: UseLongPressOptions): LongPressHandlers & { wasLongPressed: () => boolean } {
  const timerRef = useRef<number | null>(null)
  const longPressedRef = useRef(false)
  const startRef = useRef<LongPressPoint | null>(null)

  const clear = useCallback(() => {
    if (timerRef.current != null) {
      window.clearTimeout(timerRef.current)
      timerRef.current = null
    }
    startRef.current = null
  }, [])

  const onPointerDown = useCallback(
    (event: React.PointerEvent) => {
      if (!isMobile) return
      longPressedRef.current = false
      const point: LongPressPoint = { x: event.clientX, y: event.clientY }
      startRef.current = point
      const node = event.currentTarget as HTMLElement
      timerRef.current = window.setTimeout(() => {
        longPressedRef.current = true
        onLongPress(node, point)
      }, delay)
    },
    [delay, isMobile, onLongPress],
  )

  const onPointerMove = useCallback(
    (event: React.PointerEvent) => {
      if (!startRef.current) return
      const dx = Math.abs(event.clientX - startRef.current.x)
      const dy = Math.abs(event.clientY - startRef.current.y)
      if (dx > moveTolerance || dy > moveTolerance) {
        clear()
      }
    },
    [clear, moveTolerance],
  )

  const onPointerUp = useCallback(() => clear(), [clear])
  const onPointerLeave = useCallback(() => clear(), [clear])

  const onContextMenu = useCallback(
    (event: React.MouseEvent) => {
      if (isMobile) event.preventDefault()
    },
    [isMobile],
  )

  const wasLongPressed = useCallback(() => {
    const value = longPressedRef.current
    longPressedRef.current = false
    return value
  }, [])

  return { onPointerDown, onPointerMove, onPointerUp, onPointerLeave, onContextMenu, wasLongPressed }
}
