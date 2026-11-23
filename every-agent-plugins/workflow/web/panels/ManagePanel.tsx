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
 *   + 动作组（＋ 新建 / 导入 .workflow.json / 刷新）；
 * - 列表（wf-manage-list，滚动）：每行=名称+描述 | meta（等宽 id · 节点数 · 更新
 *   时间）| ⋯ 行菜单（运行 / 查看 JSON / 导出 JSON / 删除）；行点击=打开编辑器；
 * - 空态：引导文案 + 新建/导入直达按钮。
 *
 * 刷新模型（侧边栏同款纪律，推送驱动）：定义的每次落盘变更经 worker 级插件通知
 * 频道扇出 plugin.workflow.changed{kind}（架构 §5.2），宿主桥接为领域事件
 * plugin-worker-event，本页订阅 kind=definition 重拉（150ms 合并突发）；注册表
 * 变化重拉；60s 低频兜底轮询。busy 标记防重入；confirm 走 ctx.ui.showDialog
 * （缺席降级 window.confirm）；toast 走 ctx.ui.showToast。
 *
 * 复用侧栏（SidebarPanel）导出的纯逻辑：formatTimestamp / formatRelativeTime /
 * exportFileName / downloadDefinitionJson / parseImportedDefinition。
 *
 * 移动端（≤768px）：顶栏动作组换行全宽、按钮触控目标加大；行改两行堆叠
 * （名称+⋯ 一行、meta 整行）；⋯ 菜单常显（Menu.tsx 的移动端放行样式）。
 */
import React from 'react'
import { Button } from 'antd'
import { getPluginContext } from '../pluginRuntime'
import { WORKFLOW_EDITOR_TAB_TYPE } from '../tabKeys'
import { RowActions } from '../Menu'
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
.wf-manage-body { flex: 1; min-height: 0; overflow-y: auto; padding: 12px 14px 20px; display: flex; flex-direction: column; gap: 6px; }
.wf-manage-error { background: var(--accent-red-dim, rgba(255,107,107,0.14)); border: 1px solid var(--accent-red, #ff6b6b); border-radius: 8px; padding: 8px 10px; color: var(--accent-red, #ff6b6b); font-size: 12.5px; line-height: 1.6; }
.wf-manage-error ul { margin: 2px 0 0; padding-inline-start: 16px; }
.wf-manage-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; grid-template-areas: "name more" "meta meta"; gap: 3px 12px; align-items: center; padding: 9px 12px; border: 1px solid var(--border-light, #1b202a); border-radius: 8px; background: var(--bg-secondary, #11141b); cursor: pointer; }
.wf-manage-row:hover { border-color: var(--accent-blue, #61a5ff); }
.wf-manage-name { grid-area: name; display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.wf-manage-name-title { font-size: 13px; font-weight: 700; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wf-manage-name-desc { font-size: 12px; color: var(--text-muted, #6f7a8b); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wf-manage-meta { grid-area: meta; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 11.5px; color: var(--text-muted, #6f7a8b); min-width: 0; }
.wf-manage-meta-id { font-family: var(--font-mono, monospace); min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 40ch; }
.wf-manage-meta-sep { color: var(--text-faint, #525c6a); }
.wf-manage-more { grid-area: more; display: inline-flex; flex-shrink: 0; }
.wf-manage-empty { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 48px 12px; color: var(--text-muted, #6f7a8b); font-size: 13px; text-align: center; }
.wf-manage-empty-actions { display: inline-flex; gap: 8px; flex-wrap: wrap; justify-content: center; }
.wf-manage-loading { padding: 32px 12px; color: var(--text-muted, #6f7a8b); font-size: 13px; text-align: center; }
.wf-manage-json { display: flex; flex-direction: column; gap: 10px; min-width: min(720px, 88vw); font-size: 13px; }
.wf-manage-json pre { margin: 0; max-height: 60vh; overflow: auto; background: var(--bg-tertiary, #171b24); border: 1px solid var(--border-light, #1b202a); border-radius: 8px; padding: 10px 12px; font-size: 12px; line-height: 1.6; font-family: var(--font-mono, monospace); color: var(--text-primary, #eef1f5); white-space: pre; }
.wf-manage-json-footer { display: flex; justify-content: flex-end; gap: 8px; }
/* ── 移动端（≤768px）：动作组全宽换行、按钮触控目标加大；行内堆叠已由 grid-template-areas 覆盖 ── */
@media (max-width: 768px) {
  .wf-manage-bar { padding: 8px 10px; gap: 8px; }
  .wf-manage-actions { margin-left: 0; width: 100%; }
  .wf-manage-btn { flex: 1; justify-content: center; min-height: 36px; font-size: 13px; }
  .wf-manage-body { padding: 8px 10px 16px; }
  .wf-manage-row { padding: 10px; }
  .wf-manage-meta-id { max-width: 30ch; }
  .wf-manage-json pre { max-height: 48vh; font-size: 11px; }
}
`
  document.head.appendChild(style)
}

// ─── 确认对话框（宿主模态壳；缺席时降级 window.confirm——侧栏同款范式） ───────

function confirmDialog(title: string, description: string, okText = '确认'): Promise<boolean> {
  const ctx = getPluginContext()
  if (typeof ctx.ui.showDialog !== 'function') {
    return Promise.resolve(window.confirm(`${title}\n\n${description}`))
  }
  return ctx
    .ui.showDialog<boolean>({
      title,
      description,
      Content: ({ close }) => (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12, minWidth: 'min(320px, 80vw)', fontSize: 13 }}>
          <p style={{ margin: 0, color: 'var(--text-secondary, #aab3c2)', lineHeight: 1.7 }}>{description}</p>
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
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
  /** 动作级 busy 标记（'import' / `def:<id>`）。 */
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

  /** 运行：定义级运行窗（变量表单 → workflow.run → 自动打开实例页）。 */
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
      await ctx.ui.showDialog<null>({
        title: `定义 JSON：${def.name || def.id}`,
        description: `${def.id} · 只读视图，可复制后另存为 .workflow.json`,
        Content: ({ close }) => (
          <div className="wf-manage-json">
            <pre>{text}</pre>
            <div className="wf-manage-json-footer">
              <Button
                onClick={() => {
                  void navigator.clipboard
                    ?.writeText(text)
                    .then(() => toast('已复制到剪贴板', 'success'))
                    .catch(() => toast('复制失败（浏览器未授权剪贴板）', 'error'))
                }}
              >
                复制
              </Button>
              <Button type="primary" onClick={() => close(null)}>
                关闭
              </Button>
            </div>
          </div>
        ),
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

  /** 增（导入）：文件文本 → 快速形态预检 → def.import（后端重赋顶层 wf id）。 */
  const onImportFile = (file: File) => {
    void withBusy('import', async () => {
      try {
        const text = await file.text()
        const definition = parseImportedDefinition(text)
        const { id } = await workflowGateway.defImport(workspaceId, definition)
        setImportError(null)
        toast(`已导入为 ${id}`, 'success')
      } catch (err) {
        if (err instanceof WorkflowValidationError) {
          setImportError(err.items.length > 0 ? err.items : [err.message])
        } else {
          setImportError([rpcErrorMessage(err)])
        }
      } finally {
        reloadDefs()
      }
    })
  }

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
          <button type="button" className="wf-manage-btn wf-manage-btn--primary" onClick={onNewDefinition}>
            <PlusIcon size={13} /> 新建
          </button>
          <button
            type="button"
            className="wf-manage-btn"
            disabled={busyKey === 'import'}
            title="导入 .workflow.json 文件到本工作区"
            onClick={() => fileInputRef.current?.click()}
          >
            <UploadIcon size={13} /> {busyKey === 'import' ? '导入中…' : '导入'}
          </button>
          <button type="button" className="wf-manage-btn" title="重新拉取定义列表" onClick={renderAll}>
            <RefreshIcon size={13} /> 刷新
          </button>
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
                onClick={() => fileInputRef.current?.click()}
              >
                <UploadIcon size={13} /> 导入 .workflow.json
              </button>
            </div>
          </div>
        ) : (
          defs.map(def => (
            <div
              key={def.id}
              className="wf-manage-row"
              onClick={() => onEditDef(def)}
              title={`定义 ${def.id}\n点击打开编辑器`}
            >
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
              <span className="wf-manage-more">
                <RowActions
                  items={[
                    {
                      key: 'run',
                      label: '运行',
                      icon: <RunIcon size={13} />,
                      onSelect: () => onRunDef(def),
                    },
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
            </div>
          ))
        )}
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
 * ── 人工验证清单（workflow-manage 标签页 · 单工作区定义管理）────────────────
 * 1. 侧边栏「工作流」面板组头点「管理」→ 打开本工作区的管理标签页；同一工作区
 *    重复点击聚焦同一标签（不新开）；不同工作区各自成标签。
 * 2. 顶栏：标题 + 工作区名/id 副标题；动作组=新建（绿）/导入/刷新；导入残缺或
 *    非法 JSON → 顶部红色逐项回显；导入成功 toast「已导入为 wf_…」并刷新列表。
 * 3. 行卡片：名称+描述 / 等宽 id · 节点数 · 更新时间；hover 边框高亮；行点击
 *    打开编辑器（definitionId 正确）；⋯ 菜单=运行 / 编辑 / 查看 JSON / 导出 JSON /
 *    删除（红色，需确认；删除后行消失）。
 * 4. 运行：⋯「运行」打开变量表单运行窗 → 成功后自动打开实例运行页（侧栏实例
 *    列表同步出现新实例）。
 * 5. 查看 JSON：对话框展示完整 definition JSON（可滚动/复制按钮）；导出 JSON
 *    下载 `<名称>.workflow.json`。
 * 6. **推送**：编辑器保存 / AI 调 workflow_create / 另一端导入 → 本页 ~150ms 内
 *    自动更新，无需手动刷新。
 * 7. **移动端（≤768px / 窄窗）**：顶栏动作组换行全宽、按钮等分放大；行内
 *    名称+⋯ 首行、meta 次行堆叠；⋯ 菜单常显可点；JSON 对话框 pre 可横向滚动。
 */
