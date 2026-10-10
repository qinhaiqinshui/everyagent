/**
 * 当前激活工作区标签的模块级镜像。
 *
 * 写入方：宿主壳层（Layout）在激活标签变化时同步写入；
 * 读取方：pluginDispatcher.getActiveTab()（即插件 ctx.ui.getActiveTab()）。
 *
 * 独立成模块而不是塞进 Layout/pluginLoader，是为了让两者都只依赖这个
 * 无副作用小模块，避免「壳层组件 ↔ 插件加载器」相互 import。
 * 变更的响应式通知走 `workspace-tab-activated` / `workspace-tab-closed`
 * 领域事件；本镜像只回答「此刻是什么」。
 */

import type { PluginActiveTabInfo } from '@everyagent/plugin-api'

export const activeTabMirror: { current: PluginActiveTabInfo | null } = {
  current: null,
}

/** 由宿主壳层在激活标签变化（含清空为 null）时调用。 */
export function setActiveTabMirror(tab: PluginActiveTabInfo | null): void {
  activeTabMirror.current = tab
}
