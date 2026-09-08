import React from 'react'
import Button from '@/components/shared/ui/Button'
import IconButton from '@/components/shared/ui/IconButton'

export type RightDrawerProps = {
  /** 抽屉是否展开。受控属性，由父组件维护。 */
  open: boolean
  /** 展开状态变化回调（点击把手、关闭按钮或按 Esc 时触发）。 */
  onOpenChange: (open: boolean) => void
  /** 抽屉标题，显示在头部左侧。 */
  title?: React.ReactNode
  /** 抽屉宽度（px），默认 360。移动端会被 max-width 约束。 */
  width?: number
  /** 是否在收起时显示右边缘竖向把手。默认 true。 */
  showHandle?: boolean
  /** 收起状态下把手上展示的文案/图标（竖向排列）。默认取 title。 */
  handleLabel?: React.ReactNode
  /** 把手的无障碍标签，默认取 handleLabel 或 title 的文本。 */
  handleAriaLabel?: string
  /** 抽屉的无障碍标签。 */
  ariaLabel?: string
  /** 抽屉主体内容。 */
  children: React.ReactNode
  /** 额外挂在根节点上的 class。 */
  className?: string
}

const DEFAULT_WIDTH = 360
const DEFAULT_Z_INDEX = 41

/**
 * 通用右侧抽屉容器。
 *
 * - 展开时从视口右边缘滑出，覆盖在内容之上（非模态，不阻挡背景交互）。
 * - 收起时若 showHandle，则在视口右边缘显示一个竖向小把手（类似迅雷隐藏在右侧）。
 * - 点击把手弹出抽屉；抽屉头部提供关闭按钮；展开时按 Esc 收起。
 *
 * 抽屉为 position: fixed，锚定视口右边缘，不依赖任何 ancestors 的布局。
 */
export default function RightDrawer({
  open,
  onOpenChange,
  title,
  width = DEFAULT_WIDTH,
  showHandle = true,
  handleLabel,
  handleAriaLabel,
  ariaLabel,
  children,
  className,
}: RightDrawerProps) {
  const rootRef = React.useRef<HTMLDivElement | null>(null)
  const resolvedHandleLabel = handleLabel ?? title ?? '面板'
  const resolvedHandleAriaLabel = handleAriaLabel ?? (typeof resolvedHandleLabel === 'string' ? resolvedHandleLabel : title != null ? String(title) : '打开面板')

  // 展开态下按 Esc 收起抽屉。
  React.useEffect(() => {
    if (!open) return
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        onOpenChange(false)
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => {
      window.removeEventListener('keydown', handleKeyDown)
    }
  }, [open, onOpenChange])

  const handleRootRef = React.useCallback((node: HTMLDivElement | null) => {
    rootRef.current = node
  }, [])

  const rootClassName = ['right-drawer', open ? 'is-open' : '', className].filter(Boolean).join(' ')

  return (
    <>
      {showHandle && !open ? (
        <Button
          type="button"
          variant="ghost"
          className="right-drawer__handle"
          onClick={() => onOpenChange(true)}
          aria-label={resolvedHandleAriaLabel}
          title={resolvedHandleAriaLabel}
        >
          <span className="right-drawer__handle-label">{resolvedHandleLabel}</span>
        </Button>
      ) : null}

      <aside
        ref={handleRootRef}
        className={rootClassName}
        role="dialog"
        aria-modal={false}
        aria-label={ariaLabel ?? (typeof title === 'string' ? title : '面板')}
        aria-hidden={!open}
        style={{ ['--right-drawer-width' as string]: `${width}px`, zIndex: DEFAULT_Z_INDEX } as React.CSSProperties}
      >
        <div className="right-drawer__header">
          <span className="right-drawer__title">{title}</span>
          <IconButton
            variant="ghost"
            className="right-drawer__close"
            icon={<span>×</span>}
            onClick={() => onOpenChange(false)}
            title="收起 (Esc)"
            aria-label="收起面板"
          />
        </div>
        <div className="right-drawer__body">{children}</div>
      </aside>
    </>
  )
}
