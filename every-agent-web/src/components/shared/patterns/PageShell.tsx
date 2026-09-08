import React from 'react'
import { cn } from '../ui/cn'

export type PageShellProps = {
  /** 页面头部（如 PageHeader）。 */
  header?: React.ReactNode
  children: React.ReactNode
  className?: string
  contentClassName?: string
  /** 内容区是否可滚动，默认 true。 */
  scroll?: boolean
}

/** 页面骨架：头部 + 可滚动内容区。 */
export default function PageShell({ header, children, className, contentClassName, scroll = true }: PageShellProps) {
  return (
    <div className={cn('ui-page-shell', className)}>
      {header}
      <div className={cn('ui-page-shell__content', scroll && 'ui-scroll-shell', contentClassName)}>{children}</div>
    </div>
  )
}
