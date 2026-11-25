/**
 * 工作流实例运行页（步骤 10 主体）：tab=workflow-instance，tab.data={instanceId}。
 *
 * ── 数据装配 ──
 * - 挂载：instance.get（meta 全量，definitionSnapshot=画布数据源）+ instance.runLog 并拉；
 * - 事件流：subscribeInstanceEvents（**后端主动推送**——订阅实例流频道收实时增量，
 *   全量回放/断线补齐由 gateway 内部走 instance.events 拉取并按 seq 去重合流；
 *   终态/他端删除自动停——本面板只消费）；收到 node.output/node.error/
 *   supervisor.decision（runs 记录已先于事件落盘）或终态事件 → 450ms 防抖重拉 meta+runs，
 *   详情面板与进度随事件自然保鲜，无空转定时器（仅运行中实例有 1s 计时 tick 刷时长）、
 *   **无手动刷新入口**（断线重连由宿主全局重连提示覆盖，恢复后自动续流）。
 * - 节点状态着色：**事件投影**（projectNodesFromEvents——node.started/output/error/
 *   skipped/wf.askUser 按序折叠；run 级留痕含 inputPreview/outputPreview/activatedEdges）
 *   + 终态 meta.nodeStates 补洞（worker 重启 activate 扫描改 meta 不补事件的场景）。
 *   投影喂 WorkflowCanvas(mode=readonly) 的 status/subtitle（副标题=最新输出摘要截断）；
 *   画布 readonly 下节点点击本就回调 onSelect——零画布改动。
 *
 * ── UI 结构 ──
 * 顶栏：状态色点+定义名+变量摘要+时长+事件流状态+「运行图/运行日志」视图切换+
 * 取消（运行中）/删除（终态）；waiting-user 决策横幅（重试/跳过/终止/换输入重试
 * → gateway.decide）；主体两态：运行图（左画布+右节点详情面板：input 三视图/output
 * 全量/error/agent+usage/激活出边/该节点全部 run 记录列表可选看）或 RunLogPanel
 * （物流式时间线，见该文件头注释）。删除/NOT_FOUND 后居中终态占位。
 * 移动端（≤768px，useIsMobile）：右侧详情面板退化为底部抽屉——点按节点/边自动滑出
 * （带 × 关闭），画布保持可见；桌面布局不变（样式收在 @media 块内）。
 *
 * ── 纯逻辑可测核（无插件 web 测试基建，导出保持可测） ──
 * projectNodesFromEvents / mergeTerminalNodeStates / deriveInstanceStatus /
 * parsePendingDecision / pendingDecisionFromEvent / variablesSummary /
 * canvasNodeSubtitle / elapsedText。人工验证清单见文件尾注释。
 */
import React from 'react'
import WorkflowCanvas, {
  type CanvasEdgeView,
  type CanvasNodeView,
  type CanvasSelection,
} from '../canvas/WorkflowCanvas'
import { statusVisual, type RenderStatus } from '../canvas/renderStates'
import { confirmDialog } from '../Dialog'
import { getPluginContext } from '../pluginRuntime'
import {
  conditionLabel,
  isTerminalInstanceEvent,
  rpcErrorMessage,
  subscribeInstanceEvents,
  workflowGateway,
  type WorkflowDefinition,
  type WorkflowEdgeDef,
  type WorkflowEventsPollState,
  type WorkflowInstanceEvent,
  type WorkflowInstanceMeta,
  type WorkflowInstanceStatus,
  type WorkflowNodeDef,
  type WorkflowRunRecord,
} from '../workflowGateway'
import { instanceStatusLabel, instanceStatusVisual } from './SidebarPanel'
import { nodePos, nodeSubtitle } from './DefinitionEditorPanel'
import RunLogPanel, {
  TokenTextView,
  asNum,
  asStr,
  asStrArray,
  firstLineText,
  formatClock,
  formatDuration,
  runAgentOf,
  runErrorExitCodeOf,
  runErrorOf,
  runInputOf,
  runOutputOf,
  runSupervisorOf,
} from './RunLogPanel'
import { useIsMobile } from '../useIsMobile'
import {
  CloseIcon,
  DeleteIcon,
  EditIcon,
  LogIcon,
  RetryIcon,
  SkipIcon,
  StopIcon,
  WorkflowIcon,
} from '../icons'

/** 非终态实例（可取消/可决策：created/running/waiting-user；侧栏同口径本地重定义）。 */
function instanceActive(status: string): boolean {
  return status === 'created' || status === 'running' || status === 'waiting-user'
}

// ─── 事件 → 节点态投影（纯逻辑可测核） ───────────────────────────────────

/** 单次节点运行的实时投影（事件侧摘要；全量数据在 runs 记录）。 */
export interface NodeRunProjection {
  runNo: number
  status: 'running' | 'done' | 'failed' | 'skipped' | 'cancelled'
  startedAt: number | null
  endedAt: number | null
  /** node.started 携带的输入预览（≤1KB 截断）。 */
  inputPreview: string
  /** node.output 携带的输出预览（≤1KB 截断）。 */
  outputPreview: string
  exitCode: number | null
  error: string | null
  activatedEdges: string[]
}

/** 节点聚合投影（status=null 表示未运行/不投影）。 */
export interface NodeProjection {
  status: RenderStatus | null
  lastRunNo: number | null
  runCount: number
  agent: string
  /** 事件侧观测的节点显示名（payload.nodeName）。 */
  nodeName: string
  outputPreview: string
  error: string | null
  /** runNo 升序的全部运行投影。 */
  runs: NodeRunProjection[]
}

/** 事件全集按序折叠 → 每节点投影（wf.askUser=waiting；被后续节点事件自然覆盖）。 */
export function projectNodesFromEvents(events: WorkflowInstanceEvent[]): Record<string, NodeProjection> {
  interface Mutable {
    status: RenderStatus | null
    lastRunNo: number | null
    agent: string
    nodeName: string
    outputPreview: string
    error: string | null
    runs: NodeRunProjection[]
  }
  const nodes = new Map<string, Mutable>()
  const nodeOf = (id: string): Mutable => {
    let n = nodes.get(id)
    if (!n) {
      n = { status: null, lastRunNo: null, agent: '', nodeName: '', outputPreview: '', error: null, runs: [] }
      nodes.set(id, n)
    }
    return n
  }
  const runOf = (n: Mutable, runNo: number): NodeRunProjection => {
    let r = n.runs.find(x => x.runNo === runNo)
    if (!r) {
      r = {
        runNo, status: 'running', startedAt: null, endedAt: null,
        inputPreview: '', outputPreview: '', exitCode: null, error: null, activatedEdges: [],
      }
      n.runs.push(r)
      n.runs.sort((a, b) => a.runNo - b.runNo)
    }
    return r
  }

  for (const e of events) {
    const p = (e.payload ?? {}) as Record<string, unknown>
    const nodeId = asStr(p.nodeId)
    if (nodeId === '') continue // 非节点域事件（wf.status/wf.done 等）
    const runNo = asNum(p.runNo) ?? 0
    const n = nodeOf(nodeId)
    switch (e.event) {
      case 'node.started': {
        n.nodeName = asStr(p.nodeName) || n.nodeName
        n.agent = asStr(p.agent) || n.agent
        n.status = 'running'
        n.lastRunNo = runNo
        const r = runOf(n, runNo)
        r.status = 'running'
        r.startedAt = e.ts || r.startedAt
        r.inputPreview = asStr(p.inputPreview) || r.inputPreview
        break
      }
      case 'node.output': {
        n.status = 'done'
        n.lastRunNo = runNo
        n.outputPreview = asStr(p.outputPreview)
        n.error = null
        const r = runOf(n, runNo)
        r.status = 'done'
        r.endedAt = e.ts || r.endedAt
        r.outputPreview = asStr(p.outputPreview)
        r.exitCode = asNum(p.exitCode)
        r.error = null
        r.activatedEdges = asStrArray(p.activatedEdges)
        break
      }
      case 'node.error': {
        n.status = 'failed'
        n.lastRunNo = runNo
        n.error = asStr(p.message)
        const r = runOf(n, runNo)
        r.status = 'failed'
        r.endedAt = e.ts || r.endedAt
        r.error = asStr(p.message)
        r.exitCode = asNum(p.exitCode) ?? r.exitCode
        break
      }
      case 'node.skipped': {
        n.status = 'skipped'
        n.lastRunNo = runNo
        n.outputPreview = ''
        const r = runOf(n, runNo)
        r.status = 'skipped'
        r.endedAt = e.ts || r.endedAt
        break
      }
      case 'node.interrupted': {
        // 实例终态收口中断的在飞运行（引擎 finish 收口扫描补发）——关闭节点/运行投影
        n.status = 'cancelled'
        n.lastRunNo = runNo
        n.error = asStr(p.message) || n.error
        const r = runOf(n, runNo)
        r.status = 'cancelled'
        r.endedAt = e.ts || r.endedAt
        r.error = asStr(p.message) || r.error
        break
      }
      case 'wf.askUser': {
        // 挂起决策 → 等待黄；由该节点后续 node.started/skipped 或实例终态自然覆盖
        // （fail 决策下节点保持黄——「决策等待中实例终止」的如实呈现）。
        n.status = 'waiting'
        break
      }
      default:
        break
    }
  }

  const out: Record<string, NodeProjection> = {}
  for (const [id, n] of nodes) {
    out[id] = {
      status: n.status,
      lastRunNo: n.lastRunNo,
      runCount: n.runs.length,
      agent: n.agent,
      nodeName: n.nodeName,
      outputPreview: n.outputPreview,
      error: n.error,
      runs: n.runs,
    }
  }
  return out
}

/** 终态 nodeStates 的 wire 状态名 → RenderStatus（pending=未运行不投影）。 */
const TERMINAL_NODE_STATUS_MAP: Record<string, RenderStatus | null> = {
  pending: null,
  running: 'running',
  done: 'done',
  skipped: 'skipped',
  failed: 'failed',
  cancelled: 'cancelled',
}

/**
 * 终态 meta.nodeStates 补洞：仅对事件投影缺席的节点生效（worker 重启 activate 扫描
 * 直接改 meta 不追加事件的兜底路径；正常终态事件齐全时为 no-op）。
 */
export function mergeTerminalNodeStates(
  projection: Record<string, NodeProjection>,
  nodeStates?: Record<string, unknown> | null,
): Record<string, NodeProjection> {
  if (!nodeStates || typeof nodeStates !== 'object') return projection
  const out = { ...projection }
  for (const [id, raw] of Object.entries(nodeStates)) {
    if (out[id]) continue
    const st = (raw ?? {}) as Record<string, unknown>
    const mapped = TERMINAL_NODE_STATUS_MAP[String(asStr(st.status))]
    out[id] = {
      status: mapped === undefined ? null : mapped,
      lastRunNo: asNum(st.lastRunNo),
      runCount: asNum(st.runCount) ?? 0,
      agent: '',
      nodeName: '',
      outputPreview: '',
      error: null,
      runs: [],
    }
  }
  return out
}

/** 事件流推实例状态（末次 wf.status / wf.done / wf.cancelled；缺席回退 meta 口径）。 */
export function deriveInstanceStatus(
  events: WorkflowInstanceEvent[],
  fallback?: WorkflowInstanceStatus | null,
): WorkflowInstanceStatus | null {
  let status: WorkflowInstanceStatus | null = null
  for (const e of events) {
    if (e.event === 'wf.status') {
      const s = asStr((e.payload as { status?: unknown } | null)?.status)
      if (s !== '') status = s as WorkflowInstanceStatus
    } else if (e.event === 'wf.done') {
      status = 'done'
    } else if (e.event === 'wf.cancelled') {
      status = 'cancelled'
    }
  }
  return status ?? fallback ?? null
}

/** pendingDecision 结构（meta.pendingDecision 与 wf.askUser 事件同构）。 */
export interface PendingDecisionInfo {
  nodeId: string
  runNo: number
  question: string
  context: string
  options: string[]
  createdAt: number | null
}

/** unknown → PendingDecisionInfo（缺 nodeId/runNo 视为无效返回 null）。 */
export function parsePendingDecision(v: unknown): PendingDecisionInfo | null {
  if (!v || typeof v !== 'object') return null
  const o = v as Record<string, unknown>
  const nodeId = asStr(o.nodeId)
  const runNo = asNum(o.runNo)
  if (nodeId === '' || runNo == null) return null
  return {
    nodeId,
    runNo,
    question: asStr(o.question),
    context: asStr(o.context),
    options: asStrArray(o.options),
    createdAt: asNum(o.createdAt),
  }
}

/** wf.askUser 事件 → PendingDecisionInfo（payload 同构；非该事件返回 null）。 */
export function pendingDecisionFromEvent(e: WorkflowInstanceEvent): PendingDecisionInfo | null {
  if (e.event !== 'wf.askUser') return null
  return parsePendingDecision(e.payload)
}

/** 运行变量 → 顶栏摘要（k=v · …，超长截断）。 */
export function variablesSummary(variables?: Record<string, string> | null, max = 120): string {
  if (!variables) return ''
  const parts = Object.entries(variables).map(([k, v]) => `${k}=${v}`)
  if (parts.length === 0) return ''
  const joined = parts.join(' · ')
  return joined.length > max ? `${joined.slice(0, max - 1)}…` : joined
}

const RENDER_STATUS_LABEL: Record<RenderStatus, string> = {
  running: '运行中',
  done: '已完成',
  failed: '失败',
  waiting: '等待决策',
  skipped: '已跳过',
  created: '未运行',
  cancelled: '已中断',
}

/**
 * 画布节点副标题：最新输出摘要（首行截断）> 运行中/错误提示 > 状态文案 > 定义副标题
 * （未运行节点回退编辑器同款类型信息）。截断至 60 字符——画布自身再按 20 字符渲染截断。
 */
export function canvasNodeSubtitle(n: WorkflowNodeDef, proj?: NodeProjection | null): string {
  if (proj) {
    if (proj.outputPreview !== '') return firstLineText(proj.outputPreview, 60)
    if (proj.status === 'running') return '运行中…'
    if (proj.error) return firstLineText(proj.error, 60)
    if (proj.status) {
      const label = RENDER_STATUS_LABEL[proj.status]
      if (label) return label
    }
  }
  return nodeSubtitle(n)
}

/** 时长文案：终态用 endedAt-startedAt（meta.durationMs 优先）；运行中用当前时刻。 */
export function elapsedText(startedAt?: number | null, endedAt?: number | null, nowMs?: number): string {
  if (typeof startedAt !== 'number' || startedAt <= 0) return ''
  const end = typeof endedAt === 'number' && endedAt > 0 ? endedAt : (nowMs ?? Date.now())
  return formatDuration(Math.max(0, end - startedAt))
}

// ─── 样式注入 ──────────────────────────────────────────────────────────────

const INSTANCE_STYLE_ID = 'wf-inst-styles'

function ensureInstanceStyles(): void {
  if (typeof document === 'undefined' || document.getElementById(INSTANCE_STYLE_ID)) return
  const style = document.createElement('style')
  style.id = INSTANCE_STYLE_ID
  style.textContent = `
.wf-inst-root { display: flex; flex-direction: column; height: 100%; min-height: 0; background: var(--bg-primary, #0b0d12); color: var(--text-primary, #eef1f5); font-size: 13px; }
.wf-inst-top { display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-bottom: 1px solid var(--border, #2a303b); background: var(--bg-secondary, #11141b); flex-wrap: wrap; flex: none; }
.wf-inst-dot { width: 9px; height: 9px; border-radius: 50%; flex: none; }
.wf-inst-dot--pulse { animation: wf-inst-pulse 1.6s ease-in-out infinite; }
@keyframes wf-inst-pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.35; } }
.wf-inst-name { font-weight: 600; max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wf-inst-status { font-size: 12px; flex: none; }
.wf-inst-vars { font-size: 11.5px; color: var(--text-muted, #6f7a8b); max-width: 320px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wf-inst-time { font-size: 11.5px; color: var(--text-secondary, #aab3c2); flex: none; }
.wf-inst-chip { display: inline-flex; align-items: center; border: 1px solid var(--border, #2a303b); border-radius: 999px; padding: 0 7px; font-size: 10.5px; color: var(--text-muted, #6f7a8b); max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wf-inst-chip--warn { color: var(--accent-amber, #f7bd54); border-color: var(--accent-amber, #f7bd54); }
.wf-inst-spacer { flex: 1; }
.wf-inst-toggle { display: inline-flex; align-items: center; gap: 4px; padding: 3px 9px; border: 1px solid var(--border-strong, #3b4658); border-radius: 6px; background: transparent; color: var(--text-muted, #6f7a8b); cursor: pointer; font-size: 11.5px; white-space: nowrap; }
.wf-inst-toggle:hover { color: var(--text-primary, #eef1f5); border-color: var(--accent-blue, #61a5ff); }
.wf-inst-toggle--on { color: var(--accent-blue, #61a5ff); border-color: var(--accent-blue, #61a5ff); background: var(--bg-hover, #202631); }
.wf-inst-btn { display: inline-flex; align-items: center; gap: 4px; padding: 3px 9px; border: 1px solid var(--border-strong, #3b4658); border-radius: 6px; background: transparent; color: var(--text-secondary, #aab3c2); cursor: pointer; font-size: 11.5px; white-space: nowrap; }
.wf-inst-btn:hover { color: var(--text-primary, #eef1f5); border-color: var(--accent-blue, #61a5ff); }
.wf-inst-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.wf-inst-btn--danger:hover { color: var(--accent-red, #ff6b6b); border-color: var(--accent-red, #ff6b6b); }
.wf-inst-decision { display: flex; flex-direction: column; gap: 8px; margin: 8px 12px 0; padding: 10px 12px; border: 1px solid var(--accent-amber, #f7bd54); border-left-width: 3px; border-radius: 8px; background: var(--bg-secondary, #11141b); flex: none; }
.wf-inst-decision-title { display: flex; align-items: center; gap: 6px; font-weight: 600; color: var(--accent-amber, #f7bd54); }
.wf-inst-decision-q { color: var(--text-primary, #eef1f5); line-height: 1.6; }
.wf-inst-decision-ctx { font-size: 12px; color: var(--text-muted, #6f7a8b); white-space: pre-wrap; word-break: break-word; max-height: 120px; overflow-y: auto; background: var(--bg-primary, #0b0d12); border: 1px solid var(--border-light, #1b202a); border-radius: 6px; padding: 6px 8px; }
.wf-inst-decision-meta { font-size: 11.5px; color: var(--text-faint, #525c6a); }
.wf-inst-decision-actions { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.wf-inst-decision-input { display: flex; flex-direction: column; gap: 6px; }
.wf-inst-textarea { width: 100%; box-sizing: border-box; min-height: 76px; resize: vertical; background: var(--bg-primary, #0b0d12); border: 1px solid var(--border, #2a303b); border-radius: 6px; color: var(--text-primary, #eef1f5); padding: 6px 9px; font-size: 12.5px; font-family: inherit; line-height: 1.6; }
.wf-inst-textarea:focus { outline: none; border-color: var(--accent-amber, #f7bd54); }
.wf-inst-error { color: var(--accent-red, #ff6b6b); font-size: 12px; }
.wf-inst-body { flex: 1; min-height: 0; display: flex; }
.wf-inst-canvas-wrap { flex: 1; min-width: 0; position: relative; }
.wf-inst-side { width: 330px; flex: none; border-left: 1px solid var(--border, #2a303b); background: var(--bg-secondary, #11141b); overflow-y: auto; padding: 12px; }
.wf-inst-center { flex: 1; display: flex; align-items: center; justify-content: center; color: var(--text-muted, #6f7a8b); flex-direction: column; gap: 12px; }
.wf-sidecard { border: 1px solid var(--border-light, #1b202a); border-radius: 8px; background: var(--bg-tertiary, #171b24); margin-bottom: 10px; overflow: hidden; }
.wf-sidecard-head { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; padding: 8px 10px; border-bottom: 1px solid var(--border-light, #1b202a); background: var(--bg-secondary, #11141b); }
.wf-sidecard-title { font-weight: 600; }
.wf-sidecard-id { font-size: 11px; color: var(--text-faint, #525c6a); }
.wf-sidecard-body { padding: 8px 10px 10px; display: flex; flex-direction: column; gap: 8px; }
.wf-inst-badge { font-size: 10.5px; border-radius: 999px; padding: 0 7px; border: 1px solid var(--text-muted, #6f7a8b); color: var(--text-muted, #6f7a8b); flex: none; }
.wf-inst-badge--ok { color: var(--accent-green, #41d19c); border-color: var(--accent-green, #41d19c); }
.wf-inst-badge--err { color: var(--accent-red, #ff6b6b); border-color: var(--accent-red, #ff6b6b); }
.wf-inst-badge--run { color: var(--accent-blue, #61a5ff); border-color: var(--accent-blue, #61a5ff); }
.wf-inst-badge--wait { color: var(--accent-amber, #f7bd54); border-color: var(--accent-amber, #f7bd54); }
.wf-inst-sec { display: flex; flex-direction: column; gap: 4px; }
.wf-inst-sec-label { font-size: 11px; color: var(--text-faint, #525c6a); }
.wf-inst-pre { margin: 0; white-space: pre-wrap; word-break: break-word; font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; line-height: 1.55; background: var(--bg-primary, #0b0d12); border: 1px solid var(--border-light, #1b202a); border-radius: 6px; padding: 8px 10px; max-height: 260px; overflow: auto; color: var(--text-primary, #eef1f5); }
.wf-inst-pre--err { color: var(--accent-red, #ff6b6b); }
.wf-inst-raw { font-size: 11.5px; color: var(--text-faint, #525c6a); word-break: break-all; }
.wf-inst-agent { font-size: 12px; color: var(--text-secondary, #aab3c2); line-height: 1.6; }
.wf-inst-chips { display: flex; gap: 4px; flex-wrap: wrap; }
.wf-inst-chip2 { display: inline-flex; align-items: center; border: 1px solid var(--border, #2a303b); border-radius: 999px; padding: 0 7px; font-size: 10.5px; color: var(--text-muted, #6f7a8b); max-width: 280px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wf-inst-runlist { display: flex; flex-direction: column; gap: 4px; }
.wf-inst-runrow { display: flex; align-items: center; gap: 8px; padding: 5px 8px; border: 1px solid var(--border-light, #1b202a); border-radius: 6px; background: var(--bg-primary, #0b0d12); cursor: pointer; font-size: 12px; }
.wf-inst-runrow:hover { border-color: var(--accent-blue, #61a5ff); }
.wf-inst-runrow--on { border-color: var(--accent-blue, #61a5ff); background: var(--bg-hover, #202631); }
.wf-inst-runrow-time { color: var(--text-faint, #525c6a); font-size: 11px; }
.wf-inst-runrow-dur { color: var(--text-secondary, #aab3c2); font-size: 11px; }
.wf-inst-hintcard { color: var(--text-faint, #525c6a); font-size: 12px; line-height: 1.7; padding: 4px 2px; }
/* 桌面端关闭按钮：面板顶部吸顶条（滚动长详情时始终可点）；移动端在媒体查询中覆盖为抽屉右上角浮钮 */
.wf-inst-side-close {
  position: sticky; top: 0; z-index: 6;
  display: flex; align-items: center; justify-content: flex-end;
  width: 100%; height: 30px; padding: 0 8px 0 0; margin: 0 0 2px;
  border: none; background: var(--bg-secondary, #11141b); color: var(--text-muted, #6f7a8b); cursor: pointer;
}
.wf-inst-side-close:hover { color: var(--text-primary, #eef1f5); }
/* ── 移动端（≤768px）：详情面板退化底部抽屉；顶栏紧凑换行；输入 16px 防 iOS 缩放 ── */
@media (max-width: 768px) {
  .wf-inst-textarea { font-size: 16px; }
  .wf-inst-top { padding: 6px 10px; gap: 6px; }
  .wf-inst-name { max-width: 150px; }
  .wf-inst-vars { max-width: 100%; flex: 1 1 100%; order: 9; }
  .wf-inst-time { order: 8; }
  .wf-inst-spacer { flex: 0 0 0; width: 0; }
  .wf-inst-btn, .wf-inst-toggle { padding: 6px 10px; font-size: 12px; }
  .wf-inst-body { position: relative; }
  .wf-inst-side {
    position: absolute; left: 0; right: 0; bottom: 0; z-index: 20;
    width: auto; max-height: 70%;
    border-left: none; border-top: 1px solid var(--border, #2a303b);
    border-radius: 14px 14px 0 0;
    box-shadow: 0 -14px 34px rgba(0, 0, 0, 0.42);
    transform: translateY(103%);
    transition: transform 0.22s ease;
  }
  .wf-inst-side.wf-inst-side--open { transform: none; }
  .wf-inst-side-close {
    display: inline-flex; align-items: center; justify-content: center;
    width: 34px; height: 34px; border: none; border-radius: 8px;
    background: transparent; color: var(--text-muted, #6f7a8b); cursor: pointer;
    position: absolute; top: 8px; right: 10px; z-index: 1;
  }
  .wf-inst-side-close:active { color: var(--text-primary, #eef1f5); background: var(--bg-hover, #202631); }
  .wf-inst-side .wf-sidecard:first-child .wf-sidecard-head { padding-right: 44px; }
  .wf-inst-pre { max-height: 200px; }
}
`
  document.head.appendChild(style)
}

// ─── 决策横幅 ──────────────────────────────────────────────────────────────

function DecisionBanner({
  pending,
  nodeName,
  deciding,
  error,
  decisionInput,
  onInputChange,
  withInputOpen,
  onToggleInput,
  onDecide,
}: {
  pending: PendingDecisionInfo
  nodeName: string
  deciding: string | null
  error: string | null
  decisionInput: string
  onInputChange: (v: string) => void
  withInputOpen: boolean
  onToggleInput: (open: boolean) => void
  onDecide: (decision: 'retry' | 'skip' | 'fail' | 'retryWithInput') => void
}): React.ReactElement {
  const options = pending.options.length > 0 ? pending.options : ['retry', 'skip', 'fail', 'retryWithInput']
  const has = (o: string) => options.includes(o)
  const busy = deciding !== null
  const submitDisabled = busy || decisionInput.trim() === ''
  return (
    <div className="wf-inst-decision">
      <div className="wf-inst-decision-title">
        <EditIcon size={14} /> 等待用户决策
      </div>
      {pending.question !== '' ? <div className="wf-inst-decision-q">{pending.question}</div> : null}
      {pending.context !== '' ? <div className="wf-inst-decision-ctx">{pending.context}</div> : null}
      <div className="wf-inst-decision-meta">
        节点 {nodeName || pending.nodeId}（{pending.nodeId}） · run #{pending.runNo}
        {pending.createdAt ? ` · 挂起于 ${formatClock(pending.createdAt)}` : ''}
      </div>
      <div className="wf-inst-decision-actions">
        {has('retry') ? (
          <button type="button" className="wf-inst-btn" disabled={busy} onClick={() => onDecide('retry')}>
            <RetryIcon size={12} /> {deciding === 'retry' ? '提交中…' : '重试'}
          </button>
        ) : null}
        {has('skip') ? (
          <button type="button" className="wf-inst-btn" disabled={busy} onClick={() => onDecide('skip')}>
            <SkipIcon size={12} /> {deciding === 'skip' ? '提交中…' : '跳过'}
          </button>
        ) : null}
        {has('retryWithInput') ? (
          <button
            type="button"
            className={`wf-inst-toggle${withInputOpen ? ' wf-inst-toggle--on' : ''}`}
            disabled={busy}
            onClick={() => onToggleInput(!withInputOpen)}
            title="用新输入替换节点输入后重试（retryWithInput）"
          >
            <EditIcon size={12} /> 换输入重试
          </button>
        ) : null}
        {has('fail') ? (
          <button
            type="button"
            className="wf-inst-btn wf-inst-btn--danger"
            disabled={busy}
            onClick={() => onDecide('fail')}
            title="终止整个实例（fail）"
          >
            <StopIcon size={12} /> {deciding === 'fail' ? '提交中…' : '终止实例'}
          </button>
        ) : null}
      </div>
      {withInputOpen ? (
        <div className="wf-inst-decision-input">
          <textarea
            className="wf-inst-textarea"
            value={decisionInput}
            placeholder="输入替换该节点的新输入（retryWithInput）…"
            onChange={e => onInputChange(e.target.value)}
            disabled={busy}
          />
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <button
              type="button"
              className="wf-inst-btn"
              disabled={submitDisabled}
              onClick={() => onDecide('retryWithInput')}
            >
              {deciding === 'retryWithInput' ? '提交中…' : '以新输入重试'}
            </button>
            <span style={{ fontSize: 11, color: 'var(--text-faint, #525c6a)' }}>留空不可提交</span>
          </div>
        </div>
      ) : null}
      {error ? <div className="wf-inst-error">{error}</div> : null}
    </div>
  )
}

// ─── 节点详情面板 ──────────────────────────────────────────────────────────

function NodeDetail({
  node,
  proj,
  nodeRuns,
  edgeLabelById,
  detailRunNo,
  onSelectRun,
}: {
  node: WorkflowNodeDef
  proj: NodeProjection | null
  nodeRuns: WorkflowRunRecord[]
  edgeLabelById: Map<string, string>
  detailRunNo: number | null
  onSelectRun: (runNo: number) => void
}): React.ReactElement {
  const record = nodeRuns.find(r => asNum(r.runNo) === detailRunNo) ?? nodeRuns[nodeRuns.length - 1] ?? null
  const lastRun = proj && proj.runs.length > 0 ? proj.runs[proj.runs.length - 1] : null
  const input = record ? runInputOf(record) : { raw: '', rendered: '', visibleToAi: '' }
  const livePreview = lastRun?.inputPreview ?? ''
  const out = record ? runOutputOf(record) : null
  const agent = record ? runAgentOf(record) : null
  const chain = record ? runSupervisorOf(record) : []
  const errorMessage = record ? runErrorOf(record) : (proj?.error ?? '')
  const exit = out ? out.exitCode ?? runErrorExitCodeOf(record ?? {}) : null
  const edges = record ? asStrArray(record.activatedEdges) : (lastRun?.activatedEdges ?? [])
  const isAi = node.type === 'ai' || asStr(record?.nodeType) === 'ai'
  const statusBadge = proj?.status == null ? null : proj.status === 'running' ? (
    <span className="wf-inst-badge wf-inst-badge--run">运行中</span>
  ) : proj.status === 'done' ? (
    <span className="wf-inst-badge wf-inst-badge--ok">完成</span>
  ) : proj.status === 'failed' ? (
    <span className="wf-inst-badge wf-inst-badge--err">失败</span>
  ) : proj.status === 'waiting' ? (
    <span className="wf-inst-badge wf-inst-badge--wait">等待决策</span>
  ) : proj.status === 'cancelled' ? (
    <span className="wf-inst-badge wf-inst-badge--wait">已中断</span>
  ) : (
    <span className="wf-inst-badge">已跳过</span>
  )
  const hasOutput = out != null && (out.stdout !== '' || out.stderr !== '' || out.text !== '')

  return (
    <>
      <div className="wf-sidecard">
        <div className="wf-sidecard-head">
          <span className="wf-sidecard-title">{node.name || node.id}</span>
          <span className="wf-sidecard-id">{node.id}</span>
          {statusBadge}
        </div>
        <div className="wf-sidecard-body">
          <div style={{ fontSize: 11.5, color: 'var(--text-muted, #6f7a8b)', lineHeight: 1.7 }}>
            类型 {node.type}
            {node.agent ? ` · agent ${node.agent}` : ''}
            {node.modelConfigId ? ` · 模型 ${node.modelConfigId}` : ''}
            {node.maxRuns ? ` · maxRuns ${node.maxRuns}` : ''}
            {node.timeoutMs ? ` · 超时 ${formatDuration(node.timeoutMs)}` : ''}
          </div>
          {proj ? (
            <div style={{ fontSize: 11.5, color: 'var(--text-faint, #525c6a)' }}>
              已运行 {proj.runCount} 次 · 最近 run #{proj.lastRunNo ?? '—'}
            </div>
          ) : (
            <div className="wf-inst-hintcard">该节点尚未运行。</div>
          )}
        </div>
      </div>

      {(input.rendered !== '' || input.raw !== '' || livePreview !== '') && (
        <div className="wf-sidecard">
          <div className="wf-sidecard-head"><span className="wf-sidecard-title">输入</span></div>
          <div className="wf-sidecard-body">
            {input.rendered !== '' || livePreview !== '' ? (
              <div className="wf-inst-sec">
                <span className="wf-inst-sec-label">{record ? '渲染后（变量已替换）' : '实时预览（截断）'}</span>
                <TokenTextView text={input.rendered !== '' ? input.rendered : livePreview} />
              </div>
            ) : null}
            {input.raw !== '' ? <div className="wf-inst-raw">模板：{input.raw}</div> : null}
            {input.visibleToAi !== '' && input.visibleToAi !== input.rendered ? (
              <div className="wf-inst-sec">
                <span className="wf-inst-sec-label">AI 所见（token 拍平后）</span>
                <TokenTextView text={input.visibleToAi} />
              </div>
            ) : null}
          </div>
        </div>
      )}

      {hasOutput || exit != null || (out == null && (lastRun?.outputPreview ?? '') !== '') ? (
        <div className="wf-sidecard">
          <div className="wf-sidecard-head"><span className="wf-sidecard-title">输出</span></div>
          <div className="wf-sidecard-body">
            {exit != null ? (
              <span
                className={`wf-inst-badge${exit === 0 ? ' wf-inst-badge--ok' : ' wf-inst-badge--err'}`}
                style={{ alignSelf: 'flex-start' }}
              >
                exit {exit}
              </span>
            ) : null}
            {out && !isAi && out.stdout !== '' ? (
              <div className="wf-inst-sec">
                <span className="wf-inst-sec-label">stdout</span>
                <pre className="wf-inst-pre">{out.stdout}</pre>
              </div>
            ) : null}
            {out && !isAi && out.stderr !== '' ? (
              <div className="wf-inst-sec">
                <span className="wf-inst-sec-label">stderr</span>
                <pre className="wf-inst-pre wf-inst-pre--err">{out.stderr}</pre>
              </div>
            ) : null}
            {out && out.text !== '' ? <pre className="wf-inst-pre">{out.text}</pre> : null}
            {out == null && (lastRun?.outputPreview ?? '') !== '' ? (
              <div className="wf-inst-sec">
                <span className="wf-inst-sec-label">实时预览（截断，收口后可重载看全量）</span>
                <pre className="wf-inst-pre">{lastRun?.outputPreview}</pre>
              </div>
            ) : null}
          </div>
        </div>
      ) : null}

      {errorMessage !== '' ? (
        <div className="wf-sidecard">
          <div className="wf-sidecard-head"><span className="wf-sidecard-title" style={{ color: 'var(--accent-red, #ff6b6b)' }}>错误</span></div>
          <div className="wf-sidecard-body">
            <pre className="wf-inst-pre wf-inst-pre--err">{errorMessage}</pre>
          </div>
        </div>
      ) : null}

      {agent || node.agent ? (
        <div className="wf-sidecard">
          <div className="wf-sidecard-head"><span className="wf-sidecard-title">Agent</span></div>
          <div className="wf-sidecard-body">
            <div className="wf-inst-agent">
              {agent?.name || node.agent || '（匿名一次性会话）'}
              {agent?.modelConfigId ? ` · 模型 ${agent?.modelConfigId}` : ''}
              {agent && (agent.seedTurns != null || agent.resultTurns != null)
                ? ` · 会话轮 ${agent.seedTurns ?? '?'} → ${agent.resultTurns ?? '?'}`
                : ''}
              {agent && (agent.inputTokens != null || agent.outputTokens != null)
                ? ` · tokens 入 ${agent.inputTokens ?? 0} / 出 ${agent.outputTokens ?? 0}`
                : ''}
            </div>
          </div>
        </div>
      ) : null}

      {chain.length > 0 ? (
        <div className="wf-sidecard">
          <div className="wf-sidecard-head"><span className="wf-sidecard-title" style={{ color: 'var(--accent-purple, #b59cff)' }}>督导介入链</span></div>
          <div className="wf-sidecard-body">
            {chain.map((c, i) => (
              <div key={i} style={{ fontSize: 12, color: 'var(--accent-purple, #b59cff)', lineHeight: 1.6 }}>
                介入 #{c.attempt ?? i + 1} → {c.action || '?'}
                {c.input !== '' ? '（输入已替换）' : ''}
                {c.reason !== '' ? ` — ${c.reason}` : ''}
              </div>
            ))}
          </div>
        </div>
      ) : null}

      {edges.length > 0 ? (
        <div className="wf-sidecard">
          <div className="wf-sidecard-head"><span className="wf-sidecard-title">激活出边</span></div>
          <div className="wf-sidecard-body">
            <div className="wf-inst-chips">
              {edges.map(id => (
                <span key={id} className="wf-inst-chip2" title={id}>{edgeLabelById.get(id) ?? id}</span>
              ))}
            </div>
          </div>
        </div>
      ) : null}

      {nodeRuns.length > 0 ? (
        <div className="wf-sidecard">
          <div className="wf-sidecard-head"><span className="wf-sidecard-title">运行记录（{nodeRuns.length}）</span></div>
          <div className="wf-sidecard-body">
            <div className="wf-inst-runlist">
              {[...nodeRuns].sort((a, b) => (asNum(b.runNo) ?? 0) - (asNum(a.runNo) ?? 0)).map(r => {
                const no = asNum(r.runNo) ?? 0
                const failed = runErrorOf(r) !== ''
                const rOut = runOutputOf(r)
                const rExit = rOut.exitCode ?? runErrorExitCodeOf(r)
                const on = record != null && asNum(record.runNo) === no
                return (
                  <div
                    key={no}
                    className={`wf-inst-runrow${on ? ' wf-inst-runrow--on' : ''}`}
                    onClick={() => onSelectRun(no)}
                    title="点击查看该次运行的输入/输出全量"
                  >
                    <span className="wf-inst-badge">#{no}</span>
                    <span className="wf-inst-runrow-time">{formatClock(asNum(r.startedAt))}</span>
                    <span className="wf-inst-runrow-dur">{formatDuration(asNum(r.durationMs))}</span>
                    {failed ? (
                      <span className="wf-inst-badge wf-inst-badge--err">失败</span>
                    ) : rExit != null && rExit !== 0 ? (
                      <span className="wf-inst-badge wf-inst-badge--err">exit {rExit}</span>
                    ) : (
                      <span className="wf-inst-badge wf-inst-badge--ok">完成</span>
                    )}
                  </div>
                )
              })}
            </div>
          </div>
        </div>
      ) : null}
    </>
  )
}

// ─── 边详情（readonly 消费） ───────────────────────────────────────────────

function EdgeDetail({
  edge,
  definition,
  activated,
}: {
  edge: WorkflowEdgeDef
  definition: WorkflowDefinition | null
  activated: boolean
}): React.ReactElement {
  const nodes = definition?.nodes ?? []
  const nameOf = (id: string) => nodes.find(n => n.id === id)?.name || id
  return (
    <div className="wf-sidecard">
      <div className="wf-sidecard-head">
        <span className="wf-sidecard-title">{nameOf(edge.from)} → {nameOf(edge.to)}</span>
        {activated ? <span className="wf-inst-badge wf-inst-badge--ok">已激活</span> : null}
      </div>
      <div className="wf-sidecard-body">
        <div style={{ fontSize: 11.5, color: 'var(--text-muted, #6f7a8b)', lineHeight: 1.7 }}>
          <div>边 id：{edge.id}</div>
          <div>来源：{edge.from}</div>
          <div>目标：{edge.to}</div>
          <div>条件：{edge.condition?.type ?? 'always'}{conditionLabel(edge.condition) ? `（${conditionLabel(edge.condition)}）` : '（恒激活）'}</div>
        </div>
        <div className="wf-inst-hintcard">
          出边条件在源节点完成时求值一次；满足即激活（记入调度器，不落盘）。跳过决策的节点全部出边无条件激活。
        </div>
      </div>
    </div>
  )
}

// ─── 面板主体 ──────────────────────────────────────────────────────────────

export interface InstancePanelProps {
  instanceId: string
  /** 打开实例页时携带的工作区 id（预留：回开定义编辑器等入口）。 */
  workspaceId?: string
}

/** runs 记录先于对应事件落盘（completeNode/onNodeFailed 均先 saveRunRecord 后 appendEvent）——
 *  收到这些事件即可安全重拉 runLog 拿全量；supervisor.decision 会重写 runs 记录补介入链。 */
const RUNS_PERSISTED_EVENTS = new Set(['node.output', 'node.error', 'supervisor.decision'])

export default function InstancePanel({ instanceId, workspaceId }: InstancePanelProps) {
  void workspaceId // 预留参数（当前 UI 未消费）
  React.useEffect(ensureInstanceStyles, [])
  const ui = getPluginContext().ui
  const toast = React.useCallback(
    (message: string, type?: 'success' | 'error' | 'info' | 'warning') => {
      try {
        ui.showToast(message, type)
      } catch {
        // 兜底静默
      }
    },
    [ui],
  )

  const [meta, setMeta] = React.useState<WorkflowInstanceMeta | null>(null)
  const [runs, setRuns] = React.useState<WorkflowRunRecord[]>([])
  const [events, setEvents] = React.useState<WorkflowInstanceEvent[]>([])
  const [metaLoading, setMetaLoading] = React.useState(true)
  const [loadError, setLoadError] = React.useState<string | null>(null)
  const [runsError, setRunsError] = React.useState<string | null>(null)
  const [reloadNonce, setReloadNonce] = React.useState(0)
  const [selection, setSelection] = React.useState<CanvasSelection>(null)
  const [detailRunNo, setDetailRunNo] = React.useState<number | null>(null)
  const [view, setView] = React.useState<'canvas' | 'log'>('canvas')
  // ── 移动端（≤768px）：节点/边详情面板为底部抽屉，点选自动滑出。
  const isMobile = useIsMobile()
  const [sheetOpen, setSheetOpen] = React.useState(false)
  const [pollState, setPollState] = React.useState<WorkflowEventsPollState | null>(null)
  const [deciding, setDeciding] = React.useState<string | null>(null)
  const [decideError, setDecideError] = React.useState<string | null>(null)
  const [decisionInput, setDecisionInput] = React.useState('')
  const [withInputOpen, setWithInputOpen] = React.useState(false)
  const [actionBusy, setActionBusy] = React.useState<'cancel' | 'delete' | null>(null)
  const [deleted, setDeleted] = React.useState(false)
  const [, setTick] = React.useState(0)

  // ── 全量拉取（meta + runLog 并行；局部失败分别落 error 态）。
  const loadAll = React.useCallback(async () => {
    if (!instanceId) return
    setMetaLoading(true)
    const [metaRes, runsRes] = await Promise.allSettled([
      workflowGateway.instanceGet(instanceId),
      workflowGateway.instanceRunLog(instanceId),
    ])
    if (metaRes.status === 'fulfilled') {
      setMeta(metaRes.value)
      setLoadError(null)
    } else {
      setLoadError(rpcErrorMessage(metaRes.reason))
    }
    if (runsRes.status === 'fulfilled') {
      setRuns(runsRes.value.runs)
      setRunsError(null)
    } else {
      setRunsError(rpcErrorMessage(runsRes.reason))
    }
    setMetaLoading(false)
  }, [instanceId])

  React.useEffect(() => {
    void loadAll()
  }, [loadAll, reloadNonce])

  // ── 事件驱动的防抖重拉（runs 落盘先于事件 / 状态迁移 / 终态收口 → 450ms 后刷 meta+runs）。
  const refreshTimerRef = React.useRef<ReturnType<typeof setTimeout> | null>(null)
  const scheduleRefresh = React.useCallback(() => {
    if (refreshTimerRef.current != null) return
    refreshTimerRef.current = setTimeout(() => {
      refreshTimerRef.current = null
      void loadAll()
    }, 450)
  }, [loadAll])

  // ── 事件流订阅（afterSeq='0' 全量回放 + 实时增量；终态/删除自动停）。
  React.useEffect(() => {
    if (!instanceId) return
    const handle = subscribeInstanceEvents(
      instanceId,
      batch => {
        if (batch.length === 0) return
        setEvents(prev => prev.concat(batch))
        if (batch.some(e => RUNS_PERSISTED_EVENTS.has(e.event)) || batch.some(isTerminalInstanceEvent)) {
          scheduleRefresh()
        }
      },
      { onState: setPollState },
    )
    return () => {
      handle.close()
    }
  }, [instanceId, scheduleRefresh])

  React.useEffect(
    () => () => {
      if (refreshTimerRef.current != null) clearTimeout(refreshTimerRef.current)
    },
    [],
  )

  // ── 派生态。
  const status = React.useMemo(() => deriveInstanceStatus(events, meta?.status), [events, meta?.status])
  const active = status != null && instanceActive(status)
  const definition: WorkflowDefinition | null = meta?.definitionSnapshot ?? null
  const projection = React.useMemo(
    () => mergeTerminalNodeStates(projectNodesFromEvents(events), meta?.nodeStates),
    [events, meta?.nodeStates],
  )

  const nodeNameById = React.useMemo(() => {
    const m: Record<string, string> = {}
    for (const n of definition?.nodes ?? []) m[n.id] = n.name || n.id
    for (const [id, p] of Object.entries(projection)) m[id] = m[id] || (p.nodeName !== '' ? p.nodeName : id)
    return m
  }, [definition, projection])

  const edgeLabelById = React.useMemo(() => {
    const m = new Map<string, string>()
    for (const e of definition?.edges ?? []) {
      m.set(e.id, `${nodeNameById[e.from] ?? e.from} → ${nodeNameById[e.to] ?? e.to}`)
    }
    return m
  }, [definition, nodeNameById])

  const activatedEdgeIds = React.useMemo(() => {
    const s = new Set<string>()
    for (const p of Object.values(projection)) {
      for (const r of p.runs) for (const id of r.activatedEdges) s.add(id)
    }
    return s
  }, [projection])
  const skippedNodeIds = React.useMemo(
    () => new Set(Object.entries(projection).filter(([, p]) => p.status === 'skipped').map(([id]) => id)),
    [projection],
  )

  const canvasNodes: CanvasNodeView[] = React.useMemo(
    () =>
      (definition?.nodes ?? []).map((n, i) => {
        const pos = nodePos(n, i)
        return {
          id: n.id,
          type: n.type,
          name: n.name,
          x: pos.x,
          y: pos.y,
          subtitle: canvasNodeSubtitle(n, projection[n.id]),
          status: projection[n.id]?.status ?? undefined,
        }
      }),
    [definition, projection],
  )
  const canvasEdges: CanvasEdgeView[] = React.useMemo(
    () =>
      (definition?.edges ?? []).map(e => ({
        id: e.id,
        from: e.from,
        to: e.to,
        conditionLabel: conditionLabel(e.condition),
        // 激活得绿：node.output 事件的 activatedEdges；skip 决策激活全部出边（事件无清单，按源节点补）。
        status: activatedEdgeIds.has(e.id) || skippedNodeIds.has(e.from) ? 'done' : undefined,
      })),
    [definition, activatedEdgeIds, skippedNodeIds],
  )

  const pending = React.useMemo(() => {
    const fromMeta = parsePendingDecision(meta?.pendingDecision)
    if (fromMeta) return fromMeta
    if (status === 'waiting-user') {
      for (let i = events.length - 1; i >= 0; i -= 1) {
        const pd = pendingDecisionFromEvent(events[i])
        if (pd) return pd
      }
    }
    return null
  }, [meta?.pendingDecision, status, events])

  const pendingNodeName = pending
    ? definition?.nodes?.find(n => n.id === pending.nodeId)?.name || nodeNameById[pending.nodeId] || pending.nodeId
    : ''

  const selectedNode = selection?.kind === 'node' ? (definition?.nodes ?? []).find(n => n.id === selection.id) ?? null : null
  const selectedEdge = selection?.kind === 'edge' ? (definition?.edges ?? []).find(e => e.id === selection.id) ?? null : null

  // ── 选中入口（画布点选）：空白点按不清选——详情面板保留最近选中，关闭只走
  // closeDetail；移动端空白 tap 仍收起抽屉（选中保留，再点节点重开）。
  const handleSelect = React.useCallback((sel: CanvasSelection) => {
    if (sel !== null) setSelection(sel)
    setSheetOpen(sel !== null)
  }, [])

  /** 关闭详情（面板 × 按钮）：唯一清选入口。 */
  const closeDetail = React.useCallback(() => {
    setSelection(null)
    setSheetOpen(false)
  }, [])

  const selectedNodeRuns = React.useMemo(
    () => runs.filter(r => asStr(r.nodeId) === selectedNode?.id),
    [runs, selectedNode?.id],
  )
  React.useEffect(() => {
    setDetailRunNo(null)
  }, [selection?.kind, selection?.id])

  // ── 运行中 1s tick（仅刷「已运行」时长显示）。
  React.useEffect(() => {
    if (!active) return
    const t = setInterval(() => setTick(x => x + 1), 1000)
    return () => clearInterval(t)
  }, [active])

  // ── 决策提交。
  const submitDecision = async (decision: 'retry' | 'skip' | 'fail' | 'retryWithInput') => {
    if (!pending || deciding !== null) return
    if (decision === 'retryWithInput' && decisionInput.trim() === '') {
      setDecideError('retryWithInput 需要输入新内容')
      return
    }
    setDeciding(decision)
    setDecideError(null)
    try {
      await workflowGateway.instanceDecide(
        instanceId,
        pending.runNo,
        decision,
        decision === 'retryWithInput' ? decisionInput : undefined,
      )
      setDecisionInput('')
      setWithInputOpen(false)
      toast('决策已提交，实例恢复运行', 'success')
      void loadAll()
    } catch (err) {
      setDecideError(rpcErrorMessage(err))
    } finally {
      setDeciding(null)
    }
  }

  // ── 取消 / 删除。
  const onCancel = async () => {
    if (actionBusy !== null) return
    const ok = await confirmDialog(
      '取消工作流实例',
      `将中断「${meta?.definitionName ?? instanceId}」（${instanceId}）运行中的节点线程，实例转入已取消。`,
      '取消实例',
    )
    if (!ok) return
    setActionBusy('cancel')
    try {
      await workflowGateway.instanceCancel(instanceId)
      toast('取消请求已发出', 'success')
    } catch (err) {
      toast(`取消失败：${rpcErrorMessage(err)}`, 'error')
    } finally {
      setActionBusy(null)
      scheduleRefresh()
    }
  }

  const onDelete = async () => {
    if (actionBusy !== null) return
    const ok = await confirmDialog(
      '删除工作流实例',
      `将删除实例 ${instanceId}（含事件日志/运行记录/agent 会话）。此操作不可撤销。`,
      '删除',
    )
    if (!ok) return
    setActionBusy('delete')
    try {
      await workflowGateway.instanceDelete(instanceId)
      toast('实例已删除', 'success')
      setDeleted(true)
    } catch (err) {
      toast(`删除失败：${rpcErrorMessage(err)}`, 'error')
    } finally {
      setActionBusy(null)
    }
  }

  // ── 渲染分支：参数缺失 / 加载 / 错误 / 已删除。
  if (!instanceId) {
    return (
      <div className="wf-inst-root">
        <div className="wf-inst-center"><span>缺少 instanceId（打开实例页时未携带参数）</span></div>
      </div>
    )
  }
  if (deleted || pollState?.phase === 'stopped' && pollState.reason === 'not-found') {
    return (
      <div className="wf-inst-root">
        <div className="wf-inst-center">
          <span>实例 {instanceId} 已删除。</span>
          <span style={{ fontSize: 12, color: 'var(--text-faint, #525c6a)' }}>关闭本标签页可回到侧边栏列表。</span>
        </div>
      </div>
    )
  }
  if (metaLoading && meta === null && events.length === 0) {
    return (
      <div className="wf-inst-root">
        <div className="wf-inst-center"><span>正在加载实例…</span></div>
      </div>
    )
  }
  if (loadError && meta === null) {
    return (
      <div className="wf-inst-root">
        <div className="wf-inst-center">
          <span>加载失败：{loadError}</span>
          <button type="button" className="wf-inst-btn" onClick={() => setReloadNonce(n => n + 1)}>重试</button>
        </div>
      </div>
    )
  }

  const visual = status ? instanceStatusVisual(status) : statusVisual(null)
  const varsText = variablesSummary(meta?.variables)
  const timeText = active
    ? meta?.startedAt
      ? `已运行 ${elapsedText(meta.startedAt, meta.endedAt)}`
      : '等待启动'
    : meta?.durationMs != null
      ? `耗时 ${formatDuration(meta.durationMs)}`
      : meta?.endedAt != null && meta?.startedAt != null
        ? `耗时 ${elapsedText(meta.startedAt, meta.endedAt)}`
        : ''
  const pollChip =
    pollState?.phase === 'stopped' && pollState.reason === 'not-found' ? (
      <span className="wf-inst-chip wf-inst-chip--warn">实例已不存在</span>
    ) : null

  return (
    <div className="wf-inst-root">
      {/* ── 顶栏 ── */}
      <div className="wf-inst-top">
        <span
          className={`wf-inst-dot${status === 'running' ? ' wf-inst-dot--pulse' : ''}`}
          style={{ background: visual.badgeFill }}
        />
        <span className="wf-inst-name" title={`${meta?.definitionName ?? ''} · ${instanceId}`}>
          {meta?.definitionName || meta?.definitionId || instanceId}
        </span>
        <span className="wf-inst-status" style={{ color: visual.stroke }}>
          {status ? instanceStatusLabel(status) : '…'}
        </span>
        {varsText ? (
          <span className="wf-inst-vars" title={variablesSummary(meta?.variables, 2000)}>变量 {varsText}</span>
        ) : null}
        {timeText ? <span className="wf-inst-time">{timeText}</span> : null}
        {pollChip}
        <span className="wf-inst-spacer" />
        <button
          type="button"
          className={`wf-inst-toggle${view === 'canvas' ? ' wf-inst-toggle--on' : ''}`}
          onClick={() => setView('canvas')}
          title="只读画布：节点状态着色 + 输出预览"
        >
          <WorkflowIcon size={12} /> 运行图
        </button>
        <button
          type="button"
          className={`wf-inst-toggle${view === 'log' ? ' wf-inst-toggle--on' : ''}`}
          onClick={() => setView('log')}
          title="物流式运行时间线（runs + events）"
        >
          <LogIcon size={12} /> 运行日志
        </button>
        {active ? (
          <button
            type="button"
            className="wf-inst-btn wf-inst-btn--danger"
            disabled={actionBusy !== null}
            title="中断运行中的节点线程，实例转入已取消"
            onClick={() => void onCancel()}
          >
            <StopIcon size={12} /> {actionBusy === 'cancel' ? '取消中…' : '取消实例'}
          </button>
        ) : (
          <button
            type="button"
            className="wf-inst-btn wf-inst-btn--danger"
            disabled={actionBusy !== null}
            title="删除整个实例目录（含事件日志/运行记录/agent 会话）"
            onClick={() => void onDelete()}
          >
            <DeleteIcon size={12} /> {actionBusy === 'delete' ? '删除中…' : '删除实例'}
          </button>
        )}
      </div>

      {/* ── waiting-user 决策横幅 ── */}
      {pending ? (
        <DecisionBanner
          pending={pending}
          nodeName={pendingNodeName}
          deciding={deciding}
          error={decideError}
          decisionInput={decisionInput}
          onInputChange={setDecisionInput}
          withInputOpen={withInputOpen}
          onToggleInput={setWithInputOpen}
          onDecide={d => void submitDecision(d)}
        />
      ) : null}

      {/* ── 主体：运行图（画布+详情） / 运行日志 ── */}
      <div className="wf-inst-body">
        {view === 'log' ? (
          <RunLogPanel
            meta={meta}
            runs={runs}
            events={events}
            loading={metaLoading}
            loadError={runsError}
            onReload={() => setReloadNonce(n => n + 1)}
          />
        ) : (
          <>
            <div className="wf-inst-canvas-wrap">
              <WorkflowCanvas
                nodes={canvasNodes}
                edges={canvasEdges}
                mode="readonly"
                selection={selection}
                onSelect={handleSelect}
                style={{ position: 'absolute', inset: 0 }}
              />
            </div>
            {/* 详情面板按需出现：仅在选中节点/边后挂载（无选中不占位、画布占满，
                关闭即卸载）；空白点按不清选（桌面面板保留、移动端抽屉收起但内容保留，
                抽屉显隐由 transform 控制）。 */}
            {selectedNode || selectedEdge ? (
              <div
                className={`wf-inst-side${isMobile && sheetOpen ? ' wf-inst-side--open' : ''}`}
                style={isMobile && !sheetOpen ? { pointerEvents: 'none' } : undefined}
              >
                <button
                  type="button"
                  className="wf-inst-side-close"
                  title="关闭详情"
                  onClick={closeDetail}
                >
                  <CloseIcon size={16} />
                </button>
                {selectedNode ? (
                  <NodeDetail
                    node={selectedNode}
                    proj={projection[selectedNode.id] ?? null}
                    nodeRuns={selectedNodeRuns}
                    edgeLabelById={edgeLabelById}
                    detailRunNo={detailRunNo}
                    onSelectRun={setDetailRunNo}
                  />
                ) : selectedEdge ? (
                  <EdgeDetail edge={selectedEdge} definition={definition} activated={activatedEdgeIds.has(selectedEdge.id)} />
                ) : null}
              </div>
            ) : null}
          </>
        )}
      </div>
    </div>
  )
}

/*
 * ── 人工验证清单（步骤 10 实例运行页）─────────────────────────────────────
 * 1. 侧边栏实例列表点击实例（或运行对话框启动后自动打开）：实例页加载出画布
 *    （位置与定义一致）、顶栏状态色点/定义名/变量摘要/时长正确。
 * 2. 运行中实例：节点随事件实时着色（start→绿、命令/AI 蓝→绿、失败红、跳过灰、
 *    等待决策黄），运行中节点描边呼吸动画；已激活出边转绿（skip 决策节点全部出边绿）。
 * 3. 节点副标题：无输出时显示「运行中…/失败原因/状态」；产出后显示最新输出首行摘要。
 * 4. 点击节点：右侧详情面板显示节点信息、输入三视图（模板/渲染后胶囊富文本/AI 所见）、
 *    输出全量（命令 stdout/stderr 分块+exit 徽标；AI text 全文）、错误红块、agent
 *    会话轮与 tokens 用量、激活出边 chips、历次 run 记录列表（点击切换查看对应全量）。
 * 5. 点击边：显示 from/to/条件类型与激活状态；点空白处取消选中回提示卡。
 * 6. askUser 策略实例：顶栏下出现琥珀决策横幅（question/context/节点与 runNo）；
 *    「重试/跳过/终止」直接提交，「换输入重试」展开 textarea（留空不可提交）；
 *    提交后横幅消失、实例回运行中、节点继续推进；后端拒绝（runNo 不匹配等）时红字回显。
 * 7. 取消（运行中顶栏按钮）：confirm → 状态转已取消、事件流停止（顶栏无重连提示）；
 *    终态实例顶栏显示「删除实例」，删除后页面转「实例已删除」占位。
 * 8. 断网/重启 worker：宿主全局重连提示弹出；恢复后事件流自动续流（游标不丢事件，
 *    onResubscribed 触发拉取补洞），节点着色/顶栏状态继续推进。
 * 9. 切「运行日志」视图：见 RunLogPanel 尾清单；两视图共享同一事件流，切换无重拉闪烁。
 * 10. 终态历史实例回放：打开即全量投影（事件回放+nodeStates 补洞），进度与侧栏一致；
 *     顶栏无刷新按钮（meta/runs 由事件驱动防抖重拉，无手动入口）。
 *
 * ── 人工验证清单（移动端，视口 ≤768px）──────────────────────────────────
 * 11. 窄视口打开实例页：顶栏换行（变量摘要独占一行），画布占满全宽；单指拖动平移
 *     画布；tap 节点/边 → 底部抽屉滑出详情（× 收起），再 tap 其他节点切换内容。
 * 12. 决策横幅（askUser）：按钮触控目标可点，「换输入重试」textarea 聚焦不触发
 *     iOS 视口放大；提交后抽屉/横幅状态正确流转。
 * 13. 「运行日志」视图在窄屏可读（时间线卡片换行、pre 块可滚动）。
 */
