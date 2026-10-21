/**
 * 渲染层 tool 域共享模块(「任务线程统一条目与画板纯化」步骤 10)。
 *
 * 工具配对(tool.result 被 message 下发块按 callId 吸收)归渲染层,核心折叠器与
 * 画板切片零知识:
 * - `resultsFor(toolCalls, items)`:message 视图批壳用——从画板 items 找 kind='tool.result'
 *   的行按 payload.callId 配对(未到 → null,与 toolCalls 对位);同 callId 多条结果
 *   首达优先(与旧 buildToolMaps 的 toolResultById 语义一致)。
 * - `absorbedIntoMessage(item, items)`:tool.result 视图用——画板内存在含该 callId 的
 *   message 下发 → true(视图 return null 不重复渲染);孤儿 → false(独立兜底不丢)。
 * - `detailOfCall/detailOfOrphan`:ThreadItem 载荷 → AggregatedToolDetail
 *   (per-tool 美化视图的唯一输入,参数/结果/状态聚合在一处)。
 * - `interruptedAfterMessage(item, items, mainAgentId)`:批中断的**可见终态信号**判定
 *   (从简,不做订阅):画板内出现 seq 更晚的 `cancelled`(任务级取消)或同 agent 的
 *   `error` → 该 message 之后未完成的调用按中断灰显(已 started「执行中被中断」/
 *   未 started「未执行」)。只看已在 items 的信号,不依赖台账订阅。
 */
import type { LLMToolCall } from '@/types'
import type { ThreadItem, ToolResultPayload } from '@/task/threadItem'
import type { AggregatedToolDetail } from '@/components/task/toolViews/types'
import { buildDetailFromCall } from '@/components/task/toolViews/helpers'
import { compareSeq } from '@/task/eventFolder'

/** 从画板 items 建 callId → tool.result 载荷索引(首达优先;只扫 kind='tool.result')。 */
function toolResultIndex(items: readonly ThreadItem[]): Map<string, ToolResultPayload> {
  const byCallId = new Map<string, ToolResultPayload>()
  for (const item of items) {
    if (item.kind !== 'tool.result') continue
    const payload = item.payload as ToolResultPayload | undefined
    const callId = payload?.callId?.trim()
    if (callId && payload && !byCallId.has(callId)) {
      byCallId.set(callId, payload)
    }
  }
  return byCallId
}

/**
 * message 视图批壳的按位结果配对:results[i] 与 toolCalls[i] 对位
 * (callId 配对到的 tool.result 载荷;结果未到 → null)。
 */
export function resultsFor(
  toolCalls: readonly LLMToolCall[],
  items: readonly ThreadItem[],
): (ToolResultPayload | null)[] {
  const index = toolResultIndex(items)
  return toolCalls.map((call) => index.get(call.id) ?? null)
}

/**
 * 该 tool.result 线程项是否已被某 message 下发块吸收(按 callId 配对到 toolCalls)。
 * true → tool.result 视图 return null(不重复渲染);false → 孤儿独立兜底。
 */
export function absorbedIntoMessage(item: ThreadItem, items: readonly ThreadItem[]): boolean {
  const callId = (item.payload as ToolResultPayload | undefined)?.callId?.trim()
  if (!callId) return false
  for (const other of items) {
    if (other.kind !== 'message') continue
    const toolCalls = (other.payload as { toolCalls?: LLMToolCall[] } | undefined)?.toolCalls
    if (toolCalls?.some((call) => call.id === callId)) return true
  }
  return false
}

/** 批壳调用行的展示详情:下发 call 为锚点 + 配对到的结果载荷(未到 null)。 */
export function detailOfCall(
  call: LLMToolCall,
  result: ToolResultPayload | null,
): AggregatedToolDetail {
  return buildDetailFromCall(call, result)
}

/** 孤儿 tool.result 的展示详情:无下发锚点,参数不可回填,结果/状态/工具名取载荷。 */
export function detailOfOrphan(payload: ToolResultPayload): AggregatedToolDetail {
  return buildDetailFromCall(
    { id: payload.callId, function: { name: payload.toolName?.trim() || '工具', arguments: '' } },
    payload,
  )
}

/**
 * 批中断的可见终态信号:画板内存在 seq 晚于该 message 的 `cancelled`(任务级)
 * 或同 agent(归一 mainAgentId)的 `error`。只影响该批中**无结果**的调用行展示。
 */
export function interruptedAfterMessage(
  item: ThreadItem,
  items: readonly ThreadItem[],
  mainAgentId: string,
): boolean {
  const agentKey = item.agentId || mainAgentId
  for (const other of items) {
    if (other.kind !== 'error' && other.kind !== 'cancelled') continue
    if (compareSeq(other.seq, item.seq) <= 0) continue
    if (other.kind === 'cancelled') return true
    if ((other.agentId || mainAgentId) === agentKey) return true
  }
  return false
}
