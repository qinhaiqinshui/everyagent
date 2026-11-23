/**
 * 工作流侧边栏面板——与任务侧边栏（宿主 TasksPanel）同一布局范式：**按注册表工作区分组**，
 * 每个工作区一张分组卡片，组内上=定义、下=实例；全部工作区同时并陈，无工作区过滤下拉。
 *
 * 布局（任务面板同构）：
 * - SidebarScrollArea 滚动区（自宿主复制，CSS 类为宿主全局样式）；
 * - 分组卡片（padding 6 / radius-md / border-light / bg-primary）：组头=折叠箭头（仅点击图标
 *   折叠，rotate 过渡）+ 粗体组名 + 右侧图标按钮（▶ 运行 / ＋ 新建 / ⋯ 导入·刷新）；
 * - 组内两段可折叠子区「定义」「实例」（箭头交互同组头）；
 * - 行卡片（.ui-row）=标题行（粗体单行名称 + hover 浮现的 ⋯「更多操作」菜单）+ 元数据行
 *   （等宽 id 省略 + 可点击切换的相对/绝对时间 + 状态药丸）+ 次要信息行。
 *
 * 与任务面板的一处刻意差异：空工作区也展示——任务在输入框创建、侧栏可隐藏空组；工作流
 * 的创建入口就在组头（▶ 运行〔有定义时可用〕、＋ 直达，导入在 ⋯ 菜单，均需选定工作区），
 * 隐藏空组会让首个工作流无处可建。
 * 归属数据的工作区未注册时收进尾组「工作区缺失」（只读展示，无创建入口）。
 *
 * 行级操作收进 ⋯ 菜单（RowActions，复用宿主 .ui-row-actions 全局样式）：
 * 实例=取消（活跃时）/删除；定义=运行/编辑/导出/删除（行点击即打开实例页/编辑器）。
 *
 * 刷新模型（任务面板同款，推送驱动）：worker 侧定义/实例的每次落盘变更经 worker 级插件
 * 通知频道扇出 `plugin.workflow.changed`（架构 §5.2），宿主桥接为领域事件
 * `plugin-worker-event`，本面板订阅后按 kind 重拉对应列表（150ms 合并突发）；面板再展示
 * / 注册表变化时重拉；另留 60s 低频兜底轮询（重连窗口内丢帧的唯一补救——插件无重连信号）。
 * 手动刷新收进组头 ⋯。busy 标记防重入；confirm 走 ctx.ui.showDialog（缺席降级
 * window.confirm）；toast 走 ctx.ui.showToast。
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
import { HeaderMenu, RowActions } from '../Menu'
import { openRunDialog, openWorkspaceRunDialog } from './RunDialog'
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

/** worker 侧变更通知事件名（与 Java WorkflowPlugin.CHANGED_EVENT 逐字一致）。 */
export const WORKFLOW_CHANGED_EVENT = 'plugin.workflow.changed'

/** 推送到达后的合并窗口：一次运行收束/批量导入会连发多帧，合并成一次重拉。 */
const PUSH_RELOAD_DEBOUNCE_MS = 150

/** 低频兜底轮询（推送为主；仅补救重连窗口内的丢帧）。 */
const FALLBACK_REFRESH_MS = 60_000

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

/**
 * 「最后运行的」定义 id：实例按 createdAt 降序首个、且定义仍存在者；都无 →
 * undefined。实例创建时间=运行发起时间（服务端事实源，比本地记忆多端一致）；
 * 定义已删的实例跳过——下拉只列现存定义，选中项必须真实可运行。
 */
export function lastRunDefinitionId(
  defs: WorkflowDefSummary[],
  instances: WorkflowInstanceSummary[],
): string | undefined {
  const known = new Set(defs.map(d => d.id))
  const byRecent = [...instances].sort((a, b) => (b.createdAt ?? 0) - (a.createdAt ?? 0))
  for (const inst of byRecent) {
    if (inst.definitionId && known.has(inst.definitionId)) return inst.definitionId
  }
  return undefined
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

/** 工作区注册表条目 → 组名（root 的 basename；缺省退 id）。 */
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
  workspaceChip,
  busy,
  onOpen,
  onCancel,
  onDelete,
}: {
  inst: WorkflowInstanceSummary
  /** 尾组「工作区缺失」里显示归属 id（正常分组下由组名承载归属，不显示）。 */
  workspaceChip?: string
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
      {progress || inst.durationMs || workspaceChip ? (
        <div style={cardSubRowStyle}>
          {inst.durationMs ? <span>耗时 {Math.round(inst.durationMs / 1000)}s</span> : null}
          {progress ? <span>{progress}</span> : null}
          {workspaceChip ? <span style={chipStyle} title={inst.workspaceId}>{workspaceChip}</span> : null}
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
  workspaceChip,
  busy,
  onEdit,
  onRun,
  onExport,
  onDelete,
}: {
  def: WorkflowDefSummary
  /** 尾组「工作区缺失」里显示归属 id。 */
  workspaceChip?: string
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
      {def.description || workspaceChip ? (
        <div style={cardSubRowStyle}>
          {def.description ? (
            <span style={cardSubTextStyle} title={def.description}>{def.description}</span>
          ) : null}
          {workspaceChip ? <span style={chipStyle} title={def.workspaceId}>{workspaceChip}</span> : null}
        </div>
      ) : null}
    </div>
  )
}

// ─── 折叠头（组头 / 子区头共用一套交互：仅点击箭头折叠） ──────────────────────

function CollapsibleHeader({
  label,
  labelTitle,
  count,
  collapsed,
  onToggle,
  sub = false,
  tools,
}: {
  label: string
  labelTitle?: string
  count?: string
  collapsed: boolean
  onToggle: () => void
  /** 子区头：更弱的字重与字色，与组名形成层级。 */
  sub?: boolean
  tools?: React.ReactNode
}): React.ReactElement {
  return (
    <div style={sub ? subHeaderStyle : groupHeaderStyle}>
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
      <span style={sub ? subLabelStyle : groupLabelStyle} title={labelTitle}>{label}</span>
      {count !== undefined ? <span style={sub ? subCountStyle : groupCountStyle}>{count}</span> : null}
      {tools ? <span style={groupToolsStyle}>{tools}</span> : null}
    </div>
  )
}

// ─── 面板主体 ──────────────────────────────────────────────────────────────

/** 分组（按注册表工作区；尾组收「工作区缺失」的数据）。 */
interface WorkspaceGroup {
  /** 折叠键（ws:<id> / ws:__missing__）。 */
  key: string
  label: string
  labelTitle?: string
  /** 注册表条目（尾组为 null：无创建入口）。 */
  entry: PluginWorkspaceEntry | null
  defs: WorkflowDefSummary[]
  instances: WorkflowInstanceSummary[]
}

export default function SidebarPanel() {
  const ctx = getPluginContext()

  const [registry, setRegistry] = React.useState<PluginWorkspaceEntry[] | null>(null)
  const [defs, setDefs] = React.useState<WorkflowDefSummary[] | null>(null)
  const [instances, setInstances] = React.useState<WorkflowInstanceSummary[] | null>(null)
  const [defsError, setDefsError] = React.useState<string | null>(null)
  const [instancesError, setInstancesError] = React.useState<string | null>(null)
  /** 导入校验错误（回显在目标工作区分组内；null=无）。 */
  const [importError, setImportError] = React.useState<{ workspaceId: string; items: string[] } | null>(null)
  /** 折叠键集合（组 ws:<id>；子区 ws:<id>/defs、ws:<id>/inst）；不在集合=展开。 */
  const [collapsedKeys, setCollapsedKeys] = React.useState<Set<string>>(() => new Set())
  /** 行级/动作级 busy 标记（'import' / `def:<id>` / `inst:<id>`）。 */
  const [busyKey, setBusyKey] = React.useState<string | null>(null)
  const fileInputRef = React.useRef<HTMLInputElement | null>(null)
  /** 导入目标工作区（点组头「导入」时记下，文件选择器 change 时取用）。 */
  const importTargetRef = React.useRef<string>('')

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

  // ── 注册表（组序=注册表顺序，默认工作区在前；id 缺失的条目无法作 workflow RPC 的 workspace 参数，跳过）。
  const reloadRegistry = React.useCallback(() => {
    void ctx.sdk.workspace
      .list()
      .then(list => setRegistry((list ?? []).filter(e => e && typeof e.id === 'string' && e.id !== '')))
      .catch(() => setRegistry([]))
  }, [ctx])

  // ── 列表拉取（不过滤=当前 worker 全部工作区；分组在前端按 workspaceId 归户）。
  const reloadDefs = React.useCallback(() => {
    workflowGateway
      .defList()
      .then(list => {
        setDefs(list)
        setDefsError(null)
      })
      .catch(err => setDefsError(rpcErrorMessage(err)))
  }, [])

  const reloadInstances = React.useCallback(() => {
    workflowGateway
      .instanceList()
      .then(list => {
        setInstances(list)
        setInstancesError(null)
      })
      .catch(err => setInstancesError(rpcErrorMessage(err)))
  }, [])

  // ── 推送驱动重拉：plugin.workflow.changed{kind} → 按 kind 重拉对应列表，突发合并成一次。
  const pendingKindsRef = React.useRef(new Set<'definition' | 'instance'>())
  const reloadTimerRef = React.useRef<number | null>(null)
  const schedulePushReload = React.useCallback(
    (kind: string) => {
      pendingKindsRef.current.add(kind === 'definition' ? 'definition' : 'instance')
      if (reloadTimerRef.current !== null) return
      reloadTimerRef.current = window.setTimeout(() => {
        reloadTimerRef.current = null
        const kinds = pendingKindsRef.current
        pendingKindsRef.current = new Set()
        if (kinds.has('definition')) reloadDefs()
        if (kinds.has('instance')) reloadInstances()
      }, PUSH_RELOAD_DEBOUNCE_MS)
    },
    [reloadDefs, reloadInstances],
  )

  // ── 挂载：注册表 + 双列表首拉；订阅推送 / 面板再展示 / 注册表变化；60s 低频兜底。
  React.useEffect(() => {
    reloadRegistry()
    reloadDefs()
    reloadInstances()
    const d1 = ctx.events.on('plugin-worker-event', payload => {
      const p = payload as { event?: string; data?: { kind?: string } } | null
      if (p?.event !== WORKFLOW_CHANGED_EVENT) return
      schedulePushReload(String(p.data?.kind ?? ''))
    })
    const d2 = ctx.events.on('workspace-registry-changed', () => {
      reloadRegistry()
      // 注册表变化会翻转条目的「工作区缺失」标记（服务端按注册表计算），列表一并重拉。
      reloadDefs()
      reloadInstances()
    })
    const d3 = ctx.events.on('sidebar-panel-shown', (payload) => {
      if ((payload as { panelId?: string } | null)?.panelId !== 'workflow') return
      reloadRegistry()
      reloadDefs()
      reloadInstances()
    })
    const fallback = setInterval(() => {
      reloadDefs()
      reloadInstances()
    }, FALLBACK_REFRESH_MS)
    return () => {
      d1.dispose()
      d2.dispose()
      d3.dispose()
      clearInterval(fallback)
      if (reloadTimerRef.current !== null) {
        window.clearTimeout(reloadTimerRef.current)
        reloadTimerRef.current = null
      }
    }
  }, [ctx, reloadRegistry, reloadDefs, reloadInstances, schedulePushReload])

  // ── 分组 ──

  const groups = React.useMemo<WorkspaceGroup[] | null>(() => {
    if (registry === null) return null
    const known = new Set(registry.map(e => e.id as string))
    const out: WorkspaceGroup[] = registry.map(entry => {
      const id = entry.id as string
      return {
        key: `ws:${id}`,
        label: workspaceEntryLabel(entry),
        labelTitle: entry.root,
        entry,
        defs: (defs ?? []).filter(d => d.workspaceId === id),
        instances: (instances ?? []).filter(i => i.workspaceId === id),
      }
    })
    const orphanedDefs = (defs ?? []).filter(d => !known.has(d.workspaceId) || d.workspaceMissing)
    const orphanedInstances = (instances ?? []).filter(i => !known.has(i.workspaceId) || i.workspaceMissing)
    if (orphanedDefs.length > 0 || orphanedInstances.length > 0) {
      out.push({
        key: 'ws:__missing__',
        label: '工作区缺失',
        labelTitle: '归属工作区未注册（数据保留，重新注册后自动回归）',
        entry: null,
        defs: orphanedDefs,
        instances: orphanedInstances,
      })
    }
    return out
  }, [registry, defs, instances])

  const toggleCollapsed = React.useCallback((key: string) => {
    setCollapsedKeys(current => {
      const next = new Set(current)
      if (next.has(key)) {
        next.delete(key)
      } else {
        next.add(key)
      }
      return next
    })
  }, [])

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

  const onNewDefinition = (wsId: string) => {
    ctx.ui.openTab(WORKFLOW_EDITOR_TAB_TYPE, { workspaceId: wsId, definitionId: '' }, '工作流（新建）')
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
      // 本地立即重拉一次（推送帧到达时再校准）。
      if (id) reloadInstances()
    })
  }

  /** 组头 ▶：工作区级运行窗（选定义→填变量→运行；默认选中最近一次实例的定义）。 */
  const onRunGroup = (group: WorkspaceGroup) => {
    const wsId = group.entry?.id as string | undefined
    if (!wsId || group.defs.length === 0) return
    void openWorkspaceRunDialog(wsId, group.defs, lastRunDefinitionId(group.defs, group.instances)).then(
      id => {
        // 本地立即重拉一次（推送帧到达时再校准）。
        if (id) reloadInstances()
      },
    )
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
        reloadDefs()
      }
    })
  }

  const onImportFile = (file: File, wsId: string) => {
    void withBusy('import', async () => {
      try {
        const text = await file.text()
        const definition = parseImportedDefinition(text)
        const { id } = await workflowGateway.defImport(wsId, definition)
        setImportError(null)
        toast(`已导入为 ${id}`, 'success')
      } catch (err) {
        if (err instanceof WorkflowValidationError) {
          setImportError({ workspaceId: wsId, items: err.items.length > 0 ? err.items : [err.message] })
        } else {
          setImportError({ workspaceId: wsId, items: [rpcErrorMessage(err)] })
        }
      } finally {
        reloadDefs()
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
        reloadInstances()
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
        reloadInstances()
      }
    })
  }

  const renderAll = () => {
    reloadRegistry()
    reloadDefs()
    reloadInstances()
  }

  // ── 渲染 ──

  /** 子区（组内「定义」/「实例」段）：折叠箭头 + 标签 + 计数 → 行卡片列表 / 单行空态。 */
  const renderSubSection = (
    groupKey: string,
    section: 'defs' | 'inst',
    label: string,
    count: string,
    emptyText: string,
    rows: React.ReactNode,
    extra?: React.ReactNode,
  ) => {
    const key = `${groupKey}/${section}`
    const collapsed = collapsedKeys.has(key)
    return (
      <>
        <CollapsibleHeader sub label={label} count={count} collapsed={collapsed} onToggle={() => toggleCollapsed(key)} />
        {collapsed ? null : (
          <div style={groupTasksStyle}>
            {extra}
            {rows}
            {emptyText ? <div style={subEmptyStyle}>{emptyText}</div> : null}
          </div>
        )}
      </>
    )
  }

  return (
    <div style={shellStyle}>
      <SidebarScrollArea style={listStyle}>
        {defsError ? <div style={errorStyle}>定义列表加载失败：{defsError}</div> : null}
        {instancesError ? <div style={errorStyle}>实例列表加载失败：{instancesError}</div> : null}
        {groups === null ? (
          <div style={emptyStyle}>正在加载…</div>
        ) : groups.length === 0 ? (
          <div style={emptyStyle}>暂无工作区——连接 worker 并注册工作区后即可使用工作流。</div>
        ) : (
          groups.map(group => {
            const collapsed = collapsedKeys.has(group.key)
            const groupDefCount = group.defs.length
            const groupInstCount = group.instances.length
            const bothEmpty =
              defs !== null && instances !== null && !defsError && !instancesError
              && groupDefCount === 0 && groupInstCount === 0
            const running = group.instances.filter(i => instanceActive(i.status)).length
            const orphanChip = group.entry === null
            return (
              <div key={group.key} style={groupSectionStyle}>
                <CollapsibleHeader
                  label={group.label}
                  labelTitle={group.labelTitle}
                  collapsed={collapsed}
                  onToggle={() => toggleCollapsed(group.key)}
                  tools={
                    <>
                      {group.entry ? (
                        <>
                          <Button
                            type="text"
                            style={toolbarButtonStyle}
                            disabled={groupDefCount === 0}
                            title={
                              groupDefCount === 0
                                ? '此工作区暂无工作流定义'
                                : '运行此工作区的工作流（选择定义、填写运行变量）'
                            }
                            aria-label="运行工作流"
                            onClick={() => onRunGroup(group)}
                          >
                            <RunIcon size={13} />
                          </Button>
                          <Button
                            type="text"
                            style={toolbarButtonStyle}
                            title="在此工作区新建工作流定义（打开编辑器空白定义）"
                            aria-label="新建工作流定义"
                            onClick={() => group.entry && onNewDefinition(group.entry.id as string)}
                          >
                            <PlusIcon size={13} />
                          </Button>
                        </>
                      ) : null}
                      <HeaderMenu
                        title="更多操作"
                        items={[
                          // 导入收进 ⋯ 菜单（低频动作，给 ＋ 新建让位；落位本组工作区）。
                          ...(group.entry
                            ? [{
                                key: 'import',
                                label: '导入工作流…',
                                icon: <UploadIcon size={13} />,
                                disabled: runBusy('import'),
                                onSelect: () => {
                                  importTargetRef.current = group.entry?.id as string
                                  fileInputRef.current?.click()
                                },
                              }]
                            : []),
                          {
                            key: 'refresh',
                            label: '刷新',
                            icon: <RefreshIcon size={13} />,
                            onSelect: renderAll,
                          },
                        ]}
                      />
                    </>
                  }
                />
                {collapsed ? null : bothEmpty ? (
                  <div style={emptyStyle}>
                    {group.entry ? '暂无工作流——点 ＋ 新建，或从 ⋯ 菜单导入 .workflow.json。' : '暂无数据。'}
                  </div>
                ) : (
                  <>
                    {renderSubSection(
                      group.key,
                      'defs',
                      '定义',
                      defs === null ? '…' : String(groupDefCount),
                      groupDefCount === 0 && defs !== null && !defsError ? '暂无定义' : '',
                      group.defs.map(def => (
                        <DefinitionRow
                          key={def.id}
                          def={def}
                          workspaceChip={orphanChip ? def.workspaceId : undefined}
                          busy={runBusy(`def:${def.id}`)}
                          onEdit={() => onEditDef(def)}
                          onRun={() => onRunDef(def)}
                          onExport={() => onExportDef(def)}
                          onDelete={() => onDeleteDef(def)}
                        />
                      )),
                      importError && group.entry?.id === importError.workspaceId ? (
                        <div style={errorStyle}>
                          导入失败：
                          <ul style={{ margin: '2px 0 0', paddingInlineStart: 16 }}>
                            {importError.items.map((item, i) => (
                              <li key={i}>{item}</li>
                            ))}
                          </ul>
                        </div>
                      ) : null,
                    )}
                    {renderSubSection(
                      group.key,
                      'inst',
                      '实例',
                      instances === null
                        ? '…'
                        : `${groupInstCount}${running > 0 ? `（${running} 活跃）` : ''}`,
                      groupInstCount === 0 && instances !== null && !instancesError
                        ? '暂无运行实例——从上方定义的 ⋯ 菜单点「运行」开始。'
                        : '',
                      group.instances.map(inst => (
                        <InstanceRow
                          key={inst.id}
                          inst={inst}
                          workspaceChip={orphanChip ? inst.workspaceId : undefined}
                          busy={runBusy(`inst:${inst.id}`)}
                          onOpen={() => onOpenInstance(inst)}
                          onCancel={() => onCancelInstance(inst)}
                          onDelete={() => onDeleteInstance(inst)}
                        />
                      )),
                    )}
                  </>
                )}
              </div>
            )
          })
        )}
      </SidebarScrollArea>

      {/* 导入文件选择（隐藏；处理后清值，同文件重复导入也触发 change）。 */}
      <input
        ref={fileInputRef}
        type="file"
        accept=".json,application/json"
        style={{ display: 'none' }}
        onChange={e => {
          const file = e.target.files?.[0]
          const wsId = importTargetRef.current
          e.target.value = ''
          if (file && wsId) onImportFile(file, wsId)
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

/** 分组卡片（每个工作区一张）。 */
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

/** 组内子区头（「定义」/「实例」）：与组头同布局，字重字色更弱以示层级。 */
const subHeaderStyle: React.CSSProperties = {
  ...groupHeaderStyle,
  padding: '4px 2px 0',
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

const subLabelStyle: React.CSSProperties = {
  ...groupLabelStyle,
  fontWeight: 600,
  color: 'var(--text-secondary)',
}

const groupCountStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  fontWeight: 400,
  color: 'var(--text-muted)',
  flexShrink: 0,
}

const subCountStyle: React.CSSProperties = {
  ...groupCountStyle,
  color: 'var(--text-faint)',
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

/** 次要信息行（实例=耗时/进度；定义=描述）。 */
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

const emptyStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  padding: '8px 4px',
}

/** 子区内单行空态（更紧凑）。 */
const subEmptyStyle: React.CSSProperties = {
  ...emptyStyle,
  padding: '4px 2px',
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
 * ── 人工验证清单（侧边栏，任务面板同构 · 工作区分组 · 推送驱动）────────────
 * 1. 打开侧边栏「工作流」：注册表里每个工作区一张分组卡片同时并陈（含空工作区，
 *    显示「暂无工作流——点 ＋ 新建…」），**无**顶部下拉/工具条；组序=注册表顺序。
 * 2. 组头：仅点击箭头折叠/展开（点组名不折叠）；右侧=▶ / ＋ / ⋯（⋯ 内「导入工作流…」
 *    打开文件选择、「刷新」=全量重拉）。＋/导入落位本组工作区；导入残缺/非法 JSON →
 *    本组「定义」段内红色逐项回显；▶ 打开工作区级运行窗（见 RunDialog 清单 §6-8）。
 * 3. 组内两段「定义」「实例」各有箭头可独立折叠；头带计数（实例含「k 活跃」）。
 * 4. 行卡片：hover 时右侧浮现 ⋯ 菜单；实例=取消实例（活跃时）/删除；定义=运行/编辑/导出 JSON/删除。
 *    点行打开实例页/编辑器（tab.data 正确）；导出下载 `<名称>.workflow.json`。
 * 5. **推送**：在编辑器保存定义 / 另一端运行工作流 / AI 调 workflow_create 工具 / 实例运行态
 *    翻转（running→waiting-user→done）→ 侧栏在 ~150ms 内自动更新，**无需手动刷新**；
 *    取消实例 → 状态药丸转琥珀「已取消」；删除 → 条目消失。
 * 6. 归属工作区未注册的定义/实例收进尾组「工作区缺失」（只读，行内 chip 显示 workspaceId）；
 *    注册表变化后尾组条目回到对应工作区组。
 * 7. 行内时间：7 天内相对时间，点击切换绝对/相对，title 显示完整绝对时间。
 * 8. 滚动行为与任务侧边栏一致（SidebarScrollArea：滚动时浮现滑块、停后淡出）。
 */
