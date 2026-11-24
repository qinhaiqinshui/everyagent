/**
 * 管理工作流定义标签页（workflow-manage）——**单个工作区**的定义管理面：
 * 增（新建/导入）、删、改（编辑器）、查（列表 + 查看 JSON）、导出、运行。
 *
 * 入口：侧边栏「工作流」面板组头「管理」按钮。tab.data.workspaceId 定住归属
 * （标签 id=workflow-manage:<workspaceId>，同一工作区重复打开聚焦同一标签，
 * 每个工作区各有一个管理标签页）。定义不在侧边栏列表展示——侧栏只看运行实例，
 * 一切定义操作收敛到本页。
 *
 * 布局：
 * - 顶栏（wf-manage-bar）：标题「管理工作流」+ 工作区名（root basename）/id 副标题
 *   + 动作组（＋ 新建 / 删除 / 导入 .workflow.json / 刷新）；
 * - 列表（wf-manage-list，滚动）：每行=名称+描述 | meta（等宽 id · 节点数 · 更新
 *   时间）| 行尾操作区（**常显「▶ 运行」直点按钮** + hover 浮现的 ⋯ 行菜单
 *   〔编辑 / 查看 JSON / 导出 JSON / 删除〕）；行点击=打开编辑器；
 * - 空态：引导文案 + 新建/导入直达按钮。
 *
 * 查看与导入的细节：
 * - 「查看 JSON」→ 只读对话框（可滚动）+「复制」——copyText（异步 API →
 *   execCommand 同步兜底），双双失败弹手动复制降落伞（侧栏同款），非安全上下文
 *   （局域网 http）也能复制。
 * - 「导入」拆两路（MenuList 二选一）：**导入文件**（.workflow.json 文件选择器，
 *   原路径）与**粘贴 JSON**（弹窗 textarea 粘贴 → 前端形态预检 → def.import；
 *   校验错误在弹窗内回显、可直接改文本重试）。两路共用 importText 核心（成功
 *   toast+重拉，后端重赋顶层 wf id）。
 *
 * 批量删除定义（侧栏实例批删同款交互）：顶栏「删除」（新建右边）进入 → 动作组换
 * 「全选 / 删除(n) / 取消」（新建/导入/刷新让位）、行首出勾选框、行点击改切勾选
 * （⋯ 行菜单隐藏）→ 确认弹窗 → **串行**逐个 def.delete——只删 definitions/ 目录，
 * 已运行与历史实例持定义快照、一律不受影响；列表刷新后勾选自动清理（定义被他端
 * 删除时），定义清空时自动退出批删模式。
 *
 * 刷新模型（侧边栏同款纪律，推送驱动）：定义的每次落盘变更经 worker 级插件通知
 * 频道扇出 plugin.workflow.changed{kind}（架构 §5.2），宿主桥接为领域事件
 * plugin-worker-event，本页订阅 kind=definition 重拉（150ms 合并突发）；注册表
 * 变化重拉；60s 低频兜底轮询。busy 标记防重入；confirm / 查看 JSON / 粘贴导入
 * 弹窗走统一弹窗组件（../Dialog）；toast 走 ctx.ui.showToast。
 *
 * 复用侧栏（SidebarPanel）导出的纯逻辑：formatTimestamp / formatRelativeTime /
 * exportFileName / downloadDefinitionJson / parseImportedDefinition。
 *
 * 移动端（≤768px）：顶栏动作组换行全宽、按钮触控目标加大；行改两行堆叠
 * （名称+⋯ 一行、meta 整行）；⋯ 菜单常显（Menu.tsx 的移动端放行样式）。
 */
import React from 'react'
import { Checkbox } from 'antd'
import { getPluginContext } from '../pluginRuntime'
import { WORKFLOW_EDITOR_TAB_TYPE } from '../tabKeys'
import { MenuList, RowActions } from '../Menu'
import { confirmDialog, DialogBody, DialogButton, DialogError, DialogFooter, showTextViewDialog } from '../Dialog'
import { openRunDialog } from './RunDialog'
import {
  formatRelativeTime,
  formatTimestamp,
  downloadDefinitionJson,
  exportFileName,
  parseImportedDefinition,
} from './SidebarPanel'
import {
  WorkflowValidationError,
  rpcErrorMessage,
  workflowGateway,
  type WorkflowDefSummary,
  type WorkflowDefinition,
} from '../workflowGateway'
import {
  DeleteIcon,
  DownloadIcon,
  EditIcon,
  PlusIcon,
  RefreshIcon,
  RunIcon,
  UploadIcon,
  VariableIcon,
} from '../icons'

// ─── 常量 ─────────────────────────────────────────────────────────────────

/** worker 侧变更通知事件名（与 Java WorkflowPlugin.CHANGED_EVENT 逐字一致）。 */
const WORKFLOW_CHANGED_EVENT = 'plugin.workflow.changed'

/** 推送到达后的合并窗口：一次保存/批量导入会连发多帧，合并成一次重拉。 */
const PUSH_RELOAD_DEBOUNCE_MS = 150

/** 低频兜底轮询（推送为主；仅补救重连窗口内的丢帧）。 */
const FALLBACK_REFRESH_MS = 60_000

const MANAGE_STYLE_ID = 'wf-manage-styles'

/** 样式注入（与 RunDialog/InstancePanel 同策略：插件 CSS 直接 <style>，幂等）。 */
function ensureManageStyles(): void {
  if (typeof document === 'undefined' || document.getElementById(MANAGE_STYLE_ID)) return
  const style = document.createElement('style')
  style.id = MANAGE_STYLE_ID
  style.textContent = `
.wf-manage { display: flex; flex-direction: column; height: 100%; min-height: 0; background: var(--bg-primary, #0b0d12); color: var(--text-primary, #eef1f5); }
.wf-manage-bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 10px 14px; border-bottom: 1px solid var(--border-light, #1b202a); }
.wf-manage-title { font-size: 14px; font-weight: 700; color: var(--text-primary, #eef1f5); white-space: nowrap; }
.wf-manage-sub { font-size: 12px; color: var(--text-muted, #6f7a8b); min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 46ch; }
.wf-manage-actions { margin-left: auto; display: inline-flex; align-items: center; gap: 6px; flex-shrink: 0; }
.wf-manage-btn { display: inline-flex; align-items: center; gap: 5px; padding: 5px 12px; border: 1px solid var(--border-strong, #3b4658); border-radius: 6px; background: transparent; color: var(--text-secondary, #aab3c2); cursor: pointer; font-size: 12.5px; white-space: nowrap; }
.wf-manage-btn:hover { color: var(--text-primary, #eef1f5); border-color: var(--accent-blue, #61a5ff); }
.wf-manage-btn:disabled { opacity: 0.55; cursor: not-allowed; }
.wf-manage-btn--primary { background: var(--accent-green, #41d19c); border-color: var(--accent-green, #41d19c); color: #0b0d12; font-weight: 600; }
.wf-manage-btn--primary:hover { color: #0b0d12; border-color: var(--accent-green, #41d19c); filter: brightness(1.05); }
.wf-manage-btn--danger { color: var(--accent-red, #ff6b6b); border-color: rgba(255, 107, 107, 0.55); }
.wf-manage-btn--danger:hover { color: var(--accent-red, #ff6b6b); border-color: var(--accent-red, #ff6b6b); filter: brightness(1.08); }
.wf-manage-batch-label { display: inline-flex; align-items: center; gap: 5px; padding: 0 4px; font-size: 12.5px; color: var(--text-secondary, #aab3c2); cursor: pointer; white-space: nowrap; }
.wf-manage-body { flex: 1; min-height: 0; overflow-y: auto; padding: 12px 14px 20px; display: flex; flex-direction: column; gap: 6px; }
.wf-manage-error { background: var(--accent-red-dim, rgba(255,107,107,0.14)); border: 1px solid var(--accent-red, #ff6b6b); border-radius: 8px; padding: 8px 10px; color: var(--accent-red, #ff6b6b); font-size: 12.5px; line-height: 1.6; }
.wf-manage-error ul { margin: 2px 0 0; padding-inline-start: 16px; }
.wf-manage-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; grid-template-areas: "name more" "meta meta"; gap: 3px 12px; align-items: center; padding: 9px 12px; border: 1px solid var(--border-light, #1b202a); border-radius: 8px; background: var(--bg-secondary, #11141b); cursor: pointer; }
.wf-manage-row:hover { border-color: var(--accent-blue, #61a5ff); }
.wf-manage-row--select { grid-template-columns: auto minmax(0, 1fr) auto; grid-template-areas: "check name more" "check meta meta"; }
.wf-manage-check { grid-area: check; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; min-height: 22px; }
.wf-manage-name { grid-area: name; display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.wf-manage-name-title { font-size: 13px; font-weight: 700; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wf-manage-name-desc { font-size: 12px; color: var(--text-muted, #6f7a8b); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wf-manage-meta { grid-area: meta; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 11.5px; color: var(--text-muted, #6f7a8b); min-width: 0; }
.wf-manage-meta-id { font-family: var(--font-mono, monospace); min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 40ch; }
.wf-manage-meta-sep { color: var(--text-faint, #525c6a); }
.wf-manage-more { grid-area: more; display: inline-flex; align-items: center; gap: 6px; flex-shrink: 0; }
/* 行尾「▶ 运行」直点按钮：绿色描边轻量变体（常显——运行是列表最高频动作，不藏菜单）。 */
.wf-manage-run { color: var(--accent-green, #41d19c); border-color: rgba(65, 209, 156, 0.45); }
.wf-manage-run:hover { color: var(--accent-green, #41d19c); border-color: var(--accent-green, #41d19c); background: rgba(65, 209, 156, 0.08); }
/* 行尾 ⋯ 菜单桌面端 hover 揭示（本页行是 .wf-manage-row 而非宿主 .ui-row，
   宿主全局 .ui-row:hover 规则够不着，这里补齐同款行为；移动端由 Menu.tsx 的
   wf-row-actions-visible 常显放行兜底）。 */
.wf-manage-row:hover .ui-row-actions { opacity: 1; visibility: visible; }
.wf-manage-empty { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 48px 12px; color: var(--text-muted, #6f7a8b); font-size: 13px; text-align: center; }
.wf-manage-empty-actions { display: inline-flex; gap: 8px; flex-wrap: wrap; justify-content: center; }
.wf-manage-loading { padding: 32px 12px; color: var(--text-muted, #6f7a8b); font-size: 13px; text-align: center; }
/* 查看 JSON / 粘贴导入弹窗：已收敛到统一弹窗组件（../Dialog 的 wf-dlg-*）。 */
/* ── 移动端（≤768px）：动作组全宽换行、按钮触控目标加大；行内堆叠已由 grid-template-areas 覆盖 ── */
@media (max-width: 768px) {
  .wf-manage-bar { padding: 8px 10px; gap: 8px; }
  .wf-manage-actions { margin-left: 0; width: 100%; }
  .wf-manage-btn { flex: 1; justify-content: center; min-height: 36px; font-size: 13px; }
  /* 行尾运行按钮不吃顶栏的 flex:1 全宽规则：恢复自然宽，仅保证触控目标（≥30px）。 */
  .wf-manage-more .wf-manage-btn { flex: none; min-height: 30px; padding: 4px 10px; }
  .wf-manage-body { padding: 8px 10px 16px; }
  .wf-manage-row { padding: 10px; }
  .wf-manage-meta-id { max-width: 30ch; }
}
`
  document.head.appendChild(style)
}

// ─── 确认弹窗：统一弹窗组件（../Dialog），本面板不再自持实现 ──────────────────

// ─── 粘贴导入对话框（「导入 → 粘贴 JSON」；textarea 粘贴 → 校验 → 导入） ─────

/**
 * 弹窗粘贴导入：textarea 承接用户粘贴的 .workflow.json 内容 → runImport（前端
 * 形态预检 + def.import，核心逻辑在 ManagePanel.importText）→ 成功关窗、失败在
 * 弹窗内回显错误（用户可直接改文本重试，不必关窗重来）。空文本/进行中有本地
 * 拦截；导入中按钮 loading 防重复提交。主体构建在统一弹窗件（../Dialog）上。
 */
function PasteImportDialog({
  close,
  runImport,
}: {
  close: () => void
  /** 导入执行器：成功返回 null，失败返回错误文案（可多行，\n 连接）。 */
  runImport: (text: string) => Promise<string | null>
}) {
  const [text, setText] = React.useState('')
  const [busy, setBusy] = React.useState(false)
  const [error, setError] = React.useState<string | null>(null)

  const submit = async () => {
    const trimmed = text.trim()
    if (busy) return
    if (trimmed === '') {
      setError('请先粘贴 JSON 内容')
      return
    }
    setBusy(true)
    setError(null)
    const err = await runImport(trimmed)
    setBusy(false)
    if (err === null) {
      close()
      return
    }
    setError(err)
  }

  return (
    <DialogBody>
      <textarea
        className="wf-dlg-textarea wf-dlg-textarea--tall"
        value={text}
        spellCheck={false}
        placeholder={'粘贴工作流定义 JSON（.workflow.json 文件的完整内容）'}
        onChange={e => setText(e.target.value)}
        // Ctrl+Enter 直达导入（表单习惯）；Esc 交给宿主模态壳关闭。
        onKeyDown={e => {
          if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
            e.preventDefault()
            void submit()
          }
        }}
      />
      {error ? <DialogError>{error}</DialogError> : null}
      <DialogFooter>
        <DialogButton disabled={busy} onClick={close}>
          取消
        </DialogButton>
        <DialogButton variant="primary" disabled={busy} onClick={() => void submit()}>
          {busy ? '导入中…' : '导入'}
        </DialogButton>
      </DialogFooter>
    </DialogBody>
  )
}

// ─── 工作区显示名 ─────────────────────────────────────────────────────────

/** 注册表条目 root 的 basename（缺省退 id）；用于顶栏副标题。 */
function rootBaseName(root: string): string {
  const base = (root ?? '').split(/[\\/]/).filter(p => p !== '').pop()
  return base || ''
}

// ─── 行时间（相对优先，超 7 天回绝对；title 带完整绝对时间） ───────────────

function defTimeText(ms?: number | null): string {
  const relative = formatRelativeTime(ms)
  return relative ?? formatTimestamp(ms)
}

// ─── 面板主体 ──────────────────────────────────────────────────────────────

export interface ManagePanelProps {
  /** 管理目标工作区（稳定 id；workflow.def.* RPC 的 workspace 参数）。 */
  workspaceId: string
}

export default function ManagePanel({ workspaceId }: ManagePanelProps) {
  const ctx = getPluginContext()
  React.useEffect(ensureManageStyles, [])

  const [workspaceLabel, setWorkspaceLabel] = React.useState<string>('')
  const [defs, setDefs] = React.useState<WorkflowDefSummary[] | null>(null)
  const [defsError, setDefsError] = React.useState<string | null>(null)
  /** 导入校验错误（逐项回显；null=无）。 */
  const [importError, setImportError] = React.useState<string[] | null>(null)
  /** 动作级 busy 标记（'import' / 'batch-delete' / `def:<id>`）。 */
  const [busyKey, setBusyKey] = React.useState<string | null>(null)
  const fileInputRef = React.useRef<HTMLInputElement | null>(null)
  /** 批量删除模式（顶栏「删除」进入；侧栏实例批删同款交互）。 */
  const [batchMode, setBatchMode] = React.useState(false)
  /** 批删模式选中的定义 id（列表刷新后按现存定义清理，防勾选悬空）。 */
  const [selectedIds, setSelectedIds] = React.useState<string[]>([])
  /** 批量删除执行中（勾选/退出/再删全部禁用）。 */
  const [batchDeleting, setBatchDeleting] = React.useState(false)
  /** 导入方式菜单的锚点按钮（null=关闭；顶栏/空态两处导入按钮共用一个菜单）。 */
  const [importMenuAnchor, setImportMenuAnchor] = React.useState<HTMLButtonElement | null>(null)

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

  // ── 注册表（顶栏副标题显示工作区名）。 ──
  const reloadRegistry = React.useCallback(() => {
    if (workspaceId === '') return
    void ctx.sdk.workspace
      .list()
      .then(list => {
        const entry = (list ?? []).find(e => e && e.id === workspaceId)
        setWorkspaceLabel(entry ? rootBaseName(entry.root ?? '') : '')
      })
      .catch(() => setWorkspaceLabel(''))
  }, [ctx, workspaceId])

  // ── 定义列表（只拉本工作区）。 ──
  const reloadDefs = React.useCallback(() => {
    if (workspaceId === '') return
    workflowGateway
      .defList(workspaceId)
      .then(list => {
        setDefs(list)
        setDefsError(null)
      })
      .catch(err => setDefsError(rpcErrorMessage(err)))
  }, [workspaceId])

  // ── 推送驱动重拉：plugin.workflow.changed{kind=definition} → 150ms 合并。 ──
  const reloadTimerRef = React.useRef<number | null>(null)
  const schedulePushReload = React.useCallback(() => {
    if (reloadTimerRef.current !== null) return
    reloadTimerRef.current = window.setTimeout(() => {
      reloadTimerRef.current = null
      reloadDefs()
    }, PUSH_RELOAD_DEBOUNCE_MS)
  }, [reloadDefs])

  // ── 挂载：注册表 + 列表首拉；订阅推送 / 注册表变化；60s 低频兜底。 ──
  React.useEffect(() => {
    reloadRegistry()
    reloadDefs()
    const d1 = ctx.events.on('plugin-worker-event', payload => {
      const p = payload as { event?: string; data?: { kind?: string } } | null
      if (p?.event !== WORKFLOW_CHANGED_EVENT) return
      if (String(p.data?.kind ?? '') !== 'definition') return
      schedulePushReload()
    })
    const d2 = ctx.events.on('workspace-registry-changed', () => {
      reloadRegistry()
      // 注册表变化会翻转「工作区缺失」判定（服务端按注册表计算），列表一并重拉。
      reloadDefs()
    })
    const fallback = setInterval(reloadDefs, FALLBACK_REFRESH_MS)
    return () => {
      d1.dispose()
      d2.dispose()
      clearInterval(fallback)
      if (reloadTimerRef.current !== null) {
        window.clearTimeout(reloadTimerRef.current)
        reloadTimerRef.current = null
      }
    }
  }, [ctx, reloadRegistry, reloadDefs, schedulePushReload])

  // ── 动作 ──

  const withBusy = async (key: string, fn: () => Promise<void>): Promise<void> => {
    if (busyKey !== null) return
    setBusyKey(key)
    try {
      await fn()
    } finally {
      setBusyKey(null)
    }
  }

  /** 增：新建（打开编辑器空白定义，保存时后端赋 wf id）。 */
  const onNewDefinition = () => {
    ctx.ui.openTab(WORKFLOW_EDITOR_TAB_TYPE, { workspaceId, definitionId: '' }, '工作流（新建）')
  }

  /** 改/查：打开定义编辑器（行点击同款入口）。 */
  const onEditDef = (def: WorkflowDefSummary) => {
    ctx.ui.openTab(WORKFLOW_EDITOR_TAB_TYPE, { workspaceId, definitionId: def.id }, def.name || def.id)
  }

  /** 运行：定义级运行窗（变量表单 → workflow.run → 自动打开实例页）；行尾直点按钮入口。 */
  const onRunDef = (def: WorkflowDefSummary) => {
    void openRunDialog(workspaceId, def.id, def.name)
  }

  /** 查：查看完整定义 JSON（def.get → 只读对话框 + 复制）。 */
  const onViewJson = (def: WorkflowDefSummary) => {
    void withBusy(`def:${def.id}`, async () => {
      let full: WorkflowDefinition
      try {
        full = await workflowGateway.defGet(workspaceId, def.id)
      } catch (err) {
        toast(`读取定义失败：${rpcErrorMessage(err)}`, 'error')
        return
      }
      if (typeof ctx.ui.showDialog !== 'function') {
        toast('当前宿主不支持插件对话框，无法查看 JSON', 'error')
        return
      }
      const text = JSON.stringify(full, null, 2)
      // 统一只读文本弹窗（../Dialog）：等宽滚动视图 + 复制（失败落手动复制降落伞）。
      await showTextViewDialog({
        title: `定义 JSON：${def.name || def.id}`,
        description: `${def.id} · 只读视图，可复制后另存为 .workflow.json`,
        text,
        copyTitle: '复制定义 JSON',
        toast,
      })
    })
  }

  /** 导出：def.export → 浏览器下载 `<名称>.workflow.json`。 */
  const onExportDef = (def: WorkflowDefSummary) => {
    void withBusy(`def:${def.id}`, async () => {
      try {
        const full = await workflowGateway.defExport(workspaceId, def.id)
        downloadDefinitionJson(full)
        toast(`已导出 ${exportFileName(def.name ?? '')}`, 'success')
      } catch (err) {
        toast(`导出失败：${rpcErrorMessage(err)}`, 'error')
      }
    })
  }

  /** 删：确认后 def.delete（历史实例持定义快照、不受影响）。 */
  const onDeleteDef = (def: WorkflowDefSummary) => {
    void withBusy(`def:${def.id}`, async () => {
      const ok = await confirmDialog(
        '删除工作流定义',
        `将删除「${def.name || def.id}」（${def.id}）。历史实例持定义快照、不受影响。此操作不可撤销。`,
        '删除',
      )
      if (!ok) return
      try {
        await workflowGateway.defDelete(workspaceId, def.id)
        toast('定义已删除', 'success')
      } catch (err) {
        toast(`删除失败：${rpcErrorMessage(err)}`, 'error')
      } finally {
        reloadDefs()
      }
    })
  }

  // ── 批量删除定义（侧栏实例批删同款状态机：勾选 + 确认 + 串行删除） ──

  /** 进入批删模式：清空勾选，行首出选择框、行点击改切勾选。 */
  const onEnterBatchMode = () => {
    setSelectedIds([])
    setBatchMode(true)
  }

  /** 退出批删模式（取消）。 */
  const onExitBatchMode = () => {
    setSelectedIds([])
    setBatchMode(false)
  }

  /** 批删模式下勾选/取消勾选单个定义。 */
  const onToggleDefSelect = (id: string) => {
    if (batchDeleting) return
    setSelectedIds(current =>
      current.includes(id) ? current.filter(x => x !== id) : [...current, id],
    )
  }

  // 列表刷新（推送/轮询）后：选中的定义已被他端删除时移出勾选集合，防勾选悬空。
  React.useEffect(() => {
    if (!batchMode) return
    setSelectedIds(current => current.filter(id => (defs ?? []).some(d => d.id === id)))
  }, [batchMode, defs])

  // 定义清空（全部被删/他端清场）时自动退出批删模式——无可选目标，留着徒增困惑。
  React.useEffect(() => {
    if (batchMode && defs !== null && defs.length === 0) {
      setBatchMode(false)
      setSelectedIds([])
    }
  }, [batchMode, defs])

  const selectedCount = selectedIds.length
  const allSelected = (defs?.length ?? 0) > 0 && (defs ?? []).every(d => selectedIds.includes(d.id))

  /** 全选/全不选（批删模式）。 */
  const onToggleSelectAll = () => {
    if (batchDeleting) return
    const ids = (defs ?? []).map(d => d.id)
    setSelectedIds(current =>
      ids.length > 0 && ids.every(id => current.includes(id))
        ? current.filter(id => !ids.includes(id))
        : [...new Set([...current, ...ids])],
    )
  }

  /**
   * 批量删除选中定义（侧栏实例批删同款执行策略）：确认后**串行**逐个 def.delete，
   * 首个失败即停（剩余保留勾选便于重试），已删除的移出勾选集合；只删 definitions/
   * 目录——**已运行与历史实例持定义快照、一律不受影响**；无论成败最后重拉列表校准
   * （推送帧到达时再校准一次）。
   */
  const onDeleteBatchSelected = () => {
    void withBusy('batch-delete', async () => {
      const targets = (defs ?? []).filter(d => selectedIds.includes(d.id))
      if (targets.length === 0) return
      const ok = await confirmDialog(
        '批量删除工作流定义',
        `确定要删除选中的 ${targets.length} 个工作流定义吗？仅删除定义——已运行与历史实例持定义快照、不受影响。此操作不可撤销。`,
        '删除',
      )
      if (!ok) return
      setBatchDeleting(true)
      const deletedIds: string[] = []
      try {
        for (const def of targets) {
          await workflowGateway.defDelete(workspaceId, def.id)
          deletedIds.push(def.id)
        }
        setBatchMode(false)
        setSelectedIds([])
        toast(`已删除 ${deletedIds.length} 个定义`, 'success')
      } catch (err) {
        toast(`删除失败（已删 ${deletedIds.length} 个）：${rpcErrorMessage(err)}`, 'error')
        setSelectedIds(current => current.filter(id => !deletedIds.includes(id)))
      } finally {
        setBatchDeleting(false)
        reloadDefs()
      }
    })
  }

  // ── 导入（两路共用核心：文件文本 / 弹窗粘贴 JSON） ──

  /**
   * 导入核心：文本 → 快速形态预检（parseImportedDefinition）→ def.import（后端
   * 重赋顶层 wf id）。成功 → 清错 + toast + 重拉列表，返回 null；失败返回逐项
   * 错误列表（不 setImportError——文件路径回显在页顶、粘贴路径回显在弹窗内）。
   */
  const importText = async (text: string): Promise<string[] | null> => {
    try {
      const definition = parseImportedDefinition(text)
      const { id } = await workflowGateway.defImport(workspaceId, definition)
      setImportError(null)
      toast(`已导入为 ${id}`, 'success')
      reloadDefs()
      return null
    } catch (err) {
      if (err instanceof WorkflowValidationError) {
        return err.items.length > 0 ? err.items : [err.message]
      }
      return [rpcErrorMessage(err)]
    }
  }

  /** 导入方式一·文件：选择器取文本 → importText；错误回显页顶（历史行为）。 */
  const onImportFile = (file: File) => {
    void withBusy('import', async () => {
      const errs = await importText(await file.text())
      if (errs !== null) {
        setImportError(errs)
      }
    })
  }

  /**
   * 导入方式二·弹窗粘贴：textarea 粘贴 JSON → importText；错误回显在弹窗内
   * （可直接改文本重试），成功关窗。文本已在弹窗里，不再走页顶回显。
   */
  const onOpenPasteImport = () => {
    if (typeof ctx.ui.showDialog !== 'function') {
      toast('当前宿主不支持插件对话框，无法粘贴导入', 'error')
      return
    }
    void ctx.ui.showDialog<null>({
      title: '粘贴导入工作流定义',
      description: '粘贴 .workflow.json 的完整 JSON 内容；导入时会重新分配顶层工作流 id。',
      Content: ({ close }) => (
        <PasteImportDialog
          close={() => close(null)}
          runImport={async text => {
            const errs = await importText(text)
            return errs === null ? null : errs.join('\n')
          }}
        />
      ),
    })
  }

  /** 导入菜单（MenuList 二选一）：导入文件 / 粘贴 JSON。锚点=点击的导入按钮；
   *  再点同一按钮切换关闭（RowActions 同款 toggle 行为）。 */
  const onOpenImportMenu = (e: React.MouseEvent<HTMLButtonElement>) => {
    setImportMenuAnchor(cur => (cur === e.currentTarget ? null : e.currentTarget))
  }

  const closeImportMenu = () => setImportMenuAnchor(null)

  const renderAll = () => {
    reloadRegistry()
    reloadDefs()
  }

  // ── 渲染 ──

  if (workspaceId === '') {
    return (
      <div className="wf-manage">
        <div className="wf-manage-body">
          <div className="wf-manage-error">标签页缺少 workspaceId 参数——请从侧边栏「工作流」面板的「管理」按钮进入。</div>
        </div>
      </div>
    )
  }

  return (
    <div className="wf-manage">
      <div className="wf-manage-bar">
        <span className="wf-manage-title">管理工作流</span>
        <span className="wf-manage-sub" title={`${workspaceLabel ? `${workspaceLabel} · ` : ''}${workspaceId}`}>
          {workspaceLabel ? `${workspaceLabel} · ` : ''}
          {workspaceId}
        </span>
        <span className="wf-manage-actions">
          {batchMode ? (
            // 批删模式独占动作组（新建/导入/刷新让位，退出后恢复）：
            // 全选 + 删除(n) + 取消，与侧栏实例批删控件同构。
            <>
              <label className="wf-manage-batch-label" onClick={e => e.stopPropagation()}>
                <Checkbox
                  checked={allSelected}
                  disabled={batchDeleting || (defs?.length ?? 0) === 0}
                  onChange={onToggleSelectAll}
                />
                <span>全选</span>
              </label>
              <button
                type="button"
                className="wf-manage-btn wf-manage-btn--danger"
                disabled={selectedCount === 0 || batchDeleting}
                title="删除选中的工作流定义（仅删定义，已运行/历史实例不受影响）"
                onClick={onDeleteBatchSelected}
              >
                <DeleteIcon size={13} /> {batchDeleting ? '删除中…' : selectedCount > 0 ? `删除 (${selectedCount})` : '删除'}
              </button>
              <button type="button" className="wf-manage-btn" disabled={batchDeleting} onClick={onExitBatchMode}>
                取消
              </button>
            </>
          ) : (
            <>
              <button type="button" className="wf-manage-btn wf-manage-btn--primary" onClick={onNewDefinition}>
                <PlusIcon size={13} /> 新建
              </button>
              <button
                type="button"
                className="wf-manage-btn wf-manage-btn--danger"
                disabled={busyKey !== null || defs === null || defs.length === 0}
                title="批量删除工作流定义（勾选后删除；不影响已运行/历史实例）"
                onClick={onEnterBatchMode}
              >
                <DeleteIcon size={13} /> 删除
              </button>
              <button
                type="button"
                className="wf-manage-btn"
                disabled={busyKey === 'import'}
                title="导入工作流定义（导入文件 / 粘贴 JSON）"
                onClick={onOpenImportMenu}
              >
                <UploadIcon size={13} /> {busyKey === 'import' ? '导入中…' : '导入'}
              </button>
              <button type="button" className="wf-manage-btn" title="重新拉取定义列表" onClick={renderAll}>
                <RefreshIcon size={13} /> 刷新
              </button>
            </>
          )}
        </span>
      </div>
      <div className="wf-manage-body">
        {defsError ? <div className="wf-manage-error">定义列表加载失败：{defsError}</div> : null}
        {importError ? (
          <div className="wf-manage-error">
            导入失败：
            <ul style={{ margin: '2px 0 0', paddingInlineStart: 16 }}>
              {importError.map((item, i) => (
                <li key={i}>{item}</li>
              ))}
            </ul>
          </div>
        ) : null}
        {defs === null ? (
          <div className="wf-manage-loading">正在加载…</div>
        ) : defs.length === 0 && !defsError ? (
          <div className="wf-manage-empty">
            <div>本工作区暂无工作流定义。</div>
            <div className="wf-manage-empty-actions">
              <button type="button" className="wf-manage-btn wf-manage-btn--primary" onClick={onNewDefinition}>
                <PlusIcon size={13} /> 新建工作流
              </button>
              <button
                type="button"
                className="wf-manage-btn"
                disabled={busyKey === 'import'}
                title="导入工作流定义（导入文件 / 粘贴 JSON）"
                onClick={onOpenImportMenu}
              >
                <UploadIcon size={13} /> 导入 .workflow.json
              </button>
            </div>
          </div>
        ) : (
          defs.map(def => (
            <div
              key={def.id}
              className={`wf-manage-row${batchMode ? ' wf-manage-row--select' : ''}`}
              // 批删模式下点击行不再打开编辑器，改切勾选（勾选框点击已 stopPropagation 防双切）。
              onClick={batchMode ? () => onToggleDefSelect(def.id) : () => onEditDef(def)}
              title={batchMode ? undefined : `定义 ${def.id}\n点击打开编辑器`}
            >
              {batchMode ? (
                <span className="wf-manage-check" onClick={e => e.stopPropagation()}>
                  <Checkbox
                    checked={selectedIds.includes(def.id)}
                    disabled={batchDeleting}
                    onChange={() => onToggleDefSelect(def.id)}
                  />
                </span>
              ) : null}
              <div className="wf-manage-name">
                <span className="wf-manage-name-title">{def.name || def.id}</span>
                {def.description ? (
                  <span className="wf-manage-name-desc" title={def.description}>{def.description}</span>
                ) : null}
              </div>
              <div className="wf-manage-meta">
                <span className="wf-manage-meta-id" title={def.id}>{def.id}</span>
                <span className="wf-manage-meta-sep">·</span>
                <span>{def.nodeCount} 节点</span>
                <span className="wf-manage-meta-sep">·</span>
                <span title={`更新于 ${new Date(def.updatedAt ?? 0).toLocaleString()}`}>
                  更新于 {defTimeText(def.updatedAt)}
                </span>
              </div>
              {!batchMode ? (
                <span className="wf-manage-more">
                  {/* 行尾「▶ 运行」直点按钮：常显、直达运行窗（stopPropagation 防触发行点击开编辑器）。 */}
                  <button
                    type="button"
                    className="wf-manage-btn wf-manage-run"
                    disabled={busyKey === `def:${def.id}`}
                    title={`运行「${def.name || def.id}」（填写运行变量后创建任务执行）`}
                    onClick={e => {
                      e.stopPropagation()
                      onRunDef(def)
                    }}
                  >
                    <RunIcon size={13} /> 运行
                  </button>
                  <RowActions
                    items={[
                      {
                        key: 'edit',
                        label: '编辑',
                        icon: <EditIcon size={13} />,
                        onSelect: () => onEditDef(def),
                      },
                      {
                        key: 'view',
                        label: '查看 JSON',
                        icon: <VariableIcon size={13} />,
                        disabled: busyKey === `def:${def.id}`,
                        onSelect: () => onViewJson(def),
                      },
                      {
                        key: 'export',
                        label: '导出 JSON',
                        icon: <DownloadIcon size={13} />,
                        onSelect: () => onExportDef(def),
                      },
                      {
                        key: 'delete',
                        label: '删除',
                        danger: true,
                        icon: <DeleteIcon size={13} />,
                        onSelect: () => onDeleteDef(def),
                      },
                    ]}
                    title="定义操作"
                    disabled={busyKey === `def:${def.id}`}
                  />
                </span>
              ) : null}
            </div>
          ))
        )}
      </div>

      {/* 导入方式菜单（顶栏/空态两处「导入」共用；选一即关，Esc/点外部由 MenuList 收口）。 */}
      {importMenuAnchor ? (
        <MenuList
          anchor={importMenuAnchor}
          anchorPointMode="top-start"
          items={[
            {
              key: 'file',
              label: '导入文件',
              description: '选择 .workflow.json 文件导入',
              icon: <UploadIcon size={13} />,
              onSelect: () => fileInputRef.current?.click(),
            },
            {
              key: 'paste',
              label: '粘贴 JSON',
              description: '在弹窗中粘贴 JSON 内容导入',
              icon: <VariableIcon size={13} />,
              onSelect: onOpenPasteImport,
            },
          ]}
          onClose={closeImportMenu}
          title="导入方式"
        />
      ) : null}

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
 * ── 人工验证清单（workflow-manage 标签页 · 单工作区定义管理）────────────────
 * 1. 侧边栏「工作流」面板组头点「管理」→ 打开本工作区的管理标签页；同一工作区
 *    重复点击聚焦同一标签（不新开）；不同工作区各自成标签。
 * 2. 顶栏：标题 + 工作区名/id 副标题；动作组=新建（绿）/删除（红）/导入/刷新；
 *    无定义或动作进行中时「删除」禁用；「导入」弹二选一菜单（导入文件 / 粘贴
 *    JSON，Esc/点外部关闭）；文件导入残缺或非法 JSON → 顶部红色逐项回显；
 *    导入成功 toast「已导入为 wf_…」并刷新列表。
 * 3. 行卡片：名称+描述 / 等宽 id · 节点数 · 更新时间；hover 边框高亮；行点击
 *    打开编辑器（definitionId 正确）；行尾=常显「▶ 运行」绿色直点按钮 + hover
 *    浮现的 ⋯ 菜单（编辑 / 查看 JSON / 导出 JSON / 删除——红色，需确认；删除后
 *    行消失）。
 * 4. 运行：行尾「▶ 运行」→ 打开变量表单运行窗 → 成功后自动打开实例运行页（侧栏
 *    实例列表同步出现新实例）；点运行按钮不触发行点击（不打开编辑器）；该定义
 *    查看 JSON 进行中时运行按钮同步禁用。
 * 5. 查看 JSON：对话框展示完整 definition JSON（可滚动）；「复制」→ toast
 *    「已复制到剪贴板」，**非安全上下文（局域网 http）下同样成功**（异步 API →
 *    execCommand 兜底 → 手动复制降落伞三级收口，绝不静默失败）；导出 JSON
 *    下载 `<名称>.workflow.json`。
 * 5a. **粘贴导入**：「导入 → 粘贴 JSON」→ 弹窗 textarea（Ctrl+Enter 直达导入）；
 *    空文本点导入 → 弹窗内红字「请先粘贴 JSON 内容」；粘贴残缺/非法 JSON →
 *    错误逐项回显在弹窗内（改完文本可直接重试，不必关窗）；成功 → 关窗 +
 *    toast「已导入为 wf_…」+ 列表刷新；导入中按钮 loading 防重复提交。
 * 5b. **批量删除**（新建右边的「删除」进入）：动作组换「☐ 全选 / 删除 / 取消」
 *    （新建/导入/刷新让位），行首出勾选框、点行=切勾选（⋯ 菜单隐藏、不再打开
 *    编辑器）；全选/反选联动「删除 (n)」计数（0 个禁用）→ 确认弹窗（提示仅删
 *    定义、已运行/历史实例不受影响）→ 串行删除、toast「已删除 N 个定义」、自动
 *    退出批删模式；删除途中报错（如定义恰好被他端删除）→ 首错即停、toast 报
 *    「已删 X 个」、剩余保持勾选可重试；批删中他端删除选中定义 → 勾选自动清理；
 *    定义清空 → 自动退出批删模式；「取消」无副作用退出。删除后侧栏实例不受影响
 *    （持定义快照，可继续查看运行页）。
 * 6. **推送**：编辑器保存 / AI 调 workflow_create / 另一端导入 → 本页 ~150ms 内
 *    自动更新，无需手动刷新。
 * 7. **移动端（≤768px / 窄窗）**：顶栏动作组换行全宽、按钮等分放大（批删模式
 *    下全选+删除+取消同样换行放大）；行内名称+⋯ 首行、meta 次行堆叠；行尾
 *    「▶ 运行」自然宽常显（不吃全宽规则）、⋯ 菜单常显（Menu.tsx 的移动端放行
 *    样式）；批删模式勾选框占行首列；粘贴弹窗 textarea 16px 防 iOS 聚焦自动
 *    放大；JSON 对话框 pre 可横向滚动。
 */
