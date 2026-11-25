/**
 * wf.node trace 卡片（工作流非 AI 节点过程呈现，§7.20.8）。
 *
 * 数据形状 = 后端 EmitEvent payload.data：
 * { instanceId, definitionName, nodeId, nodeName, nodeType, runNo,
 *   input, output, error, exitCode, activatedEdges, durationMs }——
 * input/output/error 为<b>全量</b>（后端不截断，截断留给本展示层）。
 *
 * 状态推导：宿主 TaskTraceShell 合成的 PluginTraceRecord 不透传 payload.status
 * （只合成 title/summary/content/data），故状态从 data 推导——interrupted 在场=cancelled
 * （实例终态收口中断）、error 非空=failed、exitCode 在场=done、否则 running（与后端
 * 四态发射一一对应）。
 *
 * 折叠行（TraceTypeDefinition 注册）：图标=按节点类型 glyph + 状态着色（宿主
 * CSS token），summary=`<类型> · <状态> · <耗时> · <输入/输出/错误首行摘要>`；
 * 展开态=本组件（徽标 + 元信息行 + 输入/输出/错误全量块）。
 */
import type { PluginTraceRecord } from '@everyagent/plugin-api'
import './wfNodeTrace.css'

/** trace kind（与引擎 WorkflowEngine.WF_NODE_TRACE_KIND 同值，wire 事件名）。 */
export const WF_NODE_TRACE_KIND = 'wf.node'

/** 四态（与后端 trace status 对应；由 data 推导，见文件头）。 */
export type WfNodeTraceStatus = 'running' | 'done' | 'failed' | 'cancelled'

export const WF_NODE_STATUS_LABELS: Record<WfNodeTraceStatus, string> = {
  running: '运行中',
  done: '完成',
  failed: '失败',
  cancelled: '已中断',
}

/** 节点类型 → 中文标签（仅命令节点发射，其余作未知类型兜底）。 */
export const WF_NODE_TYPE_LABELS: Record<string, string> = {
  command: '命令',
  ai: 'AI',
  start: '开始',
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

/** 读 metadata 数值（可解析数字的字符串同样转数值；其余 undefined）。 */
export function readMetaNumber(value: unknown): number | undefined {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value
  }
  if (typeof value === 'string' && value.trim() !== '') {
    const n = Number(value)
    if (Number.isFinite(n)) {
      return n
    }
  }
  return undefined
}

/** 由 data 推导节点运行状态（interrupted 在场=cancelled、error 非空=failed、exitCode 在场=done、否则 running）。 */
export function wfNodeStatus(meta: Record<string, unknown>): WfNodeTraceStatus {
  if (meta.interrupted === true) {
    return 'cancelled'
  }
  if (readMetaString(meta.error)) {
    return 'failed'
  }
  if (meta.exitCode != null) {
    return 'done'
  }
  return 'running'
}

/** 毫秒人性化（与后端引擎 formatDuration 同形：<1s 原样 ms；<60s 一位小数秒；更长 分秒）。 */
export function formatWfNodeDuration(ms: number): string {
  if (ms < 1000) {
    return `${ms}ms`
  }
  if (ms < 60_000) {
    return `${(ms / 1000).toFixed(1)}s`
  }
  return `${Math.floor(ms / 60_000)}分${Math.floor((ms % 60_000) / 1000)}秒`
}

/** 首行 + 长度截断（后端全量下发，展示层在此收敛；截断阈值只作用于折叠摘要）。 */
function firstLineClamped(text: string, max: number): string {
  const firstLine = text.split(/\r?\n/, 1)[0].trim()
  if (firstLine.length <= max) {
    return firstLine
  }
  return `${firstLine.slice(0, max)}…`
}

/** 折叠行摘要：`<类型> · <状态> · <耗时> · <摘要>`（failed/cancelled=错误 / done=输出 / running=输入）。 */
export function summarizeWfNode(trace: {
  summary?: string
  metadata?: Record<string, unknown>
}): string | undefined {
  const meta = trace.metadata ?? {}
  const nodeType = readMetaString(meta.nodeType)
  const status = wfNodeStatus(meta)
  const parts: string[] = [
    WF_NODE_TYPE_LABELS[nodeType] ?? nodeType ?? '节点',
    WF_NODE_STATUS_LABELS[status],
  ]
  const durationMs = readMetaNumber(meta.durationMs)
  if (durationMs != null && durationMs > 0) {
    parts.push(formatWfNodeDuration(durationMs))
  }
  const preview = readMetaString(
    status === 'failed' || status === 'cancelled'
      ? meta.error
      : status === 'done' ? meta.output : meta.input,
  )
  const clamped = firstLineClamped(preview, 160)
  if (clamped) {
    parts.push(clamped)
  }
  return parts.join(' · ') || trace.summary?.trim() || undefined
}

/** 全量文本块（输入/输出/错误；空值不渲染；超长块内部滚动）。 */
function Block({ label, value, tone }: { label: string; value: string; tone?: 'error' }) {
  if (!value) {
    return null
  }
  return (
    <div className={`wf-node-card__block${tone === 'error' ? ' wf-node-card__block--error' : ''}`}>
      <span className="wf-node-card__label">{label}</span>
      <pre className={`wf-node-card__pre${tone === 'error' ? ' wf-node-card__pre--error' : ''}`}>{value}</pre>
    </div>
  )
}

/** wf.node 展开态卡片。 */
export default function WfNodeTraceView({ trace }: { trace: PluginTraceRecord }) {
  const meta = trace.metadata ?? {}
  const nodeType = readMetaString(meta.nodeType)
  const status = wfNodeStatus(meta)
  const definitionName = readMetaString(meta.definitionName)
  const instanceId = readMetaString(meta.instanceId)
  const nodeId = readMetaString(meta.nodeId)
  const runNo = readMetaNumber(meta.runNo)
  const durationMs = readMetaNumber(meta.durationMs)
  const exitCode = readMetaNumber(meta.exitCode)
  const input = readMetaString(meta.input)
  const output = readMetaString(meta.output)
  const error = readMetaString(meta.error)
  const activatedEdges = Array.isArray(meta.activatedEdges)
    ? meta.activatedEdges.filter((e): e is string => typeof e === 'string')
    : []

  const metaParts = [
    definitionName ? `工作流 ${definitionName}` : '',
    instanceId ? `实例 ${instanceId}` : '',
    nodeId ? `节点 ${nodeId}` : '',
    runNo != null ? `第 ${runNo} 次运行` : '',
    activatedEdges.length > 0 ? `激活边 ${activatedEdges.length}` : '',
  ].filter(Boolean)

  return (
    <div className="wf-node-card">
      <div className="wf-node-card__head">
        <span className={`wf-node-card__badge wf-node-card__badge--${status}`}>
          {WF_NODE_STATUS_LABELS[status]}
        </span>
        <span className="wf-node-card__type">{WF_NODE_TYPE_LABELS[nodeType] ?? nodeType ?? '节点'}</span>
        {durationMs != null && durationMs > 0 ? (
          <span className="wf-node-card__duration">{formatWfNodeDuration(durationMs)}</span>
        ) : null}
        {exitCode != null ? <span className="wf-node-card__exit">退出码 {exitCode}</span> : null}
      </div>
      {metaParts.length > 0 ? <div className="wf-node-card__meta">{metaParts.join(' · ')}</div> : null}
      <div className="wf-node-card__body">
        <Block label="输入" value={input} />
        <Block label="输出" value={output} />
        <Block label="错误" value={error} tone="error" />
      </div>
    </div>
  )
}
