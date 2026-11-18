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
import { activeTabMirror } from './activeTabMirror'

/** 宿主壳层导航能力子集（来自 WorkspaceShellContext 的 actions）。 */
export interface ShellBridge {
  /** 打开标签（内置/插件统一入口）。 */
  openTab(type: string, data?: Record<string, string>, title?: string): string | null
  openFileTab(workspaceRoot: string, filePath: string, options?: { mode?: string; lineNumber?: number }): void
  openDiffTab(input: PluginDiffTabInput): string
  /**
   * 打开设置页标签（page:settings）并滚动到 sectionId 对应设置段（可省略）。
   * 委托语义：宿主 Layout 注入的实现 = openTab('page:settings') 打开/聚焦标签，
   * 再经 settingsNavigation 记录 pending 意图 + 发领域事件；未注册 id 静默忽略。
   */
  openSettingsPage(sectionId?: string): void
}

/** 输入框草稿桥接能力子集（来自 ComposerDraftBridgeContext）。 */
export interface ComposerBridge {
  appendText(text: string): void
  setRawContent(rawContent: string): void
  /**
   * 在输入框**光标处**插入一段草稿内容（opaque token 串或纯文本），聚焦编辑器；
   * 编辑器尚未挂载时退化为末尾追加。与 appendText（恒定末尾追加）相对，
   * 供「引用到AI对话框」等需要落位光标位置的宿主动作使用。
   */
  insertAtCaret(rawContent: string): void
}

let shellBridge: ShellBridge | null = null
let composerBridge: ComposerBridge | null = null

/** 由宿主 Layout 在挂载时注入壳层导航能力；传 null 卸载。 */
export function setShellBridge(bridge: ShellBridge | null): void {
  shellBridge = bridge
}

/** 待写入的草稿队列项：bridge 尚未注入时（新标签页刚打开、TaskChat 未挂载）暂存，待注入时按序 flush。 */
interface PendingComposerEntry {
  /** 草稿文本（可含 opaque token 串）。 */
  text: string
  /** true = 注入后经 insertAtCaret 落位光标处；false = appendText 追加到末尾。 */
  atCaret: boolean
}

const pendingComposerTexts: PendingComposerEntry[] = []

/**
 * 追加草稿文本：桥接就绪直接写入；未就绪则排队，待 setComposerBridge 注入时按序 flush。
 * （解决「新标签页打开后 bridge 尚未注入导致 appendComposerToken 静默失败」的时序问题。）
 *
 * 陈旧桥接守卫：工作区标签常驻挂载（非激活仅 display:none），且 TaskChat 的桥
 * 「仅激活时注入、不活跃时不清空」，故停留在非任务标签（如文件页）时 composerBridge
 * 可能是**陈旧引用**——指向某个已隐藏的旧任务页。此时直写会把内容塞进错误草稿
 * （典型现场：文件页「引用到AI对话框」先 openTab(新任务) 再 append，同步执行的
 * 此刻新任务页尚未激活，bridge 仍指旧任务页 → 引用落进旧草稿、新标签页为空）。
 * 因此仅当「当前激活标签是任务标签」（bridge 必属该激活页）时才直写，否则一律排队，
 * 待目标任务页激活注入 bridge 时 flush。
 *
 * 激活切换守卫（invalidateComposerBridge）：上面「镜像仍是 task」的判据在
 * openTab 切换标签的**同一同步调用栈**内是过时的——镜像由 Layout 的 effect 在
 * React commit 后才更新，此刻它还指向切换前的旧任务标签，直写仍会落进旧草稿。
 * 故宿主壳层在激活标签真正切换（openTab/点选/关闭）的同步路径上先作废桥，
 * 让紧跟其后的 append 一律排队，待新激活任务页注入桥时 flush。
 */
export function appendComposerTextQueued(text: string, options?: { atCaret?: boolean }): void {
  const atCaret = options?.atCaret === true
  if (composerBridge && activeTabMirror.current?.tabType === 'task') {
    if (atCaret) {
      composerBridge.insertAtCaret(text)
    } else {
      composerBridge.appendText(text)
    }
    return
  }
  pendingComposerTexts.push({ text, atCaret })
}

/**
 * 作废当前草稿桥引用（不清待写队列）：宿主壳层在激活标签切换/关闭的同步路径上调用。
 * 桥此后由新激活任务页的 TaskChat 重新注入（注入即 flush 队列），窗口期内 append
 * 一律走排队路径，杜绝「桥仍指向已隐藏/已卸载旧任务页」的陈旧直写。
 */
export function invalidateComposerBridge(): void {
  composerBridge = null
}

/** 由宿主 TaskChat 在挂载时注入草稿桥接能力；传 null 卸载。注入时 flush 已排队的草稿文本。 */
export function setComposerBridge(bridge: ComposerBridge | null): void {
  composerBridge = bridge
  if (bridge && pendingComposerTexts.length > 0) {
    for (const entry of pendingComposerTexts.splice(0)) {
      if (entry.atCaret) {
        bridge.insertAtCaret(entry.text)
      } else {
        bridge.appendText(entry.text)
      }
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
