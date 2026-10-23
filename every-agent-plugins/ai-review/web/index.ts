/**
 * AI 安全审议 trace 渲染插件——PluginModule 入口。
 *
 * 经 worker plugin.list 发现、plugin.webSource RPC 拉取 esbuild 预编译产物动态加载，通过 ctx.ui.registerTraceType 注册 kind='auth.review' 的 trace 渲染类型。
 * worker 每次审议结束时发 kind='auth.review' 的 task.trace。
 * 折叠行图标按审议结论三态分发并着色（ALLOW 盾勾绿 / DENY 盾叉红 / ESCALATE 问号琥珀，
 * 结论缺省按 ALLOW），图标组件插件自带（AuthReviewGlyphs，13px 与工具行图标同尺寸），
 * 经 getIcon 的 ReactNode 通道直渲染；三态色由 className 通道落在插件自带 CSS 上。
 */

import React from 'react'
import AuthReviewTraceView, { AUTH_REVIEW_DECISION_LABELS, readMetaString } from './AuthReviewTraceView'
import { QuestionMarkIcon, ShieldCheckIcon, ShieldXIcon } from './AuthReviewGlyphs'
import type { PluginContext, PluginModule, PluginTraceRecord, TraceTypeDefinition } from '@everyagent/plugin-api'

export const AUTH_REVIEW_TRACE_KIND = 'auth.review'

/** trace 类型定义。 */
const authReviewTraceDef: TraceTypeDefinition = {
  kind: AUTH_REVIEW_TRACE_KIND,
  // 图标按审议结论三态分发并着色：DENY 盾叉红 / ESCALATE 问号琥珀 / 其余（含 ALLOW、
  // 结论缺省）盾勾绿。色值走插件自带 CSS（authReview.css 的 auth-review-glyph--*）——
  // 「审议结论 → 颜色」属本插件领域语义，不进宿主（宿主 trace 扩展点无 tone 字段，§8.7）。
  // 注：本入口按 .ts 走 ts loader（esbuild 默认，不支持 JSX），节点用 React.createElement 构造。
  getIcon: (trace) => {
    const decision = readMetaString(trace.metadata?.decision).toUpperCase()
    const tone = decision === 'DENY' ? 'deny' : decision === 'ESCALATE' ? 'escalate' : 'allow'
    const className = `auth-review-glyph auth-review-glyph--${tone}`
    if (decision === 'DENY') {
      return React.createElement(ShieldXIcon, { className })
    }
    if (decision === 'ESCALATE') {
      return React.createElement(QuestionMarkIcon, { className })
    }
    return React.createElement(ShieldCheckIcon, { className })
  },
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
  renderContent: (trace) => React.createElement(AuthReviewTraceView, { trace: trace as PluginTraceRecord }),
}

const authReviewPlugin: PluginModule = {
  activate(ctx: PluginContext) {
    ctx.ui.registerTraceType(authReviewTraceDef)
  },
}

export default authReviewPlugin
