import React from 'react'
import { cn } from '../ui/cn'

export type ListItemRowProps = {
  leading?: React.ReactNode
  title: React.ReactNode
  description?: React.ReactNode
  trailing?: React.ReactNode
  active?: boolean
  disabled?: boolean
  onClick?: () => void
  className?: string
  /** 行 data-row-id 属性值；长按等场景供回调定位行（如打开行级更多菜单）。 */
  rowId?: string
  /** 行根节点 ref（移动端长按时亦可作为行级菜单锚点）。 */
  rowRef?: React.Ref<HTMLDivElement>
  /**
   * 透传到行根节点的事件处理器（如 useLongPress 返回的 longPressHandlers）。
   * 不含 onClick / onKeyDown——二者由专属 prop 提供，避免覆盖行内置键盘/点击行为。
   */
  rowHandlers?: Omit<React.HTMLAttributes<HTMLDivElement>, 'onClick' | 'onKeyDown'>
}

/**
 * 列表项：左图标 / 中标题+描述 / 右附加内容，基于 ui-list-row。
 *
 * 同时挂 `ui-row` hook 类，使 `trailing` 内的 `ListRowActions`（⋯ 行级更多菜单）
 * 能在桌面端随行 hover 揭示、移动端配合 `rowHandlers`（useLongPress）长按弹出——
 * 与 `.ui-row:hover .ui-row-actions` 的显隐契约一致。
 */
export default function ListItemRow({ leading, title, description, trailing, active, disabled, onClick, className, rowId, rowRef, rowHandlers }: ListItemRowProps) {
  return (
    <div
      ref={rowRef}
      className={cn('ui-list-row ui-row', active && 'is-active', disabled && 'is-disabled', className)}
      data-row-id={rowId}
      role="button"
      aria-disabled={disabled || undefined}
      tabIndex={disabled ? -1 : 0}
      {...rowHandlers}
      onClick={() => {
        if (!disabled) onClick?.()
      }}
      onKeyDown={(e) => {
        if (!disabled && (e.key === 'Enter' || e.key === ' ')) {
          e.preventDefault()
          onClick?.()
        }
      }}
    >
      {leading && <span className="ui-list-row__leading">{leading}</span>}
      <div className="ui-list-row__main">
        <div className="ui-list-row__title">{title}</div>
        {description && <div className="ui-list-row__desc">{description}</div>}
      </div>
      {trailing && <span className="ui-list-row__trailing">{trailing}</span>}
    </div>
  )
}
