/**
 * workflow 插件内部弹出式菜单（自宿主 components/shared/ui/Menu 的 MenuList
 * 复制精简——去掉子菜单支持，插件不引用宿主模块；git 插件同款范式）。
 *
 * 视觉完全复用宿主全局 CSS（.ui-menu / .ui-menu--popup / .ui-menu__item 等），
 * 与任务侧边栏行级「更多操作」菜单同一套观感；定位/关闭行为与宿主 MenuList
 * 一致：portal 到 body、fixed 定位、视口边缘夹紧、Esc 与点击外部关闭、
 * 滚动/缩放跟随重定位。
 */
import React from 'react'
import { createPortal } from 'react-dom'
import { MoreHorizontalIcon } from './icons'

type ClassValue = string | false | null | undefined
function cn(...values: ClassValue[]): string {
  return values.filter(Boolean).join(' ')
}

/** 菜单列表条目（workflow 插件侧边栏行级菜单用）。 */
export type MenuListItem = {
  key: string
  label?: React.ReactNode
  description?: React.ReactNode
  icon?: React.ReactNode
  tag?: React.ReactNode
  danger?: boolean
  disabled?: boolean
  onSelect?: () => void
}

export type MenuListAnchorPoint = { x: number; y: number }
export type MenuListAnchorPointMode = 'center' | 'top-start'

const MENU_EDGE_GAP = 8
const MENU_FLYOUT_GAP = 4

export type MenuListProps = {
  items: MenuListItem[]
  anchor: HTMLElement | null
  anchorPoint?: MenuListAnchorPoint | null
  anchorPointMode?: MenuListAnchorPointMode
  onClose: () => void
  title?: string
  className?: string
}

export function MenuList({
  items,
  anchor,
  anchorPoint,
  anchorPointMode = 'center',
  onClose,
  title,
  className,
}: MenuListProps) {
  const menuRef = React.useRef<HTMLDivElement | null>(null)
  const [menuPosition, setMenuPosition] = React.useState<{ top: number; left: number } | null>(null)

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
    window.addEventListener('pointerdown', handlePointerDown)
    window.addEventListener('keydown', handleKeyDown)
    window.addEventListener('resize', updateMenuPosition)
    window.addEventListener('scroll', updateMenuPosition, true)
    return () => {
      window.removeEventListener('pointerdown', handlePointerDown)
      window.removeEventListener('keydown', handleKeyDown)
      window.removeEventListener('resize', updateMenuPosition)
      window.removeEventListener('scroll', updateMenuPosition, true)
    }
  }, [anchor, anchorPoint, anchorPointMode, onClose])

  if (items.length === 0) {
    return null
  }

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
          {items.map((item) => (
            <button
              key={item.key}
              type="button"
              role="menuitem"
              className={cn('ui-menu__item', item.danger && 'ui-menu__item--danger')}
              disabled={item.disabled}
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
          ))}
        </div>
      ),
      document.body,
    )
    : null
}

/**
 * 移动端按钮常显：宿主全局 CSS 在 ≤768px 隐藏 .ui-row-actions（宿主列表行
 * 靠长按手势 openMenu 补救，本复制版无长按句柄），这里对插件侧行菜单放行
 * 常显，保证移动端行操作（取消/删除/运行…）仍可触达；同时放大触发按钮
 * 满足触控目标（≥30px）。
 */
const MOBILE_ACTIONS_STYLE_ID = 'wf-row-actions-mobile-style'

function ensureMobileActionsStyles(): void {
  if (typeof document === 'undefined' || document.getElementById(MOBILE_ACTIONS_STYLE_ID)) return
  const style = document.createElement('style')
  style.id = MOBILE_ACTIONS_STYLE_ID
  style.textContent = `
@media (max-width: 768px) {
  .wf-row-actions-visible { opacity: 1 !important; visibility: visible !important; }
  .wf-row-actions-visible .ui-row-actions__trigger { display: inline-flex; min-width: 30px; min-height: 30px; }
  .wf-row-actions-visible .ui-menu__item { min-height: 36px; }
}
`
  document.head.appendChild(style)
}

/**
 * 列表行「更多操作」（⋯）按钮——自宿主 ListRowActions 复制精简（无移动端长按句柄）：
 * 桌面 hover 整行（.ui-row）时浮现（宿主全局 .ui-row:hover .ui-row-actions），
 * 点击弹出 MenuList；移动端常显并放大触控目标（见上方样式）。侧边栏行卡片内使用。
 *
 * `onOpen`：菜单**即将打开**时的回调（对齐宿主 ListRowActions.onOpen 语义）——用于
 * 「点击菜单项之前」的轻量准备，例如预取「复制工作流路径」要用的实例目录（剪贴板
 * 同步写入要求用户手势仍在，跨 await 的取数会把手势吃掉），不得阻塞开菜单。
 */
export function RowActions({
  items,
  title = '更多操作',
  disabled = false,
  onOpen,
}: {
  items: MenuListItem[]
  title?: string
  disabled?: boolean
  onOpen?: () => void
}) {
  const triggerRef = React.useRef<HTMLButtonElement | null>(null)
  const [open, setOpen] = React.useState(false)

  React.useEffect(ensureMobileActionsStyles, [])

  if (items.length === 0) {
    return null
  }

  return (
    <div className={cn('ui-row-actions', 'wf-row-actions-visible')} onClick={(event) => event.stopPropagation()}>
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
            setOpen(false)
            return
          }
          setOpen(true)
          onOpen?.()
        }}
      >
        <MoreHorizontalIcon size={13} />
      </button>
      {open && typeof document !== 'undefined' ? (
        <MenuList
          anchor={triggerRef.current}
          anchorPointMode="top-start"
          items={items}
          onClose={() => setOpen(false)}
          title={title}
        />
      ) : null}
    </div>
  )
}

/**
 * 组头「更多操作」（⋯）按钮——与宿主 ActionMenu 同角色：常显（不靠 hover 揭示），
 * 复用 .ui-row-actions__trigger 的图标按钮外观，点击弹出 MenuList（锚定按钮右下）。
 * 分组卡片组头使用（宿主 .ui-row-actions 包裹层靠 hover 揭示，组头处不适用）；
 * 移动端同样套 wf-row-actions-visible 放大触控目标。
 */
export function HeaderMenu({
  items,
  title = '更多操作',
  disabled = false,
}: {
  items: MenuListItem[]
  title?: string
  disabled?: boolean
}) {
  const triggerRef = React.useRef<HTMLButtonElement | null>(null)
  const [open, setOpen] = React.useState(false)

  React.useEffect(ensureMobileActionsStyles, [])

  if (items.length === 0) {
    return null
  }

  return (
    <div className="ui-more-button" onClick={(event) => event.stopPropagation()}>
      <button
        ref={triggerRef}
        type="button"
        className="ui-row-actions__trigger wf-row-actions-visible"
        title={title}
        aria-label={title}
        aria-haspopup="menu"
        aria-expanded={open}
        disabled={disabled}
        onClick={() => setOpen(v => !v)}
      >
        <MoreHorizontalIcon size={13} />
      </button>
      {open && typeof document !== 'undefined' ? (
        <MenuList anchor={triggerRef.current} items={items} onClose={() => setOpen(false)} title={title} />
      ) : null}
    </div>
  )
}
