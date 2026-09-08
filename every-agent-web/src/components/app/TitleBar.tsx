import React from 'react'
import { useWorkspaceShell } from './WorkspaceShellContext'
import { useResponsiveViewport } from '@/hooks/useResponsiveViewport'
import TitleBarTab from './TitleBarTab'
import { getTabDefinition } from '@/plugin/workspaceTabTypes'
import OverlayScrollbar from '@/components/shared/ui/OverlayScrollbar'
import DesktopWindowControls from './DesktopWindowControls'
import { isDesktopWindowControlAvailable } from '@/platform/desktopBootstrap'

export default function TitleBar() {
  const { isMobile } = useResponsiveViewport()
  const tabsStripRef = React.useRef<HTMLDivElement | null>(null)
  const activeTabRef = React.useRef<HTMLDivElement | null>(null)
  const {
    workspaceTabs,
    activeWorkspaceTabId,
    setActiveWorkspaceTab,
    closeWorkspaceTab,
  } = useWorkspaceShell()

  React.useLayoutEffect(() => {
    const container = tabsStripRef.current
    const activeTab = activeTabRef.current
    if (!container || !activeTab) return

    /**
     * 激活标签切换后，自动把当前标签滚回可视区域。
     * 只校正标题栏自身的横向滚动，不影响页面主体滚动位置。
     */
    const containerLeft = container.scrollLeft
    const containerRight = containerLeft + container.clientWidth
    const tabLeft = activeTab.offsetLeft
    const tabRight = tabLeft + activeTab.offsetWidth
    const horizontalPadding = isMobile ? 8 : 12

    if (tabLeft < containerLeft + horizontalPadding) {
      container.scrollTo({
        left: Math.max(0, tabLeft - horizontalPadding),
        behavior: 'smooth',
      })
      return
    }

    if (tabRight > containerRight - horizontalPadding) {
      container.scrollTo({
        left: Math.max(0, tabRight - container.clientWidth + horizontalPadding),
        behavior: 'smooth',
      })
    }
    // 滚动期间的指示条更新由 OverlayScrollbar 监听 scroll 事件自行处理
  }, [activeWorkspaceTabId, isMobile, workspaceTabs.length])

  const hasTabs = workspaceTabs.length > 0
  // 桌面版(无边框窗口)无标签时也要保留可拖拽标题栏;浏览器环境无标签则完全不占空间(原行为)。
  const desktopControlsAvailable = isDesktopWindowControlAvailable()

  /**
   * frameless 窗口下双击标签栏空白区域=最大化/还原(仅桌面版生效)。
   * 双击标签、关闭按钮等 no-drag 控件时不触发。
   * 注意:必须在下方提前 return 之前调用,否则无标签→有标签切换时
   * hooks 数量变化会触发 "Rendered more hooks than during the previous render"。
   */
  const handleTitlebarDoubleClick = React.useCallback((event: React.MouseEvent<HTMLDivElement>) => {
    const control = typeof window !== 'undefined' ? window.everyAgentDesktop?.windowControl : undefined
    if (!control) return
    const target = event.target as HTMLElement | null
    if (target && target.closest('.titlebar-no-drag')) return
    void control.toggleMaximize()
  }, [])

  if (!hasTabs && !desktopControlsAvailable) {
    return null
  }

  return (
    <div
      className="titlebar-drag"
      onDoubleClick={handleTitlebarDoubleClick}
      style={{
        height: 'var(--titlebar-height)',
        background: 'var(--titlebar-bg)',

        display: 'flex',
        alignItems: 'stretch',
        padding: 0,
        gap: 12,
        userSelect: 'none',
        flexShrink: 0,
      }}
    >
      <div className={`titlebar-shell${isMobile ? ' is-mobile' : ''}`} style={titlebarTabsWrapStyle}>
        <div className="titlebar-tabs-frame">
          <div
            ref={tabsStripRef}
            className={isMobile ? 'titlebar-mobile-scroll titlebar-tabs-strip is-mobile' : 'titlebar-tabs-strip'}
            style={titlebarTabsStripStyle}
          >
            {workspaceTabs.map((tab) => {
              const isActive = tab.id === activeWorkspaceTabId
              const def = getTabDefinition(tab)
              const icon = def?.renderIcon() ?? null
              const customLabel = def?.renderLabel?.(tab) ?? null
              const label = def?.getLabel(tab) ?? ''
              const title = def?.getTitle(tab) ?? (typeof customLabel === 'string' ? customLabel : label)
              const closeAriaLabel = def?.getCloseAriaLabel(tab) ?? `关闭${title || '标签'}`
              return (
                <TitleBarTab
                  key={tab.id}
                  tabRef={isActive ? activeTabRef : undefined}
                  active={isActive}
                  icon={icon}
                  label={customLabel ?? label}
                  title={title}
                  closeAriaLabel={closeAriaLabel}
                  isMobile={isMobile}
                  onActivate={() => setActiveWorkspaceTab(tab.id)}
                  onClose={() => {
                    closeWorkspaceTab(tab.id)
                  }}
                />
              )
            })}
          </div>
          <OverlayScrollbar
            targetRef={tabsStripRef}
            offset={6}
            version={workspaceTabs.length}
            className="titlebar-overlay-scrollbar"
            hoverReveal
          />
          {/* 拖拽底座:标签条的 flex 兄弟(排在其后),占满标签条右侧
              暴露的空隙;标签条宽随内容自适应(max-content,上限 100%),
              占满后底座宽度归 0。drag 矩形与标签条天然不重叠 —— 本应用
              软件渲染配置下滚动容器的 no-drag 挖洞不可靠,不能把底座
              垫在标签条下面(详见 index.css 头部说明)。 */}
          <div className="titlebar-drag-base" />
        </div>
      </div>
      {/* 顶部 6px 透明拖拽层:标签占满整条标题栏时仍可拖窗口(仅桌面版)。
          视觉透明,不产生色差白条。 */}
      {desktopControlsAvailable && <div className="titlebar-drag-overlay" />}
      <DesktopWindowControls />
    </div>
  )
}

const titlebarTabsWrapStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  display: 'flex',
  alignItems: 'stretch',
  overflow: 'hidden',
}

const titlebarTabsStripStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-end',
  gap: 0,
  minWidth: 0,
  // 宽度随标签数量自适应;占满可用宽度(100%)后再新增标签即横向滚动。
  // 无标签时宽度为 0,露出下层 .titlebar-drag-base 拖拽底座。
  flex: '0 1 auto',
  width: 'max-content',
  maxWidth: '100%',
  overflowX: 'auto',
  padding: 0,
}
