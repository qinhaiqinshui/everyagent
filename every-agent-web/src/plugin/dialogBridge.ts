/**
 * 插件模态对话框的模块级单例 store。
 *
 * 对标 task-wait 旧版 pickerStore，但泛化为「任意插件瞬态模态交互」的宿主中转层：
 * `ctx.ui.showDialog(request)` 经 PluginDispatcher 调 `openDialog(request)` 挂起 Promise，
 * PluginDialogHost（Layout 全局挂载一次）订阅本 store 渲染遮罩 + 居中浮层 + 插件 Content，
 * 用户收口（close(result)）或取消（Esc / 点遮罩）经 `resolveDialog(result)` 收口 Promise。
 *
 * 设计要点：
 * - 全局挂载：不依附任何 composer / 标签页，从根上消除「弹窗不知道弹到哪里去了」
 *   （旧 pickerStore 依附 composer_above_panel，草稿态漏挂即丢弹窗）。
 * - 零状态原则：store 只持当前活跃请求 + 一个 resolve 句柄，宿主零缓冲零业务逻辑。
 * - 重复 open（上一轮未收口又开新一轮）：先 resolve(null) 旧的，防旧 Promise 悬挂泄漏。
 */
import type { ComponentType } from 'react'

/** 当前活跃对话框的运行态（null = 无活跃对话框）。 */
export interface ActiveDialog<R = unknown> {
  /** 请求标题。 */
  title: string
  /** 副标题（可选）。 */
  description?: string
  /** 插件主体内容组件（props = { close }）。 */
  Content: ComponentType<{ close: (result: R | null) => void }>
  /** 收口回调（openDialog 返回的 Promise 的 resolve）。 */
  resolve: (result: R | null) => void
}

let current: ActiveDialog | null = null
const listeners = new Set<() => void>()

function emit(): void {
  for (const listener of [...listeners]) {
    listener()
  }
}

/**
 * 打开模态对话框并挂起等待用户收口。
 *
 * @returns 用户收口值；取消 / Esc / 点遮罩 / 上一轮未收口被顶替 → null
 */
export function openDialog<R>(request: {
  title: string
  description?: string
  Content: ComponentType<{ close: (result: R | null) => void }>
}): Promise<R | null> {
  // 上一轮还没收口：先 resolve(null) 旧的，防旧 Promise 悬挂泄漏
  const stale = current
  return new Promise<R | null>((resolve) => {
    current = {
      title: request.title,
      description: request.description,
      Content: request.Content as ActiveDialog['Content'],
      resolve: resolve as ActiveDialog['resolve'],
    }
    emit()
    if (stale) {
      ;(stale.resolve as (v: unknown) => void)(null)
    }
  })
}

/**
 * 收口当前对话框：resolve Promise 为 result 并清空活跃态。
 * 无活跃对话框（已收口 / 从未打开）时为 no-op（幂等）。
 */
export function resolveDialog<R>(result: R | null): void {
  if (!current) {
    return
  }
  const resolve = current.resolve
  current = null
  emit()
  ;(resolve as (v: R | null) => void)(result)
}

/** 当前快照（PluginDialogHost 经 useSyncExternalStore 订阅）。 */
export function dialogState(): ActiveDialog | null {
  return current
}

/** 订阅变化；返回取消订阅函数。 */
export function subscribeDialog(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}
