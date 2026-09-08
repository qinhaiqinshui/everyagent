/**
 * 工具调用负载的纯函数工具集（从 AgentMessageThread 抽取，供 toolViews 统一复用）。
 * 只依赖类型，不依赖展示层，避免循环依赖。
 */

import type { LLMToolCall, AgentMessageRecord } from '@/types'
import type { AggregatedToolDetail } from './types'

/**
 * 解析工具调用或工具结果的 JSON 负载。
 * 失败时回退为空对象，避免展示层被脏数据打断。
 */
export function parseToolPayload(rawText: string | undefined): Record<string, unknown> {
  const text = rawText?.trim()
  if (!text) {
    return {}
  }
  try {
    const parsed = JSON.parse(text)
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>
    }
  } catch {
    // 保持默认空对象，不把解析错误抛到展示层。
  }
  return {}
}

function buildStructuredToolField(
  rawText: string | undefined,
  parsedKey: string,
  rawKey: string,
): Record<string, unknown> {
  const text = rawText?.trim() ?? ''
  if (!text) {
    return { [parsedKey]: null }
  }
  try {
    return { [parsedKey]: JSON.parse(text) as unknown }
  } catch {
    return { [rawKey]: text }
  }
}

/**
 * 聚合工具调用的展开详情：工具名 + 下发参数（arguments）+ 调用结果（result）。
 * arguments 来自按 toolCallId 取回的下发负载；result 来自 tool 角色消息 content。
 */
export function buildAggregatedToolDetail(
  message: AgentMessageRecord,
  resolvedToolName: string,
  args: Record<string, unknown> | null,
): AggregatedToolDetail {
  const structured = buildStructuredToolField(message.content, 'result', 'result') as Record<string, unknown>
  return {
    type: 'tool_call',
    toolName: resolvedToolName?.trim() || message.toolName?.trim() || '工具',
    toolCallId: message.toolCallId?.trim() || null,
    status: message.status,
    arguments: args,
    result: structured.result ?? null,
  }
}

/**
 * 聚合工具调用的展开详情（下发视角）：以 assistant 的 toolCall 为锚点，
 * 把下发参数（call.function.arguments）与按 callId 匹配到的 tool 结果消息合并为同一
 * AggregatedToolDetail。与 buildAggregatedToolDetail 对称——后者以 tool 消息为锚点反查下发参数，
 * 两者产出同一形状，保证 live 下发块与历史 tool 块走同一套 View。
 */
export function buildAggregatedToolDetailFromCall(
  call: LLMToolCall,
  resultMessage?: AgentMessageRecord | null,
): AggregatedToolDetail {
  const args = parseToolPayload(call.function.arguments)
  const structured = resultMessage?.content
    ? (buildStructuredToolField(resultMessage.content, 'result', 'result') as Record<string, unknown>)
    : null
  return {
    type: 'tool_call',
    toolName: resultMessage?.toolName?.trim() || call.function.name,
    toolCallId: call.id,
    status: resultMessage?.status,
    arguments: Object.keys(args).length > 0 ? args : null,
    result: structured?.result ?? null,
  }
}

export function truncateInlineText(text: string, maxLength = 160): string {
  if (text.length <= maxLength) {
    return text
  }
  return `${text.slice(0, Math.max(maxLength - 1, 1))}…`
}

/**
 * 折叠行点击守卫：inline-preview 的参数文本已放开 user-select 可拖选复制，
 * 拖选结束会触发一次 click——若此刻选区非空则视为"选文字"而非"点行"，
 * 不切换折叠态，避免选中参数时误展开/收起。
 */
export function hasActiveTextSelection(): boolean {
  const selection = typeof window !== 'undefined' ? window.getSelection() : null
  return !!selection && !selection.isCollapsed && selection.toString().length > 0
}
