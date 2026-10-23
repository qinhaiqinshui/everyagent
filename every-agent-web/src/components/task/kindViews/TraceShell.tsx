/**
 * 单条 trace 族线程项的呈现壳(payload = trace 形状:title/summary/content/data)。
 *
 * 步骤 9(kind 渲染注册表基建)由 TaskThread.tsx **原位提取**为独立组件文件;
 * 渲染层两级分发统一化后 error/cancelled 等一切未注册第一层视图的 kind 均经
 * FallbackView 落本壳,壳内按传入 kind(= wire kind)直查第二级 traceTypeRegistry
 * 取定义,不做任何 kind 改写。
 *
 * 本组件只做「解析 + 填槽」:解析逻辑(getTraceType/PluginTraceRecord 合成/
 * getSummary/expandable/renderContent)保留,折叠行结构与展开容器由共享壳
 * EntryShell(槽位制)承担——**trace 行与工具行共用同一套列几何与展开几何**(图标列
 * 13px + gap 8px → 文字栏 21px;展开详情扁平、无外框),外层容器类 nagent-trace-shell
 * 只保留 trace 行族的布局必需项与「把缩进挂在行内」的图标挂出规则(§8.7);
 * nagent-msg 与容器 title 属性保持原样。
 */
import type { ThreadItem, TracePayload } from '@/task/threadItem'
import type { PluginTraceRecord } from '@everyagent/plugin-api'
import { getTraceType, resolveTraceIcon, stringifyTraceContent } from '@/plugin/traceTypeRegistry'
import { EntryShell } from '@/components/task/entryViews/EntryShell'

/** 单条 trace 族线程项(payload = trace 形状:title/summary/content/data)。 */
export function TaskTraceShell({
  item,
  taskId,
}: {
  item: ThreadItem
  taskId: string
}) {
  const payload: TracePayload = (item.payload as TracePayload | undefined) ?? { title: '' }
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

  return (
    <EntryShell
      as="section"
      className="nagent-trace-shell nagent-msg"
      title={titleText}
      expandable={expandable}
      icon={icon}
      name={showTitle ? title : undefined}
      summary={summary}
    >
      {content}
    </EntryShell>
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