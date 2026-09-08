import React from 'react'
import { cn } from '../ui/cn'

export type PanelSectionProps = {
  title?: React.ReactNode
  description?: React.ReactNode
  /** 标题右侧操作区。 */
  actions?: React.ReactNode
  /** 去掉面板内边距（用于承载自定义网格/列表）。 */
  flush?: boolean
  className?: string
  bodyClassName?: string
  children: React.ReactNode
}

/** 面板区块：面板容器 + 标题/操作头部 + 主体。 */
export default function PanelSection({ title, description, actions, flush, className, bodyClassName, children }: PanelSectionProps) {
  return (
    <section className={cn('ui-panel', flush && 'ui-panel--flush', className)}>
      {(title || actions) && (
        <div className="ui-panel-section__header">
          <div className="ui-panel-section__heading">
            {title && <div className="ui-panel-section__title">{title}</div>}
            {description && <div className="ui-panel-section__desc">{description}</div>}
          </div>
          {actions && <div className="ui-panel-section__actions">{actions}</div>}
        </div>
      )}
      <div className={cn('ui-panel-section__body', bodyClassName)}>{children}</div>
    </section>
  )
}
