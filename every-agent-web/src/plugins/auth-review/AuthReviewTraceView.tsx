import type { TaskTraceRecord } from '@/types'
import './authReview.css'

/** AI 审议结论英文枚举 -> 中文标签。 */
export const AUTH_REVIEW_DECISION_LABELS: Record<string, string> = {
  ALLOW: '允许',
  DENY: '拒绝',
  ESCALATE: '升级',
}

/** 决议徽标色调：ALLOW 绿色 / DENY 红色 / ESCALATE 琥珀 / 其余中性蓝。 */
export type AuthReviewDecisionTone = 'allow' | 'deny' | 'escalate' | 'unknown'

function decisionTone(rawDecision: string): AuthReviewDecisionTone {
  switch (rawDecision.toUpperCase()) {
    case 'ALLOW':
      return 'allow'
    case 'DENY':
      return 'deny'
    case 'ESCALATE':
      return 'escalate'
    default:
      return 'unknown'
  }
}

/** 读 metadata 字符串值（数字/布尔也转字符串，空值返回空串）。 */
export function readMetaString(value: unknown): string {
  if (typeof value === 'string') {
    return value.trim()
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value)
  }
  return ''
}

/** 置信度展示：数值（0~1 或 1~100）转百分比；可解析数字的字符串同样转百分比；其余原样。 */
export function formatConfidence(value: unknown): string | undefined {
  if (value == null) {
    return undefined
  }
  const raw = String(value).trim()
  if (raw === '') {
    return undefined
  }
  const n = Number(raw)
  if (Number.isFinite(n) && raw !== '') {
    if (n >= 0 && n <= 1) {
      return `${Math.round(n * 100)}%`
    }
    if (n > 1 && n <= 100) {
      return `${Math.round(n)}%`
    }
    return String(n)
  }
  return raw
}

/** 单行字段行（等宽场景走 mono）。 */
function FieldRow({
  label,
  value,
  mono = false,
}: {
  label: string
  value: string | undefined
  mono?: boolean
}) {
  if (value == null || value === '') {
    return null
  }
  return (
    <div className="auth-review-card__row">
      <span className="auth-review-card__label">{label}</span>
      <span className={`auth-review-card__value${mono ? ' auth-review-card__value--mono' : ''}`}>{value}</span>
    </div>
  )
}

/** 块级字段（长文本：理由 / 授权请求原文），允许换行。 */
function BlockRow({ label, value, mono = false }: { label: string; value: string | undefined; mono?: boolean }) {
  if (value == null || value === '') {
    return null
  }
  return (
    <div className="auth-review-card__row auth-review-card__row--block">
      <span className="auth-review-card__label">{label}</span>
      <span className={`auth-review-card__value auth-review-card__value--block${mono ? ' auth-review-card__value--mono' : ''}`}>
        {value}
      </span>
    </div>
  )
}

/**
 * AI 安全审议 trace 展开态：只读判断卡。
 * 展示 decision（决议徽标）/ confidence（置信度）/ reason / scope / grantKey / prompt / taskId / agentId，
 * 数据来自 trace.metadata（taskId/agentId 缺省回退到 trace 顶层字段）。
 */
export default function AuthReviewTraceView({ trace }: { trace: TaskTraceRecord }) {
  const meta = trace.metadata ?? {}
  const rawDecision = readMetaString(meta.decision)
  const decisionLabel = (AUTH_REVIEW_DECISION_LABELS[rawDecision.toUpperCase()] ?? rawDecision) || '未知'
  const tone = decisionTone(rawDecision)
  const confidence = formatConfidence(meta.confidence)
  const reason = readMetaString(meta.reason)
  const scope = readMetaString(meta.scope)
  const grantKey = readMetaString(meta.grantKey)
  const prompt = readMetaString(meta.prompt)
  const taskId = readMetaString(meta.taskId) || trace.taskId
  const agentId = readMetaString(meta.agentId) || trace.agentId

  return (
    <div className="auth-review-card">
      <div className="auth-review-card__head">
        <span className={`auth-review-card__badge auth-review-card__badge--${tone}`}>{decisionLabel}</span>
        {confidence ? <span className="auth-review-card__confidence">置信度 {confidence}</span> : null}
      </div>
      <div className="auth-review-card__body">
        <BlockRow label="理由" value={reason} />
        <FieldRow label="范围" value={scope} />
        <FieldRow label="授权 Key" value={grantKey} mono />
        <BlockRow label="授权请求" value={prompt} mono />
        <FieldRow label="任务 ID" value={taskId} mono />
        <FieldRow label="审议 Agent" value={agentId} mono />
      </div>
    </div>
  )
}