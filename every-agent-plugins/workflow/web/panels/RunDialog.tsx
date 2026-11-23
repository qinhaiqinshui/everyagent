/**
 * 工作流运行对话框（步骤 9）：收集运行变量 → workflow.run → 打开实例页。
 *
 * 承载方式：ctx.ui.showDialog（宿主出模态壳——遮罩/居中/Esc/点遮罩取消/焦点管理，
 * task-wait WaitTaskPickerContent 同款范式）；宿主缺席 showDialog（极旧版本）时降级
 * toast 提示。本文件不引宿主 web 模块、不依赖 antd。
 *
 * 数据流：def.get 拉完整定义 → 解析 variables 声明渲染输入（required 标 *，
 * label/description/defaultValue 提示）→ 前端先本地校验 required（与后端
 * missingRequiredVariables 同口径：有 defaultValue 的 required 允许留空）→
 * workflow.run → close({instanceId})；openRunDialog 拿到结果后 openTab 实例页。
 * BAD_PARAMS（缺变量/模型配置不存在等）按后端 message 原样展示（已拼好定位列表）。
 * 模型配置存在性不做前端预校验（后端 run 时管——编辑器与运行窗之间可能改配置）。
 *
 * 纯逻辑可测核（无插件 web 测试基建，导出保持可测，人工清单见文件尾注释）：
 * missingRequiredVariables / buildVariablesPayload。
 */
import React from 'react'
import type { PluginDialogProps } from '@everyagent/plugin-api'
import { getPluginContext, resolveWorkspaceRef } from '../pluginRuntime'
import { WORKFLOW_INSTANCE_TAB_TYPE } from '../tabKeys'
import { RunIcon, VariableIcon } from '../icons'
import { rpcErrorMessage, workflowGateway, type WorkflowVariableDef } from '../workflowGateway'

const RUN_DIALOG_STYLE_ID = 'wf-run-dialog-styles'

/** 对话框样式注入（与编辑器同策略：插件 CSS 直接 <style>，幂等）。 */
function ensureRunDialogStyles(): void {
  if (typeof document === 'undefined' || document.getElementById(RUN_DIALOG_STYLE_ID)) return
  const style = document.createElement('style')
  style.id = RUN_DIALOG_STYLE_ID
  style.textContent = `
.wf-run-body { display: flex; flex-direction: column; gap: 12px; min-width: min(460px, 86vw); max-height: 62vh; overflow-y: auto; font-size: 13px; color: var(--text-primary, #eef1f5); }
.wf-run-meta { display: flex; flex-direction: column; gap: 2px; padding: 8px 10px; border: 1px solid var(--border-light, #1b202a); border-radius: 8px; background: var(--bg-tertiary, #171b24); }
.wf-run-meta-line { display: flex; gap: 8px; font-size: 12px; color: var(--text-muted, #6f7a8b); }
.wf-run-meta-line b { color: var(--text-secondary, #aab3c2); font-weight: 600; }
.wf-run-empty { color: var(--text-muted, #6f7a8b); font-size: 12.5px; }
.wf-run-vars { display: flex; flex-direction: column; gap: 10px; }
.wf-run-field { display: flex; flex-direction: column; gap: 4px; }
.wf-run-field > label { font-size: 12px; color: var(--text-secondary, #aab3c2); display: inline-flex; align-items: center; gap: 4px; }
.wf-run-required { color: var(--accent-red, #ff6b6b); font-weight: 700; }
.wf-run-input { width: 100%; box-sizing: border-box; background: var(--bg-primary, #0b0d12); border: 1px solid var(--border, #2a303b); border-radius: 6px; color: var(--text-primary, #eef1f5); padding: 5px 9px; font-size: 13px; font-family: inherit; }
.wf-run-input:focus { outline: none; border-color: var(--accent-blue, #61a5ff); }
.wf-run-hint { font-size: 11px; color: var(--text-faint, #525c6a); margin: 0; line-height: 1.5; }
.wf-run-error { background: var(--accent-red-dim, rgba(255,107,107,0.14)); border: 1px solid var(--accent-red, #ff6b6b); border-radius: 8px; padding: 8px 10px; color: var(--accent-red, #ff6b6b); font-size: 12.5px; white-space: pre-wrap; }
.wf-run-footer { display: flex; justify-content: flex-end; gap: 8px; }
.wf-run-btn { display: inline-flex; align-items: center; gap: 5px; padding: 5px 14px; border: 1px solid var(--border-strong, #3b4658); border-radius: 6px; background: transparent; color: var(--text-secondary, #aab3c2); cursor: pointer; font-size: 12.5px; }
.wf-run-btn:hover { color: var(--text-primary, #eef1f5); border-color: var(--accent-blue, #61a5ff); }
.wf-run-btn-primary { background: var(--accent-green, #41d19c); border-color: var(--accent-green, #41d19c); color: #0b0d12; font-weight: 600; }
.wf-run-btn-primary:disabled { opacity: 0.55; cursor: not-allowed; }
.wf-run-missing { font-size: 11.5px; color: var(--accent-amber, #f7bd54); margin: 0; }
`
  document.head.appendChild(style)
}

// ─── 纯逻辑（可测核） ──────────────────────────────────────────────────────

/**
 * 缺失的 required 变量名：值为空串且无 defaultValue（与后端
 * WorkflowRpc.missingRequiredVariables 同口径——defaultValue 可满足 required）。
 */
export function missingRequiredVariables(
  decls: WorkflowVariableDef[],
  values: Record<string, string>,
): string[] {
  const missing: string[] = []
  for (const v of decls) {
    if (!v || typeof v.name !== 'string' || v.name === '' || v.required !== true) continue
    const provided = values[v.name]
    const hasDefault = typeof v.defaultValue === 'string' && v.defaultValue !== ''
    if ((typeof provided !== 'string' || provided === '') && !hasDefault) {
      missing.push(v.name)
    }
  }
  return missing
}

/** 组装 run 的 variables 载荷：只带非空串（空值不传=让 defaultValue 生效）。 */
export function buildVariablesPayload(
  decls: WorkflowVariableDef[],
  values: Record<string, string>,
): Record<string, string> {
  const out: Record<string, string> = {}
  for (const v of decls) {
    if (!v || typeof v.name !== 'string' || v.name === '') continue
    const provided = values[v.name]
    if (typeof provided === 'string' && provided !== '') out[v.name] = provided
  }
  return out
}

// ─── 对话框主体 ────────────────────────────────────────────────────────────

/** close 的收口值：{instanceId}=已成功启动；close(null)=取消。 */
export interface RunDialogResult {
  instanceId: string
}

export interface RunDialogContentProps extends PluginDialogProps<RunDialogResult> {
  /** 定义归属工作区（稳定 id；workflow.run 的 workspace 参数）。 */
  workspace: string
  /** 定义 id（wf_…）。 */
  definitionId: string
}

export function RunDialogContent({ close, workspace, definitionId }: RunDialogContentProps) {
  React.useEffect(ensureRunDialogStyles, [])

  const [decls, setDecls] = React.useState<WorkflowVariableDef[] | null>(null)
  const [defName, setDefName] = React.useState('')
  const [defDesc, setDefDesc] = React.useState('')
  const [loadError, setLoadError] = React.useState<string | null>(null)
  const [values, setValues] = React.useState<Record<string, string>>({})
  const [running, setRunning] = React.useState(false)
  const [runError, setRunError] = React.useState<string | null>(null)
  const [missing, setMissing] = React.useState<string[]>([])
  const [wsRoot, setWsRoot] = React.useState('')

  // def.get 拉完整定义（每次打开真拉——定义可能在编辑器里刚改过变量声明）。
  React.useEffect(() => {
    let cancelled = false
    workflowGateway
      .defGet(workspace, definitionId)
      .then(def => {
        if (cancelled) return
        setDefName(def.name ?? '')
        setDefDesc(def.description ?? '')
        const vars = (Array.isArray(def.variables) ? def.variables : []).filter(
          v => v && typeof v.name === 'string' && v.name !== '',
        )
        setDecls(vars)
        const initial: Record<string, string> = {}
        for (const v of vars) initial[v.name] = v.defaultValue ?? ''
        setValues(initial)
      })
      .catch(err => {
        if (!cancelled) setLoadError(rpcErrorMessage(err))
      })
    return () => {
      cancelled = true
    }
  }, [workspace, definitionId])

  // workspace 只读展示：经注册表解析根路径（解析失败退回 id 本身）。
  React.useEffect(() => {
    let cancelled = false
    void resolveWorkspaceRef(workspace)
      .then(ref => {
        if (!cancelled) setWsRoot(ref?.root ?? '')
      })
      .catch(() => {
        if (!cancelled) setWsRoot('')
      })
    return () => {
      cancelled = true
    }
  }, [workspace])

  const submit = async () => {
    if (!decls || running) return
    const miss = missingRequiredVariables(decls, values)
    setMissing(miss)
    if (miss.length > 0) return
    setRunning(true)
    setRunError(null)
    try {
      const { instanceId } = await workflowGateway.run(
        workspace,
        definitionId,
        buildVariablesPayload(decls, values),
      )
      close({ instanceId })
    } catch (err) {
      setRunError(rpcErrorMessage(err))
      setRunning(false)
    }
  }

  if (loadError) {
    return (
      <div className="wf-run-body">
        <div className="wf-run-error">读取定义失败：{loadError}</div>
        <div className="wf-run-footer">
          <button type="button" className="wf-run-btn" onClick={() => close(null)}>
            关闭
          </button>
        </div>
      </div>
    )
  }

  return (
    <form
      className="wf-run-body"
      onSubmit={e => {
        e.preventDefault()
        void submit()
      }}
    >
      <div className="wf-run-meta">
        <div className="wf-run-meta-line">
          <b>{defName || definitionId}</b>
        </div>
        {defDesc ? <div className="wf-run-meta-line">{defDesc}</div> : null}
        <div className="wf-run-meta-line">
          工作区：<b title={wsRoot || workspace}>{wsRoot || workspace}</b>
        </div>
      </div>

      {decls === null ? (
        <div className="wf-run-empty">正在读取定义与变量声明…</div>
      ) : decls.length === 0 ? (
        <div className="wf-run-empty">该工作流未声明运行变量，可直接运行。</div>
      ) : (
        <div className="wf-run-vars">
          {decls.map(v => (
            <div className="wf-run-field" key={v.name}>
              <label>
                <VariableIcon size={12} />
                <span>
                  {v.label?.trim() ? v.label : v.name}
                  {v.required === true ? <span className="wf-run-required"> *</span> : null}
                  <span style={{ color: 'var(--text-faint, #525c6a)' }}>（{v.name}）</span>
                </span>
              </label>
              <input
                className="wf-run-input"
                value={values[v.name] ?? ''}
                placeholder={v.defaultValue ? `默认：${v.defaultValue}` : `输入 ${v.name}`}
                onChange={e => setValues(cur => ({ ...cur, [v.name]: e.target.value }))}
                autoFocus={v.required === true}
              />
              {v.description ? <p className="wf-run-hint">{v.description}</p> : null}
            </div>
          ))}
        </div>
      )}

      {missing.length > 0 ? (
        <p className="wf-run-missing">缺少必填变量：{missing.join('、')}</p>
      ) : null}
      {runError ? <div className="wf-run-error">{runError}</div> : null}

      <div className="wf-run-footer">
        <button type="button" className="wf-run-btn" disabled={running} onClick={() => close(null)}>
          取消
        </button>
        <button type="submit" className="wf-run-btn wf-run-btn-primary" disabled={running || decls === null}>
          <RunIcon size={12} /> {running ? '启动中…' : '开始运行'}
        </button>
      </div>
    </form>
  )
}

/**
 * 打开运行对话框；确认成功后 openTab 实例页（workflow-instance，tab.data 带
 * instanceId + workspaceId）。返回实例 id；取消/失败启动返回 null。
 */
export async function openRunDialog(
  workspace: string,
  definitionId: string,
  definitionName?: string,
): Promise<string | null> {
  const ctx = getPluginContext()
  if (typeof ctx.ui.showDialog !== 'function') {
    ctx.ui.showToast('当前宿主不支持插件对话框，无法打开运行窗口', 'error')
    return null
  }
  const result = await ctx.ui.showDialog<RunDialogResult>({
    title: `运行工作流${definitionName ? `：${definitionName}` : ''}`,
    description: '确认运行变量后开始执行；实例创建后将自动打开实例运行页。',
    Content: props => <RunDialogContent {...props} workspace={workspace} definitionId={definitionId} />,
  })
  if (result?.instanceId) {
    ctx.ui.openTab(
      WORKFLOW_INSTANCE_TAB_TYPE,
      { instanceId: result.instanceId, workspaceId: workspace },
      `${definitionName ?? '工作流'} · ${result.instanceId}`,
    )
    return result.instanceId
  }
  return null
}

/*
 * ── 人工验证清单（步骤 9）─────────────────────────────────────────────────
 * 1. 侧边栏定义列表 →「运行」→ 对话框列出全部变量声明；required 标 *、
 *    label/description/defaultValue（placeholder「默认：…」）正确显示。
 * 2. required 留空点「开始运行」→ 黄字「缺少必填变量：…」，不发 RPC。
 * 3. 填齐后运行 → 对话框关闭，实例页标签打开（当前为步骤 10 占位页，地址栏
 *    tab.data.instanceId 正确），侧边栏实例列表出现 running 实例。
 * 4. 模型配置故意填错运行 → 后端 BAD_PARAMS 的 message 完整展示在对话框红框内。
 * 5. Esc / 点遮罩 / 取消 → 对话框关闭，无实例创建。
 */
