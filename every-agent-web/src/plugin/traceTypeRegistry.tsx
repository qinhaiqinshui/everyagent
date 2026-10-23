import React from 'react'
import type { TraceContentRenderContext, TraceTypeDefinition } from '@everyagent/plugin-api'
import { AlertTriangleIcon, FileTextIcon, ShieldCheckIcon, type AppGlyphProps } from '@/components/shared/AppGlyphs'

export type { TraceContentRenderContext, TraceTypeDefinition }

const traceTypes = new Map<string, TraceTypeDefinition>()

/** 判断值是否为可展示的结构化对象。 */
function isStructuredContent(
  content: unknown,
): content is Record<string, unknown> {
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
  // 错误 trace 缺省图标(三角警示)。
  alert: AlertTriangleIcon,
}

/**
 * 把 trace 图标字段解析为可渲染节点(双通道):
 * - string:内置图标 key——命中渲染对应 SVG 组件,未命中的 key 原样渲染为文本
 *   (旧数据向后兼容);
 * - ReactNode:插件自带图标节点,直接透传渲染。
 * 空值返回 null(不渲染图标)。
 */
export function resolveTraceIcon(icon?: string | React.ReactNode): React.ReactNode {
  if (icon == null || icon === '') {
    return null
  }
  if (typeof icon !== 'string') {
    return icon
  }
  const Glyph = TRACE_ICON_COMPONENTS[icon]
  if (Glyph) {
    // 13px = 工具行折叠行图标尺寸(WrenchIcon/TerminalIcon size=13):trace 图标与工具图标
    // 同列同尺寸(样式侧图标槽宽亦按 13px 算,见 chatPanel.css
    // `.nagent-trace-shell .nagent-entry__icon`)。
    return <Glyph size={13} />
  }
  return icon
}

/** 把 trace content 转成通用文本；content 缺省时返回空串。 */
export function stringifyTraceContent(content: unknown): string {
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
function renderFallbackContent(trace: {
  content?: unknown
  summary?: string
}): React.ReactNode {
  const text = stringifyTraceContent(trace.content).trim()
  if (!text) {
    return <span className="nagent-entry__empty">（空追踪）</span>
  }
  return (
    <pre className="nagent-entry__pre">
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

/** 按 kind 注销 trace 类型（插件 dispose 侧路清理用）；返回是否确实移除。 */
export function unregisterTraceType(kind: string): boolean {
  if (!kind.trim()) {
    return false
  }
  return traceTypes.delete(kind)
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

/**
 * 注册核心 trace 类型(注册键 = wire kind,与落盘/线程 kind 一致——点号形态)。
 *
 * 历史死键 system_notice/model_switch/task_duration/request_retry 已删:下划线
 * 键从未命中(真键是点号);任务耗时已不再发 trace(worker 改为回填 rounds.jsonl,
 * 见 WorkerIntegrationTest「耗时不再发 task_duration trace」)。历史数据里若仍有
 * 旧 kind 行,FallbackView 兜底渲染,内容零丢失。
 */
export function initializeTraceTypes(): void {
  // 默认定义(getSummary 派生 + getIcon trace.icon 透传)的注册集;error/retry 另有
  // 差异项(error 缺省 alert 图标;retry 隐藏与 summary 语义重复的固定 title)。
  const defaultKinds = ['cancelled', 'model.switch', 'context.compression', 'system.notice']
  for (const kind of defaultKinds) {
    registerTraceType({
      kind,
      renderContent: renderFallbackContent,
      getSummary: (trace) => trace.summary?.trim() || stringifyTraceContent(trace.content),
      getIcon: (trace) => trace.icon,
    })
  }
  // error = 任务/子任务运行错误(worker error 事件;ErrorPayload {title,summary?,content?}
  // 形状兼容):缺省三角警示图标(alert 内置 key);无 tone——视觉仅保留图标标识。
  registerTraceType({
    kind: 'error',
    renderContent: renderFallbackContent,
    getSummary: (trace) => trace.summary?.trim() || stringifyTraceContent(trace.content),
    getIcon: (trace) => trace.icon ?? 'alert',
  })
  // retry(点号,wire 键) = 重试生命周期(empty-response-retry/transient-error-retry
  // 插件发射):title 固定为「请求重试/已恢复/失败」类短语,与 summary 语义重复,
  // 收起态只展示 summary。
  registerTraceType({
    kind: 'retry',
    renderContent: renderFallbackContent,
    getSummary: (trace) => trace.summary?.trim() || stringifyTraceContent(trace.content),
    getIcon: (trace) => trace.icon,
    hideTitle: true,
  })
  // model.failover:模型池自动切换;worker 填 summary(「容灾切换模型:」+ 最终成功配置名 + 模型)
  // 与 content(完整切换链「模型1 -> 模型2 -> …」),不填 title。kind = 线程项 kind(事件名,
  // 点号)——FallbackView 会把空 title 回退成 item.kind,注册命中后 hideTitle 把它隐藏,
  // 收起态只展示 summary,展开态渲染 content(完整切换链)。
  registerTraceType({
    kind: 'model.failover',
    renderContent: (trace) => {
      const text = stringifyTraceContent(trace.content).trim() || trace.summary?.trim() || ''
      return <pre className="nagent-entry__pre">{text}</pre>
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
