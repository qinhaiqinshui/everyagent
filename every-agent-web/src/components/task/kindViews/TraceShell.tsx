/**
 * 单条 trace 族线程项的呈现壳(payload = trace 形状:title/summary/content/data)。
 *
 * 步骤 9(kind 渲染注册表基建)由 TaskThread.tsx **原位提取**为独立组件文件:
 * 逻辑逐字节保持(提取不改行为);kindViews 注册表侧的 FallbackView 复用本壳呈现
 * (TaskTraceShell 形状兜底,内容零丢失)。渲染层两级分发统一化后 error/cancelled
 * 等一切未注册第一层视图的 kind 均经 FallbackView 落本壳,壳内按传入 kind(= wire
 * kind)直查第二级 traceTypeRegistry 取定义,不做任何 kind 改写。
 */
import React from 'react'
import type { ThreadItem, TracePayload } from '@/task/threadItem'
import type { PluginTraceRecord } from '@everyagent/plugin-api'
import { getTraceType, resolveTraceIcon, stringifyTraceContent } from '@/plugin/traceTypeRegistry'
import { ChevronDownIcon, ChevronRightIcon } from '@/components/shared/AppGlyphs'

/** 单条 trace 族线程项(payload = trace 形状:title/summary/content/data)。 */
export function TaskTraceShell({
  item,
  taskId,
}: {
  item: ThreadItem
  taskId: string
}) {
  const payload: TracePayload = (item.payload as TracePayload | undefined) ?? { title: '' }
  const [open, setOpen] = React.useState(false)
  // trace 类型解析 key = 传入 kind(wire kind)直配,error/cancelled 等 status 族
  // 也由第二级表按真键给定义,本壳不改写 kind。
  const traceTypeKey = item.kind
  const traceType = getTraceType(traceTypeKey)
  // traceTypeRegistry(插件 trace 类型扩展点)消费 PluginTraceRecord 形状:
  // 由 item 五字段 + payload 合成兼容视图对象(getSummary/renderContent/canExpand/getIcon)。
  const trace: PluginTraceRecord = {
    traceId: item.seq,
    taskId,
    agentId: item.agentId || undefined,
    kind: traceTypeKey,
    title: payload.title,
    summary: payload.summary,
    content: payload.content,
    metadata: payload.data,
    createdAt: item.ts,
  }
  const icon = resolveTraceIcon(trace.icon ?? traceType.getIcon?.(trace))
  const title = payload.title?.trim() || ''
  const showTitle = !traceType.hideTitle && !!title
  const summary = buildTraceSummary(trace, traceType.getSummary)
  const titleText = [showTitle ? title : '', summary].filter(Boolean).join(' ')
  // 缺省仅当存在 content 时才可折叠/展开；kind 自定义 canExpand(如 auth.review 数据在 data)时以其结果为准。
  const expandable = payload.content != null || traceType.canExpand?.(trace) === true
  const content = expandable ? (traceType.renderContent?.(trace, { taskId }) ?? null) : null

  const summaryRow = expandable ? (
    <button
      type="button"
      className="nagent-trace-shell__summary"
      aria-expanded={open}
      onClick={() => setOpen((current) => !current)}
    >
      {icon ? <span className="nagent-trace-shell__icon">{icon}</span> : null}
      {showTitle ? <span className="nagent-trace-shell__title">{title}</span> : null}
      {summary ? <span className="nagent-trace-shell__text">{summary}</span> : null}
      <span className="nagent-trace-shell__chevron">
        {open ? <ChevronDownIcon size={13} /> : <ChevronRightIcon size={13} />}
      </span>
    </button>
  ) : (
    <div className="nagent-trace-shell__summary nagent-trace-shell__summary--static">
      {icon ? <span className="nagent-trace-shell__icon">{icon}</span> : null}
      {showTitle ? <span className="nagent-trace-shell__title">{title}</span> : null}
      {summary ? <span className="nagent-trace-shell__text">{summary}</span> : null}
    </div>
  )

  return (
    <section
      className={`nagent-trace-shell nagent-msg${open && expandable ? ' is-open' : ''}`}
      title={titleText}
    >
      {summaryRow}
      {open && expandable ? <div className="nagent-trace-shell__content">{content}</div> : null}
    </section>
  )
}

/** 生成收起态摘要。 */
function buildTraceSummary(
  trace: Pick<PluginTraceRecord, 'summary' | 'content'>,
  getSummary?: (trace: PluginTraceRecord) => string | undefined,
): string {
  // 已注册 getSummary 时以其结果为准（返回空即不展示摘要）；未注册的旧数据才回退派生。
  const raw = getSummary
    ? (getSummary(trace as PluginTraceRecord)?.trim() ?? '')
    : (trace.summary?.trim() || stringifyTraceContent(trace.content).trim())
  if (!raw) {
    return ''
  }
  // 不截断摘要：summary 需完整展示（如 model.failover 的最后切换模型），
  // 过长时由样式层换行/省略控制。
  return raw
}
