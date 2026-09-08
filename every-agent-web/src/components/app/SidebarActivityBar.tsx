import React from 'react'
import { theme } from 'antd'
import { useResponsiveViewport } from '@/hooks/useResponsiveViewport'
import { IconButton } from '@/components/shared/ui'

export type SidebarActivityItem = {
  id: string
  label: string
  icon: React.ReactNode
  badgeCount?: number
}

export type SidebarActivityBarProps = {
  items: SidebarActivityItem[]
  activeItemIds: string[]
  panelOpen: boolean
  onSelect: (itemId: string) => void
  onBrandClick?: () => void
}

/**
 * 左侧活动栏。
 * 负责承载图标入口，并控制当前侧边面板的展开与切换。
 */
export default function SidebarActivityBar({
  items,
  activeItemIds,
  panelOpen,
  onSelect,
  onBrandClick,
}: SidebarActivityBarProps) {
  const { isMobile } = useResponsiveViewport()
  const { token } = theme.useToken()
  const resolvedBrandButtonStyle: React.CSSProperties = isMobile
    ? {
      ...brandButtonStyle,
      width: 32,
      height: 32,
      minWidth: 32,
      borderRadius: 8,
    }
    : brandButtonStyle
  const resolvedBrandMarkStyle: React.CSSProperties = isMobile
    ? {
      ...brandMarkStyle,
      width: 18,
      height: 18,
      fontSize: 'var(--text-xs)',
    }
    : brandMarkStyle
  const resolvedActivityButtonStyle: React.CSSProperties = isMobile
    ? {
      ...activityButtonStyle,
      width: 32,
      height: 32,
      minWidth: 32,
      borderRadius: 8,
    }
    : activityButtonStyle
  const resolvedActiveActivityButtonStyle: React.CSSProperties = isMobile
    ? {
      ...activeActivityButtonStyle,
      width: 32,
      height: 32,
      minWidth: 32,
      borderRadius: 8,
    }
    : activeActivityButtonStyle
  const resolvedActivityIconStyle: React.CSSProperties = isMobile
    ? {
      ...activityIconStyle,
      fontSize: 'var(--text-lg)',
    }
    : activityIconStyle
  const resolvedActivityBarStyle: React.CSSProperties = isMobile
    ? {
      ...activityBarStyle,
      width: '100%',
      height: 'var(--mobile-bottom-bar-height)',
      minHeight: 'var(--mobile-bottom-bar-height)',
      flexDirection: 'row',
      alignItems: 'center',
      justifyContent: 'flex-start',
      borderRight: 'none',
      padding: '0 7px var(--page-safe-bottom)',
      gap: 18,
      overflowX: 'auto',
      overflowY: 'hidden',
    }
    : activityBarStyle
  const resolvedItemsStyle: React.CSSProperties = isMobile
    ? {
      ...activityBarItemsStyle,
      width: 'auto',
      flexDirection: 'row',
      alignItems: 'center',
      gap: 14,
      flexWrap: 'nowrap',
      minWidth: 'max-content',
      paddingRight: 4,
    }
    : activityBarItemsStyle

  return (
    <div
      className={`sidebar-activity-bar${isMobile ? ' is-mobile' : ''}${panelOpen ? ' is-open' : ''}`}
      style={resolvedActivityBarStyle}
    >
      <IconButton
        variant="ghost"
        onClick={() => { onBrandClick?.() }}
        aria-label="打开新任务入口"
        icon={<span style={resolvedBrandMarkStyle}>N</span>}
        style={resolvedBrandButtonStyle}
      />
      <div
        className={`sidebar-activity-bar__items${isMobile ? ' is-mobile' : ''}`}
        style={resolvedItemsStyle}
      >
        {items.map((item) => {
          const isActive = activeItemIds.includes(item.id)
          return (
            <IconButton
              key={item.id}
              variant="ghost"
              onClick={() => onSelect(item.id)}
              aria-label={item.label}
              aria-pressed={isActive}
              icon={(
                <span style={resolvedActivityIconStyle}>
                  {item.icon}
                  {item.badgeCount && item.badgeCount > 0 ? (
                    <span
                      className="sidebar-activity-bar__badge"
                      style={{ ...activityBadgeStyle, background: token.colorPrimary }}
                    >
                      {item.badgeCount > 99 ? '99+' : item.badgeCount}
                    </span>
                  ) : null}
                </span>
              )}
              style={isActive ? resolvedActiveActivityButtonStyle : resolvedActivityButtonStyle}
            />
          )
        })}
      </div>
    </div>
  )
}

const activityBarStyle: React.CSSProperties = {
  width: 44,
  height: '100%',
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  justifyContent: 'flex-start',
  background: 'color-mix(in srgb, var(--bg-secondary) 88%, #111)',
  borderRight: '1px solid var(--border)',
  padding: '10px 0 12px',
  flexShrink: 0,
  gap: 10,
  // 移动端父容器(workspace-layout__sidebar-rail)是 pointer-events: none,
  // 活动栏是常驻入口按钮,必须显式恢复可点击,否则移动端按钮全部无反应。
  pointerEvents: 'auto',
}

const brandButtonStyle: React.CSSProperties = {
  width: 36,
  height: 36,
  minWidth: 36,
  border: 'none',
  borderRadius: 10,
  background: 'transparent',
  cursor: 'pointer',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  padding: 0,
  flexShrink: 0,
}

const brandMarkStyle: React.CSSProperties = {
  width: 24,
  height: 24,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  borderRadius: 'var(--radius-md)',
  background: 'linear-gradient(135deg, var(--accent-blue), var(--accent-green))',
  color: '#061014',
  fontSize: 'var(--text-sm)',
  fontWeight: 800,
  boxShadow: '0 8px 22px rgba(65, 209, 156, 0.18)',
}

const activityBarItemsStyle: React.CSSProperties = {
  width: '100%',
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  gap: 6,
}

const activityButtonStyle: React.CSSProperties = {
  position: 'relative',
  width: 36,
  height: 36,
  minWidth: 36,
  border: 'none',
  borderRadius: 10,
  background: 'transparent',
  color: 'var(--text-muted)',
  cursor: 'pointer',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  transition: 'background-color 160ms ease, color 160ms ease, transform 160ms ease',
}

const activeActivityButtonStyle: React.CSSProperties = {
  ...activityButtonStyle,
  background: 'color-mix(in srgb, var(--accent-blue) 14%, transparent)',
  color: 'var(--text-primary)',
}

const activityIconStyle: React.CSSProperties = {
  position: 'relative',
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: 'var(--text-xl)',
  lineHeight: 1,
}

const activityBadgeStyle: React.CSSProperties = {
  position: 'absolute',
  right: -3,
  bottom: -3,
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  minWidth: 16,
  height: 16,
  padding: '0 4px',
  borderRadius: 999,
  color: '#fff',
  fontSize: 10,
  lineHeight: 1,
  fontWeight: 600,
  boxShadow: '0 0 0 2px color-mix(in srgb, var(--bg-secondary) 88%, #111)',
}
