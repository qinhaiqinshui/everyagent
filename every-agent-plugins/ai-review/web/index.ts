/**
 * AI 安全审议 trace 渲染插件——PluginModule 入口。
 *
 * 经 worker plugin.list 发现、plugin.webSource RPC 拉取 esbuild 预编译产物动态加载，通过 ctx.ui.registerTraceType 注册 kind='auth.review' 的 trace 渲染类型。
 * worker 每次审议结束时发 kind='auth.review' 的 task.trace。
 * 折叠行图标按审议结论三态分发（ALLOW 盾勾 / DENY 盾叉 / ESCALATE 问号，结论缺省按 ALLOW），
 * 图标组件插件自带（AuthReviewGlyphs），经 getIcon 的 ReactNode 通道直渲染。
 */

import React from 'react'
import AuthReviewTraceView, { AUTH_REVIEW_DECISION_LABELS, readMetaString } from './AuthReviewTraceView'
import { QuestionMarkIcon, ShieldCheckIcon, ShieldXIcon } from './AuthReviewGlyphs'
import type { PluginContext, PluginModule, PluginTraceRecord, TraceTypeDefinition } from '@everyagent/plugin-api'

export const AUTH_REVIEW_TRACE_KIND = 'auth.review'

/** trace 类型定义。 */
const authReviewTraceDef: TraceTypeDefinition = {
  kind: AUTH_REVIEW_TRACE_KIND,
  // 图标按审议结论三态分发：DENY 盾叉 / ESCALATE 问号 / 其余（含 ALLOW、结论缺省）盾勾。
  // 注：本入口按 .ts 走 ts loader（esbuild 默认，不支持 JSX），节点用 React.createElement 构造。
  getIcon: (trace) => {
    const decision = readMetaString(trace.metadata?.decision).toUpperCase()
    if (decision === 'DENY') {
      return React.createElement(ShieldXIcon)
    }
    if (decision === 'ESCALATE') {
      return React.createElement(QuestionMarkIcon)
    }
    return React.createElement(ShieldCheckIcon)
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
