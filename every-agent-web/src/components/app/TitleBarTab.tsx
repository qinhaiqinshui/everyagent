import React from 'react'
import { CloseOutlined } from '@ant-design/icons'
import { IconButton } from '@/components/shared/ui'

type TitleBarTabProps = {
  /** 标签页根节点引用。 */
  tabRef?: React.Ref<HTMLDivElement>
  /** 当前标签页是否处于激活状态。 */
  active: boolean
  /** 标签页图标。 */
  icon: React.ReactNode
  /** 标签页显示内容（支持自定义 ReactNode，如内联状态点）。 */
  label: React.ReactNode
  /** 标签页悬浮提示文本。 */
  title: string
  /** 关闭按钮的无障碍文本。 */
  closeAriaLabel: string
  /** 当前是否处于移动端布局。 */
  isMobile?: boolean
  /** 激活标签页。 */
  onActivate: () => void
  /** 关闭标签页。 */
  onClose: () => void | Promise<void>
}

export default function TitleBarTab({
  tabRef,
  active,
  icon,
  label,
  title,
  closeAriaLabel,
  isMobile = false,
  onActivate,
  onClose,
}: TitleBarTabProps) {
  const className = [
    'titlebar-no-drag',
    'titlebar-tab-button',
    active ? 'is-active' : '',
    isMobile ? 'is-mobile' : '',
  ].filter(Boolean).join(' ')

  const handleKeyDown = React.useCallback((event: React.KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      onActivate()
    }
  }, [onActivate])

  const handleCloseClick = React.useCallback((event: React.MouseEvent<HTMLButtonElement>) => {
    event.stopPropagation()
    void onClose()
  }, [onClose])

  return (
    <div
      ref={tabRef}
      className={className}
      role="button"
      tabIndex={0}
      onClick={onActivate}
      onKeyDown={handleKeyDown}
      title={title}
    >
      <span className="titlebar-tab-button__shape" aria-hidden="true">
        <span className="titlebar-tab-button__shape-left" />
        <span className="titlebar-tab-button__shape-center" />
        <span className="titlebar-tab-button__shape-right" />
      </span>

      <span className="titlebar-tab-button__layout">
        <span className="titlebar-tab-button__cap-spacer titlebar-tab-button__cap-spacer--left" aria-hidden="true" />
        <span className="titlebar-tab-button__content">
          {icon != null && <span className="titlebar-tab-button__icon">{icon}</span>}
          <span className="titlebar-tab-button__label">{label}</span>
        </span>
        <span className="titlebar-tab-button__cap-spacer titlebar-tab-button__cap-spacer--right" aria-hidden="true" />
      </span>

      <IconButton
        className="titlebar-no-drag titlebar-tab-button__close"
        variant="ghost"
        size="sm"
        aria-label={closeAriaLabel}
        icon={<CloseOutlined style={{ fontSize: 14 }} />}
        onClick={handleCloseClick}
        style={closeButtonStyle}
      />
    </div>
  )
}

/** 关闭按钮尺寸/配色（覆盖 antd 默认按钮盒模型，位置仍由 titlebar CSS 决定）。 */
const closeButtonStyle: React.CSSProperties = {
  width: 24,
  height: 24,
  minWidth: 24,
  padding: 0,
  color: 'var(--text-muted)',
}
