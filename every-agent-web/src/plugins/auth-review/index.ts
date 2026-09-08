import React from 'react'
import { registerTraceType } from '@/plugin/traceTypeRegistry'
import AuthReviewTraceView, { AUTH_REVIEW_DECISION_LABELS, readMetaString } from './AuthReviewTraceView'

/** AI 安全审议 trace 类型。 */
export const AUTH_REVIEW_TRACE_KIND = 'auth.review'

/** AI 安全审议 trace 图标 key(traceTypeRegistry 的 TRACE_ICON_COMPONENTS 已登记 'shield')。 */
export const AUTH_REVIEW_TRACE_ICON = 'shield'

/**
 * 注册 AI 安全审议 trace 渲染类型(plan-unattended-ai-auth 步骤 7):
 * worker 每次授权审议结束时发 kind='auth.review' 的 task.trace(payload 的 metadata 承载
 * decision/confidence/reason/scope/grantKey/prompt/taskId/agentId,content 为空)。
 * 收起态只展示派生 summary(「AI 审议：允许 · reason」),展开态渲染只读判断卡。
 */
export function registerAuthReviewTraceType(): void {
  registerTraceType({
    kind: AUTH_REVIEW_TRACE_KIND,
    getIcon: () => AUTH_REVIEW_TRACE_ICON,
    // 标题「AI 安全审议」与 summary 的「AI 审议：…」语义重复,收起态只展示 summary。
    hideTitle: true,
    // 数据承载在 metadata、content 为空:显式声明可展开以显示判断卡。
    canExpand: (trace) => Boolean(trace.metadata && Object.keys(trace.metadata).length > 0),
    getSummary: (trace) => {
      const decision = readMetaString(trace.metadata?.decision)
      const reason = readMetaString(trace.metadata?.reason)
      if (!decision && !reason) {
        // 异常兜底:没有任何审议信息时退回 worker 下发的 summary。
        return trace.summary?.trim() || undefined
      }
      const label = decision ? (AUTH_REVIEW_DECISION_LABELS[decision.toUpperCase()] ?? decision) : ''
      const prefix = label ? `AI 审议：${label}` : 'AI 审议'
      return reason ? `${prefix} · ${reason}` : prefix
    },
    renderContent: (trace) => React.createElement(AuthReviewTraceView, { trace }),
  })
}