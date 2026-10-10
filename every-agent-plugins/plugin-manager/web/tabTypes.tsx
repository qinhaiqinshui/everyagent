/**
 * `extension-detail` 工作区标签类型定义。
 *
 * 侧边栏扩展面板点击行后经 ctx.ui.openPluginTab(EXTENSION_DETAIL_TAB_TYPE, { id })
 * 打开；宿主以 data.id 构造确定性 tab id（extension-detail:<pluginId>），
 * 同一插件恒为同一标签页，重复点击聚焦不重建（对标 VSCode 编辑器区的扩展详情页）。
 */
import React from 'react'
import type { UiWorkspaceTabTypeDefinition } from '@everyagent/plugin-api'
import ExtensionDetailPage from './ExtensionDetailPage'
import { DefaultPluginIcon } from './icons'
import { PluginIcon } from './PluginIcon'
import { findPluginEntry } from './pluginStore'
import { EXTENSION_DETAIL_TAB_TYPE } from './tabTypeKey'

/** 扩展详情标签类型定义（由 index.ts activate 时注册）。 */
export const extensionDetailTabType: UiWorkspaceTabTypeDefinition = {
  tabTypeKey: EXTENSION_DETAIL_TAB_TYPE,
  pluginId: 'plugin-manager',
  renderTab: (tab, tabCtx) => {
    const pluginId = tab.data?.id
    if (!pluginId) return null
    return React.createElement(ExtensionDetailPage, {
      pluginId,
      tabId: tab.id,
      closeTab: tabCtx.closeTab,
      showToast: tabCtx.showToast,
    })
  },
  renderIcon: (tab) => {
    const pluginId = tab.data?.id
    if (!pluginId) return React.createElement(DefaultPluginIcon, { size: 14 })
    // 目录已拉到（含 icon 声明）→ 展示插件图标；否则默认扩展图标占位
    const entry = findPluginEntry(pluginId)
    return React.createElement(PluginIcon, {
      size: 14,
      pluginId,
      iconPath: entry?.icon,
      alt: entry?.name,
    })
  },
  getLabel: (tab) => tab.data?.title ?? tab.title ?? tab.data?.id ?? '扩展详情',
  getTitle: (tab) => `${tab.data?.title ?? tab.data?.id ?? ''} — 扩展详情`,
  getCloseAriaLabel: (tab) => `关闭扩展详情：${tab.data?.title ?? tab.data?.id ?? ''}`,
  // 激活详情标签时保持扩展侧栏选中（VSCode 语义：左侧仍是扩展视图）
  getSidebarActivityId: () => 'plugins',
}
