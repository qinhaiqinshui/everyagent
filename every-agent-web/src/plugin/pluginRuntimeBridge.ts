/**
 * 插件运行期桥接：宿主 React 树把「非 React 模块可用的能力」注册到模块级 holder，
 * 供 PluginDispatcher（ctx.ui.*）在插件 activate 时同步调用。
 *
 * 这些能力原本只挂在 React Context（WorkspaceShellContext / ComposerDraftBridgeContext）
 * 上，插件 ctx 是非 React 模块拿不到 hook 结果，因此由宿主在挂载时通过 setter 注入。
 *
 * 桥接目标是「保持零状态、零缓冲」：setter 只持最新引用，调用方直接同步委托，
 * 无任何排队/缓存。未注入时调用方静默降级（不影响核心）。
 */
import type { PluginDiffTabInput } from '@everyagent/plugin-api'

/** 宿主壳层导航能力子集（来自 WorkspaceShellContext 的 actions）。 */
export interface ShellBridge {
  openPluginTab(type: string, data: Record<string, string>, title?: string): string | null
  openFileTab(workspaceRoot: string, filePath: string, options?: { mode?: string }): void
  openDiffTab(input: PluginDiffTabInput): string
}

/** 输入框草稿桥接能力子集（来自 ComposerDraftBridgeContext）。 */
export interface ComposerBridge {
  appendText(text: string): void
  setRawContent(rawContent: string): void
}

let shellBridge: ShellBridge | null = null
let composerBridge: ComposerBridge | null = null

/** 由宿主 Layout 在挂载时注入壳层导航能力；传 null 卸载。 */
export function setShellBridge(bridge: ShellBridge | null): void {
  shellBridge = bridge
}

/** 由宿主 TaskChat 在挂载时注入草稿桥接能力；传 null 卸载。 */
export function setComposerBridge(bridge: ComposerBridge | null): void {
  composerBridge = bridge
}

/** 读取当前壳层桥接（未注入返回 null）。 */
export function getShellBridge(): ShellBridge | null {
  return shellBridge
}

/** 读取当前草稿桥接（未注入返回 null）。 */
export function getComposerBridge(): ComposerBridge | null {
  return composerBridge
}
