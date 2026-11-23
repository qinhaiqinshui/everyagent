/**
 * 工作流侧边栏面板：上=实例列表、下=定义列表。
 *
 * 布局与视觉与任务侧边栏（宿主 TasksPanel）对齐：
 * - SidebarScrollArea 滚动区（自宿主复制的滑块行为，CSS 类为宿主全局样式）；
 * - 实例/定义各为一张分组卡片（padding 6 / radius-md / border-light / bg-primary），
 *   组头=折叠箭头（仅点击图标折叠，rotate 过渡）+ 粗体组名 + 计数 + 右侧图标按钮；
 * - 行卡片（.ui-row）=标题行（粗体单行名称 + hover 浮现的 ⋯「更多操作」菜单）+
 *   元数据行（等宽 id 省略 + 可点击切换的相对/绝对时间 + 状态药丸）+ 次要信息行。
 * - 顶部工具条：工作区选择下拉（ctx.sdk.workspace.list 注册表；缺省「全部」——
 *   实例/定义按所选工作区服务端过滤，列表行仍带 workspaceId 归属展示）+ 刷新。
 *
 * 行级操作收进 ⋯ 菜单（RowActions，复用宿主 .ui-row-actions 全局样式）：
 * 实例=取消（活跃时）/删除；定义=运行/编辑/导出/删除（行点击即打开实例页/编辑器）。
 * 「＋」新建（编辑器空定义）、「导入」选择文件 → def.import（落入当前所选工作区，
 * 校验失败逐项回显）。
 *
 * 刷新节流：实例 10s / 定义 30s 定时 + 事件驱动（sidebar-panel-shown /
 * workspace-registry-changed）；行级操作置 busy 标记防重入。confirm 走
 * ctx.ui.showDialog（宿主模态壳，缺席降级 window.confirm）；toast 走 ctx.ui.showToast。
 *
 * 纯逻辑可测核（无插件 web 测试基建，导出保持可测，人工清单见文件尾注释）：
 * formatTimestamp / formatRelativeTime / instanceProgressText / exportFileName /
 * parseImportedDefinition。
 */
import React from 'react'
import { Button } from 'antd'
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
import SidebarScrollArea from '../SidebarScrollArea'
import { RowActions } from '../Menu'
import { openRunDialog } from './RunDialog'
import {
  ChevronDownIcon,
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

/**
 * 实例状态 → 行卡片状态药丸文字色（与任务侧边栏 statusPill 同一色彩语言：
 * 运行=蓝 / 等待·取消=琥珀 / 完成=绿 / 失败=红 / 已创建=弱化）。
 */
const INSTANCE_STATUS_PILL_COLOR: Record<WorkflowInstanceStatus, string> = {
  created: 'var(--text-muted)',
  running: 'var(--accent-blue)',
  'waiting-user': 'var(--accent-amber)',
  done: 'var(--accent-green)',
  failed: 'var(--accent-red)',
  cancelled: 'var(--accent-amber)',
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
 * 相对时间文案（与任务侧边栏同口径）：1 分钟内→刚刚 / 1 小时内→x 分钟前 /
 * 24 小时内→x 小时前 / 7 天内→x 天前；超过 7 天或无效时间返回 null（调用方
 * 回退到 formatTimestamp 绝对时间）。未来时间（时钟偏差）按「刚刚」处理。
 */
export function formatRelativeTime(ms?: number | null): string | null {
  if (typeof ms !== 'number' || !Number.isFinite(ms) || ms <= 0) return null
  const diff = Date.now() - ms
  if (diff < 60_000) return '刚刚'
  const minutes = Math.floor(diff / 60_000)
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days <= 7) return `${days} 天前`
  return null
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
        <div style={confirmBodyStyle}>
          <p style={{ margin: 0, color: 'var(--text-secondary)', lineHeight: 1.7 }}>{description}</p>
          <div style={confirmFooterStyle}>
            <Button onClick={() => close(false)}>取消</Button>
            <Button type="primary" danger onClick={() => close(true)}>
              {okText}
            </Button>
          </div>
        </div>
      ),
    })
    .then(r => r === true)
}

// ─── 行卡片（与任务侧边栏任务卡同构） ─────────────────────────────────────

/** 时间片段：默认相对时间（7 天内），点击切换绝对/相对（title 带完整绝对时间）。 */
function CardTime({ ms, label }: { ms?: number | null; label: string }): React.ReactElement | null {
  const [absolute, setAbsolute] = React.useState(false)
  const relative = formatRelativeTime(ms)
  const text = !absolute && relative !== null ? relative : formatTimestamp(ms)
  if (text === '') return null
  return (
    <span
      style={{ ...cardTimeStyle, cursor: 'help' }}
      title={`${label} ${new Date(ms ?? 0).toLocaleString()}`}
      onClick={event => {
        event.stopPropagation() // 不触发行的打开
        setAbsolute(v => !v)
      }}
    >
      {text}
    </span>
  )
}

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
  const active = instanceActive(inst.status)
  const progress = instanceProgressText(inst.nodeStates)
  const pillColor = (INSTANCE_STATUS_PILL_COLOR as Record<string, string>)[inst.status] ?? 'var(--text-muted)'
  const menuItems = [
    ...(active
      ? [{
          key: 'cancel',
          label: '取消实例',
          icon: <StopIcon size={13} />,
          onSelect: onCancel,
        }]
      : []),
    {
      key: 'delete',
      label: '删除',
      danger: true,
      icon: <DeleteIcon size={13} />,
      onSelect: onDelete,
    },
  ]
  const errorMessage =
    inst.error && typeof inst.error === 'object'
      ? String((inst.error as { message?: unknown }).message ?? '')
      : ''
  return (
    <div className="ui-row" style={cardStyle} onClick={onOpen} title={`实例 ${inst.id}\n点击打开实例运行页`}>
      <div style={cardHeaderStyle}>
        <span style={cardTitleStyle}>{inst.definitionName || inst.definitionId}</span>
        <RowActions items={menuItems} title="实例更多操作" disabled={busy} />
      </div>
      <div style={cardMetaRowStyle}>
        <span style={cardIdStyle} title={inst.id}>{inst.id}</span>
        <CardTime ms={inst.createdAt} label="创建于" />
        <span style={{ ...cardPillStyle, color: pillColor }}>{instanceStatusLabel(inst.status)}</span>
      </div>
      {progress || inst.durationMs || wsLabel || inst.workspaceMissing ? (
        <div style={cardSubRowStyle}>
          {inst.durationMs ? <span>耗时 {Math.round(inst.durationMs / 1000)}s</span> : null}
          {progress ? <span>{progress}</span> : null}
          {wsLabel ? <span style={chipStyle} title={inst.workspaceId}>{wsLabel}</span> : null}
          {inst.workspaceMissing ? (
            <span style={chipWarnStyle} title="工作区未注册（数据保留，重新注册后自动回归）">
              ⚠ 工作区缺失
            </span>
          ) : null}
        </div>
      ) : null}
      {errorMessage ? (
        <div style={cardErrorStyle} title="实例失败原因">✕ {errorMessage.slice(0, 80)}</div>
      ) : null}
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
  const menuItems = [
    {
      key: 'run',
      label: '运行',
      icon: <RunIcon size={13} />,
      onSelect: onRun,
    },
    {
      key: 'edit',
      label: '编辑',
      icon: <EditIcon size={13} />,
      onSelect: onEdit,
    },
    {
      key: 'export',
      label: '导出 JSON',
      icon: <DownloadIcon size={13} />,
      onSelect: onExport,
    },
    {
      key: 'delete',
      label: '删除',
      danger: true,
      icon: <DeleteIcon size={13} />,
      onSelect: onDelete,
    },
  ]
  return (
    <div className="ui-row" style={cardStyle} onClick={onEdit} title={`定义 ${def.id}\n点击打开编辑器`}>
      <div style={cardHeaderStyle}>
        <span style={cardTitleStyle}>{def.name || def.id}</span>
        <RowActions items={menuItems} title="定义更多操作" disabled={busy} />
      </div>
      <div style={cardMetaRowStyle}>
        <span style={cardIdStyle} title={def.id}>{def.id}</span>
        <CardTime ms={def.updatedAt} label="更新于" />
        <span style={cardPillStyle}>{def.nodeCount} 节点</span>
      </div>
      {def.description || wsLabel || def.workspaceMissing ? (
        <div style={cardSubRowStyle}>
          {def.description ? (
            <span style={cardSubTextStyle} title={def.description}>{def.description}</span>
          ) : null}
          {wsLabel ? <span style={chipStyle} title={def.workspaceId}>{wsLabel}</span> : null}
          {def.workspaceMissing ? (
            <span style={chipWarnStyle} title="工作区未注册（数据保留，重新注册后自动回归）">
              ⚠ 工作区缺失
            </span>
          ) : null}
        </div>
      ) : null}
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

  /** 分组卡片组头（与任务侧边栏组头同构：仅点击箭头折叠，图标按钮推到右侧）。 */
  const renderGroupHeader = (
    label: string,
    countText: string,
    collapsed: boolean,
    onToggle: () => void,
    tools?: React.ReactNode,
  ) => (
    <div style={groupHeaderStyle}>
      <span
        style={groupChevronButtonStyle}
        title={collapsed ? '展开' : '折叠'}
        role="button"
        aria-expanded={!collapsed}
        onClick={onToggle}
      >
        <ChevronDownIcon
          size={13}
          style={collapsed ? { ...groupChevronStyle, ...groupChevronCollapsedStyle } : groupChevronStyle}
        />
      </span>
      <span style={groupLabelStyle}>{label}</span>
      <span style={groupCountStyle}>{countText}</span>
      {tools ? <span style={groupToolsStyle}>{tools}</span> : null}
    </div>
  )

  return (
    <div style={shellStyle}>
      <SidebarScrollArea style={listStyle}>
        {/* ── 工具条：工作区过滤 + 刷新（功能项；任务侧边栏无对应物） ── */}
        <div style={toolbarStyle}>
          <select
            style={selectStyle}
            value={selectedWs}
            title="按工作区过滤实例与定义列表"
            onChange={e => setSelectedWs(e.target.value)}
          >
            <option value="">全部工作区</option>
            {workspaces.map(w => (
              <option key={w.id} value={w.id}>
                {w.label}
              </option>
            ))}
          </select>
          <Button
            type="text"
            style={toolbarButtonStyle}
            title="立即刷新实例与定义列表"
            aria-label="立即刷新实例与定义列表"
            onClick={() => {
              reloadWorkspaces()
              reloadDefs(selectedWs)
              reloadInstances(selectedWs)
            }}
          >
            <RefreshIcon size={13} />
          </Button>
        </div>

        {/* ── 上：实例列表（可折叠分组卡片） ── */}
        <div style={groupSectionStyle}>
          {renderGroupHeader(
            '实例',
            instances === null ? '…' : `${instances.length}${runningCount > 0 ? `（${runningCount} 活跃）` : ''}`,
            !showInstances,
            () => setShowInstances(v => !v),
          )}
          {showInstances ? (
            <div style={groupTasksStyle}>
              {instancesError ? <div style={errorStyle}>实例列表加载失败：{instancesError}</div> : null}
              {instances === null && !instancesError ? <div style={emptyStyle}>正在加载实例…</div> : null}
              {instances !== null && instances.length === 0 && !instancesError ? (
                <div style={emptyStyle}>暂无运行实例——从下方定义列表点「运行」开始。</div>
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

        {/* ── 下：定义列表（可折叠分组卡片；组头带 导入/＋ 新建） ── */}
        <div style={groupSectionStyle}>
          {renderGroupHeader(
            '定义',
            defs === null ? '…' : String(defs.length),
            !showDefs,
            () => setShowDefs(v => !v),
            <>
              <Button
                type="text"
                style={toolbarButtonStyle}
                disabled={runBusy('import')}
                title={`导入 .workflow.json（落入${selectedWs ? '当前所选工作区' : '插件归属工作区'}）`}
                aria-label="导入工作流定义"
                onClick={() => fileInputRef.current?.click()}
              >
                <UploadIcon size={13} />
              </Button>
              <Button
                type="text"
                style={toolbarButtonStyle}
                title="新建工作流定义（打开编辑器空白定义）"
                aria-label="新建工作流定义"
                onClick={onNewDefinition}
              >
                <PlusIcon size={13} />
              </Button>
            </>,
          )}
          {showDefs ? (
            <div style={groupTasksStyle}>
              {defsError ? <div style={errorStyle}>定义列表加载失败：{defsError}</div> : null}
              {importError ? (
                <div style={errorStyle}>
                  导入失败：
                  <ul style={{ margin: '2px 0 0', paddingInlineStart: 16 }}>
                    {importError.map((item, i) => (
                      <li key={i}>{item}</li>
                    ))}
                  </ul>
                </div>
              ) : null}
              {defs === null && !defsError ? <div style={emptyStyle}>正在加载定义…</div> : null}
              {defs !== null && defs.length === 0 && !defsError ? (
                <div style={emptyStyle}>暂无定义——点 ＋ 新建，或导入 .workflow.json。</div>
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
      </SidebarScrollArea>

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

// ─── 样式（与任务侧边栏 TasksPanel 同一套参数） ─────────────────────────────

const shellStyle: React.CSSProperties = {
  height: '100%',
  display: 'flex',
  flexDirection: 'column',
  minWidth: 0,
  minHeight: 0,
  background: 'var(--bg-secondary)',
}

const listStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  minHeight: 0,
  paddingRight: 2,
}

/** 顶部工具条（工作区过滤 + 刷新）。 */
const toolbarStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  padding: '2px 4px',
}

const selectStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  background: 'var(--bg-primary)',
  border: '1px solid var(--border-light)',
  borderRadius: 'var(--radius-sm)',
  color: 'var(--text-primary)',
  padding: '2px 6px',
  fontSize: 'var(--text-xs)',
  fontFamily: 'inherit',
}

const toolbarButtonStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  width: 22,
  height: 22,
  minWidth: 22,
  padding: 0,
  flexShrink: 0,
}

/** 分组卡片（实例区/定义区）。 */
const groupSectionStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
  padding: '6px 6px',
  borderRadius: 'var(--radius-md)',
  border: '1px solid var(--border-light)',
  background: 'var(--bg-primary)',
}

const groupHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  minWidth: 0,
}

/** 折叠/展开触发图标：仅点击该图标才折叠或展开（与任务侧边栏一致）。 */
const groupChevronButtonStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  flexShrink: 0,
  cursor: 'pointer',
  padding: 2,
  borderRadius: 'var(--radius-sm)',
  color: 'var(--text-muted)',
}

const groupChevronStyle: React.CSSProperties = {
  flexShrink: 0,
  color: 'var(--text-muted)',
  transition: 'transform 0.15s ease',
}

const groupChevronCollapsedStyle: React.CSSProperties = {
  transform: 'rotate(-90deg)',
}

const groupLabelStyle: React.CSSProperties = {
  fontSize: 'var(--text-sm)',
  fontWeight: 700,
  color: 'var(--text-primary)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  minWidth: 0,
  flex: '0 1 auto',
}

const groupCountStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  fontWeight: 400,
  color: 'var(--text-muted)',
  flexShrink: 0,
}

const groupToolsStyle: React.CSSProperties = {
  marginLeft: 'auto',
  display: 'inline-flex',
  alignItems: 'center',
  gap: 2,
  flexShrink: 0,
}

const groupTasksStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 3,
}

/** 行卡片（.ui-row：hover 浮现 ⋯ 菜单靠宿主全局 CSS）。 */
const cardStyle: React.CSSProperties = {
  borderWidth: 1,
  borderStyle: 'solid',
  borderColor: 'transparent',
  borderRadius: 'var(--radius-md)',
  background: 'var(--bg-primary)',
  color: 'var(--text-primary)',
  padding: '6px 10px',
  display: 'flex',
  flexDirection: 'column',
  gap: 3,
  cursor: 'pointer',
}

const cardHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  justifyContent: 'space-between',
  gap: 6,
}

const cardTitleStyle: React.CSSProperties = {
  fontSize: 'var(--text-sm)',
  fontWeight: 700,
  minWidth: 0,
  flex: 1,
  overflow: 'hidden',
  textOverflow: 'clip',
  whiteSpace: 'nowrap',
}

const cardMetaRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  flexWrap: 'nowrap',
  minWidth: 0,
}

const cardIdStyle: React.CSSProperties = {
  minWidth: 0,
  flex: '1 1 auto',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  fontFamily: 'var(--font-mono, monospace)',
}

const cardTimeStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  flexShrink: 0,
  whiteSpace: 'nowrap',
}

const cardPillStyle: React.CSSProperties = {
  borderRadius: 999,
  padding: '1px 6px',
  fontSize: 'var(--text-xs)',
  fontWeight: 400,
  flexShrink: 0,
  background: 'transparent',
  color: 'var(--text-muted)',
  whiteSpace: 'nowrap',
}

/** 次要信息行（实例=耗时/进度/工作区；定义=描述/工作区）。 */
const cardSubRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  flexWrap: 'wrap',
  minWidth: 0,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
}

const cardSubTextStyle: React.CSSProperties = {
  minWidth: 0,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

const cardErrorStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--accent-red)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

const chipStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  border: '1px solid var(--border-light)',
  borderRadius: 999,
  padding: '0 6px',
  fontSize: 'var(--text-2xs)',
  color: 'var(--text-muted)',
  maxWidth: 110,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

const chipWarnStyle: React.CSSProperties = {
  ...chipStyle,
  color: 'var(--accent-amber)',
  borderColor: 'color-mix(in srgb, var(--accent-amber) 40%, var(--border-light))',
}

const emptyStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  padding: '8px 4px',
}

const errorStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--accent-red)',
  padding: '8px 4px',
  lineHeight: 1.5,
}

const confirmBodyStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 12,
  minWidth: 'min(320px, 80vw)',
  fontSize: 13,
}

const confirmFooterStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'flex-end',
  gap: 8,
}

/*
 * ── 人工验证清单（侧边栏，任务面板同构布局版）──────────────────────────────
 * 1. 打开侧边栏「工作流」：实例/定义两张分组卡片（与任务侧边栏分组卡片同样式）；
 *    顶部工作区下拉列出注册表工作区（含「全部工作区」缺省项）+ 刷新按钮。
 * 2. 组头：仅点击箭头图标折叠/展开（点组名/计数不折叠）；折叠时箭头旋转 -90°。
 * 3. 行卡片：hover 时右侧浮现 ⋯「更多操作」菜单（与任务卡片一致的浮现行为）；
 *    实例菜单=取消（活跃时）/删除；定义菜单=运行/编辑/导出 JSON/删除。
 * 4. 「＋」→ 打开空白定义编辑器；「导入」选择 .workflow.json → toast「已导入为
 *    wf_…」；导入残缺/非法 JSON → 分组卡片内红色逐项回显。
 * 5. 定义行「导出」→ 浏览器下载 `<名称>.workflow.json`；内容=definition.json 原样。
 * 6. 定义「删除」→ 宿主模态 confirm → 确认后条目消失；实例保留（持快照）。
 * 7. 运行中实例：行卡片状态药丸蓝色「运行中」；⋯ 菜单「取消实例」→ confirm →
 *    状态转已取消（琥珀「已取消」）；点行打开实例页（tab.data.instanceId 正确）。
 * 8. 终态实例显示进度「n/m 节点完成」与耗时；删除终态实例 → 目录清除、列表消失。
 * 9. 行内时间：7 天内显示相对时间（刚刚/x 分钟前…），点击切换绝对/相对，
 *    title 显示完整绝对时间（与任务卡片时间交互一致）。
 * 10. 切换工作区下拉 → 两列表按所选工作区过滤；切回「全部」→ 恢复全量且行内
 *     显示工作区归属 chip；注册表变化后下拉自动更新、失效选择回落「全部」。
 * 11. 工作区缺失的定义/实例显示「⚠ 工作区缺失」琥珀色标记（数据保留可查看）。
 * 12. 滚动行为与任务侧边栏一致（SidebarScrollArea：滚动时浮现滑块、停后淡出）。
 */
