import React, { useState } from 'react'
import { Button, Dropdown } from 'antd'
import type { MenuProps } from 'antd'
import { EllipsisOutlined } from '@ant-design/icons'
import { cn } from './cn'
import type { MenuItemType } from './Menu'

export type ActionMenuProps = {
  /** 便捷菜单项；也可通过 children 传入自定义内容。 */
  items?: MenuItemType[]
  ariaLabel?: string
  /** 自定义图标，默认 ⋯。 */
  triggerIcon?: React.ReactNode
  /** 浮层对齐，默认 end（右对齐）。 */
  align?: 'start' | 'end'
  /** 自定义菜单内容（覆盖 items）。 */
  children?: React.ReactNode
  className?: string
}

/**
 * 更多操作菜单（antd Dropdown + Menu 实现）。
 * 与原 ActionMenu 保持相同对外 props：items / ariaLabel / triggerIcon / align / children。
 */
export default function ActionMenu({
  items,
  ariaLabel = '更多操作',
  triggerIcon,
  align = 'end',
  children,
  className,
}: ActionMenuProps) {
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
      <Button
        type="text"
        aria-label={ariaLabel}
        icon={triggerIcon ?? <EllipsisOutlined />}
        className={cn(className)}
      />
    </Dropdown>
  )
}
