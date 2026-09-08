import React from 'react'
import { cn } from '../ui/cn'

export type PageHeaderProps = {
  title: React.ReactNode
  description?: React.ReactNode
  icon?: React.ReactNode
  /** 右侧操作区（按钮 / 菜单等）。 */
  actions?: React.ReactNode
  className?: string
}

/** 页面顶部标题栏：标题 + 简介 + 右侧操作。 */
export default function PageHeader({ title, description, icon, actions, className }: PageHeaderProps) {
  return (
    <header className={cn('ui-page-header', className)}>
      <div className="ui-page-header__main">
        <div className="ui-page-header__title">
          {icon && <span className="ui-page-header__icon">{icon}</span>}
          {title}
        </div>
        {description && <div className="ui-page-header__desc">{description}</div>}
      </div>
      {actions && <div className="ui-page-header__actions">{actions}</div>}
    </header>
  )
}
