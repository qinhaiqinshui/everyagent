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
  // 按 `||||` 切分为字段段，首段即 kind（纯 kind，无 `====`）。
  const parts = rest.split(FIELD_SEP)
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
 * 直接按 `[[[[…]]]]` 边界识别，对每个命中现解析出 { kind, label, summary, payload }，
 * 当场构造胶囊视图（不依赖外部 tokens 数组做 Map 反查）。tokens 数组可选：
 * 提供时优先用其真实 id（保证点击定位/去重），未提供时由 opaque 串派生 id。
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
  const re = /\[\[\[\[[\s\S]*?\]\]\]\]/g
  let cursor = 0
  let m: RegExpExecArray | null
  while ((m = re.exec(rawContent))) {
    const start = m.index
    const match = m[0]
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
 *   （如 git.auto_sync 仅是节点侧触发标记，应当被清空而非注入提示文本）。
 */
function resolveTokenReplacement(opaqueText: string): string {
  const parsed = parseOpaqueTokenText(opaqueText)
  if (!parsed) return opaqueText
  if (!composerTokenRegistry.has(parsed.kind)) return opaqueText
  return composerTokenRegistry.resolve(parsed.kind, parsed.payload)
}
