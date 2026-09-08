import React, { forwardRef, useImperativeHandle, useRef, useState } from 'react'
import { cn } from './cn'
import { MoreHorizontalIcon } from '../AppGlyphs'
import { MenuList, type MenuListItem, type MenuListAnchorPoint } from './Menu'

/** 列表行「更多操作」菜单项。与 MenuList 的 MenuListItem 结构一致。 */
export type ListRowActionItem = MenuListItem

/** 列表行「更多操作」对外句柄，供父级在移动端长按时直接打开菜单。 */
export type ListRowActionsHandle = {
  /**
   * 打开菜单。
   * @param anchorEl 行节点作为弹层锚点（无 point 时用于定位；也用于「点击外部关闭」判定）。
   * @param point    鼠标/触摸点坐标（clientX/clientY）。桌面端右键与移动端长按都会传入，
   *                 用于按鼠标或手指位置精确定位；省略则按 anchorEl 定位。
   */
  openMenu: (anchorEl?: HTMLElement | null, point?: MenuListAnchorPoint | null) => void
  closeMenu: () => void
}

export type ListRowActionsProps = {
  items: ListRowActionItem[]
  title?: string
  className?: string
  disabled?: boolean
  /** 是否为移动端。移动端隐藏触发按钮、改用长按；弹层锚定到 openMenu 传入的行节点。 */
  isMobile?: boolean
}

/**
 * 列表行「更多操作」统一按钮。
 *
 * 行为约定（与 AGENTS.md「列表项更多操作菜单统一规范」对齐）：
 * - 更多按钮默认隐藏。
 * - 桌面端：鼠标移到整行（`.ui-row`）时显示，由 CSS 控制；点击按钮弹菜单。
 * - 移动端（≤768）：触发按钮不渲染（CSS 隐藏）；由父级行长按经 `openMenu`
 *   直接弹出菜单，弹层锚定到传入的行节点。
 * - 弹层渲染统一委托 `MenuList`（`ui-menu` / `ui-menu__item`），样式与聊天输入
 *   @/ 弹层一致。
 */
const ListRowActions = forwardRef<ListRowActionsHandle, ListRowActionsProps>(function ListRowActions(
  { items, title = '更多操作', className, disabled = false, isMobile = false },
  ref,
) {
  const rootRef = useRef<HTMLDivElement | null>(null)
  const triggerRef = useRef<HTMLButtonElement | null>(null)
  const [open, setOpen] = useState(false)
  const [externalAnchor, setExternalAnchor] = useState<HTMLElement | null>(null)
  const [anchorPoint, setAnchorPoint] = useState<MenuListAnchorPoint | null>(null)
  const closeMenu = React.useCallback(() => {
    setOpen(false)
    setExternalAnchor(null)
    setAnchorPoint(null)
  }, [])

  useImperativeHandle(
    ref,
    () => ({
      openMenu: (anchorEl?: HTMLElement | null, point?: MenuListAnchorPoint | null) => {
        setExternalAnchor(anchorEl ?? null)
        setAnchorPoint(point ?? null)
        setOpen(true)
      },
      closeMenu,
    }),
    [closeMenu],
  )

  const listItems = items as MenuListItem[]
  if (listItems.length === 0) {
    return null
  }

  const anchor = externalAnchor ?? triggerRef.current

  return (
    <div
      ref={rootRef}
      className={cn('ui-row-actions', className)}
      onClick={(event) => event.stopPropagation()}
    >
      <button
        ref={triggerRef}
        type="button"
        className="ui-row-actions__trigger"
        title={title}
        aria-label={title}
        aria-haspopup="menu"
        aria-expanded={open}
        disabled={disabled}
        onClick={() => {
          if (open) {
            closeMenu()
            return
          }
          setOpen(true)
        }}
      >
        <MoreHorizontalIcon size={13} />
      </button>
      {open && typeof document !== 'undefined' ? (
        <MenuList
          anchor={anchor}
          anchorPoint={anchorPoint}
          anchorPointMode={isMobile ? 'center' : 'top-start'}
          items={listItems}
          onClose={closeMenu}
          title={title}
        />
      ) : null}
    </div>
  )
})

export default ListRowActions
