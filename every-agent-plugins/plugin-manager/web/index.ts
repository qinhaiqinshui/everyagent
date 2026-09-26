/**
 * 插件管理插件——PluginModule 入口(纯 Web 插件，无 worker 端)。
 *
 * 经 pluginLoader.ts 自动发现加载，注册侧边栏「扩展」面板。
 * 面板通过 ctx.sdk.rpc 调用 worker 的 plugin.list / plugin.enable / plugin.disable
 * RPC，实现插件目录的查看与启用/禁用切换。
 */
import React from 'react'
import type { PluginModule, PluginContext, PluginSdk } from '@everyagent/plugin-api'
import PluginManagerPanel from './PluginManagerPanel'
import { ExtensionIcon } from './icons'

const pluginManagerPlugin: PluginModule = {
  activate(ctx: PluginContext) {
    // 将 sdk 存入模块级引用，供 PluginManagerPanel 在渲染时使用。
    _sdkRef = ctx.sdk

    ctx.ui.registerSidebarItem({
      id: 'plugins',
      title: '扩展',
      icon: React.createElement(ExtensionIcon),
      Panel: PluginManagerPanel,
    })
  },
}

/** 模块级 sdk 引用，由 activate 时设置，面板组件读取。 */
let _sdkRef: PluginSdk | null = null

/** 面板组件获取 sdk 的访问器。 */
export function getSdk(): PluginSdk | null {
  return _sdkRef
}

export default pluginManagerPlugin
