/**
 * 插件运行期桥接：宿主 React 树把「非 React 模块可用的能力」注册到模块级 holder，
 * 供 PluginDispatcher（ctx.ui.*）在插件 activate 时同步调用。
 *
 * 这些能力原本只挂在 React Context（WorkspaceShellContext / ComposerDraftBridgeContext）
 * 上，插件 ctx 是非 React 模块拿不到 hook 结果，因此由宿主在挂载时通过 setter 注入。
 *
 * 桥接目标是「保持零状态、零缓冲」：setter 只持最新引用，调用方直接同步委托，
 * 无任何排队/缓存。未注入时调用方静默降级（不影响核心）。
 *
 * 例外：ShellBridge 仍严格零状态；ComposerBridge 仅在新标签页刚打开、bridge
 * 尚未注入时对草稿文本做一次性排队（appendComposerTextQueued），注入即按序 flush。
 */
import type { PluginDiffTabInput } from '@everyagent/plugin-api'

/** 宿主壳层导航能力子集（来自 WorkspaceShellContext 的 actions）。 */
export interface ShellBridge {
  /** 打开标签（内置/插件统一入口）。 */
  openTab(type: string, data?: Record<string, string>, title?: string): string | null
  openFileTab(workspaceRoot: string, filePath: string, options?: { mode?: string; lineNumber?: number }): void
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

/** 待写入的草稿文本队列：bridge 尚未注入时（新标签页刚打开、TaskChat 未挂载）暂存，待注入时按序 flush。 */
const pendingComposerTexts: string[] = []

/**
 * 追加草稿文本：桥接就绪直接写入；未就绪则排队，待 setComposerBridge 注入时按序 flush。
 * （解决「新标签页打开后 bridge 尚未注入导致 appendComposerToken 静默失败」的时序问题。）
 */
export function appendComposerTextQueued(text: string): void {
  if (composerBridge) {
    composerBridge.appendText(text)
    return
  }
  pendingComposerTexts.push(text)
}

/** 由宿主 TaskChat 在挂载时注入草稿桥接能力；传 null 卸载。注入时 flush 已排队的草稿文本。 */
export function setComposerBridge(bridge: ComposerBridge | null): void {
  composerBridge = bridge
  if (bridge && pendingComposerTexts.length > 0) {
    for (const text of pendingComposerTexts.splice(0)) {
      bridge.appendText(text)
    }
  }
}

/** 清空草稿桥接与待写队列（标签卸载/会话切换时避免残留）。 */
export function clearComposerBridge(): void {
  composerBridge = null
  pendingComposerTexts.length = 0
}

/** 读取当前壳层桥接（未注入返回 null）。 */
export function getShellBridge(): ShellBridge | null {
  return shellBridge
}

/** 读取当前草稿桥接（未注入返回 null）。 */
export function getComposerBridge(): ComposerBridge | null {
  return composerBridge
}
