import React from 'react'
import { createPortal } from 'react-dom'
import { QuestionMarkIcon } from './AppGlyphs'
import Button from '@/components/shared/ui/Button'

export interface PropertyItem {
  /** 属性名(如「文件名」「大小」)。 */
  label: string
  /** 属性值。 */
  value: React.ReactNode
}

type PropertiesDialogProps = {
  open: boolean
  /** 弹窗标题(如「属性」)。 */
  title?: string
  /** 属性目标名称(标题下方显示,如「foo.ts」)。 */
  name?: string
  /** 属性条目列表。 */
  items: PropertyItem[]
  onClose: () => void
}

/**
 * 通用「属性」展示组件:以列表形式列出某个东西(文件/目录/工作区等)的已有属性。
 * 纯展示,不感知具体对象类型——调用方把属性条目组织好传入(items)。
 */
export default function PropertiesDialog({
  open,
  title = '属性',
  name,
  items,
  onClose,
}: PropertiesDialogProps) {
  if (!open) return null

  const dialog = (
    <div
      className="confirm-dialog__overlay"
      style={overlayStyle}
      onClick={(event) => event.target === event.currentTarget && onClose()}
    >
      <div className="confirm-dialog" style={dialogStyle}>
        <div style={headerStyle}>
          <div style={iconStyle}>
            <QuestionMarkIcon size={16} />
          </div>
          <div style={headerTextBlockStyle}>
            <div style={titleRowStyle}>
              <div style={titleStyle}>{title}</div>
            </div>
            {name ? <div style={subtitleStyle} title={name}>{name}</div> : null}
          </div>
        </div>

        <div style={bodyScrollStyle}>
          {items.length === 0 ? (
            <div style={emptyStyle}>暂无属性</div>
          ) : (
            <div style={listStyle}>
              {items.map((item, index) => (
                <div key={`${item.label}-${index}`} style={rowStyle}>
                  <span style={labelStyle}>{item.label}</span>
                  <span style={valueStyle}>{item.value}</span>
                </div>
              ))}
            </div>
          )}
        </div>

        <div style={actionsStyle}>
          <Button variant="secondary" onClick={onClose} style={closeButtonStyle}>关闭</Button>
        </div>
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
  width: 'min(440px, 100%)',
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
  padding: '20px 22px 14px',
  borderBottom: '1px solid var(--border-light)',
  flexShrink: 0,
  background: 'var(--bg-secondary)',
}

const iconStyle: React.CSSProperties = {
  width: 34,
  height: 34,
  borderRadius: 10,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  flexShrink: 0,
  background: 'var(--accent-blue-dim)',
  color: 'var(--accent-blue)',
  border: '1px solid color-mix(in srgb, var(--accent-blue) 22%, var(--border))',
}

const headerTextBlockStyle: React.CSSProperties = {
  minWidth: 0,
  flex: 1,
  display: 'flex',
  flexDirection: 'column',
  justifyContent: 'center',
  minHeight: 34,
}

const titleRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  minWidth: 0,
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

const subtitleStyle: React.CSSProperties = {
  marginTop: 2,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-secondary)',
  lineHeight: 1.5,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

const bodyScrollStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  overflowY: 'auto',
  padding: '14px 22px 6px',
  display: 'flex',
  flexDirection: 'column',
}

const emptyStyle: React.CSSProperties = {
  padding: '8px 0 16px',
  fontSize: 'var(--text-sm)',
  color: 'var(--text-muted)',
}

const listStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
}

const rowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'baseline',
  gap: 12,
  padding: '6px 0',
  borderBottom: '1px solid var(--border-light)',
  flexShrink: 0,
}

const labelStyle: React.CSSProperties = {
  flexShrink: 0,
  width: 84,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
}

const valueStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  fontSize: 'var(--text-sm)',
  color: 'var(--text-primary)',
  overflowWrap: 'break-word',
}

const actionsStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'flex-end',
  gap: 10,
  padding: '12px 22px 20px',
  flexShrink: 0,
  background: 'var(--bg-secondary)',
}

const closeButtonStyle: React.CSSProperties = {
  padding: '8px 20px',
  borderRadius: 'var(--radius-md)',
  border: '1px solid var(--border)',
  background: 'var(--bg-tertiary)',
  color: 'var(--text-secondary)',
  fontSize: 'var(--text-sm)',
  fontWeight: 500,
  cursor: 'pointer',
}