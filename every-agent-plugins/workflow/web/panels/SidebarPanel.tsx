/**
 * 工作流侧边栏面板——与任务侧边栏（宿主 TasksPanel）同一布局范式：**按注册表工作区分组**，
 * 每个工作区一张分组卡片，组内只展示**运行实例**（定义不在侧栏展示——定义的增删改查/
 * 导入/导出/运行全部收敛到管理标签页 workflow-manage，经组头「管理」按钮进入）。
 *
 * 布局（任务面板同构）：
 * - SidebarScrollArea 滚动区（自宿主复制，CSS 类为宿主全局样式）；
 * - 分组卡片（padding 6 / radius-md / border-light / bg-primary）：组头=折叠箭头（仅点击图标
 *   折叠，rotate 过渡）+ 粗体组名 + 右侧图标按钮（▶ 运行 / 管理 / ⋯ 刷新）；
 * - 组内实例行**直接平铺**（无子区头、无折叠、无计数——数量与状态在行卡片药丸自明）；
 * - 行卡片（.ui-row）=两行：首行粗体实例 id + 运行参数（「；」分隔只显示参数值，
 *   单行省略）+ hover 浮现的 ⋯「更多操作」菜单；次行工作流名（弹性占位、单行
 *   省略）+ 可点击切换的相对/绝对时间 + 状态药丸；
 *   失败实例追加错误行，尾组「工作区缺失」追加归属 chip 行。
 *
 * 与任务面板的一处刻意差异：空工作区也展示——任务在输入框创建、侧栏可隐藏空组；工作流
 * 的定义创建入口在管理页（组头「管理」直达），隐藏空组会让首个工作流无处可建。
 * 归属数据的工作区未注册时收进尾组「工作区缺失」（只读展示，无创建入口）。
 *
 * 行级操作收进 ⋯ 菜单（RowActions，复用宿主 .ui-row-actions 全局样式）：
 * 实例=复制工作流路径（instance.dataDir RPC 按需拉取+菜单打开预取，宿主「复制任务
 * 路径」同款链路）/取消（活跃时）/删除（行点击即打开实例页）。
 *
 * 组级「批量删除」（宿主任务面板同款交互，作用域=单工作区组）：组头 ⋯ 菜单进入 →
 * 组头工具位切「全选/删除(n)/取消」（▶/管理/⋯ 让位）、组内实例行出勾选行（活跃实例
 * 禁勾「进行中不可删除」，保留「打开」逃生口，⋯ 行菜单隐藏）→ 确认弹窗 → **串行**
 * 逐个 instance.delete（首个失败即停，已删的移出选中便于重试）；列表刷新后勾选自动
 * 清理（实例消失/转活跃），批删组消失或组内无可删实例时自动退出批删模式。
 *
 * 刷新模型（任务面板同款，推送驱动）：worker 侧定义/实例的每次落盘变更经 worker 级插件
 * 通知频道扇出 `plugin.workflow.changed`（架构 §5.2），宿主桥接为领域事件
 * `plugin-worker-event`，本面板订阅后按 kind 重拉对应列表（150ms 合并突发；kind=definition
 * 仍重拉 defs——组头 ▶ 运行窗的定义选项依赖它，只是不再渲染定义行）；面板再展示
 * / 注册表变化时重拉；另留 60s 低频兜底轮询（重连窗口内丢帧的唯一补救——插件无重连信号）。
 * 手动刷新收进组头 ⋯。busy 标记防重入；confirm / 手动复制降落伞走统一弹窗组件
 * （../Dialog，宿主模态壳 + wf-dlg-* 主体）；toast 走 ctx.ui.showToast。
 *
 * 纯逻辑可测核（无插件 web 测试基建，导出保持可测、供 ManagePanel 复用，人工清单见文件尾注释）：
 * formatTimestamp / formatRelativeTime / instanceVariablesText / exportFileName /
 * parseImportedDefinition / lastRunDefinitionId。
 */
import React from 'react'
import { Button, Checkbox } from 'antd'
import type { PluginWorkspaceEntry } from '@everyagent/plugin-api'
import { getPluginContext } from '../pluginRuntime'
import { WORKFLOW_INSTANCE_TAB_TYPE, WORKFLOW_MANAGE_TAB_TYPE } from '../tabKeys'
import { statusVisual, type RenderStatus, type StatusVisual } from '../canvas/renderStates'
import {
  rpcErrorMessage,
  workflowGateway,
  type WorkflowDefSummary,
  type WorkflowDefinition,
  type WorkflowInstanceStatus,
  type WorkflowInstanceSummary,
} from '../workflowGateway'
import SidebarScrollArea from '../SidebarScrollArea'
import { HeaderMenu, RowActions } from '../Menu'
import { openWorkspaceRunDialog } from './RunDialog'
import { confirmDialog, showManualCopyDialog } from '../Dialog'
import { copyText, copyTextSync } from '../clipboard'
import {
  ChevronDownIcon,
  CopyIcon,
  DeleteIcon,
  ManageIcon,
  RefreshIcon,
  RunIcon,
  StopIcon,
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
 * 实例运行参数展示文案：按参数名字典序稳定排序，只取值（不显示参数名），
 * 「；」分隔；空值参数跳过；无参数 → 空串（调用方据此隐藏该行）。
 */
export function instanceVariablesText(variables?: Record<string, string> | null): string {
  if (!variables) return ''
  return Object.keys(variables)
    .sort()
    .map(name => String(variables[name] ?? ''))
    .filter(value => value !== '')
    .join('；')
}

/**
 * 「最后运行的」定义 id：实例按 createdAt 降序首个、且定义仍存在者；都无 →
 * undefined。实例创建时间=运行发起时间（服务端事实源，比本地记忆多端一致）；
 * 定义已删的实例跳过——下拉只列现存定义，选中项必须真实可运行。
 */export function lastRunDefinitionId(
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

// ─── 确认弹窗 / 手动复制降落伞：统一弹窗组件（../Dialog），本面板不再自持实现 ──

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
  batchActive,
  batchSelected,
  batchDeleting,
  onOpen,
  onToggleSelect,
  onCopyPath,
  onCancel,
  onDelete,
}: {
  inst: WorkflowInstanceSummary
  /** 尾组「工作区缺失」里显示归属 id（正常分组下由组名承载归属，不显示）。 */
  workspaceChip?: string
  busy: boolean
  /** 批量删除模式（仅当实例所属组是当前批删组时为 true，宿主任务面板同款）：行点击不再打开实例，改出勾选行。 */
  batchActive: boolean
  /** 批删模式下的勾选态。 */
  batchSelected: boolean
  /** 批量删除进行中（勾选禁用）。 */
  batchDeleting: boolean
  onOpen: () => void
  /** 批删模式下切换勾选（活跃实例不可勾）。 */
  onToggleSelect: () => void
  onCopyPath: () => void
  onCancel: () => void
  onDelete: () => void
}): React.ReactElement {
  const active = instanceActive(inst.status)
  const pillColor = (INSTANCE_STATUS_PILL_COLOR as Record<string, string>)[inst.status] ?? 'var(--text-muted)'
  const varsText = instanceVariablesText(inst.variables)
  const menuItems = [
    {
      key: 'copyDataDir',
      label: '复制工作流路径',
      icon: <CopyIcon size={13} />,
      onSelect: onCopyPath,
    },
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
    <div
      className="ui-row"
      style={cardStyle}
      // 批删模式下点击行不再打开实例（勾选走行内 Checkbox，宿主任务面板同款）。
      onClick={batchActive ? undefined : onOpen}
      title={batchActive ? undefined : `实例 ${inst.id}\n点击打开实例运行页`}
    >
      {batchActive ? (
        // 勾选行（批删模式独有）：活跃实例禁勾并说明「进行中不可删除」；
        // 「打开」保留逃生口——批删模式下想看某个实例仍可一键直达。
        <div style={cardBatchRowStyle} onClick={event => event.stopPropagation()}>
          <label
            style={{
              ...checkboxLabelStyle,
              ...(active ? disabledCheckboxLabelStyle : null),
            }}
          >
            <Checkbox
              checked={batchSelected}
              disabled={active || batchDeleting}
              onChange={onToggleSelect}
            />
            <span>{active ? '进行中不可删除' : '选择删除'}</span>
          </label>
          <Button type="text" size="small" onClick={onOpen}>
            打开
          </Button>
        </div>
      ) : null}
      <div style={cardMetaRowStyle}>
        <span style={cardIdStyle}>{inst.id}</span>
        {varsText ? (
          // 运行参数跟在实例 id 后（「；」分隔只显示参数值，单行省略），
          // title 悬浮可看全量。
          <div style={cardVarsStyle} title={varsText}>{varsText}</div>
        ) : null}
        {!batchActive ? (
          <RowActions
            items={menuItems}
            title="实例更多操作"
            disabled={busy}
            // 菜单一打开就预取路径:点击「复制工作流路径」时路径已就绪,
            // 同步写入剪贴板不跨 await(非安全上下文下 execCommand 才认这个手势)。
            onOpen={() => workflowGateway.prefetchInstanceDataDir(inst.id)}
          />
        ) : null}
      </div>
      <div style={cardMetaRowStyle}>
        <span style={cardNameStyle} title={inst.definitionName || inst.definitionId}>
          {inst.definitionName || inst.definitionId}
        </span>
        <CardTime ms={inst.createdAt} label="创建于" />
        <span style={{ ...cardPillStyle, color: pillColor }}>{instanceStatusLabel(inst.status)}</span>
      </div>
      {workspaceChip ? (
        <div style={cardSubRowStyle}>
          <span style={chipStyle} title={inst.workspaceId}>{workspaceChip}</span>
        </div>
      ) : null}
      {errorMessage ? (
        <div style={cardErrorStyle}>✕ {errorMessage.slice(0, 80)}</div>
      ) : null}
    </div>
  )
}

// ─── 折叠头（组头 / 子区头共用一套交互：仅点击箭头折叠） ──────────────────────

/** 折叠头（仅组头使用：箭头折叠 + 组名 + 右侧工具位；实例行直接平铺无子区头）。 */
function CollapsibleHeader({
  label,
  labelTitle,
  collapsed,
  onToggle,
  tools,
}: {
  label: string
  labelTitle?: string
  collapsed: boolean
  onToggle: () => void
  tools?: React.ReactNode
}): React.ReactElement {
  return (
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
      <span style={groupLabelStyle} title={labelTitle}>{label}</span>
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
  /** 折叠键集合（组 ws:<id>）；不在集合=展开。 */
  const [collapsedKeys, setCollapsedKeys] = React.useState<Set<string>>(() => new Set())
  /** 行级/动作级 busy 标记（`inst:<id>`）。 */
  const [busyKey, setBusyKey] = React.useState<string | null>(null)
  /**
   * 批量删除（宿主任务面板同款交互，作用域=单工作区组）：组头 ⋯ 进入 → 组头切
   * 「全选/删除(n)/取消」、组内实例行出勾选（活跃实例不可选）→ 确认弹窗 → 串行
   * 逐个 instance.delete。只作用于一个组，防跨组误删。
   */
  /** 当前处于批量删除模式的工作区组 key；null = 未启用。 */
  const [batchGroupKey, setBatchGroupKey] = React.useState<string | null>(null)
  /** 批删模式选中的实例 id（可能含其他组的残留，派生时按组过滤）。 */
  const [selectedInstanceIds, setSelectedInstanceIds] = React.useState<string[]>([])
  /** 批量删除执行中（勾选/退出/再删全部禁用）。 */
  const [batchDeleting, setBatchDeleting] = React.useState(false)

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
    const orphanedInstances = (instances ?? []).filter(i => !known.has(i.workspaceId) || i.workspaceMissing)
    if (orphanedInstances.length > 0) {
      out.push({
        key: 'ws:__missing__',
        label: '工作区缺失',
        labelTitle: '归属工作区未注册（数据保留，重新注册后自动回归）',
        entry: null,
        defs: [],
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

  // ── 批量删除（宿主任务面板同款状态机：单组作用域 + 勾选 + 串行删除） ──

  // 当前批删作用的工作区组：批量删除只针对该组实例（instance.delete 虽按全局
  // instanceId 寻址，但选择范围锁定单组——与任务侧边栏同款纪律，防跨组误删）。
  const batchGroup = React.useMemo(
    () => (batchGroupKey ? groups?.find(group => group.key === batchGroupKey) ?? null : null),
    [batchGroupKey, groups],
  )

  /** 当前批删组内可删除（非活跃）的实例。 */
  const batchSelectableInstances = React.useMemo(
    () => batchGroup?.instances.filter(inst => !instanceActive(inst.status)) ?? [],
    [batchGroup],
  )

  /** 当前批删组内被选中的实例（删除按此执行）。 */
  const batchSelectedInstances = React.useMemo(
    () => batchGroup?.instances.filter(inst => selectedInstanceIds.includes(inst.id)) ?? [],
    [batchGroup, selectedInstanceIds],
  )

  // 列表刷新（推送/轮询）后：选中的实例已被他端删除或转为活跃（不可删）时移出选中集合，
  // 防勾选悬空（引用的实例已不存在）或勾住运行中实例。
  React.useEffect(() => {
    setSelectedInstanceIds(current =>
      current.filter(id => {
        const inst = (instances ?? []).find(item => item.id === id)
        return Boolean(inst && !instanceActive(inst.status))
      }),
    )
  }, [instances])

  // 批删组消失（工作区注销/再注册）或组内无可删除实例时自动退出批量删除模式。
  React.useEffect(() => {
    if (!batchGroupKey) return
    if (!batchGroup || batchSelectableInstances.length === 0) {
      setBatchGroupKey(null)
      setSelectedInstanceIds([])
    }
  }, [batchGroupKey, batchGroup, batchSelectableInstances])

  const allSelectableChecked = batchSelectableInstances.length > 0
    && batchSelectableInstances.every(inst => selectedInstanceIds.includes(inst.id))

  const onToggleBatchSelectAll = React.useCallback(() => {
    const ids = batchSelectableInstances.map(inst => inst.id)
    setSelectedInstanceIds(current => {
      const allChecked = ids.length > 0 && ids.every(id => current.includes(id))
      return allChecked
        ? current.filter(id => !ids.includes(id))
        : [...new Set([...current, ...ids])]
    })
  }, [batchSelectableInstances])

  const onToggleBatchInstance = (inst: WorkflowInstanceSummary) => {
    if (instanceActive(inst.status) || batchDeleting) return
    setSelectedInstanceIds(current =>
      current.includes(inst.id)
        ? current.filter(id => id !== inst.id)
        : [...current, inst.id],
    )
  }

  const onExitBatchMode = () => {
    setBatchGroupKey(null)
    setSelectedInstanceIds([])
  }

  /**
   * 批量删除选中实例（宿主任务面板同款执行策略）：确认后**串行**逐个
   * instance.delete，首个失败即停（剩余保留勾选便于重试），已删除的移出选中集合；
   * 无论成败最后重拉列表校准（推送帧到达时再校准一次）。
   */
  const onDeleteBatchSelected = () => {
    void withBusy('batch-delete', async () => {
      if (batchSelectedInstances.length === 0) return
      const ok = await confirmDialog(
        '批量删除工作流实例',
        `确定要删除选中组内的 ${batchSelectedInstances.length} 个实例吗？操作不可恢复。`,
        '删除',
      )
      if (!ok) return
      setBatchDeleting(true)
      const deletedIds: string[] = []
      try {
        for (const inst of batchSelectedInstances) {
          await workflowGateway.instanceDelete(inst.id)
          deletedIds.push(inst.id)
        }
        setBatchGroupKey(null)
        setSelectedInstanceIds([])
        toast(`已删除 ${deletedIds.length} 个实例`, 'success')
      } catch (err) {
        toast(
          `删除失败（已删 ${deletedIds.length} 个）：${rpcErrorMessage(err)}`,
          'error',
        )
        setSelectedInstanceIds(current => current.filter(id => !deletedIds.includes(id)))
      } finally {
        setBatchDeleting(false)
        reloadInstances()
      }
    })
  }

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

  /** 组头「管理」：打开本工作区的定义管理标签页（标签 id=workflow-manage:<wsId>，
   *  同一工作区重复打开聚焦同一标签；定义的增删改查/导入/导出/运行都在该页）。 */
  const onOpenManage = (wsId: string) => {
    ctx.ui.openTab(WORKFLOW_MANAGE_TAB_TYPE, { workspaceId: wsId }, '管理工作流')
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

  const onOpenInstance = (inst: WorkflowInstanceSummary) => {
    ctx.ui.openTab(
      WORKFLOW_INSTANCE_TAB_TYPE,
      { instanceId: inst.id, workspaceId: inst.workspaceId },
      inst.definitionName || inst.id,
    )
  }

  /**
   * 「复制工作流路径」：拉 workflow.instance.dataDir（workspaces/<workspaceId>/
   * workflows/instances/<instanceId>/ 在 worker 宿主上的完整绝对路径）并复制到剪贴板
   * （宿主「复制任务路径」同款链路）。路径必须由 worker 提供——前端（可能远端经 hub）
   * 无从拼出 worker 宿主上的数据目录。
   *
   * 复制**必须**在点击手势内完成：非安全上下文（局域网 http）下 `navigator.clipboard`
   * 不存在，只剩同步 execCommand，而它要求手势仍在——因此先用菜单打开时预取到的路径
   * 走同步写（见 workflowGateway.prefetchInstanceDataDir），预取没命中才退让到「取数后
   * 复制」（跨 await 可能丢手势），最终失败则弹手动复制模态，绝不只报一句失败。
   */
  const onCopyInstancePath = (inst: WorkflowInstanceSummary) => {
    const prefetched = workflowGateway.getPrefetchedInstanceDataDir(inst.id)
    if (prefetched && copyTextSync(prefetched)) {
      toast('已复制工作流路径', 'success')
      return
    }
    void (async () => {
      let path = prefetched
      let failureMessage = '复制工作流路径失败'
      try {
        if (!path) {
          path = await workflowGateway.instanceDataDir(inst.id)
        }
        if (await copyText(path)) {
          toast('已复制工作流路径', 'success')
          return
        }
        failureMessage = '浏览器拒绝了剪贴板访问'
      } catch (err) {
        failureMessage = rpcErrorMessage(err)
      }
      if (path) {
        // 路径拿到了、只是剪贴板写不进（典型：非安全上下文 + 跨 await 手势失效）。
        showManualCopyDialog('复制工作流路径', path)
        return
      }
      toast(failureMessage, 'error')
    })()
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

  return (
    <div style={shellStyle}>
      <SidebarScrollArea style={listStyle}>
        {defsError ? <div style={errorStyle}>定义列表加载失败（组头「运行」暂不可用）：{defsError}</div> : null}
        {instancesError ? <div style={errorStyle}>实例列表加载失败：{instancesError}</div> : null}
        {groups === null ? (
          <div style={emptyStyle}>正在加载…</div>
        ) : groups.length === 0 ? (
          <div style={emptyStyle}>暂无工作区——连接 worker 并注册工作区后即可使用工作流。</div>
        ) : (
          groups.map(group => {
            const collapsed = collapsedKeys.has(group.key)
            const groupDefCount = group.defs.length
            const groupEmpty = instances !== null && !instancesError && group.instances.length === 0
            const orphanChip = group.entry === null
            const groupBatchActive = group.key === batchGroupKey
            return (
              <div key={group.key} style={groupSectionStyle}>
                <CollapsibleHeader
                  label={group.label}
                  labelTitle={group.labelTitle}
                  collapsed={collapsed}
                  onToggle={() => toggleCollapsed(group.key)}
                  tools={
                    groupBatchActive ? (
                      // 批删模式独占组头工具位（▶/管理/⋯ 让位，退出后恢复）：
                      // 全选 + 删除(n) + 取消，与任务侧边栏批删控件同构。
                      <span style={batchControlsStyle}>
                        {batchSelectableInstances.length > 0 ? (
                          <label style={checkboxLabelStyle}>
                            <Checkbox
                              checked={allSelectableChecked}
                              disabled={batchDeleting}
                              onChange={onToggleBatchSelectAll}
                            />
                            <span>全选</span>
                          </label>
                        ) : null}
                        <Button
                          danger
                          size="small"
                          disabled={batchSelectedInstances.length === 0 || batchDeleting}
                          onClick={onDeleteBatchSelected}
                        >
                          {batchDeleting
                            ? '删除中…'
                            : `删除${batchSelectedInstances.length > 0 ? ` (${batchSelectedInstances.length})` : ''}`}
                        </Button>
                        <Button type="text" size="small" disabled={batchDeleting} onClick={onExitBatchMode}>
                          取消
                        </Button>
                      </span>
                    ) : (
                      <>
                        {group.entry ? (
                          <>
                            <Button
                              type="text"
                              style={toolbarButtonStyle}
                              disabled={groupDefCount === 0}
                              title={
                                groupDefCount === 0
                                  ? '此工作区暂无工作流定义（到「管理」页新建或导入）'
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
                              title="管理此工作区的工作流定义（新建 / 编辑 / 导入 / 导出 / 运行）"
                              aria-label="管理工作流定义"
                              onClick={() => group.entry && onOpenManage(group.entry.id as string)}
                            >
                              <ManageIcon size={13} />
                            </Button>
                          </>
                        ) : null}
                        <HeaderMenu
                          title="更多操作"
                          items={[
                            {
                              key: 'refresh',
                              label: '刷新',
                              icon: <RefreshIcon size={13} />,
                              onSelect: renderAll,
                            },
                            {
                              key: 'batchDelete',
                              label: '批量删除',
                              danger: true,
                              icon: <DeleteIcon size={13} />,
                              // 组内实例全部活跃（含空组）/已在批删模式/有动作进行中时不可进入。
                              disabled:
                                group.instances.every(inst => instanceActive(inst.status))
                                || batchGroupKey !== null
                                || busyKey !== null,
                              onSelect: () => {
                                setSelectedInstanceIds([])
                                setBatchGroupKey(group.key)
                              },
                            },
                          ]}
                        />
                      </>
                    )
                  }
                />
                {/* 实例平铺（无子区头/折叠/计数）；空态收一行弱文案。 */}
                {collapsed ? null : (
                  <div style={groupTasksStyle}>
                    {group.instances.map(inst => (
                      <InstanceRow
                        key={inst.id}
                        inst={inst}
                        workspaceChip={orphanChip ? inst.workspaceId : undefined}
                        busy={runBusy(`inst:${inst.id}`)}
                        batchActive={groupBatchActive}
                        batchSelected={selectedInstanceIds.includes(inst.id)}
                        batchDeleting={batchDeleting}
                        onOpen={() => onOpenInstance(inst)}
                        onToggleSelect={() => onToggleBatchInstance(inst)}
                        onCopyPath={() => onCopyInstancePath(inst)}
                        onCancel={() => onCancelInstance(inst)}
                        onDelete={() => onDeleteInstance(inst)}
                      />
                    ))}
                    {groupEmpty ? (
                      <div style={subEmptyStyle}>
                        {group.entry ? '暂无运行实例——点右上「管理」新建或运行工作流。' : '暂无数据。'}
                      </div>
                    ) : null}
                  </div>
                )}
              </div>
            )
          })
        )}
      </SidebarScrollArea>
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

const groupToolsStyle: React.CSSProperties = {
  marginLeft: 'auto',
  display: 'inline-flex',
  alignItems: 'center',
  gap: 2,
  flexShrink: 0,
}

/** 组头批量删除控件行（全选 + 删除(n) + 取消；批删模式独占组头工具位）。 */
const batchControlsStyle: React.CSSProperties = {
  marginLeft: 'auto',
  display: 'inline-flex',
  alignItems: 'center',
  gap: 6,
  flexShrink: 0,
}

/** 勾选标签（全选/行内选择共用）：Checkbox + 说明文字，与任务侧边栏同构。 */
const checkboxLabelStyle: React.CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  gap: 5,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-secondary)',
  cursor: 'pointer',
  whiteSpace: 'nowrap',
  minWidth: 0,
}

/** 活跃实例的禁用态勾选标签（不可删，弱化并去手势）。 */
const disabledCheckboxLabelStyle: React.CSSProperties = {
  color: 'var(--text-muted)',
  cursor: 'default',
}

/** 行卡片勾选行（批删模式首行）：左=勾选+说明，右=「打开」逃生口。 */
const cardBatchRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 8,
  minWidth: 0,
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

/** 实例行首列 id：粗体单行、不收缩（短 id 长度恒定，运行参数接在后面）。 */
const cardIdStyle: React.CSSProperties = {
  fontSize: 'var(--text-sm)',
  fontWeight: 700,
  flexShrink: 0,
  whiteSpace: 'nowrap',
  color: 'var(--text-primary)',
}

/** 次行工作流名：弹性占位、单行省略（title 悬浮可看全名），时间/药丸靠右。 */
const cardNameStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-secondary)',
  minWidth: 0,
  flex: 1,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

const cardMetaRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  flexWrap: 'nowrap',
  minWidth: 0,
}

/**
 * 运行参数：跟在实例 id 之后、同行尾有 ⋯ 菜单按钮，故取 flex 弹性占位 + 单行
 * 省略（原独立行刻意「溢出可见不截断」，并进首行后溢出可见会压到尾随按钮）；
 * 完整内容在悬浮 title。
 */
const cardVarsStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  flex: '1 1 0',
  minWidth: 0,
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
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

/** 次要信息行（尾组「工作区缺失」的归属 chip；正常分组不渲染）。 */
const cardSubRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  flexWrap: 'wrap',
  minWidth: 0,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
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

/*
 * ── 人工验证清单（侧边栏 · 工作区分组 · 实例平铺 · 管理页承载定义）──────────
 * 1. 打开侧边栏「工作流」：注册表里每个工作区一张分组卡片同时并陈（含空工作区，
 *    显示「暂无运行实例——点右上「管理」新建或运行工作流。」），**无**顶部下拉/工具条，
 *    **无定义子区**（定义只在管理标签页展示）；组序=注册表顺序。
 * 2. 组头：仅点击箭头折叠/展开（点组名不折叠）；右侧=▶ / 管理 / ⋯（⋯ 内仅「刷新」；
 *    **无 ＋ 新建、无导入**——两者都在管理页）。▶ 打开工作区级运行窗（选定义→填变量，
 *    无定义时禁用；见 RunDialog 清单 §6-8）；「管理」打开 workflow-manage 标签页
 *    （每工作区一个，重复点击聚焦；见 ManagePanel 尾部清单）。
 * 3. 组内实例行**直接平铺**（无「实例」子区头、无折叠箭头、无计数）。
 * 4. 实例行卡片（**单行**）：粗体工作流名 + 创建时间 + 状态药丸 + hover 浮现 ⋯ 菜单
 *    （复制工作流路径/取消实例〔活跃时〕/删除；行悬浮 title 显示实例 id）；点行打开
 *    实例页（tab.data 正确）；带运行参数的实例在下行只显示参数值（「；」分隔、单行
 *    不截断溢出可见），无参数不占行。
 * 4b. **复制工作流路径**：hover 实例行 → 打开 ⋯ 菜单（此刻发 instance.dataDir 预取）→
 *    点「复制工作流路径」→ toast「已复制工作流路径」，粘贴得到 worker 宿主上的
 *    `…/workspaces/<wsId>/workflows/instances/<wiId>/` 绝对路径；局域网 http（非安全
 *    上下文）下同样成功（预取+同步写保手势）；实例已被他端删除时点复制 → toast 报
 *    「工作流实例不存在…」；极少数环境写不进剪贴板 → 弹手动复制模态（文本域已全选，
 *    Ctrl+C 或「重试复制」可用）。
 * 5. **推送**：在管理页/编辑器保存定义 / 另一端运行工作流 / AI 调 workflow_create 工具 /
 *    实例运行态翻转（running→waiting-user→done）→ 侧栏在 ~150ms 内自动更新，**无需手动
 *    刷新**（kind=definition 的推送只校准 ▶ 运行窗选项，不渲染定义行）；取消实例 → 状态
 *    药丸转琥珀「已取消」；删除 → 条目消失。
 * 5b. **批量删除**：组头 ⋯ →「批量删除」→ 组头变「全选/删除/取消」、组内实例行出勾选行
 *     （活跃实例禁勾、显示「进行中不可删除」；「打开」可直达实例页；行 ⋯ 菜单隐藏、
 *     点行不再打开实例）→ 勾选若干 →「删除 (n)」→ 确认弹窗（不可恢复）→ 串行删除、
 *     toast「已删除 N 个实例」、自动退出批删模式；「取消」直接退出不动数据；组内实例
 *     全部活跃时菜单项禁用；删除途中 worker 报错 → 首错即停、已删计数、剩余保持勾选；
 *    批删中他端删掉选中实例/实例转活跃 → 勾选自动清理；批删组消失（工作区注销）自动退出。
 * 6. 归属工作区未注册的实例收进尾组「工作区缺失」（只读，行内 chip 显示 workspaceId）；
 *    注册表变化后尾组条目回到对应工作区组（尾组同样支持批量删除清理孤儿数据）。
 * 7. 行内时间：7 天内相对时间，点击切换绝对/相对，title 显示完整绝对时间。
 * 8. 滚动行为与任务侧边栏一致（SidebarScrollArea：滚动时浮现滑块、停后淡出）。
 */
