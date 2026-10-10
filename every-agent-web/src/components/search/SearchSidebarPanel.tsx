/**
 * 搜索侧边栏面板(活动栏一级入口):核心逻辑在 SearchPanel。
 * 本壳职责:把「侧边栏切到搜索面板」转换为 openSignal 驱动 on-open 流程,
 * 并接管资源管理器右键「搜索」跳转事件(弹窗实例不监听该事件)。
 */
import React from 'react'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import SearchPanel from './SearchPanel'

export default function SearchSidebarPanel() {
  const [openSignal, setOpenSignal] = React.useState(0)

  React.useEffect(
    () => domainEventBus.subscribe(DOMAIN_EVENTS.SIDEBAR_PANEL_SHOWN, ({ panelId }) => {
      if (panelId === 'search') {
        setOpenSignal((current) => current + 1)
      }
    }),
    [],
  )

  return <SearchPanel openSignal={openSignal} listenWorkspaceSearchRequested />
}
