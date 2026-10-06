/**
 * 插件管理插件——PluginModule 入口(纯 Web 插件，无 worker 端)。
 *
 * 经 pluginLoader.ts 自动发现加载，注册：
 * - 侧边栏「扩展」面板（VSCode 扩展视图风格列表，ctx.ui.registerSidebarItem）。
 * - `extension-detail` 工作区标签类型（ctx.ui.registerWorkspaceTabType）——
 *   面板点击扩展行后经 ctx.ui.openPluginTab 打开详情标签页（同一插件恒为同一
 *   标签页，重复点击聚焦），对标 VSCode 在编辑器区打开扩展详情页。
 *
 * 面板与详情页共享 pluginStore（plugin.list / enable / disable / uninstall /
 * install RPC 的统一封装），动作后两处视图自动同步。
 */
import React from 'react'
import type { PluginModule, PluginContext, PluginSdk, UiRegistry } from '@everyagent/plugin-api'
import PluginManagerPanel from './PluginManagerPanel'
import { ExtensionIcon } from './icons'
import { extensionDetailTabType } from './tabTypes'

const pluginManagerPlugin: PluginModule = {
  activate(ctx: PluginContext) {
    // 将 sdk / ui 存入模块级引用，供面板与详情页组件在渲染时使用。
    _sdkRef = ctx.sdk
    _uiRef = ctx.ui

    ctx.ui.registerSidebarItem({
      id: 'plugins',
      title: '扩展',
      icon: React.createElement(ExtensionIcon),
      Panel: PluginManagerPanel,
      // 活动栏排序：排在源代码管理 (5) 之后、内置「设置」(10) 之前。
      order: 9,
    })

    ctx.ui.registerWorkspaceTabType(extensionDetailTabType)
  },
}

/** 模块级 sdk 引用，由 activate 时设置，组件读取。 */
let _sdkRef: PluginSdk | null = null

/** 模块级 ui 引用，由 activate 时设置，组件读取（openPluginTab 等）。 */
let _uiRef: UiRegistry | null = null

/** 面板/详情组件获取 sdk 的访问器。 */
export function getSdk(): PluginSdk | null {
  return _sdkRef
}

/** 组件获取 ui 注册表的访问器（openPluginTab 打开详情标签页）。 */
export function getUi(): UiRegistry | null {
  return _uiRef
}

export default pluginManagerPlugin
