/**
 * 工作流运行对话框：收集运行变量 → 组装「引导语 + workflow.reference token 原串 +
 * 参数普通文本」的初始内容 → ctx.ui.startNewTask 经宿主新任务草稿页启动任务
 * （预填初始内容 + 定义的主 agent 模型配置，模拟用户点发送按钮）。
 *
 * 承载方式：ctx.ui.showDialog（宿主出模态壳——遮罩/居中/Esc/点遮罩取消/焦点管理，
 * task-wait WaitTaskPickerContent 同款范式）；宿主缺席 showDialog（极旧版本）时降级
 * toast 提示。本文件不引宿主 web 模块、不依赖 antd。
 *
 * 数据流：def.get 拉完整定义（变量声明 + 主 agent 配置 agentConfigId）→ 渲染输入
 * （required 标 *，label/description/defaultValue 提示）→ 前端先本地校验 required
 * （与后端 missingRequiredVariables 同口径：有 defaultValue 的 required 允许留空）→
 * resolveWorkspaceRef 解析 {root, workerId} → ctx.ui.buildComposerToken 构造
 * kind=workflow.reference 的 opaque token（中性引用：payload {definitionId,
 * definitionName}，无 variables——字段名/编码与 worker 侧 WorkflowReferenceToken 逐字
 * 对齐）→ rawContent = 引导语 + ' ' + token + ' ' + 参数普通文本 →
 * ctx.ui.startNewTask({rawContent, modelConfigId: agentConfigId,
 * workspace: root, title})：宿主打开新任务草稿标签页、预填输入框与模型选择、模拟
 * 用户点发送——任务走宿主原有建立路径创建（task.run 新建 + 任务级默认 token 逐个
 * apply），主 agent 即用定义的主 agent 配置模型、AI 审议等默认启用能力自动生效
 * （§7.20 运行入口统一走任务）。token 原串上行（程序化预填不经前端提交替换，
 * 未登记 kind 的 token 原样进 input，任务页胶囊回放由 rawContent 还原）。
 * worker 侧 SlashTokenResolveAdvisor + WorkflowReferenceSlashResolver 把 token 替换为
 * 「[引用工作流] 名 (definitionId)」中性引用文本；运行意图（引导语）与参数是普通
 * 文字（用户可改写），主 agent 依文字调 workflow_run 工具执行——AI 节点过程/wf.node
 * 卡片都在任务页呈现。task.run 的失败错误（worker 掉线等）显示在新任务草稿页输入区，
 * 预填内容保留供用户重发（本对话框只承载解析/组装错误）。
 *
 * 两级入口：定义级（openRunDialog，管理页行尾「▶ 运行」直入变量表单）与工作区级
 * （openWorkspaceRunDialog，侧栏组头 ▶——先选定义〔默认=最近一次实例的定义，由
 * SidebarPanel.lastRunDefinitionId 从实例列表算出〕，选中后内嵌同一变量表单；
 * 切换定义即重挂载表单重置输入）。启动成功后宿主已切到新任务页，这里不再开标签。
 *
 * 纯逻辑可测核（无插件 web 测试基建，导出保持可测，人工清单见文件尾注释）：
 * missingRequiredVariables / buildVariablesPayload /
 * buildWorkflowReferenceTokenInput / buildWorkflowRunSubmission。
 */
import React from 'react'
import type { PluginDialogProps } from '@everyagent/plugin-api'
import { ensureDialogStyles } from '../Dialog'
import { getPluginContext, resolveWorkspaceRef } from '../pluginRuntime'
import { RunIcon, VariableIcon, WorkflowIcon } from '../icons'
import {
  rpcErrorMessage,
  workflowGateway,
  type WorkflowDefSummary,
  type WorkflowVariableDef,
} from '../workflowGateway'

const RUN_DIALOG_STYLE_ID = 'wf-run-dialog-styles'

/** 对话框样式注入（与编辑器同策略：插件 CSS 直接 <style>，幂等）。 */
function ensureRunDialogStyles(): void {
  if (typeof document === 'undefined' || document.getElementById(RUN_DIALOG_STYLE_ID)) return
  const style = document.createElement('style')
  style.id = RUN_DIALOG_STYLE_ID
  style.textContent = `
.wf-run-body { display: flex; flex-direction: column; gap: 12px; min-width: min(440px, 86vw); max-height: 62vh; overflow-y: auto; font-size: 13px; color: var(--text-primary, #eef1f5); }
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
.wf-run-picker { display: flex; flex-direction: column; gap: 12px; min-width: min(440px, 86vw); font-size: 13px; color: var(--text-primary, #eef1f5); }
.wf-run-missing { font-size: 11.5px; color: var(--accent-amber, #f7bd54); margin: 0; }
/* 错误框 / 页脚 / 按钮统一走 ../Dialog 的 wf-dlg-*（不再自持副本）。 */
/* ── 移动端（≤768px）：输入 16px 防 iOS 聚焦自动放大（按钮触控目标由 wf-dlg-* 移动端规则统一加大） ── */
@media (max-width: 768px) {
  .wf-run-input { font-size: 16px; }
  .wf-run-body { min-width: min(440px, 92vw); gap: 10px; }
  .wf-run-picker { min-width: min(440px, 92vw); gap: 10px; }
}
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

/** workflow.reference token 的 kind（与 worker 侧 WorkflowReferenceToken.KIND 逐字一致）。 */
export const WORKFLOW_REFERENCE_TOKEN_KIND = 'workflow.reference'

/**
 * 参数普通文本（引用化后参数不再进 token，落在胶囊外的普通文字里）：
 * `参数: k=v; k2=v2`（「; 」分隔，保表单声明序）；无参数返回空串（不追加参数文本）。
 */
export function summarizeRunVariables(variables: Record<string, string>): string {
  const keys = Object.keys(variables)
  if (keys.length === 0) return ''
  return `参数: ${keys.map(k => `${k}=${variables[k]}`).join('; ')}`
}

/**
 * 构造 workflow.reference 胶囊的 buildComposerToken 入参（纯逻辑，可测核）。
 *
 * <b>中性引用语义</b>（原「运行工作流」token 引用化）：label=工作流「名」、
 * summary=definitionId、payload 仅 {@code definitionId/definitionName}（无 variables
 * ——参数写在 token 外的普通文字）；对该工作流做什么操作由胶囊外普通文本描述，
 * 提交解析（worker 侧 WorkflowReferenceSlashResolver）只渲染「[引用工作流] 名
 * (definitionId)」。字段名/键序与 worker 侧 WorkflowReferenceToken 逐字一致
 * （definitionId → definitionName），经宿主单点编码（buildOpaqueTokenText，与 Java
 * SlashTokenEncoder 逐字节对齐）。definitionName 空时 label 退用 definitionId
 * （payload 原样携带空串，worker 侧经 WorkflowStore 反查补全）。
 */
export function buildWorkflowReferenceTokenInput(
  definitionId: string,
  definitionName: string,
): { kind: string; label: string; summary: string; payload: Record<string, unknown> } {
  const name = definitionName && definitionName.trim() !== '' ? definitionName : definitionId
  return {
    kind: WORKFLOW_REFERENCE_TOKEN_KIND,
    label: `工作流「${name}」`,
    summary: definitionId,
    payload: { definitionId, definitionName },
  }
}

/** 任务用户消息引导语：对该工作流的操作意图（运行）写在普通文字里，用户可改写。 */
export function workflowRunGuide(definitionName: string): string {
  return `请运行工作流《${definitionName}》：`
}

/**
 * 组装 task.run 的用户消息（=rawContent）：`引导语 + ' ' + token 串 + ' ' + 参数文本`。
 * token 原串上行（前端不注册 workflow.reference 提交 resolver，对齐 external_file
 * 先例）——worker 侧 SlashTokenResolveAdvisor 按 kind 分发 WorkflowReferenceSlashResolver
 * 渲染「[引用工作流] 名 (definitionId)」；参数与操作意图在普通文字中（用户可改）。
 */
export function buildWorkflowRunSubmission(
  definitionName: string,
  tokenText: string,
  variables: Record<string, string>,
): string {
  const paramsText = summarizeRunVariables(variables)
  return paramsText !== ''
    ? `${workflowRunGuide(definitionName)} ${tokenText} ${paramsText} `
    : `${workflowRunGuide(definitionName)} ${tokenText} `
}

/**
 * 「运行工作流」→ 经宿主新任务草稿页启动任务（对话框提交与两个入口共用）。
 *
 * 链路：resolveWorkspaceRef(workspace 稳定 id) → {root, workerId}（注册表解析不到
 * 根路径时报可读错误——新任务挂靠的 workspace 参数是工作区根绝对路径）→
 * ctx.ui.buildComposerToken 构造 opaque token → rawContent = 引导语 + token →
 * ctx.ui.startNewTask({rawContent, modelConfigId, workspace, title})。
 *
 * 为何走宿主草稿启动而不是插件直发 task.run RPC：程序化 RPC 建任务绕过了宿主
 * 任务建立路径——定义的主 agent 模型配置（agentConfigId）传不进去（主 agent 按
 * 系统默认模型跑，即本 bug），AI 审议等「默认启用的任务级 slash token」也不会
 * 随常规提交链 apply。经宿主草稿页（预填 + 模拟用户点发送）两者都自动生效。
 */
export async function launchWorkflowRun(
  workspace: string,
  definitionId: string,
  definitionName: string,
  variables: Record<string, string>,
  modelConfigId: string | null,
): Promise<void> {
  const ctx = getPluginContext()
  if (typeof ctx.ui.startNewTask !== 'function') {
    throw new Error('当前宿主不支持任务草稿启动（startNewTask），请升级宿主前端')
  }
  const ref = await resolveWorkspaceRef(workspace)
  if (!ref) {
    throw new Error('无法解析工作区根路径（工作区注册表无此工作区），无法创建运行任务')
  }
  const tokenText = ctx.ui.buildComposerToken(
    buildWorkflowReferenceTokenInput(definitionId, definitionName),
  )
  const rawContent = buildWorkflowRunSubmission(definitionName, tokenText, variables)
  ctx.ui.startNewTask({
    rawContent,
    modelConfigId: modelConfigId || undefined,
    workspace: ref.root,
    title: `运行工作流：${definitionName}`,
  })
}

// ─── 对话框主体 ────────────────────────────────────────────────────────────

/** close 的收口值：{launched:true}=已启动（宿主新任务页已预填并自动发送）；close(null)=取消。 */
export interface RunDialogResult {
  /** 已启动（宿主新任务草稿页已预填并自动发送，任务页自动打开）。 */
  launched: boolean
  /** 任务将运行的工作流定义名（启动方回执/侧栏刷新判定用）。 */
  definitionName?: string
}

export interface RunDialogContentProps extends PluginDialogProps<RunDialogResult> {
  /** 定义归属工作区（稳定 id；def.get / resolveWorkspaceRef / 新任务挂靠解析用）。 */
  workspace: string
  /** 定义 id（wf_…）。 */
  definitionId: string
}

export function RunDialogContent({ close, workspace, definitionId }: RunDialogContentProps) {
  React.useEffect(() => {
    ensureRunDialogStyles()
    ensureDialogStyles()
  }, [])

  const [decls, setDecls] = React.useState<WorkflowVariableDef[] | null>(null)
  const [defName, setDefName] = React.useState('')
  const [defDesc, setDefDesc] = React.useState('')
  /** 主 agent 模型配置（定义级 agentConfigId；空 = 跟随宿主输入框当前选中）。 */
  const [agentConfigId, setAgentConfigId] = React.useState('')
  const [loadError, setLoadError] = React.useState<string | null>(null)
  const [values, setValues] = React.useState<Record<string, string>>({})
  const [running, setRunning] = React.useState(false)
  const [runError, setRunError] = React.useState<string | null>(null)
  const [missing, setMissing] = React.useState<string[]>([])
  const [wsRoot, setWsRoot] = React.useState('')

  // def.get 拉完整定义（每次打开真拉——定义可能在编辑器里刚改过变量声明/主 agent 配置）。
  React.useEffect(() => {
    let cancelled = false
    workflowGateway
      .defGet(workspace, definitionId)
      .then(def => {
        if (cancelled) return
        setDefName(def.name ?? '')
        setDefDesc(def.description ?? '')
        setAgentConfigId(def.agentConfigId ?? '')
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
      // 运行入口统一走任务（§7.20）：经宿主新任务草稿页启动（预填「引导语 +
      // workflow.reference token 原串 + 参数普通文本」+ 主 agent 模型配置，模拟用户点
      // 发送按钮），主 agent 依普通文字调 workflow_run 工具执行（阻塞至终态）。
      await launchWorkflowRun(
        workspace,
        definitionId,
        defName || definitionId,
        buildVariablesPayload(decls, values),
        agentConfigId,
      )
      close({ launched: true, definitionName: defName || definitionId })
    } catch (err) {
      setRunError(rpcErrorMessage(err))
      setRunning(false)
    }
  }

  if (loadError) {
    return (
      <div className="wf-run-body wf-dlg">
        <div className="wf-dlg-error">读取定义失败：{loadError}</div>
        <div className="wf-dlg-footer">
          <button type="button" className="wf-dlg-btn" onClick={() => close(null)}>
            关闭
          </button>
        </div>
      </div>
    )
  }

  return (
    <form
      className="wf-run-body wf-dlg"
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
        {agentConfigId ? (
          <div className="wf-run-meta-line">
            主 agent 模型：<b title={agentConfigId}>{agentConfigId}</b>
          </div>
        ) : null}
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
      {runError ? <div className="wf-dlg-error">{runError}</div> : null}

      <div className="wf-dlg-footer">
        <button type="button" className="wf-dlg-btn" disabled={running} onClick={() => close(null)}>
          取消
        </button>
        <button type="submit" className="wf-dlg-btn wf-dlg-btn--primary" disabled={running || decls === null}>
          <RunIcon size={12} /> {running ? '启动中…' : '开始运行'}
        </button>
      </div>
    </form>
  )
}

/**
 * 打开运行对话框；确认后经宿主新任务草稿页启动（宿主自动切到新任务页并发送，
 * 本插件不再开标签）。返回是否已启动；取消/失败启动返回 false。
 */
export async function openRunDialog(
  workspace: string,
  definitionId: string,
  definitionName?: string,
): Promise<boolean> {
  const ctx = getPluginContext()
  if (typeof ctx.ui.showDialog !== 'function') {
    ctx.ui.showToast('当前宿主不支持插件对话框，无法打开运行窗口', 'error')
    return false
  }
  const result = await ctx.ui.showDialog<RunDialogResult>({
    title: `运行工作流${definitionName ? `：${definitionName}` : ''}`,
    description:
      '确认运行变量后启动：宿主将打开新任务页，预填初始内容与主 agent 模型配置并自动发送；主 agent 调用 workflow_run 运行该工作流，过程与结果在任务页查看。',
    Content: props => <RunDialogContent {...props} workspace={workspace} definitionId={definitionId} />,
  })
  return Boolean(result?.launched)
}

// ─── 工作区级运行（组头 ▶ 入口：先选定义，再填变量） ────────────────────────

export interface WorkspaceRunDialogProps extends PluginDialogProps<RunDialogResult> {
  /** 定义归属工作区（稳定 id；def.get / resolveWorkspaceRef / 新任务挂靠解析用）。 */
  workspace: string
  /** 本工作区定义摘要列表（下拉数据源；调用方保证非空再开窗）。 */
  defs: WorkflowDefSummary[]
  /** 默认选中：最近一次实例的定义 id（不在 defs 中则退首个；缺省=首个）。 */
  lastRunDefinitionId?: string
}

/**
 * 工作区级运行窗：顶部定义下拉（默认选中最近运行的）→ 下方内嵌 RunDialogContent
 * （选中定义的变量表单 + 底部运行/取消）。切换定义用 key 重挂载表单——输入、校验、
 * busy 态全部重置，且 def.get 重拉最新声明（编辑器里刚改过也能反映）。
 */
export function WorkspaceRunDialogContent({
  close,
  workspace,
  defs,
  lastRunDefinitionId,
}: WorkspaceRunDialogProps) {
  React.useEffect(() => {
    ensureRunDialogStyles()
    ensureDialogStyles()
  }, [])

  const [definitionId, setDefinitionId] = React.useState(() => {
    if (lastRunDefinitionId && defs.some(d => d.id === lastRunDefinitionId)) return lastRunDefinitionId
    return defs[0]?.id ?? ''
  })
  const selected = defs.find(d => d.id === definitionId)

  // 包一层 close：成功时补记选中定义名（openTab 标题用；取消原样透传）。
  const closeWithDef = React.useCallback(
    (result: RunDialogResult | null) => {
      close(result && selected ? { ...result, definitionName: selected.name || selected.id } : result)
    },
    [close, selected],
  )

  if (!selected) {
    return (
      <div className="wf-run-picker wf-dlg">
        <div className="wf-run-empty">该工作区暂无工作流定义——先点 ＋ 新建，或从 ⋯ 菜单导入。</div>
        <div className="wf-dlg-footer">
          <button type="button" className="wf-dlg-btn" onClick={() => close(null)}>
            关闭
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="wf-run-picker wf-dlg">
      <div className="wf-run-field">
        <label>
          <WorkflowIcon size={12} />
          <span>工作流定义</span>
        </label>
        <select
          className="wf-run-input"
          value={definitionId}
          onChange={e => setDefinitionId(e.target.value)}
        >
          {defs.map(d => (
            <option key={d.id} value={d.id} title={d.id}>
              {d.name || d.id}
            </option>
          ))}
        </select>
      </div>
      <RunDialogContent
        key={definitionId}
        close={closeWithDef}
        workspace={workspace}
        definitionId={definitionId}
      />
    </div>
  )
}

/**
 * 打开工作区级运行窗（侧栏组头 ▶）；确认后经宿主新任务草稿页启动（宿主自动切到
 * 新任务页并发送）。返回是否已启动；取消/失败启动返回 false。
 */
export async function openWorkspaceRunDialog(
  workspace: string,
  defs: WorkflowDefSummary[],
  lastRunDefinitionId?: string,
): Promise<boolean> {
  const ctx = getPluginContext()
  if (typeof ctx.ui.showDialog !== 'function') {
    ctx.ui.showToast('当前宿主不支持插件对话框，无法打开运行窗口', 'error')
    return false
  }
  const result = await ctx.ui.showDialog<RunDialogResult>({
    title: '运行工作流',
    description:
      '选择工作流定义并确认运行变量；确认后宿主将打开新任务页，预填初始内容与主 agent 模型配置并自动发送，过程与结果在任务页查看。',
    Content: props => (
      <WorkspaceRunDialogContent
        {...props}
        workspace={workspace}
        defs={defs}
        lastRunDefinitionId={lastRunDefinitionId}
      />
    ),
  })
  return Boolean(result?.launched)
}

/*
 * ── 人工验证清单（草稿启动改造：宿主新任务页预填 + 自动发送）──────────────
 * 1. 管理页定义行行尾「▶ 运行」→ 对话框列出全部变量声明；required 标 *、
 *    label/description/defaultValue（placeholder「默认：…」）正确显示；定义配了
 *    「主 agent 配置」时 meta 区显示「主 agent 模型：<configId>」。
 * 2. required 留空点「开始运行」→ 黄字「缺少必填变量：…」，不发请求。
 * 3. 填齐后运行 → 对话框关闭，宿主新任务草稿页打开（标题=「运行工作流：<定义名>」）
 *    并自动发送：草稿页先短暂可见「引导语 + 运行胶囊 + 底部 AI 审议胶囊」，随后
 *    草稿页替换为真实任务页；任务首条用户消息渲染为「引导语 + 运行胶囊」
 *    （workflow.run token 原串经 rawContent 还原胶囊，非明文乱码）。
 * 4. 模型链路（本修复的核心）：定义「主 agent 配置」选了非默认模型 → 运行任务的
 *    主 agent（及未配节点级模型的 AI 节点）用该模型；新任务页输入区下拉选中项
 *    = 该模型配置；未配置时跟随宿主输入框当前选中。
 * 5. 任务级默认能力随常规提交链生效：任务创建后底部「AI 审议」胶囊在任务页
 *    回显（slash.taskTokens 已 apply，✕ 可取消）。
 * 6. 主 agent 收到的替换文本（worker WorkflowRunSlashResolver 产物）含
 *    《定义名》/definitionId/参数摘要/「调用 workflow_run」引导；随后调用
 *    workflow_run 工具，工作流在任务页流式可见（wf.node 卡片 + AI 节点会话）。
 * 7. 启动阶段错误（工作区解析失败 / 宿主过旧无 startNewTask）→ 错误展示在对话框
 *    红框内；task.run 阶段失败（worker 掉线）→ 错误展示在新任务草稿页输入区，
 *    预填内容保留，连上后手动点发送即可重试。
 * 8. Esc / 点遮罩 / 取消 → 对话框关闭，无任务创建（草稿页不动）。
 *
 * ── 人工验证清单（工作区级入口 · 组头 ▶）────────────────────────────────
 * 9. 组头 ▶（无定义时禁用）→ 弹窗顶部下拉列出本工作区全部定义；默认选中
 *    「最近一次实例的定义」（SidebarPanel.lastRunDefinitionId：实例 createdAt
 *    降序首个且定义仍存在；无实例/定义已删 → 首个定义）。
 * 10. 切换下拉定义 → 下方变量表单整体重挂载：变量声明、默认值、已填输入全部
 *    重置为该定义的最新声明（编辑器里刚改过变量也能反映）。
 * 11. 底部「取消/开始运行」：取消关闭无任务；运行成功 → 关闭弹窗，宿主新任务页
 *    自动发送并转正为任务页，任务列表出现新任务。
 *
 * ── 人工验证清单（移动端，视口 ≤768px）──────────────────────────────────
 * 12. 对话框宽度收窄至 92vw 不溢出；变量输入聚焦不触发 iOS 视口自动放大（16px）；
 *    底部「取消/开始运行」按钮触控目标加大，可单手点达；定义下拉触控可点。
 */
