/**
 * 插件调度器——v1 空壳。
 *
 * n 分支的插件底座(扩展点收集/技能/斜杠命令)属于浏览器内 agent 运行时,
 * 已随运行时下沉到 worker。搬运过来的 UI 组件仍有少量扩展点调用
 * (ui.sidebar_items / ui.file_sidebar_panels 等),
 * 这里统一返回空结果:对应 UI 区域(更多操作/插件侧栏)自然不渲染,
 * 组件本身零改动。若未来前端插件体系回归,以真实实现替换本文件即可。
 */

import type { UiSidebarItemDefinition } from './types'

interface HollowDispatcher {
  /** 所有扩展点分发:一律空结果。 */
  dispatch: <T>(_extensionPoint: string) => Promise<T[]>
  /** 侧边栏项单独留类型(调用方直接用 dispatch 的场景也已覆盖)。 */
  getSidebarItems?: () => Promise<UiSidebarItemDefinition[]>
}

export const pluginDispatcher: HollowDispatcher = {
  dispatch: async <T,>() => [] as T[],
}
