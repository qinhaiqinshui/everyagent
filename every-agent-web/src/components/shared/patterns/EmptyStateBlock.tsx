import React from 'react'
import { cn } from '../ui/cn'

export type EmptyStateBlockProps = {
  icon?: React.ReactNode
  title: React.ReactNode
  description?: React.ReactNode
  action?: React.ReactNode
  className?: string
}

/** 空态块：基于 ui-empty 原语组合图标 / 标题 / 说明 / 操作。 */
export default function EmptyStateBlock({ icon, title, description, action, className }: EmptyStateBlockProps) {
  return (
    <div className={cn('ui-empty', className)}>
      {icon && <div className="ui-empty__icon">{icon}</div>}
      <div className="ui-empty__title">{title}</div>
      {description && <div className="ui-empty__desc">{description}</div>}
      {action && <div className="ui-empty__action">{action}</div>}
    </div>
  )
}
