/**
 * 工具调用负载的纯函数工具集（从 AgentMessageThread 抽取，供 toolViews 统一复用）。
 * 只依赖类型，不依赖展示层，避免循环依赖。
 */

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
 * 工具结果内容 → 展示用 result 字段:JSON 可解析取解析值,否则保留原始文本字符串;
 * 空内容返回 null。批壳调用行(detailFromCall)与孤儿结果(detailFromOrphanResult)
 * 共用的结果解读规则(与旧聚合块的 buildStructuredToolField 同口径)。
 */
function readToolResult(content: string | undefined): unknown {
  if (!content || !content.trim()) return null
  return (buildStructuredToolField(content, 'result', 'result') as Record<string, unknown>).result ?? null
}

/**
 * 以 message 下发的 toolCall 为锚点聚合展示详情(ThreadItem 载荷 → 美化视图输入):
 * 参数来自下发 arguments JSON(解析失败/为空 → null),结果/状态/耗时来自按 callId
 * 配对到的 tool.result 载荷(未到 → null/undefined)。批壳调用行与孤儿兜底共用。
 */
export function buildDetailFromCall(
  call: { id: string; function: { name: string; arguments: string } },
  result: { content?: string; status?: 'success' | 'error'; toolName?: string } | null,
): AggregatedToolDetail {
  const args = parseToolPayload(call.function.arguments)
  return {
    type: 'tool_call',
    toolName: result?.toolName?.trim() || call.function.name,
    toolCallId: call.id,
    status: result?.status,
    arguments: Object.keys(args).length > 0 ? args : null,
    result: result ? readToolResult(result.content) : null,
  }
}

/**
 * 从任意形态路径（POSIX `/` 与 Windows `\` 分隔符可混合）提取末段文件名。
 * 末尾分隔符（目录路径）会被剥掉；无有效片段（空串 / 纯分隔符）时回退原字符串。
 */
export function extractFileName(path: string): string {
  const segments = path.split(/[\\/]+/).filter(Boolean)
  return segments.length > 0 ? segments[segments.length - 1] : path
}

export function truncateInlineText(text: string, maxLength = 160): string {
  if (text.length <= maxLength) {
    return text
  }
  return `${text.slice(0, Math.max(maxLength - 1, 1))}…`
}

/**
 * 拆分命令首行注释(意图说明)与命令体。
 * bash / powershell 单行注释均以 `#` 起头;`#!` 开头是 shebang,不算语义注释。
 *
 * 命中返回 { comment: 剥掉 # 与前导空白后的注释文本, rest: 剩余行 trim 后的命令体 };
 * 未命中(无注释 / 注释剥完为空 / shebang / 命令为空)返回 null,调用方回退原样渲染。
 */
export function splitLeadingComment(command: string): { comment: string; rest: string } | null {
  if (!command) return null
  const firstLine = command.split('\n', 1)[0] ?? ''
  const head = firstLine.trimStart()
  // `#!` 是 shebang,不是语义注释;空注释也不拆。
  if (!head.startsWith('#') || head.startsWith('#!')) return null
  const comment = head.slice(1).trim()
  if (!comment) return null
  const rest = command.slice(firstLine.length).trim()
  return { comment, rest }
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

/** 调用行内联预览:文本(原样等宽)或「注释 + 命令」两段(注释走 AI 正文样式)。 */
export type CallInlinePreview =
  | { kind: 'comment'; comment: string; rest: string }
  | { kind: 'text'; text: string }

/**
 * 调用行内联预览:取 detail 的 path / command / 文本参数摘要,无参时回退空串。
 * command 首行是注释(意图说明)时拆分为「正文样式注释 + 等宽命令」两段,
 * 与单条 CommandToolView 折叠态一致。原 ToolCallView 聚合壳的
 * buildGroupInlinePreview 原位迁入(批壳调用行与孤儿兜底共用,行为不变)。
 */
export function buildCallInlinePreview(detail: AggregatedToolDetail): CallInlinePreview {
  const args = (detail.arguments ?? {}) as Record<string, unknown>
  // 优先 path(非命令工具);其次 command(bash/powershell),命中首行注释则拆分高亮。
  const path = typeof args.path === 'string' ? args.path : ''
  if (path.trim()) {
    return { kind: 'text', text: truncateInlineText(path, 160) }
  }
  const command = typeof args.command === 'string' ? args.command : ''
  if (command.trim()) {
    const split = splitLeadingComment(command.trim())
    if (split) {
      return {
        kind: 'comment',
        comment: truncateInlineText(split.comment, 160),
        rest: truncateInlineText(split.rest, 160),
      }
    }
    return { kind: 'text', text: truncateInlineText(command, 160) }
  }
  // 退化:把若干标量参数以 "k=v · k=v" 形式铺平(够短),完全无参时给个占位。
  const scalars = Object.entries(args)
    .filter(([, v]) => v !== null && (typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean'))
    .slice(0, 3)
    .map(([k, v]) => `${k}=${typeof v === 'string' ? v : JSON.stringify(v)}`)
    .join(' · ')
  return { kind: 'text', text: scalars ? truncateInlineText(scalars, 160) : '' }
}
