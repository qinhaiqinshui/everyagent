/**
 * 工作流定义编辑器标签页内容（步骤 8 核心 UI）。
 *
 * tab.data 约定（index.ts 打开编辑器时的参数袋）：
 *   { workspaceId: string   稳定工作区 id（workflow.* RPC 的 workspace 参数）
 *     definitionId?: string 定义 id；空/缺省 = 新建（空白定义，保存时后端赋 wf id）
 *     workspaceRoot?: string 工作区根（RichComposer @ 引用定向；缺省经注册表解析）
 *     workerId?: string     定向 worker（config.get / RichComposer 菜单；缺省经注册表解析）
 *   }
 *
 * 布局三栏：左 = palette（添加节点）+ 变量/onError+supervisor 折叠编辑区；
 * 中 = WorkflowCanvas(mode=edit)；右 = 属性面板（选中节点 → 类型特定字段；选中边 →
 * 八种出边条件编辑器）——与实例详情面板同款隐藏规则：仅在选中后挂载，空白点按
 * 保留内容，× 关闭清选。顶部工具栏：定义名/描述 + 保存（def.save）+ 校验错误列表。
 *
 * AI 节点会话线（无 agents 总表）：agent 名候选 = 沿边反向可达的上游 AI 节点已用名
 * （扫描逻辑见 {@link upstreamAgentDefinitions}，与后端运行时解析同语义）；
 * 下拉含「新建会话线」——本节点成为该会话线的定义节点（名 + system prompt 可编辑）；
 * 引用上游名则回显定义节点的 system prompt（只读，要改去定义节点），本节点可另配
 * 模型。引用节点不存 systemPrompt 副本，运行时由后端沿图解析（单一事实源）。
 *
 * 移动端（≤768px，useIsMobile）：左右栏退化为滑入抽屉（遮罩 + 关闭按钮，工具栏
 * 「组件 / 变量」「属性」按钮切换）；点按（tap，位移 ≤8px）选中节点/边自动弹右抽屉
 * （palette 添加节点亦然），拖动/双指平移不弹；画布平移=空白处单指或任意位置双指
 * （见 WorkflowCanvas 头注释）；「运行变量」折叠区默认展开（否则入口太深难发现）。
 * 画布占满全宽。桌面布局不受影响（抽屉样式全部收在 @media 块内）。
 *
 * 往返自测口径（无插件 web 测试基建，人工验证）：见文件底部注释「人工验证清单」。
 */
import React from 'react'
import WorkflowCanvas, {
  type CanvasEdgeView,
  type CanvasNodeView,
  type CanvasSelection,
} from '../canvas/WorkflowCanvas'
import { computeAutoLayout } from '../canvas/autoLayout'
import { getPluginContext, resolveWorkspaceRef } from '../pluginRuntime'
import { useIsMobile } from '../useIsMobile'
import {
  WorkflowValidationError,
  conditionLabel,
  localShortId,
  rpcErrorMessage,
  workflowGateway,
  type WorkflowCondition,
  type WorkflowConditionType,
  type WorkflowDefinition,
  type WorkflowEdgeDef,
  type WorkflowNodeDef,
  type WorkflowOnError,
  type WorkflowVariableDef,
} from '../workflowGateway'
import { modelConfigOptionLabel, refreshModelConfigs, type WorkflowModelConfig } from '../modelConfigStore'
import {
  AiNodeIcon,
  AutoLayoutIcon,
  CloseIcon,
  CommandNodeIcon,
  ConditionIcon,
  DeleteIcon,
  JoinModeIcon,
  PlusIcon,
  SettingsIcon,
  StartNodeIcon,
  SupervisorIcon,
  VariableIcon,
} from '../icons'

// ─── 常量 ─────────────────────────────────────────────────────────────────

/** 八种出边条件（后端 ConditionDef.KNOWN_TYPES 一一对应；hint=求值语义速查）。 */
const CONDITION_TYPES: Array<{ value: WorkflowConditionType; label: string; hint: string }> = [
  { value: 'always', label: 'always · 恒激活', hint: '无论上游成败，该边总激活（缺省）。' },
  { value: 'onSuccess', label: 'onSuccess · 成功', hint: 'exitCode == 0（AI 节点正常完成视为 0）。' },
  { value: 'onError', label: 'onError · 失败', hint: 'exitCode != 0。' },
  { value: 'exitCode', label: 'exitCode · 指定退出码', hint: '退出码等于指定 value 时激活。' },
  { value: 'outputContains', label: 'outputContains · 输出包含', hint: '输出包含指定 text 时激活。' },
  { value: 'outputNotContains', label: 'outputNotContains · 输出不含', hint: '输出不包含指定 text 时激活。' },
  { value: 'outputEquals', label: 'outputEquals · 输出等于', hint: '输出与期望文本各自去首尾空白（trim）后完全一致时激活；命令输出的尾随换行不影响。' },
  { value: 'outputMatches', label: 'outputMatches · 正则匹配', hint: '输出匹配指定 pattern；正则不可编译会在保存时被拒。' },
]

const EDITOR_STYLE_ID = 'wf-editor-styles'

/** 编辑器样式注入（插件 CSS 不经 esbuild 产物耦合，直接 <style>；幂等）。 */
function ensureEditorStyles(): void {
  if (typeof document === 'undefined' || document.getElementById(EDITOR_STYLE_ID)) return
  const style = document.createElement('style')
  style.id = EDITOR_STYLE_ID
  style.textContent = `
.wf-editor-root { display: flex; flex-direction: column; height: 100%; min-height: 0; background: var(--bg-primary, #0b0d12); color: var(--text-primary, #eef1f5); font-size: 13px; }
.wf-editor-toolbar { display: flex; gap: 8px; align-items: center; padding: 8px 12px; border-bottom: 1px solid var(--border, #2a303b); background: var(--bg-secondary, #11141b); flex-wrap: wrap; }
.wf-editor-toolbar input { flex: 0 1 auto; min-width: 120px; }
.wf-editor-body { flex: 1; display: flex; min-height: 0; }
.wf-editor-left { width: 218px; flex: none; border-right: 1px solid var(--border, #2a303b); background: var(--bg-secondary, #11141b); overflow-y: auto; padding: 10px; display: flex; flex-direction: column; gap: 8px; }
.wf-editor-mid { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.wf-editor-right { width: 292px; flex: none; border-left: 1px solid var(--border, #2a303b); background: var(--bg-secondary, #11141b); overflow-y: auto; padding: 12px; }
.wf-editor-canvas-wrap { flex: 1; min-height: 0; position: relative; }
.wf-editor-canvas-hint { position: absolute; left: 10px; bottom: 8px; font-size: 11px; color: var(--text-faint, #525c6a); pointer-events: none; }
.wf-editor-input, .wf-editor-select, .wf-editor-textarea { width: 100%; box-sizing: border-box; background: var(--bg-primary, #0b0d12); border: 1px solid var(--border, #2a303b); border-radius: 6px; color: var(--text-primary, #eef1f5); padding: 4px 8px; font-size: 13px; font-family: inherit; }
.wf-editor-textarea { min-height: 72px; resize: vertical; line-height: 1.6; }
.wf-editor-btn { display: inline-flex; align-items: center; gap: 5px; padding: 4px 10px; border: 1px solid var(--border-strong, #3b4658); border-radius: 6px; background: transparent; color: var(--text-secondary, #aab3c2); cursor: pointer; font-size: 12.5px; white-space: nowrap; }
.wf-editor-btn:hover { color: var(--text-primary, #eef1f5); border-color: var(--accent-blue, #61a5ff); }
.wf-editor-btn-primary { background: var(--accent-blue, #61a5ff); border-color: var(--accent-blue, #61a5ff); color: #0b0d12; font-weight: 600; }
.wf-editor-btn-primary:disabled { opacity: 0.55; cursor: not-allowed; }
.wf-editor-icon-btn { display: inline-flex; align-items: center; justify-content: center; width: 24px; height: 24px; border: none; border-radius: 6px; background: transparent; color: var(--text-muted, #6f7a8b); cursor: pointer; flex: none; }
.wf-editor-icon-btn:hover { color: var(--accent-red, #ff6b6b); background: var(--bg-hover, #202631); }
.wf-editor-palette-btn { display: flex; align-items: center; gap: 8px; width: 100%; padding: 8px 10px; border: 1px dashed var(--border-strong, #3b4658); border-radius: 8px; background: transparent; color: var(--text-secondary, #aab3c2); cursor: pointer; font-size: 13px; text-align: left; }
.wf-editor-palette-btn:hover { border-color: var(--accent-blue, #61a5ff); color: var(--text-primary, #eef1f5); }
.wf-editor-section { border: 1px solid var(--border-light, #1b202a); border-radius: 8px; background: var(--bg-tertiary, #171b24); }
.wf-editor-section-head { display: flex; align-items: center; gap: 6px; width: 100%; padding: 7px 10px; background: transparent; border: none; color: var(--text-secondary, #aab3c2); font-size: 12.5px; cursor: pointer; text-align: left; }
.wf-editor-section-head:hover { color: var(--text-primary, #eef1f5); }
.wf-editor-section-body { padding: 4px 10px 10px; display: flex; flex-direction: column; gap: 8px; }
.wf-editor-card { border: 1px solid var(--border-light, #1b202a); border-radius: 8px; padding: 8px; background: var(--bg-secondary, #11141b); display: flex; flex-direction: column; gap: 6px; }
.wf-editor-field { display: flex; flex-direction: column; gap: 4px; }
.wf-editor-field > label { font-size: 11.5px; color: var(--text-muted, #6f7a8b); }
.wf-editor-row { display: flex; gap: 6px; align-items: center; }
.wf-editor-hint { font-size: 11px; color: var(--text-faint, #525c6a); line-height: 1.5; margin: 0; }
.wf-editor-errors { background: var(--accent-red-dim, rgba(255,107,107,0.14)); border: 1px solid var(--accent-red, #ff6b6b); border-radius: 8px; padding: 8px 12px; margin: 8px 12px; color: var(--accent-red, #ff6b6b); font-size: 12.5px; }
.wf-editor-errors ul { margin: 4px 0 0; padding-left: 18px; }
.wf-editor-center { flex: 1; display: flex; align-items: center; justify-content: center; color: var(--text-muted, #6f7a8b); flex-direction: column; gap: 12px; }
.wf-editor-prop-title { font-size: 13px; font-weight: 600; margin: 0 0 2px; }
.wf-editor-prop-sub { font-size: 11px; color: var(--text-faint, #525c6a); margin: 0 0 10px; }
.wf-editor-divider { border-top: 1px solid var(--border-light, #1b202a); margin: 12px 0; }
.wf-editor-mask { position: absolute; inset: 0; background: rgba(4, 6, 10, 0.5); z-index: 24; }
.wf-editor-drawer-close { display: none; }
/* 属性面板关闭按钮：桌面=面板顶部吸顶条（滚动长表单时始终可点）；移动端媒体查询覆盖为抽屉右上角浮钮 */
.wf-editor-prop-close {
  position: sticky; top: 0; z-index: 6;
  display: flex; align-items: center; justify-content: flex-end;
  width: 100%; height: 30px; padding: 0 8px 0 0; margin: 0 0 2px;
  border: none; background: var(--bg-secondary, #11141b); color: var(--text-muted, #6f7a8b); cursor: pointer;
}
.wf-editor-prop-close:hover { color: var(--text-primary, #eef1f5); }
.wf-editor-toolbar-label { white-space: nowrap; }
/* ── 移动端（≤768px）：左右栏退化滑入抽屉；输入 16px 防 iOS 聚焦自动放大 ── */
@media (max-width: 768px) {
  .wf-editor-input, .wf-editor-select, .wf-editor-textarea { font-size: 16px; }
  .wf-editor-toolbar { padding: 8px 10px; gap: 6px; }
  .wf-editor-toolbar-label { display: none; }
  .wf-editor-toolbar .wf-editor-input { flex: 1 1 100%; min-width: 0; }
  .wf-editor-body { position: relative; }
  .wf-editor-left, .wf-editor-right {
    position: absolute; top: 0; bottom: 0; z-index: 30;
    max-width: 88vw; box-shadow: 0 0 24px rgba(0, 0, 0, 0.4);
    transition: transform 0.2s ease;
  }
  .wf-editor-left { left: 0; width: min(304px, 88vw); border-right: 1px solid var(--border, #2a303b); transform: translateX(-102%); }
  .wf-editor-right { right: 0; width: min(336px, 92vw); border-left: 1px solid var(--border, #2a303b); transform: translateX(102%); }
  .wf-editor-left.wf-editor-drawer--open, .wf-editor-right.wf-editor-drawer--open { transform: none; }
  .wf-editor-drawer-close {
    display: inline-flex; align-items: center; justify-content: center;
    width: 32px; height: 32px; margin: 0 0 6px; border: none; border-radius: 8px;
    background: transparent; color: var(--text-muted, #6f7a8b); cursor: pointer; align-self: flex-end;
  }
  .wf-editor-drawer-close:active { color: var(--text-primary, #eef1f5); background: var(--bg-hover, #202631); }
  .wf-editor-prop-close {
    position: absolute; top: 8px; right: 10px; z-index: 1;
    width: 32px; height: 32px; justify-content: center; margin: 0; background: transparent;
  }
  .wf-editor-prop-close:active { color: var(--text-primary, #eef1f5); background: var(--bg-hover, #202631); }
  .wf-editor-icon-btn { width: 32px; height: 32px; }
  .wf-editor-btn { padding: 6px 12px; }
  .wf-editor-canvas-hint { left: 8px; right: 8px; }
}
`
  document.head.appendChild(style)
}

// ─── 常量（会话线下拉） ────────────────────────────────────────────────────

/** AI 节点会话线下拉的「新建」哨兵值（真实 agent 名不含该前缀）。 */
const NEW_LINE_VALUE = '__new_agent_line__'

/**
 * AI 节点 system prompt 编辑 UI 的可见性开关（2026-12 暂时隐藏）。
 * 隐藏的是纯展示层：数据模型（WorkflowNodeDef.systemPrompt）、保存链路、
 * 引用节点不存副本（agent 引用时写 systemPrompt: null）与后端沿图解析
 * 全部原样保留——已有定义里的 systemPrompt 读写不受影响，重启用改回 true 即可。
 */
const SHOW_NODE_SYSTEM_PROMPT = false

// ─── 纯逻辑（往返自测的最小核：new→mutate→save payload 组装） ─────────────

/** 新建空白定义（一个 start 节点 + 默认 supervisor 策略）。 */
export function blankDefinition(workspaceId: string): WorkflowDefinition {
  return {
    schemaVersion: 1,
    id: '',
    name: '未命名工作流',
    description: '',
    workspaceId,
    variables: [],
    onError: 'supervisor',
    supervisor: { modelConfigId: null, systemPrompt: '' },
    nodes: [{ id: localShortId('wn'), type: 'start', name: '开始', x: 120, y: 280 }],
    edges: [],
  }
}

/** 画布节点副标题（类型特定简略信息）。 */
export function nodeSubtitle(n: WorkflowNodeDef): string {
  if (n.type === 'command') {
    const cmd = (n.command ?? '').trim()
    if (!cmd) return '（未配置命令）'
    const first = cmd.split(/\r?\n/)[0] ?? cmd
    return first.length > 18 ? `${first.slice(0, 17)}…` : first
  }
  if (n.type === 'ai') return n.agent ? `agent: ${n.agent}` : '一次性会话'
  return '起点'
}

/** def.nodes 缺 x/y（如 AI 经 workflow_create 建的定义）→ 网格兜底坐标。 */
export function nodePos(n: WorkflowNodeDef, index: number): { x: number; y: number } {
  if (typeof n.x === 'number' && typeof n.y === 'number') return { x: n.x, y: n.y }
  return { x: 60 + (index % 4) * 230, y: 60 + Math.floor(index / 4) * 130 }
}

/** 组装 def.save 的 payload（schemaVersion 归一 + 新建时空 id）。 */
export function buildSavePayload(d: WorkflowDefinition): WorkflowDefinition {
  return { ...d, schemaVersion: 1, id: d.id ?? '' }
}

// ─── 会话线上游扫描（与后端 NodeRunners.upstreamAgentDefinitions 同语义） ───

/**
 * 沿边反向找 {@link nodeId} 的可达祖先 AI 节点，收集它们已填的会话线名 →
 * 定义节点（拓扑序最早的持有者 =「新建那条会话线」的节点）。
 *
 * 规则与后端运行时解析严格一致（NodeRunners.upstreamAgentDefinitions）：
 * - 反向遍历按 visited 截断（工作流允许环，maxRuns 兜底）；
 * - 上游子集内做 Kahn 拓扑排序，同层按 def.nodes 列表序平局；
 *   环内残留按列表序附加（环上「定义节点」取列表序首个，语义近似）；
 * - 每个会话线名取拓扑序最早的持有节点。
 */
export function upstreamAgentDefinitions(
  def: WorkflowDefinition,
  nodeId: string,
): Map<string, WorkflowNodeDef> {
  const nodes = (def.nodes ?? []).filter(n => n && n.id)
  const edges = (def.edges ?? []).filter(e => e && e.from && e.to)
  const byId = new Map(nodes.map(n => [n.id, n]))
  const result = new Map<string, WorkflowNodeDef>()

  // ① 反向可达（不含自身）
  const incoming = new Map<string, string[]>()
  for (const e of edges) {
    const list = incoming.get(e.to) ?? []
    list.push(e.from)
    incoming.set(e.to, list)
  }
  const seen = new Set<string>()
  const stack = [...(incoming.get(nodeId) ?? [])]
  while (stack.length > 0) {
    const cur = stack.pop() as string
    if (seen.has(cur)) continue
    seen.add(cur)
    for (const prev of incoming.get(cur) ?? []) stack.push(prev)
  }
  if (seen.size === 0) return result

  // ② 上游子集拓扑序（Kahn + 列表序平局；环残留尾部附加）
  const index = new Map<string, number>()
  const subset: WorkflowNodeDef[] = []
  for (const n of nodes) {
    if (seen.has(n.id)) {
      index.set(n.id, subset.length)
      subset.push(n)
    }
  }
  const indegree = new Map<string, number>(subset.map(n => [n.id, 0]))
  for (const e of edges) {
    if (indegree.has(e.from) && indegree.has(e.to)) {
      indegree.set(e.to, (indegree.get(e.to) ?? 0) + 1)
    }
  }
  const ready = subset.filter(n => (indegree.get(n.id) ?? 0) === 0).map(n => n.id)
  const order: WorkflowNodeDef[] = []
  const emitted = new Set<string>()
  while (ready.length > 0) {
    const id = ready.shift() as string
    if (emitted.has(id)) continue
    emitted.add(id)
    const node = byId.get(id)
    if (node) order.push(node)
    for (const e of edges) {
      if (e.from !== id || !indegree.has(e.to)) continue
      const d = (indegree.get(e.to) ?? 0) - 1
      indegree.set(e.to, d)
      if (d === 0) {
        ready.push(e.to)
        ready.sort((a, b) => (index.get(a) ?? 0) - (index.get(b) ?? 0))
      }
    }
  }
  for (const n of subset) {
    if (!emitted.has(n.id)) order.push(n)
  }

  // ③ 拓扑序最早的持有者 = 定义节点
  for (const n of order) {
    if (n.type === 'ai' && n.agent && n.agent.trim() && !result.has(n.agent)) {
      result.set(n.agent, n)
    }
  }
  return result
}

// ─── 组件 ─────────────────────────────────────────────────────────────────

export interface DefinitionEditorPanelProps {
  workspaceId: string
  definitionId?: string
  workspaceRoot?: string
  workerId?: string
}

export default function DefinitionEditorPanel({
  workspaceId,
  definitionId,
  workspaceRoot,
  workerId,
}: DefinitionEditorPanelProps) {
  ensureEditorStyles()
  const ui = getPluginContext().ui

  const [def, setDef] = React.useState<WorkflowDefinition | null>(null)
  const [loading, setLoading] = React.useState(true)
  const [loadError, setLoadError] = React.useState<string | null>(null)
  const [reloadNonce, setReloadNonce] = React.useState(0)
  const [saving, setSaving] = React.useState(false)
  const [saveError, setSaveError] = React.useState<string[] | null>(null)
  const [dirty, setDirty] = React.useState(false)
  const [selection, setSelection] = React.useState<CanvasSelection>(null)
  const [modelConfigs, setModelConfigs] = React.useState<WorkflowModelConfig[]>([])
  const [resolvedRoot, setResolvedRoot] = React.useState(workspaceRoot ?? '')
  const [resolvedWorker, setResolvedWorker] = React.useState(workerId ?? '')
  const [fallbackTokenLabel, setFallbackTokenLabel] = React.useState('')
  const viewportCenterRef = React.useRef<{ x: number; y: number } | null>(null)
  // ── 移动端抽屉（≤768px）：left=palette/变量/策略，right=属性面板。
  const isMobile = useIsMobile()
  const [mobileDrawer, setMobileDrawer] = React.useState<'none' | 'left' | 'right'>('none')
  // 自动布局 nonce：自增驱动画布视口滚回原点（重排后新网格从原点铺开）。
  const [viewResetNonce, setViewResetNonce] = React.useState(0)

  // ── 加载：defGet（新建=空白定义）+ config.get 重拉（插件收不到 config.changed，每次打开重拉）。
  React.useEffect(() => {
    let cancelled = false
    const load = async () => {
      if (!workspaceId) {
        setLoadError('缺少 workspaceId（打开编辑器时未携带工作区参数）')
        setLoading(false)
        return
      }
      setLoading(true)
      setLoadError(null)
      try {
        let root = workspaceRoot ?? ''
        let worker = workerId ?? ''
        if (!root || !worker) {
          const ref = await resolveWorkspaceRef(workspaceId)
          if (ref) {
            root = root || ref.root
            worker = worker || ref.workerId
          }
        }
        const [loaded, configs] = await Promise.all([
          definitionId
            ? workflowGateway.defGet(workspaceId, definitionId)
            : Promise.resolve<WorkflowDefinition | null>(null),
          (worker
            ? refreshModelConfigs(worker)
            : Promise.resolve<WorkflowModelConfig[]>([])).catch(() => [] as WorkflowModelConfig[]),
        ])
        if (cancelled) return
        setResolvedRoot(root)
        setResolvedWorker(worker)
        setModelConfigs(configs)
        setDef(loaded ?? blankDefinition(workspaceId))
        setDirty(!loaded)
      } catch (err) {
        if (!cancelled) setLoadError(rpcErrorMessage(err))
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    void load()
    return () => {
      cancelled = true
    }
  }, [workspaceId, definitionId, workspaceRoot, workerId, reloadNonce])

  // ── 定义状态更新入口（统一置 dirty）。
  const updateDef = React.useCallback((updater: (d: WorkflowDefinition) => WorkflowDefinition) => {
    setDef(cur => (cur ? updater(cur) : cur))
    setDirty(true)
    setSaveError(null)
  }, [])

  // ── 选中入口（画布点选）：空白点按不清选——属性面板保留最近选中，关闭只走
  // closeProps；移动端空白 tap 收起抽屉（选中保留，再点节点重开）。
  const handleSelect = React.useCallback((sel: CanvasSelection) => {
    if (sel !== null) setSelection(sel)
    setMobileDrawer(sel ? (isMobile ? 'right' : 'none') : 'none')
  }, [isMobile])

  /** 关闭属性面板（× 按钮）：唯一清选入口（删除选中项后也走此处语义）。 */
  const closeProps = React.useCallback(() => {
    setSelection(null)
    setMobileDrawer('none')
  }, [])

  const patchNode = React.useCallback((id: string, patch: Partial<WorkflowNodeDef>) => {
    updateDef(d => ({ ...d, nodes: (d.nodes ?? []).map(n => (n.id === id ? { ...n, ...patch } : n)) }))
  }, [updateDef])

  const patchEdge = React.useCallback((id: string, patch: Partial<WorkflowEdgeDef>) => {
    updateDef(d => ({ ...d, edges: (d.edges ?? []).map(e => (e.id === id ? { ...e, ...patch } : e)) }))
  }, [updateDef])

  // ── palette：新节点落画布可视中心 + 少量层叠偏移（id=wn_ 前端本地短 id）。
  const addNode = React.useCallback((type: 'command' | 'ai') => {
    const center = viewportCenterRef.current ?? { x: 480, y: 340 }
    const id = localShortId('wn')
    updateDef(d => {
      const offset = ((d.nodes ?? []).length % 8) * 26
      const base: WorkflowNodeDef = {
        id,
        type,
        name: type === 'command' ? '新命令节点' : '新 AI 节点',
        x: center.x + offset,
        y: center.y + offset,
      }
      if (type === 'command') {
        base.command = ''
        base.timeoutMs = 60000
        base.maxRuns = 2
      } else {
        base.input = ''
        base.maxRuns = 2
      }
      return { ...d, nodes: [...(d.nodes ?? []), base] }
    })
    handleSelect({ kind: 'node', id })
  }, [updateDef, handleSelect])

  // ── 连线（画布锚点拖出 → 目标节点）：去重/自环由画布命中测试排除，条件缺省 always。
  const createEdge = React.useCallback((from: string, to: string) => {
    const id = localShortId('we')
    updateDef(d => ({
      ...d,
      edges: [...(d.edges ?? []), { id, from, to, condition: { type: 'always' } }],
    }))
    handleSelect({ kind: 'edge', id })
  }, [updateDef, handleSelect])

  // ── 删除选中（节点连带引用它的边；start 节点禁删——校验器要求唯一 start）。
  const deleteSelection = React.useCallback(() => {
    if (!selection) return
    if (selection.kind === 'node') {
      const node = (def?.nodes ?? []).find(n => n.id === selection.id)
      if (!node) return
      if (node.type === 'start') {
        ui.showToast('开始节点不可删除（每个定义有且仅有一个 start）', 'warning')
        return
      }
      updateDef(d => ({
        ...d,
        nodes: (d.nodes ?? []).filter(n => n.id !== selection.id),
        edges: (d.edges ?? []).filter(e => e.from !== selection.id && e.to !== selection.id),
      }))
    } else {
      updateDef(d => ({ ...d, edges: (d.edges ?? []).filter(e => e.id !== selection.id) }))
    }
    // 显式清选（handleSelect 对 null 不再清选）：选中项已删，面板随之卸载。
    setSelection(null)
    setMobileDrawer('none')
  }, [selection, def, updateDef, ui])

  // Delete 键删除（输入控件聚焦时不拦截）；用 ref 防闭包过期。
  const deleteRef = React.useRef<() => void>(() => {})
  deleteRef.current = deleteSelection
  React.useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Delete') return
      const t = e.target as HTMLElement | null
      if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable)) return
      deleteRef.current()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  // ── 保存（def.save；校验错误逐项展示——BAD_PARAMS 的「; 」join 拆回列表）。
  const handleSave = React.useCallback(async () => {
    if (!def || !workspaceId || saving) return
    setSaving(true)
    setSaveError(null)
    try {
      const { id } = await workflowGateway.defSave(workspaceId, buildSavePayload(def))
      const isNew = !def.id
      setDef(cur => (cur ? { ...cur, id, workspaceId } : cur))
      setDirty(false)
      ui.showToast(isNew ? `工作流已创建：${id}` : '工作流定义已保存', 'success')
    } catch (err) {
      if (err instanceof WorkflowValidationError) {
        setSaveError(err.items)
      } else {
        setSaveError([rpcErrorMessage(err)])
      }
    } finally {
      setSaving(false)
    }
  }, [def, workspaceId, saving, ui])

  // ── 自动布局（工具栏按钮）：Sugiyama 简化分层（算法见 canvas/autoLayout.ts）。
  //    结果经 updateDef 一次性写回全部节点 x/y——与手工拖拽同一条定义更新链
  //    （dirty/保存语义一致），节点其余字段与定义其它部分不动；视口带回原点。
  const handleAutoLayout = React.useCallback(() => {
    if (!def) return
    const nodeCount = (def.nodes ?? []).length
    if (nodeCount === 0) {
      ui.showToast('定义中没有节点，无需布局', 'info')
      return
    }
    const layout = computeAutoLayout(def)
    updateDef(d => ({
      ...d,
      nodes: (d.nodes ?? []).map(n => {
        const p = layout.positions.get(n.id)
        return p ? { ...n, x: Math.round(p.x), y: Math.round(p.y) } : n
      }),
    }))
    setViewResetNonce(n => n + 1)
    const parts = [`${nodeCount} 个节点排为 ${layout.layerCount} 层`]
    if (layout.backEdgeCount > 0) parts.push(`${layout.backEdgeCount} 条回边留作回线`)
    if (layout.orphanCount > 0) parts.push(`孤立节点 ${layout.orphanCount} 个集中在右侧`)
    ui.showToast(`已自动布局：${parts.join('；')}（可继续手工微调，保存后持久化）`, 'success')
  }, [def, ui, updateDef])

  // ── 画布视图模型组装（缺坐标兜底 + 副标题 + 条件短标签）。
  const canvasNodes: CanvasNodeView[] = React.useMemo(() => {
    return (def?.nodes ?? []).map((n, i) => {
      const pos = nodePos(n, i)
      return {
        id: n.id,
        type: n.type,
        name: n.name,
        x: pos.x,
        y: pos.y,
        subtitle: nodeSubtitle(n),
        joinMode: n.joinMode,
      }
    })
  }, [def])

  const canvasEdges: CanvasEdgeView[] = React.useMemo(() => {
    return (def?.edges ?? []).map(e => ({
      id: e.id,
      from: e.from,
      to: e.to,
      conditionLabel: conditionLabel(e.condition),
    }))
  }, [def])

  const selectedNode = selection?.kind === 'node' ? (def?.nodes ?? []).find(n => n.id === selection.id) ?? null : null
  const selectedEdge = selection?.kind === 'edge' ? (def?.edges ?? []).find(e => e.id === selection.id) ?? null : null

  // ── 渲染分支：加载/错误态。
  if (loading) {
    return (
      <div className="wf-editor-root">
        <div className="wf-editor-center"><span>正在加载工作流定义…</span></div>
      </div>
    )
  }
  if (loadError) {
    return (
      <div className="wf-editor-root">
        <div className="wf-editor-center">
          <span>加载失败：{loadError}</span>
          <button className="wf-editor-btn" onClick={() => setReloadNonce(n => n + 1)}>重试</button>
        </div>
      </div>
    )
  }
  if (!def) return <div className="wf-editor-root" />

  const onErrorValue: WorkflowOnError = def.onError ?? 'supervisor'

  return (
    <div className="wf-editor-root">
      {/* ── 顶部工具栏 ── */}
      <div className="wf-editor-toolbar">
        <span
          className="wf-editor-toolbar-label"
          style={{ color: 'var(--text-faint, #525c6a)', fontSize: 12 }}
        >
          {definitionId ?? '新建定义'}
        </span>
        <input
          className="wf-editor-input"
          style={isMobile ? { flex: '1 1 100%' } : { width: 200 }}
          value={def.name}
          placeholder="定义名"
          onChange={e => updateDef(d => ({ ...d, name: e.target.value }))}
        />
        <input
          className="wf-editor-input"
          style={isMobile ? { flex: '1 1 100%' } : { flex: 1, minWidth: 160 }}
          value={def.description ?? ''}
          placeholder="描述（可选）"
          onChange={e => updateDef(d => ({ ...d, description: e.target.value }))}
        />
        {dirty ? <span title="有未保存更改" style={{ color: 'var(--accent-amber, #f7bd54)' }}>●</span> : null}
        <button
          type="button"
          className="wf-editor-btn"
          title="按图结构一键分层排布：回指边留作回线、孤立节点靠右；重写全部节点坐标（可继续手工微调）"
          onClick={handleAutoLayout}
        >
          <AutoLayoutIcon size={15} /> 自动布局
        </button>
        {isMobile ? (
          <>
            <button
              type="button"
              className={`wf-editor-btn${mobileDrawer === 'left' ? ' wf-editor-btn-primary' : ''}`}
              title="节点组件与变量/策略（移动端抽屉）"
              onClick={() => setMobileDrawer(d => (d === 'left' ? 'none' : 'left'))}
            >
              组件 / 变量
            </button>
            <button
              type="button"
              className={`wf-editor-btn${mobileDrawer === 'right' ? ' wf-editor-btn-primary' : ''}`}
              title="选中节点/边的属性面板（移动端抽屉）"
              onClick={() => setMobileDrawer(d => (d === 'right' ? 'none' : 'right'))}
            >
              属性
            </button>
          </>
        ) : null}
        <button className="wf-editor-btn wf-editor-btn-primary" onClick={() => void handleSave()} disabled={saving}>
          {saving ? '保存中…' : '保存'}
        </button>
      </div>

      {/* ── 校验错误列表（后端逐项） ── */}
      {saveError && saveError.length > 0 ? (
        <div className="wf-editor-errors">
          <div style={{ fontWeight: 600 }}>保存被拒绝（{saveError.length} 项，修正后重试）：</div>
          <ul>
            {saveError.map((it, i) => (
              <li key={i}>{it}</li>
            ))}
          </ul>
        </div>
      ) : null}

      <div className="wf-editor-body">
        {/* ── 移动端抽屉遮罩 ── */}
        {isMobile && mobileDrawer !== 'none' ? (
          <div className="wf-editor-mask" onClick={() => setMobileDrawer('none')} />
        ) : null}

        {/* ── 左栏：palette + 变量/策略（移动端=左滑抽屉） ── */}
        <div className={`wf-editor-left${isMobile && mobileDrawer === 'left' ? ' wf-editor-drawer--open' : ''}`}>
          {isMobile ? (
            <button type="button" className="wf-editor-drawer-close" title="收起面板" onClick={() => setMobileDrawer('none')}>
              <CloseIcon size={16} />
            </button>
          ) : null}
          <div style={{ fontSize: 11.5, color: 'var(--text-muted, #6f7a8b)' }}>添加节点</div>
          <button className="wf-editor-palette-btn" onClick={() => addNode('command')} title="添加命令节点">
            <CommandNodeIcon size={15} /> 命令节点
          </button>
          <button className="wf-editor-palette-btn" onClick={() => addNode('ai')} title="添加 AI 节点">
            <AiNodeIcon size={15} /> AI 节点
          </button>
          <div className="wf-editor-hint" style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
            <StartNodeIcon size={13} /> 开始节点唯一（新建自带）
          </div>

          <VariablesSection def={def} updateDef={updateDef} />
          <PolicySection def={def} onErrorValue={onErrorValue} modelConfigs={modelConfigs} updateDef={updateDef} />
        </div>

        {/* ── 中栏：画布 ── */}
        <div className="wf-editor-mid">
          <div className="wf-editor-canvas-wrap">
            <WorkflowCanvas
              nodes={canvasNodes}
              edges={canvasEdges}
              mode="edit"
              selection={selection}
              onSelect={handleSelect}
              onNodeMove={(id, x, y) => patchNode(id, { x, y })}
              onCreateEdge={createEdge}
              viewportCenterRef={viewportCenterRef}
              resetViewNonce={viewResetNonce}
              style={{ position: 'absolute', inset: 0 }}
            />
            <div className="wf-editor-canvas-hint">
              {isMobile
                ? '点按节点/边 → 「属性」面板编辑 · 拖动移动节点 · 空白处或任意位置双指拖动平移画布 · 变量在「组件 / 变量」面板'
                : '拖动节点移动 · 从右侧锚点拖出连线 · 点击选中 · Delete 删除（连线标签为出边条件）'}
            </div>
          </div>
        </div>

        {/* ── 右栏：属性面板（移动端=右滑抽屉）；与实例详情面板同款隐藏规则——
            仅在选中节点/边后挂载（无选中不占位、画布占满），空白点按保留内容，
            关闭只走 ×（清选卸载）。 ── */}
        {selectedNode || selectedEdge ? (
          <div className={`wf-editor-right${isMobile && mobileDrawer === 'right' ? ' wf-editor-drawer--open' : ''}`}>
            <button
              type="button"
              className="wf-editor-prop-close"
              title="关闭属性面板"
              onClick={closeProps}
            >
              <CloseIcon size={16} />
            </button>
            {selectedNode ? (
              <NodePropertySection
                node={selectedNode}
                def={def}
                modelConfigs={modelConfigs}
                resolvedRoot={resolvedRoot}
                resolvedWorker={resolvedWorker}
                fallbackTokenLabel={fallbackTokenLabel}
                setFallbackTokenLabel={setFallbackTokenLabel}
                patchNode={patchNode}
                updateDef={updateDef}
                onDelete={deleteSelection}
              />
            ) : selectedEdge ? (
              <EdgePropertySection
                edge={selectedEdge}
                def={def}
                patchEdge={patchEdge}
                onDelete={deleteSelection}
              />
            ) : null}
          </div>
        ) : null}
      </div>
    </div>
  )
}

// ─── 左栏折叠区：变量声明 ─────────────────────────────────────────────────

function Section({
  icon,
  title,
  count,
  defaultOpen,
  children,
}: React.PropsWithChildren<{ icon: React.ReactNode; title: string; count?: number; defaultOpen?: boolean }>) {
  const [open, setOpen] = React.useState(defaultOpen ?? false)
  return (
    <div className="wf-editor-section">
      <button className="wf-editor-section-head" onClick={() => setOpen(o => !o)}>
        {icon}
        <span style={{ flex: 1 }}>{title}</span>
        {typeof count === 'number' ? (
          <span style={{ color: 'var(--text-faint, #525c6a)' }}>{count}</span>
        ) : null}
        <span style={{ fontSize: 10 }}>{open ? '▾' : '▸'}</span>
      </button>
      {open ? <div className="wf-editor-section-body">{children}</div> : null}
    </div>
  )
}

function VariablesSection({
  def,
  updateDef,
}: {
  def: WorkflowDefinition
  updateDef: (updater: (d: WorkflowDefinition) => WorkflowDefinition) => void
}) {
  const vars = def.variables ?? []
  const patch = (i: number, patch: Partial<WorkflowVariableDef>) => {
    updateDef(d => ({ ...d, variables: (d.variables ?? []).map((v, j) => (j === i ? { ...v, ...patch } : v)) }))
  }
  const add = () => {
    updateDef(d => ({
      ...d,
      variables: [...(d.variables ?? []), { name: '', label: '', required: false, defaultValue: '', description: '' }],
    }))
  }
  const remove = (i: number) => {
    updateDef(d => ({ ...d, variables: (d.variables ?? []).filter((_, j) => j !== i) }))
  }
  return (
    <Section icon={<VariableIcon size={14} />} title="运行变量" count={vars.length} defaultOpen>
      {vars.length === 0 ? <p className="wf-editor-hint">未声明变量。command 与 input 支持 {'{{变量名}}'} 渲染。</p> : null}
      {vars.map((v, i) => (
        <div className="wf-editor-card" key={i}>
          <div className="wf-editor-row">
            <input
              className="wf-editor-input"
              style={{ flex: 1 }}
              value={v.name ?? ''}
              placeholder="name"
              onChange={e => patch(i, { name: e.target.value })}
            />
            <button className="wf-editor-icon-btn" title="删除变量" onClick={() => remove(i)}>
              <DeleteIcon size={13} />
            </button>
          </div>
          <div className="wf-editor-row">
            <input
              className="wf-editor-input"
              style={{ flex: 1 }}
              value={v.label ?? ''}
              placeholder="label（显示名）"
              onChange={e => patch(i, { label: e.target.value })}
            />
            <label className="wf-editor-hint" style={{ display: 'flex', alignItems: 'center', gap: 3, whiteSpace: 'nowrap' }}>
              <input
                type="checkbox"
                checked={v.required === true}
                onChange={e => patch(i, { required: e.target.checked })}
              />
              必填
            </label>
          </div>
          <input
            className="wf-editor-input"
            value={v.defaultValue ?? ''}
            placeholder="defaultValue（可选）"
            onChange={e => patch(i, { defaultValue: e.target.value })}
          />
          <input
            className="wf-editor-input"
            value={v.description ?? ''}
            placeholder="description（可选）"
            onChange={e => patch(i, { description: e.target.value })}
          />
        </div>
      ))}
      <button className="wf-editor-btn" onClick={add}>
        <PlusIcon size={12} /> 添加变量
      </button>
    </Section>
  )
}

// ─── 左栏折叠区：onError 策略 + supervisor 配置 ───────────────────────────

function PolicySection({
  def,
  onErrorValue,
  modelConfigs,
  updateDef,
}: {
  def: WorkflowDefinition
  onErrorValue: WorkflowOnError
  modelConfigs: WorkflowModelConfig[]
  updateDef: (updater: (d: WorkflowDefinition) => WorkflowDefinition) => void
}) {
  const setSupervisor = (patch: { modelConfigId?: string | null; systemPrompt?: string | null }) => {
    updateDef(d => ({
      ...d,
      supervisor: { ...(d.supervisor ?? {}), ...patch },
    }))
  }
  return (
    <Section icon={<SettingsIcon size={14} />} title="出错策略与督导">
      <div className="wf-editor-field">
        <label>onError（默认 supervisor）</label>
        <select
          className="wf-editor-select"
          value={onErrorValue}
          onChange={e => updateDef(d => ({ ...d, onError: e.target.value as WorkflowOnError }))}
        >
          <option value="fail">fail · 实例直接失败</option>
          <option value="supervisor">supervisor · 督导 agent 介入</option>
          <option value="askUser">askUser · 挂起等待用户决策</option>
        </select>
      </div>
      {onErrorValue === 'supervisor' ? (
        <>
          <div className="wf-editor-field">
            <label>
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                <SupervisorIcon size={12} /> 督导模型配置
              </span>
            </label>
            <select
              className="wf-editor-select"
              value={def.supervisor?.modelConfigId ?? ''}
              onChange={e => setSupervisor({ modelConfigId: e.target.value || null })}
            >
              <option value="">（系统默认模型）</option>
              {modelConfigs.map(c => (
                <option key={c.configId} value={c.configId}>{modelConfigOptionLabel(c)}</option>
              ))}
              {def.supervisor?.modelConfigId && !modelConfigs.some(c => c.configId === def.supervisor?.modelConfigId) ? (
                <option value={def.supervisor.modelConfigId}>{def.supervisor.modelConfigId}（缺失）</option>
              ) : null}
            </select>
          </div>
          <div className="wf-editor-field">
            <label>督导 system prompt（空 = 默认模板）</label>
            <textarea
              className="wf-editor-textarea"
              style={{ minHeight: 56 }}
              value={def.supervisor?.systemPrompt ?? ''}
              placeholder="你是工作流督导 agent：只输出 JSON 决策……"
              onChange={e => setSupervisor({ systemPrompt: e.target.value || null })}
            />
          </div>
        </>
      ) : (
        <p className="wf-editor-hint">当前策略不消费督导配置。</p>
      )}
    </Section>
  )
}

// ─── 右栏：节点属性（类型特定字段） ───────────────────────────────────────

function NodePropertySection({
  node,
  def,
  modelConfigs,
  resolvedRoot,
  resolvedWorker,
  fallbackTokenLabel,
  setFallbackTokenLabel,
  patchNode,
  updateDef,
  onDelete,
}: {
  node: WorkflowNodeDef
  def: WorkflowDefinition
  modelConfigs: WorkflowModelConfig[]
  resolvedRoot: string
  resolvedWorker: string
  fallbackTokenLabel: string
  setFallbackTokenLabel: (v: string) => void
  patchNode: (id: string, patch: Partial<WorkflowNodeDef>) => void
  updateDef: (updater: (d: WorkflowDefinition) => WorkflowDefinition) => void
  onDelete: () => void
}) {
  const ui = getPluginContext().ui

  /** 上游会话线扫描（候选下拉 + 引用回显）：与后端运行时解析同语义。 */
  const upstreamAgents = React.useMemo(
    () => upstreamAgentDefinitions(def, node.id),
    [def, node.id],
  )
  /** 引用模式：agent 名来自上游定义节点（systemPrompt 只读回显，不存本节点）。 */
  const referencing = !!node.agent && upstreamAgents.has(node.agent)
  const definingNode = referencing ? upstreamAgents.get(node.agent ?? '') ?? null : null

  /** 条件可见性：仅当节点有出边时提示条件在边上编辑。 */
  const outEdges = (def.edges ?? []).filter(e => e.from === node.id)

  return (
    <div>
      <p className="wf-editor-prop-title">{node.name || node.id}</p>

      <div className="wf-editor-field">
        <label>节点名称</label>
        <input
          className="wf-editor-input"
          value={node.name}
          placeholder="节点名"
          onChange={e => patchNode(node.id, { name: e.target.value })}
        />
      </div>

      {/* ── 命令节点 ── */}
      {node.type === 'command' ? (
        <>
          <div className="wf-editor-field">
            <label>command（shell 执行；支持 {'{{变量}}'} 渲染）</label>
            <textarea
              className="wf-editor-textarea"
              value={node.command ?? ''}
              placeholder={'mkdir -p out/{{novelName}}'}
              onChange={e => patchNode(node.id, { command: e.target.value })}
            />
          </div>
          <div className="wf-editor-row">
            <div className="wf-editor-field" style={{ flex: 1 }}>
              <label>timeoutMs（超时，仅命令节点）</label>
              <input
                className="wf-editor-input"
                type="number"
                min={1}
                value={node.timeoutMs ?? 60000}
                onChange={e => patchNode(node.id, { timeoutMs: e.target.value ? Number(e.target.value) : null })}
              />
            </div>
            <div className="wf-editor-field" style={{ flex: 1 }}>
              <label>maxRuns（循环上限 1-10）</label>
              <input
                className="wf-editor-input"
                type="number"
                min={1}
                max={10}
                value={node.maxRuns ?? 2}
                onChange={e => patchNode(node.id, { maxRuns: e.target.value ? Number(e.target.value) : null })}
              />
            </div>
          </div>
        </>
      ) : null}

      {/* ── AI 节点 ── */}
      {node.type === 'ai' ? (
        <>
          <div className="wf-editor-field">
            <label>input 输入模板（支持 {'{{变量}}'} 与胶囊引用）</label>
            {ui.RichComposer ? (
              <ui.RichComposer
                value={node.input ?? ''}
                onChange={v => patchNode(node.id, { input: v })}
                placeholder="AI 节点输入模板……"
                workspace={resolvedRoot || undefined}
                workerId={resolvedWorker || undefined}
                enableAt
              />
            ) : (
              <>
                <textarea
                  className="wf-editor-textarea"
                  value={node.input ?? ''}
                  placeholder="AI 节点输入模板……（宿主未注入富输入框）"
                  onChange={e => patchNode(node.id, { input: e.target.value })}
                />
                {/* 降级路径的胶囊手工插入：buildComposerToken 构造 opaque token 追加进模板 */}
                <div className="wf-editor-row" style={{ marginTop: 4 }}>
                  <input
                    className="wf-editor-input"
                    style={{ flex: 1 }}
                    value={fallbackTokenLabel}
                    placeholder="胶囊标签（如文件路径）"
                    onChange={e => setFallbackTokenLabel(e.target.value)}
                  />
                  <button
                    className="wf-editor-btn"
                    onClick={() => {
                      const label = fallbackTokenLabel.trim()
                      if (!label) return
                      const token = ui.buildComposerToken({ kind: 'file', label, payload: { path: label } })
                      patchNode(node.id, { input: `${node.input ?? ''}${node.input ? ' ' : ''}${token}` })
                      setFallbackTokenLabel('')
                    }}
                  >
                    插入胶囊
                  </button>
                </div>
              </>
            )}
          </div>
          <div className="wf-editor-field">
            <label>agent名</label>
            <select
              className="wf-editor-select"
              value={referencing ? node.agent ?? '' : NEW_LINE_VALUE}
              onChange={e => {
                if (e.target.value === NEW_LINE_VALUE) {
                  // 切回新建模式：清名（systemPrompt 留给用户重填；引用值不落本节点）
                  patchNode(node.id, { agent: '' })
                } else {
                  // 引用上游会话线：不存 systemPrompt 副本（定义节点是唯一事实源）
                  patchNode(node.id, { agent: e.target.value, systemPrompt: null })
                }
              }}
            >
              <option value={NEW_LINE_VALUE}>＋ 新建会话线…</option>
              {[...upstreamAgents.entries()].map(([name, defNode]) => (
                <option key={name} value={name}>
                  {name} · 由「{defNode.name || defNode.id}」定义
                </option>
              ))}
            </select>
            {upstreamAgents.size === 0 ? (
              <p className="wf-editor-hint">上游暂无已建立的会话线——在本节点新建一条，下游节点即可延续。</p>
            ) : null}
          </div>
          {referencing ? (
            SHOW_NODE_SYSTEM_PROMPT ? (
              <>
                <div className="wf-editor-field">
                  <label>system prompt（来自定义节点「{definingNode?.name || definingNode?.id || node.agent}」，只读）</label>
                  <textarea
                    className="wf-editor-textarea"
                    style={{ minHeight: 56, opacity: 0.85 }}
                    value={definingNode?.systemPrompt ?? ''}
                    placeholder="（定义节点未填写 system prompt）"
                    readOnly
                  />
                </div>
                <p className="wf-editor-hint">
                  要修改系统提示词，请在画布选中上游定义节点「{definingNode?.name || definingNode?.id || node.agent}」；
                  本节点仅延续会话线，可另配模型。
                </p>
              </>
            ) : null
          ) : (
            <>
              <div className="wf-editor-field">
                <label>会话线名（agent；本节点为该会话线的定义节点）</label>
                <input
                  className="wf-editor-input"
                  value={node.agent ?? ''}
                  placeholder="如 writer"
                  onChange={e => patchNode(node.id, { agent: e.target.value })}
                />
              </div>
              {SHOW_NODE_SYSTEM_PROMPT ? (
                <div className="wf-editor-field">
                  <label>system prompt（可编辑；下游引用本会话线的节点只读回显）</label>
                  <textarea
                    className="wf-editor-textarea"
                    style={{ minHeight: 56 }}
                    value={node.systemPrompt ?? ''}
                    placeholder="system prompt（可选）"
                    onChange={e => patchNode(node.id, { systemPrompt: e.target.value || null })}
                  />
                </div>
              ) : null}
            </>
          )}
          <div className="wf-editor-field">
            <label>模型配置（本节点生效；空 = 系统默认模型）</label>
            <select
              className="wf-editor-select"
              value={node.modelConfigId ?? ''}
              onChange={e => patchNode(node.id, { modelConfigId: e.target.value || null })}
            >
              <option value="">（系统默认模型）</option>
              {modelConfigs.map(c => (
                <option key={c.configId} value={c.configId}>{modelConfigOptionLabel(c)}</option>
              ))}
              {node.modelConfigId && !modelConfigs.some(c => c.configId === node.modelConfigId) ? (
                <option value={node.modelConfigId}>{node.modelConfigId}（缺失）</option>
              ) : null}
            </select>
          </div>
          <div className="wf-editor-field">
            <label>maxRuns（循环上限 1-10）</label>
            <input
              className="wf-editor-input"
              type="number"
              min={1}
              max={10}
              value={node.maxRuns ?? 2}
              onChange={e => patchNode(node.id, { maxRuns: e.target.value ? Number(e.target.value) : null })}
            />
          </div>
        </>
      ) : null}

      {/* ── 汇合模式（command/ai 有入边语义；start 无入边不显示）。写回走
          patchNode（与其它字段同一条 dirty/保存链）；选「每次到达即执行」写回
          null——后端 NON_NULL 序列化落盘不带该字段，序列化干净。 ── */}
      {node.type !== 'start' ? (
        <div className="wf-editor-field">
          <label>
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
              <JoinModeIcon size={12} /> 汇合模式（joinMode）
            </span>
          </label>
          <select
            className="wf-editor-select"
            value={node.joinMode === 'all' ? 'all' : 'none'}
            onChange={e => patchNode(node.id, { joinMode: e.target.value === 'all' ? 'all' : null })}
          >
            <option value="none">每次到达即执行（none）</option>
            <option value="all">等齐全部入边（all）</option>
          </select>
          <p className="wf-editor-hint">
            缺省=每条激活入边到达即执行一次；「等齐」=汇合屏障，等齐全部入边各激活一次后才触发一次执行
            （画布上该节点右上角出现「等齐」角标）。
          </p>
        </div>
      ) : null}

      {node.type === 'start' ? (
        <p className="wf-editor-hint" style={{ marginTop: 6 }}>
          开始节点先行执行，全图唯一；无类型特定字段。
        </p>
      ) : null}

      {outEdges.length > 0 ? (
        <>
          <div className="wf-editor-divider" />
          <p className="wf-editor-hint" style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
            <ConditionIcon size={13} /> 该节点有 {outEdges.length} 条出边——选中边可在右侧编辑出边条件。
          </p>
        </>
      ) : null}

      {node.type !== 'start' ? (
        <>
          <div className="wf-editor-divider" />
          <button className="wf-editor-btn" onClick={onDelete} style={{ color: 'var(--accent-red, #ff6b6b)' }}>
            <DeleteIcon size={13} /> 删除节点（Delete）
          </button>
        </>
      ) : null}
    </div>
  )
}

// ─── 右栏：边条件编辑器（八种类型 + 参数按类型显隐） ───────────────────────

function EdgePropertySection({
  edge,
  def,
  patchEdge,
  onDelete,
}: {
  edge: WorkflowEdgeDef
  def: WorkflowDefinition
  patchEdge: (id: string, patch: Partial<WorkflowEdgeDef>) => void
  onDelete: () => void
}) {
  const fromNode = (def.nodes ?? []).find(n => n.id === edge.from)
  const toNode = (def.nodes ?? []).find(n => n.id === edge.to)
  const condition: WorkflowCondition = edge.condition ?? { type: 'always' }
  const current = CONDITION_TYPES.find(t => t.value === (condition.type ?? 'always'))

  /** 切换类型：参数按类型重建（保留同参数字段，其余丢弃防脏数据）。 */
  const changeType = (nextType: WorkflowConditionType) => {
    const base: WorkflowCondition = { type: nextType }
    if (nextType === 'exitCode') base.value = condition.value ?? 0
    if (nextType === 'outputContains' || nextType === 'outputNotContains' || nextType === 'outputEquals') base.text = condition.text ?? ''
    if (nextType === 'outputMatches') base.pattern = condition.pattern ?? ''
    patchEdge(edge.id, { condition: base })
  }

  return (
    <div>
      <p className="wf-editor-prop-title">
        {fromNode?.name ?? edge.from} → {toNode?.name ?? edge.to}
      </p>
      <p className="wf-editor-prop-sub">边 {edge.id} · 出边条件在源节点完成时求值一次</p>

      <div className="wf-editor-field">
        <label>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
            <ConditionIcon size={12} /> 条件类型（八种）
          </span>
        </label>
        <select className="wf-editor-select" value={condition.type ?? 'always'} onChange={e => changeType(e.target.value as WorkflowConditionType)}>
          {CONDITION_TYPES.map(t => (
            <option key={t.value} value={t.value}>{t.label}</option>
          ))}
        </select>
        {current ? <p className="wf-editor-hint">{current.hint}</p> : null}
      </div>

      {/* 参数输入按类型显隐 */}
      {(condition.type ?? 'always') === 'exitCode' ? (
        <div className="wf-editor-field">
          <label>value（退出码）</label>
          <input
            className="wf-editor-input"
            type="number"
            value={condition.value ?? 0}
            onChange={e => patchEdge(edge.id, { condition: { ...condition, value: e.target.value ? Number(e.target.value) : 0 } })}
          />
        </div>
      ) : null}

      {condition.type === 'outputContains' || condition.type === 'outputNotContains' ? (
        <div className="wf-editor-field">
          <label>text（匹配文本）</label>
          <input
            className="wf-editor-input"
            value={condition.text ?? ''}
            placeholder="输出中包含/不包含的文本"
            onChange={e => patchEdge(edge.id, { condition: { ...condition, text: e.target.value } })}
          />
        </div>
      ) : null}

      {condition.type === 'outputEquals' ? (
        <div className="wf-editor-field">
          <label>text（期望输出；比较时两侧忽略首尾空白）</label>
          <input
            className="wf-editor-input"
            value={condition.text ?? ''}
            placeholder="输出应精确等于的文本，如 PASS"
            onChange={e => patchEdge(edge.id, { condition: { ...condition, text: e.target.value } })}
          />
          <p className="wf-editor-hint">输出与该文本各自 trim 后完全一致才激活——命令尾随换行、两侧空格均不影响。</p>
        </div>
      ) : null}

      {condition.type === 'outputMatches' ? (
        <div className="wf-editor-field">
          <label>pattern（正则）</label>
          <input
            className="wf-editor-input"
            value={condition.pattern ?? ''}
            placeholder="如 ^OK$（不可编译会在保存时被拒）"
            onChange={e => patchEdge(edge.id, { condition: { ...condition, pattern: e.target.value } })}
          />
        </div>
      ) : null}

      {(condition.type ?? 'always') === 'always' || condition.type === 'onSuccess' || condition.type === 'onError' ? (
        <p className="wf-editor-hint">该类型无需参数。</p>
      ) : null}

      <p className="wf-editor-hint" style={{ marginTop: 6 }}>
        求值语义：源节点完成后所有满足条件的出边全部激活；存在条件出边但无一满足 = 该分支走到尽头（不报错）。
      </p>

      <div className="wf-editor-divider" />
      <button className="wf-editor-btn" onClick={onDelete} style={{ color: 'var(--accent-red, #ff6b6b)' }}>
        <DeleteIcon size={13} /> 删除边（Delete）
      </button>
    </div>
  )
}

/*
 * ── 人工验证清单（保存 → 重新打开还原往返） ────────────────────────────────
 * 1. 侧边栏「工作流」→ 新建工作流定义 → 画布出现「开始」节点，右下角 dirty ●。
 * 2. palette 添加命令节点 + AI 节点（落画布中心附近，自动选中）。
 * 3. 拖动节点改位置；从「开始」右侧锚点拖到命令节点建立边；再连到 AI 节点。
 * 4. 选中边 → 条件类型切 onSuccess，再切 outputEquals 填期望文本（保存重开后画布
 *    边标签显示「等于 …」）；选中命令节点 → 填 command/timeoutMs/maxRuns；
 *    选中 AI 节点 → 会话线下拉选「＋ 新建会话线…」→ 填名「writer」+ system prompt
 *    + 模型 + input 模板（本节点成为该会话线的定义节点）。
 * 4b. 再加一个下游 AI 节点并连线 → 其会话线下拉应出现「writer · 由「…」定义」；
 *    选中后 system prompt 只读回显定义节点的值，模型可另选；回到定义节点改
 *    system prompt → 下游引用节点的回显随之变化（不存副本）。
 * 4c. 汇合模式：选中 command/ai 节点 → 「汇合模式」下拉选「等齐全部入边（all）」
 *    → 画布该节点右上角出现 amber「等齐」角标、dirty ● 亮；切回「每次到达即执行
 *    （none）」→ 角标消失；start 节点不显示该控件。保存 → 重开同一定义 → 下拉回显
 *    「等齐全部入边」（无 joinMode 字段/为 none 的旧定义回显「每次到达即执行」）；
 *    实例运行图（readonly）上带角标节点与运行态徽点并排不遮挡。
 * 5. 左栏「运行变量」默认展开，加一个 required 变量、「出错策略」确认默认 supervisor +
 *    选督导模型。
 * 6. 保存 → toast「工作流已创建：wf_…」，dirty ● 消失，标题处显示定义 id。
 * 7. 关闭标签页 → 重新从侧边栏/定义列表打开同一定义 → 节点位置/连线/条件/命令/
 *    会话线/input/变量/onError/supervisor 全部还原（def.get 原样回读）。
 * 8. 反向校验：删掉 command 内容保存 → 出现红色逐项错误列表（后端校验器返回）；
 *    AI 节点清空会话线名保存 → 「AI 节点必须指定 agent 名」逐项报错。
 * 8b. 画布容量：连续添加/拖动节点到 3200×2400 固定画布边界之外（右/下）→ 画布随
 *     内容扩展，滚动能看到每个节点（桌面与移动端同验证）。
 * 8c. 自动布局：搭线性 + 并行分支 + 汇合图，再连一条回指边（如 C→B）与一个孤立
 *     节点 → 工具栏「自动布局」→ 节点按层重排（start 第 0 列，回边右→左弯回，
 *     孤立节点集中最深列右侧独立列，全部无重叠），dirty ● 出现、toast 汇报层数/
 *     回边/孤立数，视口回到原点；微调拖动一两个节点后再点一次自动布局仍正常；
 *     保存 → 重开还原新坐标（其它字段不变）。
 *
 * ── 人工验证清单（移动端，视口 ≤768px / 手机或桌面窄窗）──────────────────
 * 9. 窄视口打开编辑器：画布占满全宽；工具栏只剩 定义名/描述/自动布局/组件 / 变量/属性/保存（定义
 *    id 标签隐藏），输入占满整行。
 * 10. 「组件 / 变量」→ 左抽屉滑入（palette + 默认展开的「运行变量」+ 策略 可滚动）；
 *     点遮罩或 × 收起。
 * 11. 触屏拖动节点改位置（不触发页面滚动，**拖完不弹属性抽屉**）；从锚点（扩大命中
 *     区）拖出连线落到目标节点；背景单指拖动平移画布；**任意位置（含节点上）双指
 *     拖动=平移画布**（节点拖拽/连线让位，抬一指后剩余手指接力平移）。
 * 12. **点按**（按下后位移 ≤8px 抬起）节点/边 → 右「属性」抽屉滑入；属性面板
 *     「删除节点/边」按钮可用（触屏无 Delete 键）；点遮罩收起后选中光环仍在，
 *     再点「属性」可重开。
 * 13. 聚焦输入框不触发 iOS 视口自动放大（16px 字号）；保存/校验错误流程同桌面。
 */
