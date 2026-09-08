import React, { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { cn } from './cn'

export type PopoverPlacement = 'bottom-start' | 'bottom-end' | 'top-start' | 'top-end'

export type PopoverProps = {
  /** 是否显示。由父组件控制。 */
  open: boolean
  /** 锚点元素 ref（触发按钮）。 */
  anchorRef: React.RefObject<HTMLElement | null>
  /** 关闭回调（点击外部 / Esc / 滚动 / resize）。 */
  onClose: () => void
  children: React.ReactNode
  /** 首选方位，空间不足时自动翻转。 */
  placement?: PopoverPlacement
  className?: string
  /** 点击浮层内非禁用菜单项时自动关闭（菜单场景）。 */
  closeOnInnerClick?: boolean
}

const GAP = 6

/**
 * 通用浮层容器。负责定位（基于锚点矩形）、点击外部关闭、Esc / 滚动 / resize 关闭。
 * 不负责内部结构——内部由 Menu / Dialog 等填充。
 */
export default function Popover({
  open,
  anchorRef,
  onClose,
  children,
  placement = 'bottom-start',
  className,
  closeOnInnerClick = false,
}: PopoverProps) {
  const panelRef = useRef<HTMLDivElement>(null)
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null)

  useLayoutEffect(() => {
    if (!open) {
      setPos(null)
      return
    }
    const anchor = anchorRef.current
    const panel = panelRef.current
    if (!anchor || !panel) return
    const rect = anchor.getBoundingClientRect()
    const ph = panel.offsetHeight
    const pw = panel.offsetWidth
    const vh = window.innerHeight
    const vw = window.innerWidth
    const alignEnd = placement.endsWith('end')
    let top = placement.startsWith('top') ? rect.top - ph - GAP : rect.bottom + GAP
    let left = alignEnd ? rect.right - pw : rect.left
    if (top + ph > vh - GAP) top = rect.top - ph - GAP
    if (top < GAP) top = GAP
    if (left + pw > vw - GAP) left = vw - pw - GAP
    if (left < GAP) left = GAP
    setPos({ top, left })
  }, [open, anchorRef, placement, children])

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      const t = e.target as Node
      if (panelRef.current?.contains(t)) return
      if (anchorRef.current?.contains(t)) return
      onClose()
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('mousedown', onDown)
    document.addEventListener('keydown', onKey)
    window.addEventListener('resize', onClose)
    window.addEventListener('scroll', onClose, true)
    return () => {
      document.removeEventListener('mousedown', onDown)
      document.removeEventListener('keydown', onKey)
      window.removeEventListener('resize', onClose)
      window.removeEventListener('scroll', onClose, true)
    }
  }, [open, anchorRef, onClose])

  if (!open || pos == null) return null

  return createPortal(
    <div
      ref={panelRef}
      className={cn('ui-popover', className)}
      style={{ top: pos.top, left: pos.left }}
      role="dialog"
      onClickCapture={
        closeOnInnerClick
          ? (e) => {
              const target = e.target as HTMLElement
              const item = target.closest('.ui-menu__item')
              if (item && !item.classList.contains('is-disabled')) onClose()
            }
          : undefined
      }
    >
      {children}
    </div>,
    document.body,
  )
}
