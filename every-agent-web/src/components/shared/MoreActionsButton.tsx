import React from 'react'
import { MoreHorizontalIcon } from './AppGlyphs'
import { MenuList, type MenuListItem } from './ui/Menu'
import IconButton from '@/components/shared/ui/IconButton'

export type MoreActionItem = MenuListItem

/**
 * 工具栏「更多操作」按钮（⋯）。与列表行 `ListRowActions` 共用 `MenuList` 弹层，
 * 因此两者视觉完全统一（均走 `.ui-menu` / `.ui-menu__item`）。
 *
 * 区别仅在于触发形态：`MoreActionsButton` 始终可见（用于表头/工具栏），
 * `ListRowActions` 默认隐藏、由行 hover / 点击切换显隐（用于列表项）。
 */
export default function MoreActionsButton({
  items,
  title = '更多操作',
  disabled = false,
  className,
}: {
  items: MoreActionItem[]
  title?: string
  disabled?: boolean
  className?: string
}) {
  const triggerRef = React.useRef<HTMLButtonElement | null>(null)
  const [open, setOpen] = React.useState(false)
  const closeMenu = React.useCallback(() => setOpen(false), [])

  const listItems = items as MenuListItem[]
  if (listItems.length === 0) {
    return null
  }

  return (
    <div className="ui-more-button" onClick={(event) => event.stopPropagation()}>
      <IconButton
        ref={triggerRef}
        type="button"
        variant="ghost"
        size="sm"
        className="ui-more-button__trigger"
        title={title}
        aria-label={title}
        aria-haspopup="menu"
        aria-expanded={open}
        disabled={disabled}
        icon={<MoreHorizontalIcon size={13} />}
        onClick={() => {
          if (open) {
            closeMenu()
            return
          }
          setOpen(true)
        }}
      />
      {open && typeof document !== 'undefined' ? (
        <MenuList anchor={triggerRef.current} items={listItems} onClose={closeMenu} title={title} className={className} />
      ) : null}
    </div>
  )
}
