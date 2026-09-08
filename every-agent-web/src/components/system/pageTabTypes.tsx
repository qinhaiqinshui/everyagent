/**
 * src/components/system/pageTabTypes.tsx
 *
 * 内置「顶层页面」标签类型定义(hub 版):page:settings。
 * n 的启动台/日志/扩展页已随运行时下沉与插件底座裁剪移除;
 * git 已收敛到左侧「源代码管理」侧边栏面板(见 GitSidebarPanel),不再作为顶层页。
 */

import type { ReactNode } from 'react'
import type { TopLevelPageId, WorkspaceTab } from '@/types'
import type { UiWorkspaceTabTypeDefinition, WorkspaceTabRenderContext } from '@/plugin/types'
import { SettingsIcon } from '@/components/icon'
import { createLazyRouteComponent } from '@/components/shared/LazyRouteView'

const LazySettingsPanel = createLazyRouteComponent(() => import('@/components/system/SettingsPanel'))

interface PageMeta {
  label: string
  icon: ReactNode
  sidebarActivityId: 'settings' | null
}

const PAGE_META: Record<TopLevelPageId, PageMeta> = {
  settings: { label: '设置', icon: <SettingsIcon />, sidebarActivityId: 'settings' },
}

function buildPageDefinition(pageId: TopLevelPageId): UiWorkspaceTabTypeDefinition {
  const meta = PAGE_META[pageId]
  return {
    tabTypeKey: `page:${pageId}`,
    pluginId: 'core',
    renderTab: (tab: WorkspaceTab, _ctx: WorkspaceTabRenderContext): ReactNode => {
      if (tab.tabType !== 'page') return null
      switch (tab.pageId) {
        case 'settings':
          return <LazySettingsPanel />
        default:
          return null
      }
    },
    renderIcon: () => meta.icon,
    getLabel: () => meta.label,
    getTitle: () => meta.label,
    getCloseAriaLabel: () => `关闭${meta.label}页面`,
    getSidebarActivityId: () => meta.sidebarActivityId,
  }
}

export const pageTabTypes: UiWorkspaceTabTypeDefinition[] = (
  ['settings'] as TopLevelPageId[]
).map(buildPageDefinition)
