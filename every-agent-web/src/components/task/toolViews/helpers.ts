/**
 * 工具调用负载的纯函数工具集（从 AgentMessageThread 抽取，供 toolViews 统一复用）。
 * 只依赖类型，不依赖展示层，避免循环依赖。
 */

import type { CSSProperties } from 'react'
import type { AggregatedToolDetail, ToolCallState } from './types'

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
  result: { content?: string; status?: 'success' | 'error'; toolName?: string; durationMs?: number } | null,
): AggregatedToolDetail {
  const args = parseToolPayload(call.function.arguments)
  return {
    type: 'tool_call',
    toolName: result?.toolName?.trim() || call.function.name,
    toolCallId: call.id,
    status: result?.status,
    arguments: Object.keys(args).length > 0 ? args : null,
    result: result ? readToolResult(result.content) : null,
    durationMs: result?.durationMs,
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

/**
 * 紧凑耗时文案:亚秒级(<1s)精确到毫秒、不四舍五入、去无意义尾零
 * (101→0.101s、120→0.12s、0→0s);1s~10s 一位小数;10s~60s 整秒;分钟级 m:ss 补零;
 * 非法值返回空串。
 */
export function formatToolDuration(ms: number | undefined): string {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return ''
  if (ms < 1000) {
    // ms 是整数毫秒,真值即最多 3 位小数的有限小数:toFixed(3) 取精确 3 位
    // (无第 4 位可舍入),再经 Number 归一化去掉无意义尾零。
    return `${Number((ms / 1000).toFixed(3))}s`
  }
  const seconds = ms / 1000
  if (seconds < 10) return `${seconds.toFixed(1)}s`
  if (seconds < 60) return `${Math.floor(seconds)}s`
  const minutes = Math.floor(seconds / 60)
  const rest = Math.floor(seconds % 60)
  return rest > 0 ? `${minutes}m${String(rest).padStart(2, '0')}s` : `${minutes}m`
}

/**
 * 详情 → 状态图标 class(nagent-tool-state--* 颜色/动效单一来源):
 * 优先 detail.rowState(批壳/单调用直渲灌入);缺省时按 status 推导(孤儿兜底:
 * 有结果 success→done/error→error;status 空 = 结果未到,无状态色)。
 */
export function toolStateClass(detail: AggregatedToolDetail): string {
  const state: ToolCallState | null = detail.rowState
    ?? (detail.status === 'error'
      ? 'error'
      : detail.status === 'success' ? 'done' : null)
  return state ? `nagent-tool-state--${state}` : ''
}

/** 详情 → 摘要行耗时文本(detail.durationMs;非法/缺失返回空串不渲染)。 */
export function toolDurationText(detail: AggregatedToolDetail): string {
  return formatToolDuration(detail.durationMs)
}

/** 慢调用色阶区间:10s 起色(刚过阈值给 12% 淡染),120s(2min)封顶,再慢不再加深。 */
const SLOW_TINT_START_MS = 10_000
const SLOW_TINT_MAX_MS = 120_000
/** 起色百分比 / 封顶百分比(color-mix 里 accent-red 的占比)。 */
const SLOW_TINT_MIN_PCT = 12
const SLOW_TINT_MAX_PCT = 100

/**
 * 耗时 → 颜色深浅百分比(0~100),供 CSS 的 `color-mix(in srgb, --accent-red P%, --text-muted)` 插值。
 *
 * <10s 返回 0(纯 --text-muted,与历史观感一致);10s~120s 线性升到封顶值;
 * ≥120s 恒定封顶——继续加深只会让满屏深红失去区分度,超过 2min 已是"极慢",无需再分级。
 *
 * 通道分工:轨道图标的 state 色只表达**成败**(done 绿 / error 红 / interrupted 黄),
 * 本百分比只作用于**耗时数字**,于是「执行成功但耗时很久」= 图标绿 + 数字深红,
 * 两个信息各走各的颜色通道,不会互相误读。
 */
export function toolDurationTint(ms: number | undefined): number {
  if (ms == null || !Number.isFinite(ms) || ms < SLOW_TINT_START_MS) return 0
  const ratio = (ms - SLOW_TINT_START_MS) / (SLOW_TINT_MAX_MS - SLOW_TINT_START_MS)
  const clamped = Math.min(1, Math.max(0, ratio))
  return Math.round(SLOW_TINT_MIN_PCT + clamped * (SLOW_TINT_MAX_PCT - SLOW_TINT_MIN_PCT))
}

/**
 * 详情 → 耗时列内联样式:把 tint 百分比写成 `--tool-dur-tint` 自定义属性,
 * 颜色插值本身留在样式层(chatPanel.css 的 .nagent-tool__duration),
 * 视图侧只负责喂数值,不各自硬编码色值。
 */
export function toolDurationTintStyle(detail: AggregatedToolDetail): CSSProperties {
  return { '--tool-dur-tint': `${toolDurationTint(detail.durationMs)}%` } as CSSProperties
}
