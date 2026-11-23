/**
 * 工作流侧边栏面板（步骤 9 主体）：上=实例列表、下=定义列表。
 *
 * - 顶部工具条：工作区选择下拉（ctx.sdk.workspace.list 注册表；缺省「全部」——
 *   实例/定义按所选工作区服务端过滤，列表行仍带 workspaceId 归属展示）。
 * - 实例区（可折叠）：instance.list 拉取（挂载 + 10s 定时 + sidebar-panel-shown /
 *   workspace-registry-changed 事件驱动刷新）；每项=状态色点（renderStates 的
 *   TaskStatusDot 色彩语言）+ 定义名 + 时间 + nodeStates 投影进度「n/m 节点完成」；
 *   点击 openTab 实例页；非终态实例显示取消按钮、全部实例可删（confirm；运行中
 *   删除由后端拒绝并把原因透传 toast）。
 * - 定义区（可折叠）：def.list；操作=编辑（openTab workflow-editor）/运行（RunDialog）/
 *   导出（下载 <name>.workflow.json）/删除（confirm）；「＋」新建（编辑器空定义）；
 *   「导入」选择文件 → def.import（落入当前所选工作区，校验失败逐项回显）。
 *
 * 刷新节流：实例 10s / 定义 30s 定时 + 事件驱动；行级操作（取消/删除/导入/导出）
 * 置 busy 标记防重入。confirm 走 ctx.ui.showDialog（宿主模态壳，缺席降级
 * window.confirm）；toast 走 ctx.ui.showToast。
 *
 * 纯逻辑可测核（无插件 web 测试基建，导出保持可测，人工清单见文件尾注释）：
 * formatTimestamp / instanceProgressText / exportFileName / parseImportedDefinition。
 */
import React from 'react'
import type { PluginWorkspaceEntry } from '@everyagent/plugin-api'
import { getPluginContext } from '../pluginRuntime'
import { WORKFLOW_EDITOR_TAB_TYPE, WORKFLOW_INSTANCE_TAB_TYPE } from '../tabKeys'
import { statusVisual, type RenderStatus, type StatusVisual } from '../canvas/renderStates'
import {
  WorkflowValidationError,
  rpcErrorMessage,
  workflowGateway,
  type WorkflowDefSummary,
  type WorkflowDefinition,
  type WorkflowInstanceStatus,
  type WorkflowInstanceSummary,
} from '../workflowGateway'
import { openRunDialog } from './RunDialog'
import {
  ChevronDownIcon,
  ChevronRightIcon,
  DeleteIcon,
  DownloadIcon,
  EditIcon,
  PlusIcon,
  RefreshIcon,
  RunIcon,
  StopIcon,
  UploadIcon,
} from '../icons'

// ─── 常量与映射 ────────────────────────────────────────────────────────────

/** 实例列表定时刷新间隔（定义列表=3 倍周期；事件驱动刷新另计）。 */
const INSTANCE_REFRESH_MS = 10_000
const DEF_REFRESH_MS = 30_000

/** 实例状态 → renderStates 的 RenderStatus（复用 TaskStatusDot 色彩语言）。 */
const INSTANCE_STATUS_RENDER: Record<WorkflowInstanceStatus, RenderStatus> = {
  created: 'created',
  running: 'running',
  'waiting-user': 'waiting',
  done: 'done',
  failed: 'failed',
  cancelled: 'skipped',
}

const INSTANCE_STATUS_LABEL: Record<WorkflowInstanceStatus, string> = {
  created: '已创建',
  running: '运行中',
  'waiting-user': '等待用户',
  done: '已完成',
  failed: '失败',
  cancelled: '已取消',
}

/** 非终态实例（可取消：engine.cancel 中断节点线程/挂起决策）。 */
function instanceActive(status: string): boolean {
  return status === 'created' || status === 'running' || status === 'waiting-user'
}

export function instanceStatusVisual(status: string): StatusVisual {
  const mapped = (INSTANCE_STATUS_RENDER as Record<string, RenderStatus>)[status]
  return statusVisual(mapped)
}

export function instanceStatusLabel(status: string): string {
  return (INSTANCE_STATUS_LABEL as Record<string, string>)[status] ?? status
}

// ─── 纯逻辑（可测核） ──────────────────────────────────────────────────────

/** 时间戳 → 侧栏短文案（同年 MM-DD HH:mm，跨年补年份；非法/0 返回空串）。 */
export function formatTimestamp(ms?: number | null): string {
  if (typeof ms !== 'number' || !Number.isFinite(ms) || ms <= 0) return ''
  const d = new Date(ms)
  const pad = (n: number) => String(n).padStart(2, '0')
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`
  const sameYear = d.getFullYear() === new Date().getFullYear()
  return sameYear
    ? `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${hm}`
    : `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${hm}`
}

/**
 * 实例进度文案：nodeStates 投影「n/m 节点完成」（n=status==='done' 计数，m=投影
 * 总节点数；运行中实例 nodeStates=null → 空串）。skipped 节点不计完成（口径保守）。
 */
export function instanceProgressText(
  nodeStates?: Record<string, { runCount?: number; status?: string; lastRunNo?: number }> | null,
): string {
  if (!nodeStates) return ''
  const entries = Object.values(nodeStates)
  if (entries.length === 0) return ''
  const done = entries.filter(s => s?.status === 'done').length
  return `${done}/${entries.length} 节点完成`
}

/** 定义名 → 导出文件名（`<name>.workflow.json`；剥文件系统非法字符）。 */
export function exportFileName(name: string): string {
  const base = (name ?? '')
    .replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_')
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/[.\s]+$/g, '')
  return `${base || 'workflow'}.workflow.json`
}

/** 导出定义 → 触发浏览器下载（Blob + URL.createObjectURL + a 标签）。 */
export function downloadDefinitionJson(def: WorkflowDefinition): void {
  if (typeof document === 'undefined') return
  const blob = new Blob([JSON.stringify(def, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = exportFileName(def.name ?? '')
  document.body.appendChild(a)
  a.click()
  a.remove()
  // 稍延 revoke：Safari 等浏览器同步 revoke 会截断下载。
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

/**
 * 导入文件文本 → 定义对象（快速形态预检：JSON 可解析、是对象、name 非空、
 * nodes/edges 是数组）。完整校验由后端 def.import 做（失败逐项回显）。
 */
export function parseImportedDefinition(text: string): WorkflowDefinition {
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  } catch (err) {
    throw new Error(`文件不是合法 JSON：${rpcErrorMessage(err)}`)
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('导入内容必须是 JSON 对象（工作流定义）')
  }
  const obj = parsed as WorkflowDefinition
  if (typeof obj.name !== 'string' || obj.name.trim() === '') {
    throw new Error('定义缺少 name 字段（或为空）')
  }
  if (obj.nodes != null && !Array.isArray(obj.nodes)) throw new Error('定义的 nodes 必须是数组')
  if (obj.edges != null && !Array.isArray(obj.edges)) throw new Error('定义的 edges 必须是数组')
  return obj
}

/** 工作区注册表条目 → 下拉/归属标签（root 的 basename；缺省退 id）。 */
function workspaceEntryLabel(entry: PluginWorkspaceEntry): string {
  const root = entry.root ?? ''
  const base = root.split(/[\\/]/).filter(p => p !== '').pop()
  return base || entry.id || root
}

// ─── 样式 ──────────────────────────────────────────────────────────────────

const SIDEBAR_STYLE_ID = 'wf-sidebar-styles'

function ensureSidebarStyles(): void {
  if (typeof document === 'undefined' || document.getElementById(SIDEBAR_STYLE_ID)) return
  const style = document.createElement('style')
  style.id = SIDEBAR_STYLE_ID
  style.textContent = `
.wf-side-root { display: flex; flex-direction: column; height: 100%; min-height: 0; background: var(--bg-secondary, #11141b); color: var(--text-primary, #eef1f5); font-size: 13px; }
.wf-side-header { display: flex; align-items: center; gap: 6px; padding: 10px 12px 6px; }
.wf-side-title { font-weight: 600; flex: 1; }
.wf-side-toolbar { display: flex; align-items: center; gap: 6px; padding: 0 12px 8px; border-bottom: 1px solid var(--border, #2a303b); }
.wf-side-toolbar label { font-size: 11.5px; color: var(--text-muted, #6f7a8b); flex: none; }
.wf-side-select { flex: 1; min-width: 0; background: var(--bg-primary, #0b0d12); border: 1px solid var(--border, #2a303b); border-radius: 6px; color: var(--text-primary, #eef1f5); padding: 3px 6px; font-size: 12px; font-family: inherit; }
.wf-side-scroll { flex: 1; min-height: 0; overflow-y: auto; padding: 8px 10px 12px; display: flex; flex-direction: column; gap: 10px; }
.wf-side-section { border: 1px solid var(--border-light, #1b202a); border-radius: 8px; background: var(--bg-tertiary, #171b24); overflow: hidden; }
.wf-side-section-head { display: flex; align-items: center; gap: 6px; width: 100%; padding: 7px 10px; background: transparent; border: none; color: var(--text-secondary, #aab3c2); font-size: 12.5px; cursor: pointer; text-align: left; }
.wf-side-section-head:hover { color: var(--text-primary, #eef1f5); }
.wf-side-section-head .wf-side-count { color: var(--text-faint, #525c6a); font-size: 11.5px; }
.wf-side-section-tools { margin-left: auto; display: inline-flex; gap: 2px; }
.wf-side-section-body { padding: 2px 6px 8px; display: flex; flex-direction: column; gap: 4px; }
.wf-side-item { border: 1px solid transparent; border-radius: 8px; padding: 6px 8px; display: flex; flex-direction: column; gap: 3px; cursor: pointer; }
.wf-side-item:hover { background: var(--bg-hover, #202631); border-color: var(--border, #2a303b); }
.wf-side-item-title { display: flex; align-items: center; gap: 6px; min-width: 0; }
.wf-side-item-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 500; }
.wf-side-item-sub { display: flex; align-items: center; gap: 6px; font-size: 11px; color: var(--text-muted, #6f7a8b); flex-wrap: wrap; }
.wf-side-item-actions { display: flex; gap: 2px; margin-top: 1px; }
.wf-side-dot { width: 8px; height: 8px; border-radius: 50%; flex: none; }
.wf-side-dot--pulse { animation: wf-side-pulse 1.6s ease-in-out infinite; }
@keyframes wf-side-pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.35; } }
.wf-side-chip { display: inline-flex; align-items: center; border: 1px solid var(--border, #2a303b); border-radius: 999px; padding: 0 6px; font-size: 10.5px; color: var(--text-muted, #6f7a8b); max-width: 110px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wf-side-chip--warn { color: var(--accent-amber, #f7bd54); border-color: var(--accent-amber, #f7bd54); }
.wf-side-status-label { font-size: 10.5px; color: var(--text-muted, #6f7a8b); }
.wf-side-error { color: var(--accent-red, #ff6b6b); font-size: 11.5px; margin: 4px 4px 0; line-height: 1.5; }
.wf-side-error ul { margin: 2px 0 0; padding-left: 16px; }
.wf-side-hint { color: var(--text-faint, #525c6a); font-size: 11.5px; padding: 4px 6px; }
.wf-side-empty { color: var(--text-faint, #525c6a); font-size: 11.5px; padding: 6px 8px; }
.wf-side-btn { display: inline-flex; align-items: center; justify-content: center; gap: 4px; padding: 3px 8px; border: 1px solid var(--border-strong, #3b4658); border-radius: 6px; background: transparent; color: var(--text-secondary, #aab3c2); cursor: pointer; font-size: 11.5px; white-space: nowrap; }
.wf-side-btn:hover { color: var(--text-primary, #eef1f5); border-color: var(--accent-blue, #61a5ff); }
.wf-side-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.wf-side-btn--danger:hover { color: var(--accent-red, #ff6b6b); border-color: var(--accent-red, #ff6b6b); }
.wf-side-icon-btn { display: inline-flex; align-items: center; justify-content: center; width: 22px; height: 22px; border: none; border-radius: 6px; background: transparent; color: var(--text-muted, #6f7a8b); cursor: pointer; flex: none; }
.wf-side-icon-btn:hover { color: var(--accent-blue, #61a5ff); background: var(--bg-hover, #202631); }
.wf-side-icon-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.wf-side-icon-btn--danger:hover { color: var(--accent-red, #ff6b6b); }
.wf-side-confirm { display: flex; flex-direction: column; gap: 12px; min-width: min(320px, 80vw); font-size: 13px; }
.wf-side-confirm-footer { display: flex; justify-content: flex-end; gap: 8px; }
.wf-side-btn--danger-solid { background: var(--accent-red, #ff6b6b); border-color: var(--accent-red, #ff6b6b); color: #0b0d12; font-weight: 600; }
.wf-side-btn--danger-solid:hover { color: #0b0d12; }
`
  document.head.appendChild(style)
}

// ─── 确认对话框（宿主模态壳；缺席时降级 window.confirm） ────────────────────

function confirmDialog(title: string, description: string, okText = '确认'): Promise<boolean> {
  const ctx = getPluginContext()
  if (typeof ctx.ui.showDialog !== 'function') {
    return Promise.resolve(window.confirm(`${title}\n\n${description}`))
  }
  return ctx.ui
    .showDialog<boolean>({
      title,
      description,
      Content: ({ close }) => (
        <div className="wf-side-confirm">
          <p style={{ margin: 0, color: 'var(--text-secondary, #aab3c2)', lineHeight: 1.7 }}>{description}</p>
          <div className="wf-side-confirm-footer">
            <button type="button" className="wf-side-btn" onClick={() => close(false)}>
              取消
            </button>
            <button type="button" className="wf-side-btn wf-side-btn--danger-solid" onClick={() => close(true)}>
              {okText}
            </button>
          </div>
        </div>
      ),
    })
    .then(r => r === true)
}

// ─── 行组件 ────────────────────────────────────────────────────────────────

function InstanceRow({
  inst,
  wsLabel,
  busy,
  onOpen,
  onCancel,
  onDelete,
}: {
  inst: WorkflowInstanceSummary
  wsLabel: string
  busy: boolean
  onOpen: () => void
  onCancel: () => void
  onDelete: () => void
}): React.ReactElement {
  const visual = instanceStatusVisual(inst.status)
  const active = instanceActive(inst.status)
  const progress = instanceProgressText(inst.nodeStates)
  return (
    <div className="wf-side-item" onClick={onOpen} title={`实例 ${inst.id}\n点击打开实例运行页`}>
      <div className="wf-side-item-title">
        <span
          className={`wf-side-dot${inst.status === 'running' ? ' wf-side-dot--pulse' : ''}`}
          style={{ background: visual.badgeFill }}
        />
        <span className="wf-side-item-name">{inst.definitionName || inst.definitionId}</span>
        <span className="wf-side-status-label" style={{ color: visual.stroke }}>
          {instanceStatusLabel(inst.status)}
        </span>
      </div>
      <div className="wf-side-item-sub">
        <span title={`创建于 ${new Date(inst.createdAt).toLocaleString()}`}>{formatTimestamp(inst.createdAt)}</span>
        {inst.durationMs ? <span>耗时 {Math.round(inst.durationMs / 1000)}s</span> : null}
        {progress ? <span>{progress}</span> : null}
        {wsLabel ? <span className="wf-side-chip" title={inst.workspaceId}>{wsLabel}</span> : null}
        {inst.workspaceMissing ? (
          <span className="wf-side-chip wf-side-chip--warn" title="工作区未注册（数据保留，重新注册后自动回归）">
            ⚠ 工作区缺失
          </span>
        ) : null}
      </div>
      {inst.error && typeof inst.error === 'object' && (inst.error as { message?: unknown }).message ? (
        <div className="wf-side-item-sub" style={{ color: 'var(--accent-red, #ff6b6b)' }} title="实例失败原因">
          ✕ {String((inst.error as { message?: unknown }).message).slice(0, 80)}
        </div>
      ) : null}
      <div className="wf-side-item-actions" onClick={e => e.stopPropagation()}>
        {active ? (
          <button
            type="button"
            className="wf-side-btn"
            disabled={busy}
            title="取消该实例（中断运行中的节点线程）"
            onClick={onCancel}
          >
            <StopIcon size={12} /> 取消
          </button>
        ) : null}
        <button
          type="button"
          className="wf-side-btn wf-side-btn--danger"
          disabled={busy}
          title={active ? '删除实例（运行中会被后端拒绝，需先取消）' : '删除该实例（含事件日志与运行记录）'}
          onClick={onDelete}
        >
          <DeleteIcon size={12} /> 删除
        </button>
      </div>
    </div>
  )
}

function DefinitionRow({
  def,
  wsLabel,
  busy,
  onEdit,
  onRun,
  onExport,
  onDelete,
}: {
  def: WorkflowDefSummary
  wsLabel: string
  busy: boolean
  onEdit: () => void
  onRun: () => void
  onExport: () => void
  onDelete: () => void
}): React.ReactElement {
  return (
    <div className="wf-side-item" onClick={onEdit} title={`定义 ${def.id}\n点击打开编辑器`}>
      <div className="wf-side-item-title">
        <span className="wf-side-item-name">{def.name || def.id}</span>
      </div>
      {def.description ? (
        <div className="wf-side-item-sub" title={def.description}>
          <span
            style={{
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
              maxWidth: '100%',
            }}
          >
            {def.description}
          </span>
        </div>
      ) : null}
      <div className="wf-side-item-sub">
        <span title={`更新于 ${new Date(def.updatedAt).toLocaleString()}`}>{formatTimestamp(def.updatedAt)}</span>
        <span>{def.nodeCount} 节点</span>
        {wsLabel ? <span className="wf-side-chip" title={def.workspaceId}>{wsLabel}</span> : null}
        {def.workspaceMissing ? (
          <span className="wf-side-chip wf-side-chip--warn" title="工作区未注册（数据保留，重新注册后自动回归）">
            ⚠ 工作区缺失
          </span>
        ) : null}
      </div>
      <div className="wf-side-item-actions" onClick={e => e.stopPropagation()}>
        <button type="button" className="wf-side-btn" disabled={busy} title="打开定义编辑器" onClick={onEdit}>
          <EditIcon size={12} /> 编辑
        </button>
        <button type="button" className="wf-side-btn" disabled={busy} title="运行该工作流" onClick={onRun}>
          <RunIcon size={12} /> 运行
        </button>
        <button
          type="button"
          className="wf-side-icon-btn"
          disabled={busy}
          title="导出为 .workflow.json 文件"
          onClick={onExport}
        >
          <DownloadIcon size={13} />
        </button>
        <button
          type="button"
          className="wf-side-icon-btn wf-side-icon-btn--danger"
          disabled={busy}
          title="删除定义（历史实例持快照不受影响）"
          onClick={onDelete}
        >
          <DeleteIcon size={13} />
        </button>
      </div>
    </div>
  )
}

// ─── 面板主体 ──────────────────────────────────────────────────────────────

interface WorkspaceOption {
  id: string
  label: string
}

export default function SidebarPanel() {
  const ctx = getPluginContext()
  React.useEffect(ensureSidebarStyles, [])

  const [workspaces, setWorkspaces] = React.useState<WorkspaceOption[]>([])
  const [selectedWs, setSelectedWs] = React.useState('')
  const [defs, setDefs] = React.useState<WorkflowDefSummary[] | null>(null)
  const [instances, setInstances] = React.useState<WorkflowInstanceSummary[] | null>(null)
  const [defsError, setDefsError] = React.useState<string | null>(null)
  const [instancesError, setInstancesError] = React.useState<string | null>(null)
  const [importError, setImportError] = React.useState<string[] | null>(null)
  const [showInstances, setShowInstances] = React.useState(true)
  const [showDefs, setShowDefs] = React.useState(true)
  /** 行级/动作级 busy 标记（'import' / `def:<id>` / `inst:<id>`）。 */
  const [busyKey, setBusyKey] = React.useState<string | null>(null)
  const fileInputRef = React.useRef<HTMLInputElement | null>(null)

  const toast = React.useCallback(
    (message: string, type?: 'success' | 'error' | 'info' | 'warning') => {
      try {
        ctx.ui.showToast(message, type)
      } catch {
        // showToast 理论不抛（宿主已降级静态 message）；兜底静默。
      }
    },
    [ctx],
  )

  // ── 工作区下拉（注册表；id 缺失的条目无法作 workflow RPC 的 workspace 参数，跳过）。
  const reloadWorkspaces = React.useCallback(() => {
    void ctx.sdk.workspace
      .list()
      .then(list => {
        const opts = (list ?? [])
          .filter(e => e && typeof e.id === 'string' && e.id !== '')
          .map(e => ({ id: e.id as string, label: workspaceEntryLabel(e) }))
          .sort((a, b) => a.label.localeCompare(b.label))
        setWorkspaces(opts)
        // 当前选中已不在注册表（工作区被移除）→ 回落「全部」。
        setSelectedWs(cur => (cur === '' || opts.some(o => o.id === cur) ? cur : ''))
      })
      .catch(() => setWorkspaces([]))
  }, [ctx])

  // ── 列表拉取（selectedWs 空=不过滤=当前 worker 全部）。
  const reloadDefs = React.useCallback((ws: string) => {
    workflowGateway
      .defList(ws || undefined)
      .then(list => {
        setDefs(list)
        setDefsError(null)
      })
      .catch(err => setDefsError(rpcErrorMessage(err)))
  }, [])

  const reloadInstances = React.useCallback((ws: string) => {
    workflowGateway
      .instanceList(ws ? { workspace: ws } : undefined)
      .then(list => {
        setInstances(list)
        setInstancesError(null)
      })
      .catch(err => setInstancesError(rpcErrorMessage(err)))
  }, [])

  // ── 挂载/工作区切换：注册表 + 双列表首拉；registry 变化与面板再展示时重拉。
  React.useEffect(() => {
    reloadWorkspaces()
    reloadDefs(selectedWs)
    reloadInstances(selectedWs)
    const d1 = ctx.events.on('workspace-registry-changed', () => reloadWorkspaces())
    const d2 = ctx.events.on('sidebar-panel-shown', () => {
      reloadWorkspaces()
      reloadDefs(selectedWs)
      reloadInstances(selectedWs)
    })
    return () => {
      d1.dispose()
      d2.dispose()
    }
  }, [ctx, reloadWorkspaces, reloadDefs, reloadInstances, selectedWs])

  // ── 定时刷新（实例 10s / 定义 30s；实例页内的事件流长轮询在步骤 10 落地）。
  React.useEffect(() => {
    const inst = setInterval(() => reloadInstances(selectedWs), INSTANCE_REFRESH_MS)
    const def = setInterval(() => reloadDefs(selectedWs), DEF_REFRESH_MS)
    return () => {
      clearInterval(inst)
      clearInterval(def)
    }
  }, [selectedWs, reloadDefs, reloadInstances])

  // ── 工作区标签（「全部」视图下显示归属；选中具体工作区时省略）。
  const wsLabelById = React.useMemo(() => {
    const m = new Map<string, string>()
    for (const w of workspaces) m.set(w.id, w.label)
    return m
  }, [workspaces])
  const labelFor = (id: string): string => (selectedWs === '' ? (wsLabelById.get(id) ?? '') : '')

  // ── 动作 ──

  const runBusy = (key: string): boolean => busyKey === key
  const withBusy = async (key: string, fn: () => Promise<void>): Promise<void> => {
    if (busyKey !== null) return
    setBusyKey(key)
    try {
      await fn()
    } finally {
      setBusyKey(null)
    }
  }

  const onNewDefinition = () => {
    const ws = selectedWs || ctx.sdk.workspace.id
    ctx.ui.openTab(WORKFLOW_EDITOR_TAB_TYPE, { workspaceId: ws, definitionId: '' }, '工作流（新建）')
  }

  const onEditDef = (def: WorkflowDefSummary) => {
    ctx.ui.openTab(
      WORKFLOW_EDITOR_TAB_TYPE,
      { workspaceId: def.workspaceId, definitionId: def.id },
      def.name || def.id,
    )
  }

  const onRunDef = (def: WorkflowDefSummary) => {
    void openRunDialog(def.workspaceId, def.id, def.name).then(id => {
      if (id) reloadInstances(selectedWs)
    })
  }

  const onExportDef = (def: WorkflowDefSummary) => {
    void withBusy(`def:${def.id}`, async () => {
      try {
        const full = await workflowGateway.defExport(def.workspaceId, def.id)
        downloadDefinitionJson(full)
        toast(`已导出 ${exportFileName(def.name ?? '')}`, 'success')
      } catch (err) {
        toast(`导出失败：${rpcErrorMessage(err)}`, 'error')
      }
    })
  }

  const onDeleteDef = (def: WorkflowDefSummary) => {
    void withBusy(`def:${def.id}`, async () => {
      const ok = await confirmDialog(
        '删除工作流定义',
        `将删除「${def.name || def.id}」（${def.id}）。历史实例持定义快照、不受影响。此操作不可撤销。`,
        '删除',
      )
      if (!ok) return
      try {
        await workflowGateway.defDelete(def.workspaceId, def.id)
        toast('定义已删除', 'success')
      } catch (err) {
        toast(`删除失败：${rpcErrorMessage(err)}`, 'error')
      } finally {
        reloadDefs(selectedWs)
      }
    })
  }

  const onImportFile = (file: File) => {
    const ws = selectedWs || ctx.sdk.workspace.id
    void withBusy('import', async () => {
      try {
        const text = await file.text()
        const definition = parseImportedDefinition(text)
        const { id } = await workflowGateway.defImport(ws, definition)
        setImportError(null)
        toast(`已导入为 ${id}`, 'success')
      } catch (err) {
        if (err instanceof WorkflowValidationError) {
          setImportError(err.items.length > 0 ? err.items : [err.message])
        } else {
          setImportError([rpcErrorMessage(err)])
        }
      } finally {
        reloadDefs(selectedWs)
      }
    })
  }

  const onOpenInstance = (inst: WorkflowInstanceSummary) => {
    ctx.ui.openTab(
      WORKFLOW_INSTANCE_TAB_TYPE,
      { instanceId: inst.id, workspaceId: inst.workspaceId },
      inst.definitionName || inst.id,
    )
  }

  const onCancelInstance = (inst: WorkflowInstanceSummary) => {
    void withBusy(`inst:${inst.id}`, async () => {
      const ok = await confirmDialog(
        '取消工作流实例',
        `将中断「${inst.definitionName || inst.id}」（${inst.id}）运行中的节点线程，实例转入已取消。`,
        '取消实例',
      )
      if (!ok) return
      try {
        await workflowGateway.instanceCancel(inst.id)
        toast('取消请求已发出', 'success')
      } catch (err) {
        toast(`取消失败：${rpcErrorMessage(err)}`, 'error')
      } finally {
        reloadInstances(selectedWs)
      }
    })
  }

  const onDeleteInstance = (inst: WorkflowInstanceSummary) => {
    void withBusy(`inst:${inst.id}`, async () => {
      const ok = await confirmDialog(
        '删除工作流实例',
        `将删除实例 ${inst.id}（含事件日志/运行记录/agent 会话）。此操作不可撤销。`,
        '删除',
      )
      if (!ok) return
      try {
        await workflowGateway.instanceDelete(inst.id)
        toast('实例已删除', 'success')
      } catch (err) {
        toast(`删除失败：${rpcErrorMessage(err)}`, 'error')
      } finally {
        reloadInstances(selectedWs)
      }
    })
  }

  // ── 渲染 ──

  const runningCount = (instances ?? []).filter(i => instanceActive(i.status)).length

  return (
    <div className="wf-side-root">
      <div className="wf-side-header">
        <span className="wf-side-title">工作流</span>
        <button
          type="button"
          className="wf-side-icon-btn"
          title="立即刷新实例与定义列表"
          onClick={() => {
            reloadWorkspaces()
            reloadDefs(selectedWs)
            reloadInstances(selectedWs)
          }}
        >
          <RefreshIcon size={14} />
        </button>
      </div>

      <div className="wf-side-toolbar">
        <label htmlFor="wf-side-ws-select">工作区</label>
        <select
          id="wf-side-ws-select"
          className="wf-side-select"
          value={selectedWs}
          onChange={e => setSelectedWs(e.target.value)}
        >
          <option value="">全部工作区</option>
          {workspaces.map(w => (
            <option key={w.id} value={w.id}>
              {w.label}
            </option>
          ))}
        </select>
      </div>

      <div className="wf-side-scroll">
        {/* ── 上：实例列表（可折叠） ── */}
        <div className="wf-side-section">
          <button
            type="button"
            className="wf-side-section-head"
            onClick={() => setShowInstances(v => !v)}
            title={showInstances ? '折叠实例列表' : '展开实例列表'}
          >
            {showInstances ? <ChevronDownIcon size={13} /> : <ChevronRightIcon size={13} />}
            实例
            <span className="wf-side-count">
              {instances === null ? '…' : `${instances.length}${runningCount > 0 ? `（${runningCount} 活跃）` : ''}`}
            </span>
          </button>
          {showInstances ? (
            <div className="wf-side-section-body">
              {instancesError ? <div className="wf-side-error">实例列表加载失败：{instancesError}</div> : null}
              {instances === null && !instancesError ? <div className="wf-side-empty">正在加载实例…</div> : null}
              {instances !== null && instances.length === 0 && !instancesError ? (
                <div className="wf-side-empty">暂无运行实例——从下方定义列表点「运行」开始。</div>
              ) : null}
              {(instances ?? []).map(inst => (
                <InstanceRow
                  key={inst.id}
                  inst={inst}
                  wsLabel={labelFor(inst.workspaceId)}
                  busy={runBusy(`inst:${inst.id}`)}
                  onOpen={() => onOpenInstance(inst)}
                  onCancel={() => onCancelInstance(inst)}
                  onDelete={() => onDeleteInstance(inst)}
                />
              ))}
            </div>
          ) : null}
        </div>

        {/* ── 下：定义列表（可折叠；头部带 导入/＋ 新建） ── */}
        <div className="wf-side-section">
          <div className="wf-side-section-head" style={{ cursor: 'default' }}>
            <button
              type="button"
              className="wf-side-icon-btn"
              style={{ width: 16, height: 16 }}
              title={showDefs ? '折叠定义列表' : '展开定义列表'}
              onClick={() => setShowDefs(v => !v)}
            >
              {showDefs ? <ChevronDownIcon size={13} /> : <ChevronRightIcon size={13} />}
            </button>
            <span
              style={{ cursor: 'pointer', flex: 1, textAlign: 'left' }}
              onClick={() => setShowDefs(v => !v)}
            >
              定义
              <span className="wf-side-count">{defs === null ? '…' : ` ${defs.length}`}</span>
            </span>
            <span className="wf-side-section-tools">
              <button
                type="button"
                className="wf-side-icon-btn"
                disabled={runBusy('import')}
                title={`导入 .workflow.json（落入${selectedWs ? '当前所选工作区' : '插件归属工作区'}）`}
                onClick={() => fileInputRef.current?.click()}
              >
                <UploadIcon size={13} />
              </button>
              <button
                type="button"
                className="wf-side-icon-btn"
                title="新建工作流定义（打开编辑器空白定义）"
                onClick={onNewDefinition}
              >
                <PlusIcon size={14} />
              </button>
            </span>
          </div>
          {showDefs ? (
            <div className="wf-side-section-body">
              {defsError ? <div className="wf-side-error">定义列表加载失败：{defsError}</div> : null}
              {importError ? (
                <div className="wf-side-error">
                  导入失败：
                  <ul>
                    {importError.map((item, i) => (
                      <li key={i}>{item}</li>
                    ))}
                  </ul>
                </div>
              ) : null}
              {defs === null && !defsError ? <div className="wf-side-empty">正在加载定义…</div> : null}
              {defs !== null && defs.length === 0 && !defsError ? (
                <div className="wf-side-empty">暂无定义——点 ＋ 新建，或导入 .workflow.json。</div>
              ) : null}
              {(defs ?? []).map(def => (
                <DefinitionRow
                  key={def.id}
                  def={def}
                  wsLabel={labelFor(def.workspaceId)}
                  busy={runBusy(`def:${def.id}`)}
                  onEdit={() => onEditDef(def)}
                  onRun={() => onRunDef(def)}
                  onExport={() => onExportDef(def)}
                  onDelete={() => onDeleteDef(def)}
                />
              ))}
            </div>
          ) : null}
        </div>

        <div className="wf-side-hint">实例每 10s 自动刷新；点击实例打开运行页，点击定义打开编辑器。</div>
      </div>

      {/* 导入文件选择（隐藏；处理后清值，同文件重复导入也触发 change）。 */}
      <input
        ref={fileInputRef}
        type="file"
        accept=".json,application/json"
        style={{ display: 'none' }}
        onChange={e => {
          const file = e.target.files?.[0]
          e.target.value = ''
          if (file) onImportFile(file)
        }}
      />
    </div>
  )
}

/*
 * ── 人工验证清单（步骤 9 侧边栏）──────────────────────────────────────────
 * 1. 打开侧边栏「工作流」：实例/定义两区均出现；顶部工作区下拉列出注册表工作区
 *    （含「全部工作区」缺省项）。
 * 2. 「＋」→ 打开空白定义编辑器（workspaceId=所选或插件归属）；保存后返回侧栏，
 *    30s 内或手动刷新后定义列表出现新条目（名称/描述/更新时间/节点数）。
 * 3. 定义行「编辑」→ 编辑器打开同一定义；「运行」→ 运行对话框（见 RunDialog 清单）。
 * 4. 定义行「导出」→ 浏览器下载 `<名称>.workflow.json`；内容=definition.json 原样。
 * 5. 「导入」选择刚导出的文件 → toast「已导入为 wf_…」（id 重赋）；导入残缺 JSON
 *    （删掉 name）→ 红框逐项回显；导入非法 JSON → 「文件不是合法 JSON：…」。
 * 6. 定义「删除」→ 宿主模态 confirm → 确认后条目消失；实例保留（持快照）。
 * 7. 运行中实例：状态点蓝色呼吸、显示「取消/删除」；点行打开实例页（步骤 10 占位，
 *    tab.data.instanceId 正确）；「取消」→ confirm → 状态转已取消（灰点）。
 * 8. 终态实例显示进度「n/m 节点完成」与耗时；删除终态实例 → 目录清除、列表消失。
 * 9. 切换工作区下拉 → 两列表按所选工作区过滤；切回「全部」→ 恢复全量；
 *    注册表变化（增删工作区）后下拉自动更新、失效选择回落「全部」。
 * 10. 工作区缺失的定义/实例显示「⚠ 工作区缺失」琥珀色标记（数据保留可查看）。
 */
