import React from 'react'
import { createPortal } from 'react-dom'
import { cn } from './cn'
import { ChevronRightIcon } from '../AppGlyphs'

export type MenuItemType = {
  key: string
  label: React.ReactNode
  icon?: React.ReactNode
  hint?: React.ReactNode
  danger?: boolean
  disabled?: boolean
  onSelect?: () => void
}

/** 统一弹出式选项列表条目（供 MenuList 使用，视觉与聊天输入 @/ 弹层一致）。 */
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
  /** 自定义渲染内容（例如内嵌下拉筛选）。存在时优先于 label 渲染，且不会触发关闭菜单的点击行为。 */
  render?: React.ReactNode
  onSelect?: () => void
}

export type MenuProps = React.HTMLAttributes<HTMLDivElement>

/** 菜单容器。 */
export function Menu({ className, children, ...rest }: MenuProps) {
  return (
    <div className={cn('ui-menu', className)} role="menu" {...rest}>
      {children}
    </div>
  )
}

/** 菜单分组标题。 */
export function MenuGroup({ label, children }: { label: React.ReactNode; children: React.ReactNode }) {
  return (
    <>
      <div className="ui-menu__group-label" role="presentation">
        {label}
      </div>
      {children}
    </>
  )
}

/** 菜单分隔线。 */
export function MenuDivider() {
  return <hr className="ui-divider ui-menu__divider" />
}

/** 菜单空态。 */
export function MenuEmpty({ children }: { children?: React.ReactNode }) {
  return <div className="ui-menu__empty">{children ?? '暂无内容'}</div>
}

export type MenuItemProps = {
  label: React.ReactNode
  icon?: React.ReactNode
  hint?: React.ReactNode
  danger?: boolean
  disabled?: boolean
  active?: boolean
  onSelect?: () => void
  className?: string
}

/** 单个菜单项。 */
export function MenuItem({ label, icon, hint, danger, disabled, active, onSelect, className }: MenuItemProps) {
  return (
    <div
      className={cn(
        'ui-menu__item',
        danger && 'ui-menu__item--danger',
        disabled && 'is-disabled',
        active && 'is-active',
        className,
      )}
      role="menuitem"
      aria-disabled={disabled || undefined}
      onClick={() => {
        if (!disabled) onSelect?.()
      }}
    >
      {icon && <span className="ui-menu__icon">{icon}</span>}
      <span className="ui-menu__label">{label}</span>
      {hint && <span className="ui-menu__hint">{hint}</span>}
    </div>
  )
}

/* ───────────────────────────────────────────────────────────
 * MenuList — 统一弹出式选项列表（portal 浮层）
 * 列表行「更多操作」、工具栏「更多操作」等均委托本组件渲染弹层，
 * 视觉与聊天输入 @/ 弹层（.ui-menu__item）完全一致。
 * ─────────────────────────────────────────────────────────── */

type SubmenuLayout = {
  /** 子菜单横向展开方向。 */
  horizontal: 'left' | 'right'
  /** 子菜单纵向展开方向。 */
  vertical: 'up' | 'down'
  /** 子菜单固定定位顶部坐标。 */
  top: number
  /** 子菜单固定定位左侧坐标。 */
  left: number
  /** 子菜单最大可视高度，用于在空间不足时启用内部滚动。 */
  maxHeight: number
  /** 子菜单最大可视宽度，用于在横向空间不足时避免溢出。 */
  maxWidth: number
}

const MENU_EDGE_GAP = 8
const MENU_FLYOUT_GAP = 4
const SUBMENU_WIDTH_ESTIMATE = 188
const SUBMENU_ROW_HEIGHT_ESTIMATE = 32

/** 锚点坐标，用于移动端长按 / 桌面端右键时按鼠标或触摸位置定位菜单。 */
export type MenuListAnchorPoint = { x: number; y: number }

/**
 * 锚点坐标的对齐方式：
 * - `center`：菜单水平居中于锚点（移动端长按，菜单落在手指下方）。
 * - `top-start`：菜单左上角对齐锚点（桌面端右键，模拟原生右键菜单）。
 */
export type MenuListAnchorPointMode = 'center' | 'top-start'

export type MenuListProps = {
  items: MenuListItem[]
  /** 触发元素（用于定位与「点击触发按钮不关闭」判定）。 */
  anchor: HTMLElement | null
  /**
   * 锚点坐标（移动端长按触摸点 / 桌面端右键鼠标点）。提供时菜单以该点为锚定位
   * （下方优先，不够则上方），优先级高于 anchor 的 getBoundingClientRect。
   */
  anchorPoint?: MenuListAnchorPoint | null
  /** 锚点坐标对齐方式，默认 `center`（保持移动端长按行为）。 */
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

  // 桌面端（>768px，与全局断点一致）子菜单常驻：hover 到无子菜单的兄弟项时不再自动收起，
  // 仅失焦（外部点击 / Escape）才关闭整个菜单。移动端（tap）行为不变。
  const isDesktop = typeof window !== 'undefined' && !window.matchMedia('(max-width: 768px)').matches

  /**
   * 根据触发按钮和当前可视空间，计算子菜单的展开方向与固定坐标。
   * 底部空间不足时优先向上展开，并限制最大宽高，保证菜单项尽量可见。
   */
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
        [pathKey]: {
          horizontal,
          vertical,
          top: nextTop,
          left: nextLeft,
          maxHeight,
          maxWidth,
        },
      }
    })
  }, [])

  const openSubmenu = React.useCallback((path: string[], childCount?: number) => {
    setOpenPath(path)
    updateSubmenuLayout(path, childCount)
  }, [updateSubmenuLayout])

  /**
   * 当前展开子菜单的布局刷新。
   * 用于首次打开、窗口缩放、滚动后重新测量，避免固定坐标失效。
   */
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
          // 桌面端右键：菜单左上角对齐鼠标点（原生右键菜单习惯），并夹紧到视口内
          nextLeft = Math.max(
            MENU_EDGE_GAP,
            Math.min(anchorPoint.x, viewportWidth - menuRect.width - MENU_EDGE_GAP),
          )
        } else {
          // 移动端长按：以触摸点为锚居中定位
          nextLeft = Math.max(
            MENU_EDGE_GAP,
            Math.min(anchorPoint.x - menuRect.width / 2, viewportWidth - menuRect.width - MENU_EDGE_GAP),
          )
        }
        const preferBottomTop = anchorPoint.y + MENU_FLYOUT_GAP
        const preferTopTop = anchorPoint.y - menuRect.height - MENU_FLYOUT_GAP
        nextTop = preferBottomTop + menuRect.height <= viewportHeight - MENU_EDGE_GAP
          ? preferBottomTop
          : Math.max(MENU_EDGE_GAP, preferTopTop)
      } else {
        // 桌面端 / 按钮触发：以 anchor 元素 rect 定位
        const triggerRect = anchor.getBoundingClientRect()
        nextLeft = Math.max(
          MENU_EDGE_GAP,
          Math.min(triggerRect.right - menuRect.width, viewportWidth - menuRect.width - MENU_EDGE_GAP),
        )
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
              className={cn(
                'ui-menu__item',
                'ui-menu__item--submenu',
                item.danger && 'ui-menu__item--danger',
                item.active && 'is-active',
              )}
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
                className={cn(
                  'ui-menu',
                  'ui-menu--popup',
                  'ui-menu--submenu',
                )}
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
          className={cn(
            'ui-menu__item',
            item.danger && 'ui-menu__item--danger',
            item.active && 'is-active',
          )}
          disabled={item.disabled}
          onMouseEnter={() => {
            // 桌面端子菜单常驻：hover 无子菜单项时不重置 openPath（不自动收起），仅失焦才关。
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

