/**
 * tool.started 瞬态运行时映射(「任务线程统一条目与画板纯化」步骤 10)。
 *
 * `tool.started` 是单个工具调用开始执行的**事实声明**(per-call 工具执行链下行段发射,
 * §5.3/§7.14.3):瞬态不落盘、不进线程 items——eventRegistry 的 handler 把
 * `{callId, name, arguments, startedAt}` 记入本模块级映射(按 taskId 分域,多任务
 * 标签并存互不串扰),message 视图的批壳按 callId 直配点亮调用行 running + 读秒
 * `now - startedAt`。对应 `tool.result` 到达后视图改用权威 durationMs,映射条目
 * 自然失效;`folder.reset()`(编辑重发/全量重载)清空该任务的映射。
 *
 * 历史回放无瞬态事件(PERSIST_SKIP 不落盘)→ 映射为空 → 调用行直接展示 result 的
 * durationMs,与设计一致。
 */

/** 单个工具调用的瞬态开始信息(wire tool.started 事件 data 形状)。 */
export interface ToolStartedInfo {
  /** 工具调用 ID(与 message.toolCalls[].id 直配)。 */
  callId: string
  /** 工具名。 */
  name: string
  /** 下发参数 JSON 原文。 */
  arguments: string
  /** 开始时间(毫秒)。 */
  startedAt: number
}

/** taskId → (callId → 瞬态开始信息)。模块级单例(与任务流生命周期无关,reset 显式清理)。 */
const startedByTask = new Map<string, Map<string, ToolStartedInfo>>()

/** 记录一次 tool.started(同 callId 重复到达覆盖,幂等)。 */
export function recordToolStarted(taskId: string, info: ToolStartedInfo): void {
  if (!taskId || !info.callId) return
  let byCall = startedByTask.get(taskId)
  if (!byCall) {
    byCall = new Map()
    startedByTask.set(taskId, byCall)
  }
  byCall.set(info.callId, info)
}

/** 按 callId 读取瞬态开始信息(未开始/已过期清理 → undefined)。 */
export function getToolStarted(taskId: string, callId: string): ToolStartedInfo | undefined {
  if (!taskId || !callId) return undefined
  return startedByTask.get(taskId)?.get(callId)
}

/** 清空某任务的瞬态映射(folder.reset:编辑重发/全量重载,旧调用行已随 items 截断)。 */
export function clearTaskToolStarted(taskId: string): void {
  startedByTask.delete(taskId)
}
