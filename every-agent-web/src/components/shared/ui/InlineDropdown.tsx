import React, { useState } from 'react'
import { Button, Dropdown } from 'antd'
import type { MenuProps } from 'antd'
import { cn } from './cn'
import type { MenuItemType } from './Menu'

export type InlineDropdownProps = {
  /** 触发文本 / 标签。 */
  label: React.ReactNode
  /** 便捷菜单项；也可通过 children 传入自定义内容。 */
  items?: MenuItemType[]
  ariaLabel?: string
  /** 浮层对齐，默认 start（左对齐）。 */
  align?: 'start' | 'end'
  children?: React.ReactNode
  className?: string
}

/**
 * 内联下拉（antd Dropdown + Menu 实现）。
 * 与原 InlineDropdown 保持相同对外 props：label / items / align / children。
 */
export default function InlineDropdown({
  label,
  items,
  ariaLabel,
  align = 'start',
  children,
  className,
}: InlineDropdownProps) {
  const [open, setOpen] = useState(false)

  const menuItems: MenuProps['items'] = items?.map((it) => ({
    key: it.key,
    label: it.label,
    icon: it.icon,
    danger: it.danger,
    disabled: it.disabled,
  }))

  return (
    <Dropdown
      open={open}
      onOpenChange={setOpen}
      trigger={['click']}
      placement={align === 'end' ? 'bottomRight' : 'bottomLeft'}
      menu={{
        items: menuItems,
        onClick: (info) => {
          const it = items?.find((i) => i.key === info.key)
          it?.onSelect?.()
          setOpen(false)
        },
      }}
      dropdownRender={(menuNode) => children ?? menuNode}
    >
      <Button variant="text" size="small" aria-label={ariaLabel} className={cn('ui-inline-dropdown', className)}>
        {label}
      </Button>
    </Dropdown>
  )
}
