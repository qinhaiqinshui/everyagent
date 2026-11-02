import type { ChatComposerToken } from '@/types'
import { composerTokenRegistry } from './composerTokenRegistry'

/**
 * 输入框 opaque token 本地实现（原 `core/task-runtime/composerOpaqueToken` 已删除）。
 *
 * 这些函数只负责把 composer 草稿里的 token 列表与纯文本正文序列化成
 * 可持久化的 `rawContent`，并在回显时再切分回来，与协议 / 运行时无关。
 *
 * 编码采用「4 个连续相同符号」定界（边界 `[[[[`/`]]]]`、`::::` 标记→kind、
 * `||||` 字段间、`====` 键=值），因 4 连符号在内容中几乎不出现，无需任何转义：
 * - `label` / `summary` 是「展示契约」明文，渲染层只读，无需解码 payload；
 * - `payload` 是「语义契约」，base64url 且去 `=` 填充，仅提交时由 `composerTokenRegistry`
 *   按 `kind` 解码，渲染层对其零感知。
 *
 * **嵌套语义（2026-11）**：任务标题存 rawContent 原样后，一个 token 的 label/payload
 * 可能内嵌另一个完整 token 串（如「等待任务」胶囊 label 嵌目标任务标题），形成嵌套
 * token。全链路按**深度平衡**配对（`[[[[` 深度+1、`]]]]` 深度-1，深度归零处为段边界），
 * 顶层字段切分只在深度 0 处生效——与 Java 侧 `SlashTokenEncoder` 逐字一致。
 */

/** opaque token 边界前缀：`[[[[`（4 个 `[`）。 */
const OPAQUE_PREFIX = '[[[['
/** opaque token 边界后缀：`]]]]`（4 个 `]`）。 */
const OPAQUE_SUFFIX = ']]]]'
/** 静态标记→kind：`agent-token::::`（4 个 `:`）。 */
const KIND_MARKER = 'agent-token::::'
/** 顶层字段间分隔符：`||||`（4 个 `|`）。 */
const FIELD_SEP = '||||'
/** 字段内键=值分隔符：`====`（4 个 `=`）。 */
const KEY_SEP = '===='

/** base64url 编码（去 `=` 填充）。 */
function toBase64Url(value: string): string {
  const encoded = btoa(unescape(encodeURIComponent(value)))
  return encoded.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '')
}

/** base64url 解码（补 `=` 填充）。 */
function fromBase64Url(value: string): string {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/')
  const padLength = (4 - (normalized.length % 4)) % 4
  const padded = normalized + '='.repeat(padLength)
  return decodeURIComponent(escape(atob(padded)))
}

/**
 * 生成单个 opaque token 文本（自包含：label/summary 在顶层明文段，payload 在末尾）。
 * 不做任何转义；渲染层只读顶层 label/summary，提交时只解码 payload。
 */
export function buildOpaqueTokenText(
  kind: string,
  opts: { label: string; summary?: string; payload: Record<string, unknown> },
): string {
  const normalizedKind = kind.trim()
  const label = (opts.label ?? '').trim()
  if (!normalizedKind) {
    throw new Error('构造 opaque token 失败：kind 为空')
  }
  if (!label) {
    throw new Error('构造 opaque token 失败：label 为空')
  }
  const payloadText = toBase64Url(JSON.stringify(opts.payload ?? {}))
  let body = KIND_MARKER + normalizedKind + FIELD_SEP + 'label' + KEY_SEP + label
  if (opts.summary) {
    body += FIELD_SEP + 'summary' + KEY_SEP + opts.summary
  }
  body += FIELD_SEP + 'payload' + KEY_SEP + payloadText
  return OPAQUE_PREFIX + body + OPAQUE_SUFFIX
}

/**
 * 解析单个 opaque token 文本（4 连符号定界，无需转义）。
 * 返回 { kind, label, summary?, payload }；非本格式返回 null。
 *
 * **嵌套支持（2026-11）**：`label` 值里可能内嵌另一个完整 token 串（如「等待任务」
 * 胶囊的 label 嵌目标任务标题的 rawContent）。顶层字段切分（`||||`）为深度感知——
 * 只在嵌套深度 0 处切分，内层 token 里的 `||||`/`====` 属于内层值、不参与外层切分。
 */
export function parseOpaqueTokenText(value: string): {
  /** token 类型。 */
  kind: string
  /** 胶囊显示文字（顶层明文段）。 */
  label: string
  /** 胶囊补充信息（可选，顶层明文段）。 */
  summary?: string
  /** token payload（base64url 解码后的对象）。 */
  payload: Record<string, unknown>
} | null {
  if (!value.startsWith(OPAQUE_PREFIX) || !value.endsWith(OPAQUE_SUFFIX)) {
    return null
  }
  const body = value.slice(OPAQUE_PREFIX.length, value.length - OPAQUE_SUFFIX.length)
  if (!body.startsWith(KIND_MARKER)) {
    return null
  }
  const rest = body.slice(KIND_MARKER.length)
  // 深度感知切分：只在 [[[[/]]]] 嵌套深度 0 处按 `||||` 切分（首段即 kind）。
  const parts = splitTopLevelFields(rest)
  const kind = (parts[0] ?? '').trim()
  if (!kind) {
    return null
  }
  let label = ''
  let summary: string | undefined
  let payload: Record<string, unknown> = {}
  for (let i = 1; i < parts.length; i++) {
    const eq = parts[i].indexOf(KEY_SEP)
    if (eq <= 0) continue
    const key = parts[i].slice(0, eq)
    const val = parts[i].slice(eq + KEY_SEP.length)
    if (key === 'label') {
      label = val
    } else if (key === 'summary') {
      summary = val
    } else if (key === 'payload') {
      try {
        const decoded = JSON.parse(fromBase64Url(val)) as unknown
        if (decoded && typeof decoded === 'object' && !Array.isArray(decoded)) {
          payload = decoded as Record<string, unknown>
        }
      } catch {
        payload = {}
      }
    }
  }
  if (!label) {
    return null
  }
  return { kind, label, summary, payload }
}

/** 深度感知的顶层字段切分：只在嵌套深度 0 处按 `||||` 切分（与 Java 侧 splitTopLevelFields 一致）。 */
function splitTopLevelFields(rest: string): string[] {
  const parts: string[] = []
  const n = rest.length
  let depth = 0
  let segStart = 0
  let i = 0
  while (i < n) {
    if (rest.startsWith(OPAQUE_PREFIX, i)) {
      depth++
      i += OPAQUE_PREFIX.length
    } else if (rest.startsWith(OPAQUE_SUFFIX, i)) {
      depth = Math.max(0, depth - 1)
      i += OPAQUE_SUFFIX.length
    } else if (depth === 0 && rest.startsWith(FIELD_SEP, i)) {
      parts.push(rest.slice(segStart, i))
      i += FIELD_SEP.length
      segStart = i
    } else {
      i++
    }
  }
  parts.push(rest.slice(segStart))
  return parts
}

/**
 * 从 prefixStart（须指向一个 `[[[[`）起按深度配对找本段 token 的结束下标（排他，含收尾 `]]]]`）。
 * `[[[[` 深度+1、`]]]]` 深度-1，深度归零处即本段（可能嵌套）边界；到串尾不归零返回 -1。
 */
function balancedTokenEnd(input: string, prefixStart: number): number {
  const n = input.length
  let depth = 0
  let i = prefixStart
  while (i + 4 <= n) {
    if (input.startsWith(OPAQUE_PREFIX, i)) {
      depth++
      i += 4
    } else if (input.startsWith(OPAQUE_SUFFIX, i)) {
      depth--
      i += 4
      if (depth === 0) {
        return i
      }
    } else {
      i++
    }
  }
  return -1
}

/**
 * 扫描输入中全部**平衡**的 opaque token 段（按出现顺序返回原串，含边界）。
 *
 * 嵌套 token 整体作为一段返回（内层是外层 label 值的一部分，不单独成段）；
 * 残缺 `[[[[`（无配对收尾）按普通文本跳过，不阻塞其后合法段的识别。
 * 与 Java 侧 `SlashTokenEncoder.scanTokens` 逐字一致。
 */
export function scanOpaqueTokens(input: string): string[] {
  const tokens: string[] = []
  if (!input) {
    return tokens
  }
  let cursor = 0
  for (;;) {
    const start = input.indexOf(OPAQUE_PREFIX, cursor)
    if (start < 0) {
      return tokens
    }
    const end = balancedTokenEnd(input, start)
    if (end < 0) {
      // 本 [[[[(无配对收尾：作为普通文本跳过这 4 个字符，继续找后续合法段
      cursor = start + OPAQUE_PREFIX.length
      continue
    }
    tokens.push(input.slice(start, end))
    cursor = end
  }
}

/**
 * 判断某段文本是否是 opaque token。
 */
export function isOpaqueTokenText(value: string): boolean {
  return parseOpaqueTokenText(value) !== null
}

/** 从 opaque 串确定性派生 DOM id（串本身不含 id 段，保持干净）。 */
function deriveTokenId(opaqueText: string): string {
  let hash = 0
  for (let i = 0; i < opaqueText.length; i++) {
    hash = (hash * 31 + opaqueText.charCodeAt(i)) >>> 0
  }
  return `opq-${hash.toString(36)}`
}

/**
 * 从原始输入中切分出文本段与 token 段。
 * 直接按 `[[[[…]]]]` 边界**深度平衡配对**识别（嵌套 token 整体成段，不被内层
 * `]]]]` 截断——2026-11 前用非贪婪正则，嵌套场景外层被截成非法串），对每个命中
 * 现解析出 { kind, label, summary, payload }，当场构造胶囊视图（不依赖外部
 * tokens 数组做 Map 反查）。tokens 数组可选：提供时优先用其真实 id（保证点击
 * 定位/去重），未提供时由 opaque 串派生 id。
 */
export function splitComposerRawContent(
  rawContent: string,
  tokens: ChatComposerToken[] = [],
): Array<{
  /** 当前分段类型。 */
  type: 'text' | 'token'
  /** 当前分段原文。 */
  value: string
  /** 命中的 token（仅 type==='token'）。 */
  token?: ChatComposerToken
}> {
  if (!rawContent) {
    return []
  }
  const tokenByOpaqueText = new Map(tokens.map((token) => [token.opaqueText, token]))
  const segments: Array<{ type: 'text' | 'token'; value: string; token?: ChatComposerToken }> = []
  let cursor = 0
  for (const match of scanOpaqueTokens(rawContent)) {
    const start = rawContent.indexOf(match, cursor)
    if (start > cursor) {
      segments.push({ type: 'text', value: rawContent.slice(cursor, start) })
    }
    const parsed = parseOpaqueTokenText(match)
    if (parsed) {
      const known = tokenByOpaqueText.get(match)
      const token: ChatComposerToken = known ?? {
        id: deriveTokenId(match),
        kind: parsed.kind,
        label: parsed.label ?? '',
        summary: parsed.summary,
        opaqueText: match,
      }
      segments.push({ type: 'token', value: match, token })
    } else {
      // 形如边界但并非合法 token：当普通文本处理。
      segments.push({ type: 'text', value: match })
    }
    cursor = start + match.length
  }
  if (cursor < rawContent.length) {
    segments.push({ type: 'text', value: rawContent.slice(cursor) })
  }
  return segments.filter((segment) => segment.type === 'token' || segment.value.length > 0)
}

/**
 * 从原始输入中剥离 opaque token 外壳,替换为各自的 label 明文。
 * 用于任务标题派生等场景:避免 `[[[[agent-token::::...]]]]` 原串出现在标题中。
 *
 * 遍历输入中所有**平衡**的 `[[[[...]]]]` 段(嵌套整体成段,见 scanOpaqueTokens),
 * 逐段尝试解析;解析成功则用 label 替换(label 里若再嵌 token,递归拍平),
 * 解析失败则丢弃该 token 段。最终 trim 返回。
 */
export function stripTokensToLabels(rawContent: string): string {
  if (!rawContent) return rawContent
  let result = ''
  let cursor = 0
  for (const match of scanOpaqueTokens(rawContent)) {
    const start = rawContent.indexOf(match, cursor)
    result += rawContent.slice(cursor, start)
    const parsed = parseOpaqueTokenText(match)
    if (parsed) {
      result += parsed.label.includes(OPAQUE_PREFIX) ? stripTokensToLabels(parsed.label) : parsed.label
    }
    // 解析失败时不追加原串,直接跳过 token 段
    cursor = start + match.length
  }
  result += rawContent.slice(cursor)
  return result.trim()
}

/**
 * 提交给 AI 前，把正文里的命令/技能/文件占位（opaque token）替换成可读文本。
 *
 * 规则：
 * - 解析 opaque → 按 kind 调 `composerTokenRegistry.resolve(kind, payload)` 现算替换文本；
 *   核心层对 kind 与 payload 内容零感知，新增来源只需在注册中心加一行 resolver。
 * - 解析失败或未知 kind → 兜底保留原 opaque 串。
 *
 * 注意：AI 可见文本与展示用 rawContent 分离——展示侧仍保留内联胶囊（见 `splitComposerRawContent`）。
 */
export function replaceComposerTokensForSubmission(
  rawContent: string,
  tokens: ChatComposerToken[],
): string {
  if (!rawContent || tokens.length === 0) {
    return rawContent
  }
  const byOpaque = new Map<string, ChatComposerToken>()
  for (const token of tokens) {
    if (token.opaqueText) {
      byOpaque.set(token.opaqueText, token)
    }
  }
  if (byOpaque.size === 0) {
    return rawContent
  }
  const sorted = [...byOpaque.keys()].sort((left, right) => right.length - left.length)
  let result = rawContent
  for (const opaque of sorted) {
    const token = byOpaque.get(opaque)!
    result = result.split(opaque).join(resolveTokenReplacement(token.opaqueText))
  }
  return result
}

/**
 * 单个 token 的提交替换文本（解析 opaque → 交注册中心按 kind 解析）。
 * - 未知 kind（注册中心未登记）：保留原始 opaque 串作为兜底，避免泄漏半成品 token；
 * - 已知 kind：以 resolver 的返回为准——返回空串即「清空该 token」，不给 AI 任何文本
 *   。
 */
function resolveTokenReplacement(opaqueText: string): string {
  const parsed = parseOpaqueTokenText(opaqueText)
  if (!parsed) return opaqueText
  if (!composerTokenRegistry.has(parsed.kind)) return opaqueText
  return composerTokenRegistry.resolve(parsed.kind, parsed.payload)
}
