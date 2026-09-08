import React from 'react'
import type { TaskTraceContent, TaskTraceRecord } from '@/types'
import { FileTextIcon, ShieldCheckIcon, type AppGlyphProps } from '@/components/shared/AppGlyphs'

/** trace 详情渲染上下文。 */
export interface TraceContentRenderContext {
  /** 当前任务 ID。 */
  taskId: string
}

/** trace 类型定义。 */
export interface TraceTypeDefinition {
  /** trace 类型 key。 */
  kind: string
  /** 渲染展开态内容。 */
  renderContent?: (trace: TaskTraceRecord, ctx: TraceContentRenderContext) => React.ReactNode
  /** 旧数据摘要适配器。 */
  getSummary?: (trace: TaskTraceRecord) => string | undefined
  /** 旧数据图标适配器。 */
  getIcon?: (trace: TaskTraceRecord) => string | undefined
  /** 收起态不渲染 title(如 request_retry:title 固定「请求重试」,与 summary 语义重复)。 */
  hideTitle?: boolean
  /**
   * 是否允许展开(缺省 = trace.content 非空)。
   * 数据承载在 metadata、content 为空但仍需展开卡片的 kind(如 auth.review)置 true。
   */
  canExpand?: (trace: TaskTraceRecord) => boolean
}

const traceTypes = new Map<string, TraceTypeDefinition>()

/** 判断值是否为可展示的结构化对象。 */
function isStructuredContent(
  content: TaskTraceContent | null | undefined,
): content is Exclude<TaskTraceContent, string> {
  return typeof content === 'object' && content !== null
}

/**
 * trace 图标注册表：把持久化的图标 key（字符串）映射到内置 SVG 组件。
 * 新增图标只需在此登记；未登记的 key 仍按原样渲染，保证旧数据向后兼容。
 */
const TRACE_ICON_COMPONENTS: Record<string, React.ComponentType<AppGlyphProps>> = {
  files: FileTextIcon,
  // AI 安全审议 trace 图标(盾牌校验)。
  shield: ShieldCheckIcon,
}

/** 把 trace 图标字段（字符串 key）解析为可渲染节点；未知 key 回退为原始文本。 */
export function resolveTraceIcon(icon?: string): React.ReactNode {
  if (!icon) {
    return null
  }
  const Glyph = TRACE_ICON_COMPONENTS[icon]
  if (Glyph) {
    return <Glyph size={14} />
  }
  return icon
}

/** 把 trace content 转成通用文本；content 缺省时返回空串。 */
export function stringifyTraceContent(content: TaskTraceContent | null | undefined): string {
  if (content == null) {
    return ''
  }
  if (typeof content === 'string') {
    return content
  }
  if (!isStructuredContent(content)) {
    return String(content)
  }
  try {
    return JSON.stringify(content, null, 2)
  } catch {
    return String(content)
  }
}

/** 通用展开态渲染器。 */
function renderFallbackContent(trace: TaskTraceRecord): React.ReactNode {
  const text = stringifyTraceContent(trace.content).trim()
  if (!text) {
    return <span className="nagent-trace-shell__empty">（空追踪）</span>
  }
  return (
    <pre className="nagent-trace-shell__pre">
      {text}
    </pre>
  )
}

/** 通用降级 trace 类型。 */
export function getFallbackTraceType(kind: string): TraceTypeDefinition {
  return {
    kind,
    renderContent: renderFallbackContent,
    getSummary: (trace) => trace.summary?.trim() || stringifyTraceContent(trace.content),
    getIcon: (trace) => trace.icon,
  }
}

/** 注册 trace 类型。重复注册同 kind 会覆盖。 */
export function registerTraceType(definition: TraceTypeDefinition): void {
  if (!definition.kind.trim()) {
    throw new Error('trace 类型 kind 不能为空')
  }
  traceTypes.set(definition.kind, definition)
}

/** 按 kind 获取 trace 类型定义。 */
export function getTraceType(kind: string): TraceTypeDefinition {
  const registered = traceTypes.get(kind)
  if (!registered) {
    return getFallbackTraceType(kind)
  }
  return {
    ...registered,
    renderContent: registered.renderContent ?? renderFallbackContent,
  }
}

/** 注册核心 trace 类型。 */
export function initializeTraceTypes(): void {
  // task_duration = 任务耗时(worker task.trace 事件);request_retry = 重试生命周期。
  const coreKinds = ['system_notice', 'run_error', 'model_switch', 'task_duration']
  for (const kind of coreKinds) {
    registerTraceType({
      kind,
      renderContent: renderFallbackContent,
      getSummary: (trace) => trace.summary?.trim() || stringifyTraceContent(trace.content),
      getIcon: (trace) => trace.icon,
    })
  }
  // request_retry:title 固定为「请求重试/已恢复/失败」,与 summary 语义重复,收起态只展示 summary。
  registerTraceType({
    kind: 'request_retry',
    renderContent: renderFallbackContent,
    getSummary: (trace) => trace.summary?.trim() || stringifyTraceContent(trace.content),
    getIcon: (trace) => trace.icon,
    hideTitle: true,
  })
  // model_failover:模型池自动切换;worker 填 summary(「容灾切换模型:」+ 最终成功配置名 + 模型)
  // 与 content(完整切换链「模型1 -> 模型2 -> …」),不填 title。
  // 收起态只展示 summary,展开态渲染 content(完整切换链)。
  registerTraceType({
    kind: 'model_failover',
    renderContent: (trace) => {
      const text = stringifyTraceContent(trace.content).trim() || trace.summary?.trim() || ''
      return <pre className="nagent-trace-shell__pre">{text}</pre>
    },
    getSummary: (trace) => trace.summary?.trim() || stringifyTraceContent(trace.content),
    getIcon: (trace) => trace.icon,
    hideTitle: true,
  })
}

/** 注册插件 trace 类型。 */
export function registerPluginTraceTypes(): void {
  // 插件在自身入口自注册；这里保留显式入口，方便初始化流程按需调用。
}
