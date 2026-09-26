/**
 * git 插件内部弹出式菜单（自宿主 components/shared/ui/Menu 与
 * components/shared/MoreActionsButton 复制，插件不引用宿主模块）。
 *
 * MenuList：portal 浮层选项列表，含子菜单与边缘夹紧；
 * MoreActionsButton：工具栏「更多操作」（⋯）按钮，共用 MenuList 弹层。
 */
import React from 'react'
import { createPortal } from 'react-dom'
import { ChevronRightIcon, MoreHorizontalIcon } from './icons'

type ClassValue = string | false | null | undefined
function cn(...values: ClassValue[]): string {
  return values.filter(Boolean).join(' ')
}

/** 菜单列表条目（git 插件专用）。 */
export type MenuListItem = {
  key: string
  label?: React.ReactNode
  description?: React.ReactNode
  icon?: React.ReactNode
  tag?: React.ReactNode
  danger?: boolean
  disabled?: boolean
  active?: boolean
  children?: MenuListItem[]
  render?: React.ReactNode
  onSelect?: () => void
}

/** 「更多操作」菜单条目（与 MenuListItem 同构）。 */
export type MoreActionItem = MenuListItem

type SubmenuLayout = {
  horizontal: 'left' | 'right'
  vertical: 'up' | 'down'
  top: number
  left: number
  maxHeight: number
  maxWidth: number
}

const MENU_EDGE_GAP = 8
const MENU_FLYOUT_GAP = 4
const SUBMENU_WIDTH_ESTIMATE = 188
const SUBMENU_ROW_HEIGHT_ESTIMATE = 32

export type MenuListAnchorPoint = { x: number; y: number }
export type MenuListAnchorPointMode = 'center' | 'top-start'

export type MenuListProps = {
  items: MenuListItem[]
  anchor: HTMLElement | null
  anchorPoint?: MenuListAnchorPoint | null
  anchorPointMode?: MenuListAnchorPointMode
  onClose: () => void
  title?: string
  className?: string
}

export function MenuList({ items, anchor, anchorPoint, anchorPointMode = 'center', onClose, title, className }: MenuListProps) {
  const menuRef = React.useRef<HTMLDivElement | null>(null)
  const submenuTriggerRefs = React.useRef(new Map<string, HTMLButtonElement | null>())
  const submenuPanelRefs = React.useRef(new Map<string, HTMLDivElement | null>())
  const [menuPosition, setMenuPosition] = React.useState<{ top: number; left: number } | null>(null)
  const [openPath, setOpenPath] = React.useState<string[]>([])
  const [submenuLayouts, setSubmenuLayouts] = React.useState<Record<string, SubmenuLayout>>({})

  const isDesktop = typeof window !== 'undefined' && !window.matchMedia('(max-width: 768px)').matches

  const updateSubmenuLayout = React.useCallback((path: string[], childCount?: number) => {
    const pathKey = path.join('/')
    const trigger = submenuTriggerRefs.current.get(pathKey)
    const submenu = submenuPanelRefs.current.get(pathKey)
    if (!trigger) return

    const rect = trigger.getBoundingClientRect()
    const submenuRect = submenu
      ? submenu.getBoundingClientRect()
      : {
          width: SUBMENU_WIDTH_ESTIMATE,
          height: Math.max(48, (childCount ?? 0) * SUBMENU_ROW_HEIGHT_ESTIMATE + 8),
        }
    const viewportWidth = window.innerWidth
    const viewportHeight = window.innerHeight

    const spaceRight = Math.max(0, viewportWidth - rect.right - MENU_FLYOUT_GAP - MENU_EDGE_GAP)
    const spaceLeft = Math.max(0, rect.left - MENU_FLYOUT_GAP - MENU_EDGE_GAP)
    const horizontal: SubmenuLayout['horizontal'] = spaceRight >= submenuRect.width
      ? 'right'
      : spaceLeft >= submenuRect.width
        ? 'left'
        : spaceRight >= spaceLeft
          ? 'right'
          : 'left'

    const spaceDown = Math.max(0, viewportHeight - rect.bottom - MENU_FLYOUT_GAP - MENU_EDGE_GAP)
    const spaceUp = Math.max(0, rect.top - MENU_FLYOUT_GAP - MENU_EDGE_GAP)
    const vertical: SubmenuLayout['vertical'] = spaceDown >= submenuRect.height
      ? 'down'
      : spaceUp >= submenuRect.height
        ? 'up'
        : spaceDown >= spaceUp
          ? 'down'
          : 'up'

    const maxWidth = Math.min(submenuRect.width, horizontal === 'right' ? spaceRight : spaceLeft)
    const maxHeight = Math.min(submenuRect.height, vertical === 'down' ? spaceDown : spaceUp)
    const nextLeft = horizontal === 'right'
      ? Math.min(rect.right + MENU_FLYOUT_GAP, viewportWidth - MENU_EDGE_GAP - maxWidth)
      : Math.max(MENU_EDGE_GAP, rect.left - MENU_FLYOUT_GAP - maxWidth)
    const nextTop = vertical === 'down'
      ? Math.min(rect.bottom + MENU_FLYOUT_GAP, viewportHeight - MENU_EDGE_GAP - maxHeight)
      : Math.max(MENU_EDGE_GAP, rect.top - MENU_FLYOUT_GAP - maxHeight)

    setSubmenuLayouts((current) => {
      const previous = current[pathKey]
      if (
        previous?.horizontal === horizontal
        && previous?.vertical === vertical
        && previous?.top === nextTop
        && previous?.left === nextLeft
        && previous?.maxHeight === maxHeight
        && previous?.maxWidth === maxWidth
      ) {
        return current
      }
      return {
        ...current,
        [pathKey]: { horizontal, vertical, top: nextTop, left: nextLeft, maxHeight, maxWidth },
      }
    })
  }, [])

  const openSubmenu = React.useCallback((path: string[], childCount?: number) => {
    setOpenPath(path)
    updateSubmenuLayout(path, childCount)
  }, [updateSubmenuLayout])

  const refreshOpenSubmenuLayout = React.useCallback(() => {
    if (openPath.length === 0) {
      return
    }
    updateSubmenuLayout(openPath)
  }, [openPath, updateSubmenuLayout])

  React.useEffect(() => {
    if (!anchor) return

    const updateMenuPosition = () => {
      const menu = menuRef.current
      if (!menu || !anchor) return

      const menuRect = menu.getBoundingClientRect()
      const viewportWidth = window.innerWidth
      const viewportHeight = window.innerHeight

      let nextLeft: number
      let nextTop: number

      if (anchorPoint) {
        if (anchorPointMode === 'top-start') {
          nextLeft = Math.max(MENU_EDGE_GAP, Math.min(anchorPoint.x, viewportWidth - menuRect.width - MENU_EDGE_GAP))
        } else {
          nextLeft = Math.max(MENU_EDGE_GAP, Math.min(anchorPoint.x - menuRect.width / 2, viewportWidth - menuRect.width - MENU_EDGE_GAP))
        }
        const preferBottomTop = anchorPoint.y + MENU_FLYOUT_GAP
        const preferTopTop = anchorPoint.y - menuRect.height - MENU_FLYOUT_GAP
        nextTop = preferBottomTop + menuRect.height <= viewportHeight - MENU_EDGE_GAP
          ? preferBottomTop
          : Math.max(MENU_EDGE_GAP, preferTopTop)
      } else {
        const triggerRect = anchor.getBoundingClientRect()
        nextLeft = Math.max(MENU_EDGE_GAP, Math.min(triggerRect.right - menuRect.width, viewportWidth - menuRect.width - MENU_EDGE_GAP))
        const preferBottomTop = triggerRect.bottom + MENU_FLYOUT_GAP
        const preferTopTop = triggerRect.top - menuRect.height - MENU_FLYOUT_GAP
        nextTop = preferBottomTop + menuRect.height <= viewportHeight - MENU_EDGE_GAP
          ? preferBottomTop
          : Math.max(MENU_EDGE_GAP, preferTopTop)
      }

      setMenuPosition({ top: nextTop, left: nextLeft })
    }

    const handlePointerDown = (event: PointerEvent) => {
      const target = event.target as Node
      if (!menuRef.current?.contains(target) && !anchor.contains(target)) {
        onClose()
      }
    }

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose()
      }
    }

    updateMenuPosition()
    refreshOpenSubmenuLayout()
    window.addEventListener('pointerdown', handlePointerDown)
    window.addEventListener('keydown', handleKeyDown)
    window.addEventListener('resize', updateMenuPosition)
    window.addEventListener('scroll', updateMenuPosition, true)
    window.addEventListener('resize', refreshOpenSubmenuLayout)
    window.addEventListener('scroll', refreshOpenSubmenuLayout, true)
    return () => {
      window.removeEventListener('pointerdown', handlePointerDown)
      window.removeEventListener('keydown', handleKeyDown)
      window.removeEventListener('resize', updateMenuPosition)
      window.removeEventListener('scroll', updateMenuPosition, true)
      window.removeEventListener('resize', refreshOpenSubmenuLayout)
      window.removeEventListener('scroll', refreshOpenSubmenuLayout, true)
    }
  }, [anchor, anchorPoint, anchorPointMode, onClose, refreshOpenSubmenuLayout])

  React.useLayoutEffect(() => {
    refreshOpenSubmenuLayout()
  }, [refreshOpenSubmenuLayout, items])

  if (items.length === 0) {
    return null
  }

  const isPathPrefix = (prefix: string[], full: string[]) =>
    prefix.length <= full.length && prefix.every((segment, index) => full[index] === segment)

  const renderMenuItems = (menuItems: MenuListItem[], parentPath: string[] = []): React.ReactNode => (
    menuItems.map((item) => {
      const itemPath = [...parentPath, item.key]
      const pathKey = itemPath.join('/')
      const hasChildren = Boolean(item.children?.length) && !item.render
      const submenuVisible = hasChildren && isPathPrefix(itemPath, openPath)
      const submenuLayout = submenuLayouts[pathKey]

      if (item.render) {
        return (
          <div key={item.key} className="ui-menu__item ui-menu__item--custom">
            {item.render}
          </div>
        )
      }

      if (hasChildren) {
        return (
          <div
            key={item.key}
            className="ui-menu__submenu-anchor"
            onMouseEnter={() => openSubmenu(itemPath, item.children?.length ?? 0)}
          >
            <button
              ref={(node) => {
                submenuTriggerRefs.current.set(pathKey, node)
              }}
              type="button"
              role="menuitem"
              aria-haspopup="menu"
              aria-expanded={submenuVisible}
              className={cn('ui-menu__item', 'ui-menu__item--submenu', item.danger && 'ui-menu__item--danger', item.active && 'is-active')}
              disabled={item.disabled}
              onClick={() => {
                if (submenuVisible) {
                  setOpenPath(parentPath)
                  return
                }
                openSubmenu(itemPath, item.children?.length ?? 0)
              }}
            >
              {item.icon && <span className="ui-menu__icon">{item.icon}</span>}
              {item.label != null && <span className="ui-menu__label">{item.label}</span>}
              <ChevronRightIcon size={12} className="ui-menu__caret" />
            </button>
            {submenuVisible ? (
              <div
                ref={(node) => {
                  submenuPanelRefs.current.set(pathKey, node)
                }}
                className={cn('ui-menu', 'ui-menu--popup', 'ui-menu--submenu')}
                role="menu"
                aria-label={typeof item.label === 'string' ? item.label : title}
                style={submenuLayout ? {
                  top: submenuLayout.top,
                  left: submenuLayout.left,
                  maxHeight: submenuLayout.maxHeight,
                  maxWidth: submenuLayout.maxWidth,
                } : undefined}
              >
                {renderMenuItems(item.children ?? [], itemPath)}
              </div>
            ) : null}
          </div>
        )
      }

      return (
        <button
          key={item.key}
          type="button"
          role="menuitem"
          className={cn('ui-menu__item', item.danger && 'ui-menu__item--danger', item.active && 'is-active')}
          disabled={item.disabled}
          onMouseEnter={() => {
            if (!isDesktop) setOpenPath(parentPath)
          }}
          onClick={() => {
            onClose()
            item.onSelect?.()
          }}
        >
          {item.icon && <span className="ui-menu__icon">{item.icon}</span>}
          {item.label != null && <span className="ui-menu__label">{item.label}</span>}
          {item.description != null && <span className="ui-menu__desc">{item.description}</span>}
          {item.tag != null && <span className="ui-menu__tag">{item.tag}</span>}
        </button>
      )
    })
  )

  return typeof document !== 'undefined'
    ? createPortal(
      (
        <div
          ref={menuRef}
          className={cn('ui-menu', 'ui-menu--popup', className)}
          role="menu"
          aria-label={title}
          style={{
            position: 'fixed',
            top: menuPosition?.top ?? -9999,
            left: menuPosition?.left ?? -9999,
          }}
        >
          {renderMenuItems(items)}
        </div>
      ),
      document.body,
    )
    : null
}

/** 工具栏「更多操作」按钮（⋯）。 */
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

  if (items.length === 0) {
    return null
  }

  return (
    <div className="ui-more-button" onClick={(event) => event.stopPropagation()}>
      <button
        ref={triggerRef}
        type="button"
        className={cn('ui-icon-button', 'ui-icon-button--ghost', 'ui-icon-button--sm', 'ui-more-button__trigger')}
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
        <MenuList anchor={triggerRef.current} items={items} onClose={closeMenu} title={title} className={className} />
      ) : null}
    </div>
  )
}
