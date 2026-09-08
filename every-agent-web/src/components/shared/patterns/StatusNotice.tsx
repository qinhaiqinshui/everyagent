import React from 'react'
import { cn } from '../ui/cn'
import IconButton from '../ui/IconButton'

const CloseX = () => (
  <svg viewBox="0 0 16 16" width="14" height="14" fill="none" aria-hidden="true">
    <path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
  </svg>
)

export type StatusNoticeTone = 'neutral' | 'info' | 'success' | 'warn' | 'danger'

export type StatusNoticeProps = {
  tone?: StatusNoticeTone
  title?: React.ReactNode
  icon?: React.ReactNode
  onClose?: () => void
  children?: React.ReactNode
  className?: string
}

/** 状态提示条（信息 / 成功 / 警告 / 危险）。 */
export default function StatusNotice({ tone = 'neutral', title, icon, onClose, children, className }: StatusNoticeProps) {
  return (
    <div className={cn('ui-notice', `ui-notice--${tone}`, className)} role="status">
      {icon && <span className="ui-notice__icon">{icon}</span>}
      <div className="ui-notice__body">
        {title && <div className="ui-notice__title">{title}</div>}
        {children && <div>{children}</div>}
      </div>
      {onClose && (
        <IconButton aria-label="关闭" icon={<CloseX />} variant="ghost" size="sm" className="ui-notice__close" onClick={onClose} />
      )}
    </div>
  )
}
