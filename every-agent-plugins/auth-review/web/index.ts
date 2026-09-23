/**
 * AI 安全审议 trace 渲染插件——PluginModule 入口。
 *
 * 经 builtInPlugins.ts 自动发现加载，通过 ctx.ui.registerTraceType 注册 kind='auth.review' 的 trace 渲染类型。
 * worker 每次审议结束时发 kind='auth.review' 的 task.trace。
 */

import React from 'react'
import AuthReviewTraceView, { AUTH_REVIEW_DECISION_LABELS, readMetaString } from './AuthReviewTraceView'
import type { PluginContext, PluginModule } from '@/plugin/api'
import type { TraceTypeDefinition } from '@/plugin/traceTypeRegistry'

export const AUTH_REVIEW_TRACE_KIND = 'auth.review'
export const AUTH_REVIEW_TRACE_ICON = 'shield'

/** trace 类型定义。 */
const authReviewTraceDef: TraceTypeDefinition = {
  kind: AUTH_REVIEW_TRACE_KIND,
  getIcon: () => AUTH_REVIEW_TRACE_ICON,
  hideTitle: true,
  canExpand: (trace) => Boolean(trace.metadata && Object.keys(trace.metadata).length > 0),
  getSummary: (trace) => {
    const decision = readMetaString(trace.metadata?.decision)
    const reason = readMetaString(trace.metadata?.reason)
    if (!decision && !reason) {
      return trace.summary?.trim() || undefined
    }
    const label = decision ? (AUTH_REVIEW_DECISION_LABELS[decision.toUpperCase()] ?? decision) : ''
    const prefix = label ? `AI 审议：${label}` : 'AI 审议'
    return reason ? `${prefix} · ${reason}` : prefix
  },
  renderContent: (trace) => React.createElement(AuthReviewTraceView, { trace }),
}

const authReviewPlugin: PluginModule = {
  activate(ctx: PluginContext) {
    ctx.ui.registerTraceType(authReviewTraceDef)
  },
}

export default authReviewPlugin
