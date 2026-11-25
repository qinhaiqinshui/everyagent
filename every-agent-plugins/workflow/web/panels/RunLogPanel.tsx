/**
 * 工作流运行日志页（步骤 10）：物流式从上到下时间线。
 *
 * 承载方式（设计说明）：以实例页顶栏「运行图 / 运行日志」**视图切换**承载，而非独立
 * 子 tab 或折叠区——运行图与日志共享同一份 events/runs 状态（父组件 InstancePanel
 * 单点订阅 instance.events 长轮询），切换零重拉、状态永不分叉。本组件为纯展示组件：
 * 数据全由 props 注入，重载经 onReload 回调（重拉 meta+runs；事件流由父级订阅维护）。
 *
 * 时间线装配（纯逻辑可测核 buildTimelineEntries）：
 * - run 记录条目 = instance.runLog（runs/*.json，按 runNo 升序）——节点名/nodeId/runNo/
 *   起止时间/durationMs/输入（rendered 经 ctx.ui.TokenRichText 渲染胶囊，缺席降级
 *   纯文本）/输出（命令节点 stdout/stderr 分块+exitCode；AI 节点 text 全文）/错误（红）/
 *   agent+usage/supervisor 介入链（attempt/decision/reason）；
 * - 状态行条目 = events 补充（wf.created/wf.status/wf.askUser/wf.cancelled/wf.done/
 *   node.skipped/supervisor.decision）；
 * - 「live run」条目 = 事件里有 node.started 但 runs 记录尚未落盘的进行中/未收口运行
 *   （按 runNo 去重后从事件合成伪记录，复用同一渲染器；收口落盘后重载即被真记录取代）。
 * 归并排序：runs 按 startedAt 与事件条目按 ts 双指针稳定归并（同刻 run 在前）。
 *
 * 本文件自持基础读取助手（asStr/formatDuration/TokenTextView 等）——刻意不反向依赖
 * InstancePanel（父级已 import 本模块，反向会成环；panel→panel 仅单向 SidebarPanel）。
 *
 * 人工验证清单见文件尾注释。
 */
import React from 'react'
import { getPluginContext } from '../pluginRuntime'
import {
  rpcErrorMessage,
  type WorkflowInstanceEvent,
  type WorkflowInstanceMeta,
  type WorkflowRunRecord,
} from '../workflowGateway'
import { instanceStatusLabel } from './SidebarPanel'
import { AiNodeIcon, CommandNodeIcon, RefreshIcon, StartNodeIcon } from '../icons'

// ─── 基础读取助手（防御性 wire/record 读取；自持防面板成环） ─────────────

/** unknown → string（非字符串返回空串）。 */
export function asStr(v: unknown): string {
  return typeof v === 'string' ? v : ''
}

/** unknown → number | null（非有限数值返回 null）。 */
export function asNum(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null
}

/** unknown → string[]（过滤非字符串项）。 */
export function asStrArray(v: unknown): string[] {
  return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []
}

/** 取文本首个非空行并压平空白（画布/摘要预览用；超长截断）。 */
export function firstLineText(s: unknown, max = 80): string {
  const t = typeof s === 'string' ? s : ''
  if (t === '') return ''
  const line = t.split(/\r?\n/).map(x => x.trim()).find(x => x !== '') ?? ''
  const collapsed = line.replace(/\s+/g, ' ')
  return collapsed.length > max ? `${collapsed.slice(0, max - 1)}…` : collapsed
}

/** 毫秒时长 → 短文案（12.3s / 45s / 3m07s / 2h05m）。 */
export function formatDuration(ms?: number | null): string {
  if (typeof ms !== 'number' || !Number.isFinite(ms) || ms < 0) return ''
  const s = ms / 1000
  if (s < 10) return `${s.toFixed(1)}s`
  if (s < 60) return `${Math.round(s)}s`
  const m = Math.floor(s / 60)
  const rs = Math.round(s % 60)
  if (m < 60) return `${m}m${String(rs).padStart(2, '0')}s`
  return `${Math.floor(m / 60)}h${String(m % 60).padStart(2, '0')}m`
}

/** 毫秒时间戳 → HH:mm:ss（时间线行时刻；非法返回 —）。 */
export function formatClock(ms?: number | null): string {
  if (typeof ms !== 'number' || !Number.isFinite(ms) || ms <= 0) return '—'
  const d = new Date(ms)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/**
 * token 富文本只读渲染：rawContent（可含 opaque token 串）→ 胶囊展示。
 * 宿主注入 ctx.ui.TokenRichText 时走胶囊渲染，缺席降级 toPlainText/纯文本。
 */
export function TokenTextView({
  text,
  className,
  singleLine,
}: {
  text: string
  className?: string
  singleLine?: boolean
}): React.ReactElement | null {
  const ui = getPluginContext().ui
  const value = typeof text === 'string' ? text : ''
  if (value === '') return null
  if (ui.TokenRichText) {
    const Rich = ui.TokenRichText
    return <Rich text={value} className={className} interactive={false} singleLine={singleLine === true} />
  }
  const plain = typeof ui.toPlainText === 'function' ? ui.toPlainText(value) : value
  return (
    <span
      className={className}
      style={singleLine === true
        ? { display: 'block', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }
        : undefined}
    >
      {plain}
    </span>
  )
}

// ─── runs 记录字段读取（wire → 视图模型） ────────────────────────────────

/** 节点输入三视图（raw=模板原文 / rendered=变量渲染 / visibleToAi=token 拍平）。 */
export function runInputOf(rec: WorkflowRunRecord): { raw: string; rendered: string; visibleToAi: string } {
  const o = (rec.input ?? {}) as Record<string, unknown>
  return { raw: asStr(o.raw), rendered: asStr(o.rendered), visibleToAi: asStr(o.visibleToAi) }
}

/** 节点输出二形态合一读取（命令 exitCode/stdout/stderr；AI text）。 */
export function runOutputOf(rec: WorkflowRunRecord): {
  exitCode: number | null
  stdout: string
  stderr: string
  text: string
} {
  const o = (rec.output ?? {}) as Record<string, unknown>
  return { exitCode: asNum(o.exitCode), stdout: asStr(o.stdout), stderr: asStr(o.stderr), text: asStr(o.text) }
}

/** 命名 agent 会话线观测（匿名 AI 节点返回 null）。 */
export interface RunAgentInfo {
  name: string
  modelConfigId: string
  seedTurns: number | null
  resultTurns: number | null
  inputTokens: number | null
  outputTokens: number | null
}

export function runAgentOf(rec: WorkflowRunRecord): RunAgentInfo | null {
  const o = rec.agent as Record<string, unknown> | null | undefined
  if (!o || typeof o !== 'object') return null
  const usage = (o.usage ?? null) as Record<string, unknown> | null
  return {
    name: asStr(o.name),
    modelConfigId: asStr(o.modelConfigId),
    seedTurns: asNum(o.seedTurns),
    resultTurns: asNum(o.resultTurns),
    inputTokens: usage ? asNum(usage.inputTokens) : null,
    outputTokens: usage ? asNum(usage.outputTokens) : null,
  }
}

/** 督导介入链条目（runs 记录侧，含 attempt；事件侧无 attempt 时为 null）。 */
export interface RunSupervisorItem {
  attempt: number | null
  action: string
  input: string
  reason: string
}

export function runSupervisorOf(rec: WorkflowRunRecord): RunSupervisorItem[] {
  const arr = rec.supervisor
  if (!Array.isArray(arr)) return []
  return arr
    .filter(e => e && typeof e === 'object')
    .map(e => {
      const o = e as Record<string, unknown>
      const d = (o.decision ?? null) as Record<string, unknown> | null
      return {
        attempt: asNum(o.attempt),
        action: asStr(d?.action),
        input: asStr(d?.input),
        reason: asStr(d?.reason) || asStr(o.reason),
      }
    })
}

/** 该次运行的错误消息（无错误返回空串）。 */
export function runErrorOf(rec: WorkflowRunRecord): string {
  const o = rec.error as Record<string, unknown> | null | undefined
  if (o && typeof o === 'object') return asStr(o.message)
  return typeof rec.error === 'string' ? rec.error : ''
}

/** 该次运行错误携带的 exitCode（命令节点失败可判）。 */
export function runErrorExitCodeOf(rec: WorkflowRunRecord): number | null {
  const o = rec.error as Record<string, unknown> | null | undefined
  return o && typeof o === 'object' ? asNum(o.exitCode) : null
}

/** 记录输出 → 单行预览（stdout/text 首行；画布副标题与列表摘要用）。 */
export function runOutputPreview(rec: WorkflowRunRecord): string {
  const out = runOutputOf(rec)
  if (out.text !== '') return firstLineText(out.text, 200)
  if (out.stdout !== '') return firstLineText(out.stdout, 200)
  if (out.stderr !== '') return firstLineText(out.stderr, 200)
  return ''
}

// ─── 时间线装配（纯逻辑，可测核） ────────────────────────────────────────

export type TimelineTone = 'info' | 'success' | 'warn' | 'error'

/** run 记录条目（真记录 + 事件合成的 live 伪记录共用渲染器）。 */
export interface TimelineRunEntry {
  kind: 'run'
  key: string
  ts: number
  run: WorkflowRunRecord
  /** live 伪记录的实时态（真记录=null，态由 error 字段自证）。 */
  liveStatus: 'running' | 'done' | 'failed' | 'cancelled' | null
}

/** 实例级状态行条目（events 补充）。 */
export interface TimelineLineEntry {
  kind: 'line'
  key: string
  ts: number
  tone: TimelineTone
  text: string
  detail?: string
}

export type TimelineEntry = TimelineRunEntry | TimelineLineEntry

/** live 合成的中间态（事件里该 run 的最新投影）。 */
interface LiveRunDraft {
  runNo: number
  nodeId: string
  nodeName: string
  startedAt: number | null
  endedAt: number | null
  inputRendered: string
  outputPreview: string
  exitCode: number | null
  error: string | null
  status: 'running' | 'done' | 'failed' | 'cancelled'
}

/** live 中间态 → 伪 WorkflowRunRecord（复用 run 渲染器；收口落盘后被真记录取代）。 */
function synthesizeLiveRun(l: LiveRunDraft): WorkflowRunRecord {
  return {
    runNo: l.runNo,
    nodeId: l.nodeId,
    nodeName: l.nodeName,
    nodeType: null,
    startedAt: l.startedAt,
    endedAt: l.endedAt,
    durationMs: l.startedAt != null && l.endedAt != null ? l.endedAt - l.startedAt : null,
    input: l.inputRendered !== '' ? { rendered: l.inputRendered } : null,
    output: l.outputPreview !== '' || l.exitCode != null
      ? { ...(l.outputPreview !== '' ? { text: l.outputPreview } : {}), ...(l.exitCode != null ? { exitCode: l.exitCode } : {}) }
      : null,
    activatedEdges: null,
    agent: null,
    error: l.error !== '' ? { message: l.error } : null,
    supervisor: null,
  }
}

/**
 * runs + events → 时间线条目（归并排序：事件条目按 ts 稳定排序后与 runs 按 startedAt
 * 双指针归并，同刻 run 在前）。nodeNameById 供事件侧（node.skipped 等无 nodeName 的
 * payload）补节点显示名。
 */
export function buildTimelineEntries(
  runs: WorkflowRunRecord[],
  events: WorkflowInstanceEvent[],
  nodeNameById?: Record<string, string>,
): TimelineEntry[] {
  const covered = new Set<number>()
  for (const r of runs) {
    const n = asNum(r.runNo)
    if (n != null) covered.add(n)
  }
  const nameOf = (nodeId: string, fallback?: string): string =>
    nodeNameById?.[nodeId] || fallback || nodeId

  const evEntries: TimelineEntry[] = []
  const live = new Map<number, LiveRunDraft>()
  const liveDraft = (runNo: number, nodeId: string, nodeName: string): LiveRunDraft => {
    const cur = live.get(runNo)
    if (cur) return cur
    const d: LiveRunDraft = {
      runNo, nodeId, nodeName, startedAt: null, endedAt: null,
      inputRendered: '', outputPreview: '', exitCode: null, error: null, status: 'running',
    }
    live.set(runNo, d)
    return d
  }

  for (const e of events) {
    const p = (e.payload ?? {}) as Record<string, unknown>
    const nodeId = asStr(p.nodeId)
    const runNo = asNum(p.runNo) ?? 0
    switch (e.event) {
      case 'wf.created': {
        const vars = p.variables && typeof p.variables === 'object'
          ? Object.entries(p.variables as Record<string, unknown>)
            .map(([k, v]) => `${k}=${asStr(v)}`)
            .join(' · ')
          : ''
        evEntries.push({
          kind: 'line', key: e.seq, ts: e.ts, tone: 'info',
          text: `实例创建${asStr(p.name) ? ` · ${asStr(p.name)}` : ''}`,
          detail: vars !== '' ? vars : undefined,
        })
        break
      }
      case 'wf.status': {
        const status = asStr(p.status)
        const tone: TimelineTone = status === 'done'
          ? 'success'
          : status === 'failed'
            ? 'error'
            : status === 'waiting-user' || status === 'cancelled'
              ? 'warn'
              : 'info'
        const errRaw = p.error && typeof p.error === 'object' ? (p.error as Record<string, unknown>) : null
        evEntries.push({
          kind: 'line', key: e.seq, ts: e.ts, tone,
          text: `实例状态 → ${status ? instanceStatusLabel(status) || status : '?'}`,
          detail: errRaw ? asStr(errRaw.message) || undefined : undefined,
        })
        break
      }
      case 'wf.askUser':
        evEntries.push({
          kind: 'line', key: e.seq, ts: e.ts, tone: 'warn',
          text: `等待用户决策（${nameOf(nodeId)} · run #${runNo}）`,
          detail: [asStr(p.question), asStr(p.context)].filter(s => s !== '').join('\n') || undefined,
        })
        break
      case 'wf.cancelled':
        evEntries.push({
          kind: 'line', key: e.seq, ts: e.ts, tone: 'warn',
          text: `实例已取消${asStr(p.by) ? `（操作者：${asStr(p.by)}）` : ''}`,
        })
        break
      case 'wf.done': {
        const dur = asNum(p.durationMs)
        evEntries.push({
          kind: 'line', key: e.seq, ts: e.ts, tone: 'success',
          text: `实例完成${dur != null ? ` · 耗时 ${formatDuration(dur)}` : ''}`,
        })
        break
      }
      case 'node.skipped':
        evEntries.push({
          kind: 'line', key: e.seq, ts: e.ts, tone: 'info',
          text: `节点 ${nameOf(nodeId)} 已跳过（run #${runNo}）`,
          detail: varsDetailOf(p),
        })
        break
      case 'node.retry':
        evEntries.push({
          kind: 'line', key: e.seq, ts: e.ts, tone: 'warn',
          text: `节点 ${nameOf(nodeId)} 失败自动重试（第 ${asNum(p.attempt) ?? '?'} / ${asNum(p.maxRuns) ?? '?'} 次 · 退出码 ${asNum(p.exitCode) ?? '?'}）`,
        })
        break
      case 'supervisor.decision': {
        const d = (p.decision ?? {}) as Record<string, unknown>
        evEntries.push({
          kind: 'line', key: e.seq, ts: e.ts, tone: 'info',
          text: `督导介入 → ${asStr(d.action) || '?'}（${nameOf(nodeId)} · run #${runNo}）`,
          detail: [
            asStr(p.reason) || asStr(d.reason),
            asStr(d.input) !== '' ? `替换输入：${asStr(d.input)}` : '',
          ].filter(s => s !== '').join('\n') || undefined,
        })
        break
      }
      // ── 节点运行三事件：runs 记录已覆盖的跳过（真记录更全）；未覆盖的合成 live ──
      case 'node.started': {
        if (covered.has(runNo)) break
        const d = liveDraft(runNo, nodeId, asStr(p.nodeName) || nameOf(nodeId))
        d.startedAt = e.ts || d.startedAt
        d.inputRendered = asStr(p.inputPreview) || d.inputRendered
        d.status = 'running'
        break
      }
      case 'node.output': {
        if (covered.has(runNo)) break
        const d = liveDraft(runNo, nodeId, nameOf(nodeId))
        d.endedAt = e.ts || d.endedAt
        d.outputPreview = asStr(p.outputPreview)
        d.exitCode = asNum(p.exitCode)
        d.status = 'done'
        break
      }
      case 'node.error': {
        if (covered.has(runNo)) break
        const d = liveDraft(runNo, nodeId, nameOf(nodeId))
        d.endedAt = e.ts || d.endedAt
        d.error = asStr(p.message)
        d.exitCode = asNum(p.exitCode) ?? d.exitCode
        d.status = 'failed'
        break
      }
      case 'node.interrupted': {
        // 实例终态收口中断的在飞运行（引擎 finish 收口扫描补发）——live 行关闭为已中断
        if (covered.has(runNo)) break
        const d = liveDraft(runNo, nodeId, nameOf(nodeId))
        d.endedAt = e.ts || d.endedAt
        d.error = asStr(p.message) || d.error
        d.status = 'cancelled'
        break
      }
      default:
        break
    }
  }

  // live 伪记录 → 条目（加入后统一按 ts 稳定排序，与状态行交织）。
  for (const l of live.values()) {
    evEntries.push({ kind: 'run', key: `live:${l.runNo}`, ts: l.startedAt ?? 0, run: synthesizeLiveRun(l), liveStatus: l.status })
  }
  evEntries.sort((a, b) => a.ts - b.ts)

  // 双指针归并：runs 按 startedAt（同 runNo 兜底）插入事件流。
  const result: TimelineEntry[] = []
  let i = 0
  const sortedRuns = [...runs].sort(
    (a, b) => (asNum(a.startedAt) ?? 0) - (asNum(b.startedAt) ?? 0) || (asNum(a.runNo) ?? 0) - (asNum(b.runNo) ?? 0),
  )
  for (const rec of sortedRuns) {
    const ts = asNum(rec.startedAt) ?? 0
    while (i < evEntries.length && evEntries[i].ts <= ts) {
      result.push(evEntries[i])
      i += 1
    }
    result.push({ kind: 'run', key: `run:${asNum(rec.runNo) ?? result.length}`, ts, run: rec, liveStatus: null })
  }
  while (i < evEntries.length) {
    result.push(evEntries[i])
    i += 1
  }
  return result
}

/**
 * 边数据传递的变量写入快照 → 展示明细（「k = v」逐行；node.output/node.skipped
 * 事件的 payload.vars；值已按引擎 8KB 截断口径留档）。
 */
function varsDetailOf(p: Record<string, unknown>): string | undefined {
  const vars = p.vars && typeof p.vars === 'object' ? (p.vars as Record<string, unknown>) : null
  if (!vars) return undefined
  const entries = Object.entries(vars)
    .map(([k, v]) => `${k} = ${asStr(v)}`)
    .filter(s => !s.endsWith('= '))
  return entries.length > 0 ? `写入变量：\n${entries.join('\n')}` : undefined
}

// ─── 样式 ────────────────────────────────────────────────────────────────

const LOG_STYLE_ID = 'wf-log-styles'

function ensureLogStyles(): void {
  if (typeof document === 'undefined' || document.getElementById(LOG_STYLE_ID)) return
  const style = document.createElement('style')
  style.id = LOG_STYLE_ID
  style.textContent = `
.wf-log-root { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; background: var(--bg-primary, #0b0d12); color: var(--text-primary, #eef1f5); font-size: 13px; }
.wf-log-head { display: flex; align-items: center; gap: 8px; padding: 8px 14px; border-bottom: 1px solid var(--border, #2a303b); background: var(--bg-secondary, #11141b); flex: none; }
.wf-log-title { font-weight: 600; }
.wf-log-count { font-size: 11.5px; color: var(--text-muted, #6f7a8b); }
.wf-log-hint { font-size: 11px; color: var(--text-faint, #525c6a); }
.wf-log-btn { display: inline-flex; align-items: center; gap: 4px; padding: 3px 9px; border: 1px solid var(--border-strong, #3b4658); border-radius: 6px; background: transparent; color: var(--text-secondary, #aab3c2); cursor: pointer; font-size: 11.5px; white-space: nowrap; }
.wf-log-btn:hover { color: var(--text-primary, #eef1f5); border-color: var(--accent-blue, #61a5ff); }
.wf-log-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.wf-log-list { flex: 1; min-height: 0; overflow-y: auto; padding: 14px 18px 28px 16px; }
.wf-log-empty { color: var(--text-faint, #525c6a); font-size: 12.5px; padding: 8px 2px; }
.wf-log-error { color: var(--accent-red, #ff6b6b); font-size: 12px; padding: 4px 2px; }
.wf-log-item { position: relative; padding: 0 0 14px 24px; }
.wf-log-item::before { content: ''; position: absolute; left: 5px; top: 6px; width: 8px; height: 8px; border-radius: 50%; background: var(--text-muted, #6f7a8b); }
.wf-log-item::after { content: ''; position: absolute; left: 8.5px; top: 17px; bottom: 0; width: 1px; background: var(--border-light, #1b202a); }
.wf-log-item:last-child::after { display: none; }
.wf-log-item--success::before { background: var(--accent-green, #41d19c); }
.wf-log-item--warn::before { background: var(--accent-amber, #f7bd54); }
.wf-log-item--error::before { background: var(--accent-red, #ff6b6b); }
.wf-log-line { display: flex; flex-direction: column; gap: 2px; padding: 2px 0; }
.wf-log-line-text { color: var(--text-secondary, #aab3c2); }
.wf-log-line-time { font-size: 11px; color: var(--text-faint, #525c6a); }
.wf-log-line-detail { font-size: 12px; color: var(--text-muted, #6f7a8b); white-space: pre-wrap; word-break: break-word; }
.wf-log-card { border: 1px solid var(--border-light, #1b202a); border-radius: 8px; background: var(--bg-secondary, #11141b); overflow: hidden; }
.wf-log-card-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 7px 10px; border-bottom: 1px solid var(--border-light, #1b202a); background: var(--bg-tertiary, #171b24); }
.wf-log-card-name { font-weight: 600; }
.wf-log-card-id { font-size: 11px; color: var(--text-faint, #525c6a); }
.wf-log-card-runno { font-size: 11.5px; color: var(--text-muted, #6f7a8b); }
.wf-log-card-time { font-size: 11.5px; color: var(--text-muted, #6f7a8b); margin-left: auto; }
.wf-log-card-dur { font-size: 11.5px; color: var(--text-secondary, #aab3c2); }
.wf-log-badge { font-size: 10.5px; border-radius: 999px; padding: 0 7px; border: 1px solid var(--text-muted, #6f7a8b); color: var(--text-muted, #6f7a8b); flex: none; }
.wf-log-badge--ok { color: var(--accent-green, #41d19c); border-color: var(--accent-green, #41d19c); }
.wf-log-badge--err { color: var(--accent-red, #ff6b6b); border-color: var(--accent-red, #ff6b6b); }
.wf-log-badge--warn { color: var(--accent-amber, #f7bd54); border-color: var(--accent-amber, #f7bd54); }
.wf-log-badge--run { color: var(--accent-blue, #61a5ff); border-color: var(--accent-blue, #61a5ff); animation: wf-log-blink 1.4s ease-in-out infinite; }
@keyframes wf-log-blink { 0%, 100% { opacity: 1; } 50% { opacity: 0.45; } }
.wf-log-card-body { padding: 8px 10px 10px; display: flex; flex-direction: column; gap: 8px; }
.wf-log-sec { display: flex; flex-direction: column; gap: 4px; }
.wf-log-sec-label { font-size: 11px; color: var(--text-faint, #525c6a); }
.wf-log-pre { margin: 0; white-space: pre-wrap; word-break: break-word; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; line-height: 1.55; background: var(--bg-primary, #0b0d12); border: 1px solid var(--border-light, #1b202a); border-radius: 6px; padding: 8px 10px; max-height: 300px; overflow: auto; color: var(--text-primary, #eef1f5); }
.wf-log-pre--err { color: var(--accent-red, #ff6b6b); }
.wf-log-raw { font-size: 11.5px; color: var(--text-faint, #525c6a); word-break: break-all; }
.wf-log-agent { font-size: 12px; color: var(--text-secondary, #aab3c2); line-height: 1.6; }
.wf-log-supchain { font-size: 12px; color: var(--accent-purple, #b59cff); line-height: 1.6; }
.wf-log-chips { display: flex; gap: 4px; flex-wrap: wrap; }
.wf-log-chip { display: inline-flex; align-items: center; border: 1px solid var(--border, #2a303b); border-radius: 999px; padding: 0 7px; font-size: 10.5px; color: var(--text-muted, #6f7a8b); max-width: 240px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wf-log-exit { font-size: 11px; border-radius: 4px; padding: 0 6px; border: 1px solid var(--text-muted, #6f7a8b); color: var(--text-muted, #6f7a8b); align-self: flex-start; }
.wf-log-exit--ok { color: var(--accent-green, #41d19c); border-color: var(--accent-green, #41d19c); }
.wf-log-exit--bad { color: var(--accent-red, #ff6b6b); border-color: var(--accent-red, #ff6b6b); }
/* ── 移动端（≤768px）：头部说明换行不挤占按钮；列表/卡片内边距收窄 ── */
@media (max-width: 768px) {
  .wf-log-head { flex-wrap: wrap; padding: 8px 10px; gap: 6px; }
  .wf-log-hint { flex-basis: 100%; }
  .wf-log-btn { padding: 6px 12px; }
  .wf-log-list { padding: 10px 10px 24px 8px; }
  .wf-log-card-head { padding: 6px 8px; gap: 6px; }
  .wf-log-card-body { padding: 6px 8px 8px; }
  .wf-log-pre { max-height: 220px; }
}
`
  document.head.appendChild(style)
}

// ─── 组件 ────────────────────────────────────────────────────────────────

export interface RunLogPanelProps {
  /** 实例 meta（definitionSnapshot 供节点/边显示名）。 */
  meta: WorkflowInstanceMeta | null
  /** instance.runLog（runNo 升序）。 */
  runs: WorkflowRunRecord[]
  /** 事件流（父级长轮询累积的全量回放）。 */
  events: WorkflowInstanceEvent[]
  /** runs/meta 拉取中。 */
  loading?: boolean
  /** runs/meta 拉取错误（重载入口）。 */
  loadError?: string | null
  /** 重载回调（重拉 meta + runLog；事件流由父级订阅维护）。 */
  onReload: () => void
}

function NodeTypeBadge({ type }: { type: string }): React.ReactElement {
  if (type === 'command') return <CommandNodeIcon size={13} color="var(--accent-cyan, #54d2e8)" />
  if (type === 'ai') return <AiNodeIcon size={13} color="var(--accent-purple, #b59cff)" />
  if (type === 'start') return <StartNodeIcon size={13} color="var(--accent-green, #41d19c)" />
  return <></>
}

function RunItem({
  entry,
  edgeLabelById,
  varsByRun,
}: {
  entry: TimelineRunEntry
  edgeLabelById: Map<string, string>
  /** runNo → 本次激活边写入的变量快照（事件侧聚合——runs 记录不含数据面）。 */
  varsByRun: Map<number, Array<[string, string]>>
}): React.ReactElement {
  const rec = entry.run
  const input = runInputOf(rec)
  const out = runOutputOf(rec)
  const agent = runAgentOf(rec)
  const chain = runSupervisorOf(rec)
  const errorMessage = runErrorOf(rec)
  const exit = out.exitCode ?? runErrorExitCodeOf(rec)
  const isAi = asStr(rec.nodeType) === 'ai'
  const failed = errorMessage !== '' || entry.liveStatus === 'failed'
  const running = entry.liveStatus === 'running'
  const cancelled = entry.liveStatus === 'cancelled'
  const startedAt = asNum(rec.startedAt)
  const endedAt = asNum(rec.endedAt)
  const dur = asNum(rec.durationMs) ?? (startedAt != null && endedAt != null ? endedAt - startedAt : null)
  const edges = asStrArray(rec.activatedEdges)
  const hasAnyOutput = out.stdout !== '' || out.stderr !== '' || out.text !== ''
  const writtenVars = varsByRun.get(asNum(rec.runNo) ?? -1) ?? []

  return (
    <div className="wf-log-card">
      <div className="wf-log-card-head">
        <NodeTypeBadge type={asStr(rec.nodeType)} />
        <span className="wf-log-card-name">{asStr(rec.nodeName) || asStr(rec.nodeId)}</span>
        <span className="wf-log-card-id">{asStr(rec.nodeId)}</span>
        <span className="wf-log-card-runno">#{asNum(rec.runNo) ?? '?'}</span>
        <span className="wf-log-card-time">
          {formatClock(startedAt)} → {formatClock(endedAt)}
        </span>
        {dur != null ? <span className="wf-log-card-dur">{formatDuration(dur)}</span> : null}
        {running ? (
          <span className="wf-log-badge wf-log-badge--run">运行中</span>
        ) : cancelled ? (
          <span className="wf-log-badge wf-log-badge--warn">已中断</span>
        ) : failed ? (
          <span className="wf-log-badge wf-log-badge--err">失败</span>
        ) : (
          <span className="wf-log-badge wf-log-badge--ok">完成</span>
        )}
      </div>
      <div className="wf-log-card-body">
        {input.rendered !== '' || input.raw !== '' ? (
          <div className="wf-log-sec">
            <div className="wf-log-sec-label">输入</div>
            <TokenTextView text={input.rendered !== '' ? input.rendered : input.raw} />
            {input.raw !== '' && input.rendered !== '' && input.raw !== input.rendered ? (
              <div className="wf-log-raw">模板：{input.raw}</div>
            ) : null}
          </div>
        ) : null}
        {hasAnyOutput || exit != null ? (
          <div className="wf-log-sec">
            <div className="wf-log-sec-label">输出</div>
            {exit != null ? (
              <span className={`wf-log-exit${exit === 0 ? ' wf-log-exit--ok' : ' wf-log-exit--bad'}`}>exit {exit}</span>
            ) : null}
            {!isAi && out.stdout !== '' ? (
              <div className="wf-log-sec">
                <span className="wf-log-sec-label">stdout</span>
                <pre className="wf-log-pre">{out.stdout}</pre>
              </div>
            ) : null}
            {!isAi && out.stderr !== '' ? (
              <div className="wf-log-sec">
                <span className="wf-log-sec-label">stderr</span>
                <pre className="wf-log-pre wf-log-pre--err">{out.stderr}</pre>
              </div>
            ) : null}
            {(isAi || (out.stdout === '' && out.stderr === '')) && out.text !== '' ? (
              <pre className="wf-log-pre">{out.text}</pre>
            ) : null}
          </div>
        ) : null}
        {errorMessage !== '' ? (
          <div className="wf-log-sec">
            <div className="wf-log-sec-label">错误</div>
            <pre className="wf-log-pre wf-log-pre--err">{errorMessage}</pre>
          </div>
        ) : null}
        {writtenVars.length > 0 ? (
          <div className="wf-log-sec">
            <div className="wf-log-sec-label">写入变量（边激活时）</div>
            <div className="wf-log-chips">
              {writtenVars.map(([k, v]) => (
                <span key={k} className="wf-log-chip" title={v}>
                  {k} = {v.length > 60 ? `${v.slice(0, 59)}…` : v}
                </span>
              ))}
            </div>
          </div>
        ) : null}
        {agent ? (
          <div className="wf-log-agent">
            agent {agent.name || '（匿名）'}
            {agent.modelConfigId !== '' ? ` · ${agent.modelConfigId}` : ''}
            {agent.seedTurns != null || agent.resultTurns != null
              ? ` · 会话轮 ${agent.seedTurns ?? '?'} → ${agent.resultTurns ?? '?'}`
              : ''}
            {agent.inputTokens != null || agent.outputTokens != null
              ? ` · tokens 入 ${agent.inputTokens ?? 0} / 出 ${agent.outputTokens ?? 0}`
              : ''}
          </div>
        ) : null}
        {chain.length > 0 ? (
          <div className="wf-log-sec">
            <div className="wf-log-sec-label">督导介入链</div>
            {chain.map((c, i) => (
              <div key={i} className="wf-log-supchain">
                介入 #{c.attempt ?? i + 1} → {c.action || '?'}
                {c.input !== '' ? '（输入已替换）' : ''}
                {c.reason !== '' ? ` — ${c.reason}` : ''}
              </div>
            ))}
          </div>
        ) : null}
        {edges.length > 0 ? (
          <div className="wf-log-sec">
            <div className="wf-log-sec-label">激活出边</div>
            <div className="wf-log-chips">
              {edges.map(id => (
                <span key={id} className="wf-log-chip" title={id}>
                  {edgeLabelById.get(id) ?? id}
                </span>
              ))}
            </div>
          </div>
        ) : null}
      </div>
    </div>
  )
}

export default function RunLogPanel({ meta, runs, events, loading, loadError, onReload }: RunLogPanelProps) {
  React.useEffect(ensureLogStyles, [])

  const nodeNameById = React.useMemo(() => {
    const m: Record<string, string> = {}
    for (const n of meta?.definitionSnapshot?.nodes ?? []) m[n.id] = n.name || n.id
    return m
  }, [meta])

  const edgeLabelById = React.useMemo(() => {
    const m = new Map<string, string>()
    const nodes = meta?.definitionSnapshot?.nodes ?? []
    const nameOf = (id: string) => nodes.find(n => n.id === id)?.name || id
    for (const e of meta?.definitionSnapshot?.edges ?? []) {
      m.set(e.id, `${nameOf(e.from)} → ${nameOf(e.to)}`)
    }
    return m
  }, [meta])

  // 边数据传递写入快照（node.output 事件 payload.vars → runNo 分组）：runs 记录
  // 不含数据面，从事件侧聚合补挂到运行卡片（真记录与 live 合成卡片同一来源）。
  const varsByRun = React.useMemo(() => {
    const m = new Map<number, Array<[string, string]>>()
    for (const e of events) {
      if (e.event !== 'node.output') continue
      const p = (e.payload ?? {}) as Record<string, unknown>
      const runNo = asNum(p.runNo)
      const vars = p.vars && typeof p.vars === 'object' ? (p.vars as Record<string, unknown>) : null
      if (runNo == null || !vars) continue
      const entries = Object.entries(vars)
        .map(([k, v]) => [k, asStr(v)] as [string, string])
        .filter(([, v]) => v !== '')
      if (entries.length > 0) {
        m.set(runNo, entries)
      }
    }
    return m
  }, [events])

  const entries = React.useMemo(
    () => buildTimelineEntries(runs, events, nodeNameById),
    [runs, events, nodeNameById],
  )

  return (
    <div className="wf-log-root">
      <div className="wf-log-head">
        <span className="wf-log-title">运行日志</span>
        <span className="wf-log-count">{loading ? '加载中…' : `${entries.length} 条`}</span>
        <button
          type="button"
          className="wf-log-btn"
          onClick={onReload}
          title="重载实例元数据与 runs 记录（事件流由长轮询自动维护，无需重载）"
        >
          <RefreshIcon size={12} /> 重载
        </button>
        <span className="wf-log-hint">物流式时间线：节点运行卡片（输入/输出/错误/督导介入）+ 实例状态行</span>
      </div>
      {loadError ? <div className="wf-log-error">runs 记录加载失败：{rpcErrorMessage(loadError)}</div> : null}
      <div className="wf-log-list">
        {entries.length === 0 && !loading ? (
          <div className="wf-log-empty">暂无运行记录——实例尚未开始任何节点。</div>
        ) : null}
        {entries.map(entry =>
          entry.kind === 'run' ? (
            <div className="wf-log-item" key={entry.key}>
              <RunItem entry={entry} edgeLabelById={edgeLabelById} varsByRun={varsByRun} />
            </div>
          ) : (
            <div className={`wf-log-item wf-log-item--${entry.tone}`} key={entry.key}>
              <div className="wf-log-line">
                <span className="wf-log-line-text">{entry.text}</span>
                {entry.detail ? <span className="wf-log-line-detail">{entry.detail}</span> : null}
                <span className="wf-log-line-time">{formatClock(entry.ts)}</span>
              </div>
            </div>
          ),
        )}
      </div>
    </div>
  )
}

/*
 * ── 人工验证清单（步骤 10 运行日志页）─────────────────────────────────────
 * 1. 实例页顶栏切「运行日志」：时间线从上到下=实例创建（含变量明细行）→ 各节点运行
 *    卡片 → 实例状态行；左侧圆点轨道按 tone 着色（绿=完成/琥珀=等待与取消/红=失败）。
 * 2. 命令节点卡片：stdout/stderr 分块（等宽字体、可滚动）+ exit 徽标（0=绿，非 0=红）。
 * 3. AI 节点卡片：输入为胶囊富文本（宿主 TokenRichText；缺席宿主下降级纯文本——
 *    含 @file 引用 token 的输入可见胶囊）；输出为最终回复全文 pre 块；agent 行显示
 *    会话轮增量与 tokens 用量。
 * 4. 失败节点卡片：红色错误块；supervisor 介入后同卡片追加「督导介入链」行
 *    （介入 #n → retry/skip/... — reason），且时间线出现对应督导状态行。
 * 4b. 边数据传递：带 assigns/边 input 的节点收口后，其运行卡片出现「写入变量（边激活
 *     时）」区块（chips：变量名 = 值预览，hover 看全量值）；被跳过节点的「已跳过」
 *     状态行明细同样携带写入变量（边 input 模板照写、assigns 无输出跳过）。
 * 5. 运行中实例：进行中节点先出现「运行中」闪烁徽标的合成卡片（输入预览来自
 *    node.started 事件）；节点收口落盘后点「重载」→ 合成卡片被真记录卡片取代
 *    （exit/输出全量）。不点重载也不重复（runNo 去重）。
 * 6. 「重载」按钮：重拉 runLog（顶栏进度/右侧详情同步刷新）；事件行不受影响。
 * 7. askUser 挂起：时间线出现「等待用户决策（节点 · run #n）」琥珀行（question/
 *    context 明细）；决策后出现「实例状态 → 运行中」行，节点继续产出卡片。
 * 8. 取消实例：出现「实例已取消（操作者：user）」行；被中断节点无收口卡片
 *    （其合成卡片保持运行中态——与引擎「中断如实呈现」口径一致）。
 * 9. 终态实例回放：整页历史完整（wf.created → … → wf.done/failed/cancelled 收尾）。
 *
 * ── 人工验证清单（移动端，视口 ≤768px）──────────────────────────────────
 * 10. 头部说明文字换行到按钮下方不遮挡；时间线卡片头/正文收窄内边距仍可读；
 *     stdout/stderr pre 块可纵向滚动（max-height 220px），长文本自动折行。
 */
