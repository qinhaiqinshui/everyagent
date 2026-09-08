import React from 'react'
import { AlertTriangleIcon, ArrowRightIcon } from './AppGlyphs'
import { createPortal } from 'react-dom'
import Button from '@/components/shared/ui/Button'

type ConfirmDialogProps = {
  open: boolean
  title: string
  message?: string
  subtitle?: string
  confirmLabel: React.ReactNode
  cancelLabel?: React.ReactNode
  danger?: boolean
  /** 自定义头部左侧图标；缺省时 danger 用 AlertTriangleIcon，其余用 ArrowRightIcon。 */
  icon?: React.ReactNode
  /** 头部标题行右侧的附加元素（如多问题选择题的进度「X / N」）。 */
  headerExtra?: React.ReactNode
  details?: React.ReactNode
  body?: React.ReactNode
  actions?: React.ReactNode
  hideDefaultMessageBlock?: boolean
  hideActions?: boolean
  width?: string | number
  maxHeight?: string | number
  overlayClassName?: string
  dialogClassName?: string
  onConfirm: () => void | Promise<void>
  onCancel: () => void
}

export default function ConfirmDialog({
  open,
  title,
  message,
  subtitle,
  confirmLabel,
  cancelLabel = '取消',
  danger = false,
  icon,
  headerExtra,
  details,
  body,
  actions,
  hideDefaultMessageBlock = false,
  hideActions = false,
  width = 'min(520px, 100%)',
  maxHeight = '85vh',
  overlayClassName,
  dialogClassName,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  if (!open) return null

  const tone = danger
    ? {
        iconBackground: 'var(--accent-red-dim)',
        iconColor: 'var(--accent-red)',
        iconBorder: 'color-mix(in srgb, var(--accent-red) 28%, var(--border))',
        confirmBackground: 'var(--accent-red)',
        confirmShadow: '0 8px 20px rgba(239,68,68,0.18)',
      }
    : {
        iconBackground: 'var(--accent-blue-dim)',
        iconColor: 'var(--accent-blue)',
        iconBorder: 'color-mix(in srgb, var(--accent-blue) 22%, var(--border))',
        confirmBackground: 'var(--accent-blue)',
        confirmShadow: '0 8px 20px rgba(59,130,246,0.18)',
      }

  const dialog = (
    <div
      className={['confirm-dialog__overlay', overlayClassName].filter(Boolean).join(' ')}
      style={overlayStyle}
      onClick={(event) => event.target === event.currentTarget && onCancel()}
    >
      <div
        className={['confirm-dialog', dialogClassName].filter(Boolean).join(' ')}
        style={{ ...dialogStyle, width, maxHeight }}
      >
        <div style={headerStyle}>
          <div style={{
            ...iconStyle,
            background: tone.iconBackground,
            color: tone.iconColor,
            border: `1px solid ${tone.iconBorder}`,
          }}
          >
            {icon ?? (danger ? <AlertTriangleIcon size={16} /> : <ArrowRightIcon size={16} />)}
          </div>
          <div style={headerTextBlockStyle}>
            <div style={titleRowStyle}>
              <div style={titleStyle}>{title}</div>
              {headerExtra ? <div style={headerExtraStyle}>{headerExtra}</div> : null}
            </div>
            {subtitle ? <div style={subtitleStyle}>{subtitle}</div> : null}
          </div>
        </div>

        <div style={bodyScrollStyle}>
          {!hideDefaultMessageBlock && message ? (
            <div style={messageStyle}>{message}</div>
          ) : null}

          {details ? (
            <div style={detailsStyle}>{details}</div>
          ) : null}

          {body}
        </div>

        {actions ? (
          <div style={actionsStyle}>
            {actions}
          </div>
        ) : !hideActions ? (
          <div style={actionsStyle}>
            <Button onClick={onCancel} variant="secondary" style={cancelButtonStyle}>{cancelLabel}</Button>
            <Button
              onClick={() => { void onConfirm() }}
              variant={danger ? 'danger' : 'primary'}
              style={{
                ...confirmButtonStyle,
                background: tone.confirmBackground,
                boxShadow: tone.confirmShadow,
              }}
            >
              {confirmLabel}
            </Button>
          </div>
        ) : null}
      </div>
    </div>
  )

  if (typeof document === 'undefined' || !document.body) {
    return dialog
  }

  return createPortal(dialog, document.body)
}

const overlayStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'var(--overlay)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  zIndex: 1100,
  padding: 20,
}

const dialogStyle: React.CSSProperties = {
  background: 'var(--bg-secondary)',
  border: '1px solid var(--border)',
  borderRadius: 'var(--radius-xl)',
  boxShadow: 'var(--shadow-lg)',
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
  minHeight: 0,
}

const headerStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 14,
  padding: '22px 22px 16px',
  borderBottom: '1px solid var(--border-light)',
  flexShrink: 0,
  background: 'var(--bg-secondary)',
}

/** 头部图标盒尺寸：标题文字块以其为基准做垂直居中。 */
const headerIconBoxSize = 34

/** 头部标题/进度/副标题文字块：短内容时按图标盒高度垂直居中，避免与左侧图标错位；长内容/带副标题时自然向下伸展。 */
const headerTextBlockStyle: React.CSSProperties = {
  minWidth: 0,
  flex: 1,
  display: 'flex',
  flexDirection: 'column',
  justifyContent: 'center',
  minHeight: headerIconBoxSize,
}

const iconStyle: React.CSSProperties = {
  width: headerIconBoxSize,
  height: headerIconBoxSize,
  borderRadius: 10,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 'var(--text-xl)',
  fontWeight: 700,
  flexShrink: 0,
}

const titleRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  minWidth: 0,
  // 与左侧图标同高：标题/进度在行内垂直居中，正好与图标中心对齐。
  minHeight: 34,
}

const titleStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  fontSize: 'var(--text-lg)',
  fontWeight: 700,
  color: 'var(--text-primary)',
  lineHeight: 1.3,
}

const headerExtraStyle: React.CSSProperties = {
  flexShrink: 0,
  padding: '2px 8px',
  borderRadius: '999px',
  background: 'var(--bg-tertiary)',
  color: 'var(--text-secondary)',
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  lineHeight: 1.5,
  whiteSpace: 'nowrap',
}

const subtitleStyle: React.CSSProperties = {
  marginTop: 4,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-secondary)',
  lineHeight: 1.6,
}

const bodyScrollStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  overflowY: 'auto',
  padding: '18px 22px',
  display: 'flex',
  flexDirection: 'column',
  gap: 16,
}

const messageStyle: React.CSSProperties = {
  fontSize: 'var(--text-sm)',
  color: 'var(--text-secondary)',
  lineHeight: 1.7,
}

const detailsStyle: React.CSSProperties = {
  background: 'var(--bg-primary)',
  border: '1px solid var(--border-light)',
  borderRadius: 'var(--radius-lg)',
  padding: '12px 14px',
}

const actionsStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'flex-end',
  gap: 10,
  padding: '16px 22px 22px',
  borderTop: '1px solid var(--border-light)',
  background: 'var(--bg-secondary)',
  flexShrink: 0,
}

const cancelButtonStyle: React.CSSProperties = {
  padding: '8px 16px',
  borderRadius: 'var(--radius-md)',
  border: '1px solid var(--border)',
  background: 'var(--bg-tertiary)',
  color: 'var(--text-secondary)',
  fontSize: 'var(--text-sm)',
  fontWeight: 500,
  cursor: 'pointer',
}

const confirmButtonStyle: React.CSSProperties = {
  padding: '8px 16px',
  borderRadius: 'var(--radius-md)',
  border: 'none',
  color: '#fff',
  fontSize: 'var(--text-sm)',
  fontWeight: 600,
  cursor: 'pointer',
}
