import React from 'react'
import { cn } from '../ui/cn'

export type FilterToolbarProps = {
  /** 筛选控件（输入 / 选择 / 切换）。 */
  children?: React.ReactNode
  /** 右侧操作（如新建 / 刷新）。 */
  actions?: React.ReactNode
  className?: string
}

/** 筛选工具栏：左侧筛选控件 + 右侧操作，基于 ui-toolbar。 */
export default function FilterToolbar({ children, actions, className }: FilterToolbarProps) {
  return (
    <div className={cn('ui-toolbar', 'ui-toolbar--filter', className)}>
      <div className="ui-toolbar__group">{children}</div>
      <div className="ui-toolbar__spacer" />
      {actions && <div className="ui-toolbar__group">{actions}</div>}
    </div>
  )
}
