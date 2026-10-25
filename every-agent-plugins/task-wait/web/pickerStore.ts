/**
 * 任务选择框的模块级单例 store。
 *
 * open(candidates) 由 slash select 回调调用（返回 Promise 挂起，等用户选择），
 * choose(t) / cancel() 由选择框组件调用（幂等 resolve）；
 * subscribe + state 供 React 组件经 `useSyncExternalStore` 订阅渲染
 * （宿主 React 18.2，原生支持）。
 *
 * 重复 open（上一轮未收口又开新一轮）：先 resolve(null) 旧的，防旧 Promise 悬挂。
 */
import type { PickerTask } from './candidates'

/** store 对外快照形状。 */
export interface PickerState {
  /** 是否处于打开态（false 时面板组件渲染 null）。 */
  open: boolean
  /** 候选任务列表（打开时非空；关闭时清空）。 */
  candidates: PickerTask[]
}

let currentState: PickerState = { open: false, candidates: [] }
let listeners: Array<() => void> = []
let resolveRef: ((picked: PickerTask | null) => void) | null = null

function emit(): void {
  for (const listener of listeners) {
    listener()
  }
}

/** 打开选择框并挂起等待用户选择；取消 / 未选中 → resolve(null)。 */
export function open(candidates: PickerTask[]): Promise<PickerTask | null> {
  // 上一轮还没收口：先取消旧的（防旧 Promise 悬挂泄漏）
  const stale = resolveRef
  resolveRef = null
  currentState = { open: true, candidates }
  emit()
  if (stale) {
    stale(null)
  }
  return new Promise<PickerTask | null>((resolve) => {
    resolveRef = resolve
  })
}

/** 幂等收口：无挂起的 open（已收口 / 从未打开）时为 no-op。 */
function settle(picked: PickerTask | null): void {
  const resolve = resolveRef
  if (!resolve) {
    return
  }
  resolveRef = null
  currentState = { open: false, candidates: [] }
  emit()
  resolve(picked)
}

/** 选中某个任务（选择框组件调用）。 */
export function choose(task: PickerTask): void {
  settle(task)
}

/** 取消选择（Esc / 点遮罩 / 取消按钮）。 */
export function cancel(): void {
  settle(null)
}

/** 当前快照（useSyncExternalStore 的 getSnapshot）。 */
export function state(): PickerState {
  return currentState
}

/** 订阅变化（useSyncExternalStore 的 subscribe）；返回取消订阅函数。 */
export function subscribe(listener: () => void): () => void {
  listeners.push(listener)
  return () => {
    listeners = listeners.filter((l) => l !== listener)
  }
}
