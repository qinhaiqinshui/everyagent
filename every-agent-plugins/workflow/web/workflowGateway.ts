/**
 * workflow 远程网关：一切 workflow.* RPC 经 ctx.sdk.rpc 定向 worker（git 插件
 * gitGateway 同款范式——本文件不引用宿主 web 模块）。
 *
 * workerId 解析：workflow RPC 的 workspace 参数是**稳定工作区 id**（wire 契约，
 * 后端落位 workspaces/<wsId>/workflows/），经 pluginRuntime.resolveWorkspaceRef
 * 从注册表反查归属 worker；注册表无该 id 时回退 ctx.sdk.workerId（单 worker 兜底）。
 *
 * 步骤 9 起全量实现：def.* / run / instance.* 全部直连；instance 事件流为
 * **推送 + 拉取混合**（subscribeInstanceEvents）：实时增量经 ctx.sdk.stream 订阅
 * 实例流频道（worker 侧落盘后扇出），历史回放/断线补齐走 instance.events RPC
 * 单发拉取，两路按 seq（字符串雪花，单实例内严格递增）去重合流。方法签名与
 * 「RPC 参数契约」表一致。
 */
import { getPluginContext, resolveWorkspaceRef } from './pluginRuntime'

// ─── wire 类型（definition.json / meta.json 结构的前端镜像） ────────────────

/** 节点类型（v1 仅三种，架构留扩展点）。 */
export type WorkflowNodeType = 'start' | 'command' | 'ai'

/** 出边条件七种类型（后端 ConditionDef.KNOWN_TYPES 一一对应）。 */
export type WorkflowConditionType =
  | 'always'
  | 'onSuccess'
  | 'onError'
  | 'exitCode'
  | 'outputContains'
  | 'outputNotContains'
  | 'outputMatches'

/** 出边条件（缺省 always；参数按类型显隐：exitCode→value、contains→text、matches→pattern）。 */
export interface WorkflowCondition {
  type: WorkflowConditionType
  value?: number | null
  text?: string | null
  pattern?: string | null
}

/** 运行变量声明。 */
export interface WorkflowVariableDef {
  name: string
  label?: string | null
  required?: boolean | null
  defaultValue?: string | null
  description?: string | null
}

/** 命名 agent 声明（一个命名 agent = 实例内一条持续会话线）。 */
export interface WorkflowAgentDef {
  name: string
  modelConfigId?: string | null
  systemPrompt?: string | null
}

/** 节点定义（x/y 即画布坐标——位置存定义）。 */
export interface WorkflowNodeDef {
  id: string
  type: WorkflowNodeType
  name: string
  x: number
  y: number
  /** command 节点：shell 命令（{{var}} 渲染）。 */
  command?: string | null
  /** command 节点：超时（仅命令节点有效）。 */
  timeoutMs?: number | null
  /** 循环上限 ∈[1,10]。 */
  maxRuns?: number | null
  /** ai 节点：命名会话线（null=一次性会话）。 */
  agent?: string | null
  /** ai 节点：模型配置覆盖（null=用 agent 声明的默认）。 */
  modelConfigId?: string | null
  /** ai 节点：输入模板（{{var}} 渲染 + opaque token）。 */
  input?: string | null
}

/** 边定义（源节点完成时对出边条件求值一次）。 */
export interface WorkflowEdgeDef {
  id: string
  from: string
  to: string
  condition?: WorkflowCondition | null
}

/** 出错策略（默认 supervisor）。 */
export type WorkflowOnError = 'fail' | 'supervisor' | 'askUser'

/** 工作流定义（definition.json 完整结构；后端 NON_NULL 序列化，字段可缺省）。 */
export interface WorkflowDefinition {
  schemaVersion?: number | null
  id?: string | null
  name: string
  description?: string | null
  workspaceId?: string | null
  createdAt?: number | null
  updatedAt?: number | null
  variables?: WorkflowVariableDef[] | null
  onError?: WorkflowOnError | null
  supervisor?: { modelConfigId?: string | null; systemPrompt?: string | null } | null
  agents?: WorkflowAgentDef[] | null
  nodes?: WorkflowNodeDef[] | null
  edges?: WorkflowEdgeDef[] | null
}

/** def.list 返回的定义摘要。 */
export interface WorkflowDefSummary {
  id: string
  name: string
  description?: string | null
  workspaceId: string
  updatedAt: number
  nodeCount: number
  workspaceMissing?: boolean
}

/** 实例状态（wire 名，与 InstanceStatus @JsonProperty 一致）。 */
export type WorkflowInstanceStatus =
  | 'created'
  | 'running'
  | 'waiting-user'
  | 'done'
  | 'failed'
  | 'cancelled'

/** instance.list 返回的实例摘要。 */
export interface WorkflowInstanceSummary {
  id: string
  definitionId: string
  definitionName: string
  workspaceId: string
  status: WorkflowInstanceStatus
  createdAt: number
  endedAt: number
  durationMs: number | null
  error?: unknown
  nodeStates?: Record<string, { runCount?: number; status?: string; lastRunNo?: number }> | null
  /** 运行参数（name → value；后端非空才带，侧栏列表行展示参数值用）。 */
  variables?: Record<string, string> | null
  workspaceMissing?: boolean
}

/** instance.get 返回的实例 meta（全量；definitionSnapshot 结构=WorkflowDefinition）。 */
export interface WorkflowInstanceMeta {
  id: string
  definitionId: string
  definitionName: string
  workspaceId: string
  definitionSnapshot?: WorkflowDefinition | null
  variables?: Record<string, string> | null
  status: WorkflowInstanceStatus
  createdAt: number
  startedAt?: number | null
  endedAt?: number | null
  durationMs?: number | null
  error?: unknown
  pendingDecision?: unknown
  nodeStates?: Record<string, unknown> | null
  runCounter?: number | null
}

/** instance.events 的单条事件（seq 一律字符串——雪花 ID 超 2^53）。 */
export interface WorkflowInstanceEvent {
  seq: string
  ts: number
  event: string
  payload: Record<string, unknown>
}

/** instance.runLog 的单次节点运行记录（runs/*.json）。 */
export interface WorkflowRunRecord {
  runNo: number
  nodeId: string
  nodeName?: string | null
  nodeType?: string | null
  startedAt?: number | null
  endedAt?: number | null
  durationMs?: number | null
  input?: Record<string, unknown> | null
  output?: Record<string, unknown> | null
  activatedEdges?: string[] | null
  agent?: Record<string, unknown> | null
  error?: unknown
  supervisor?: unknown
}

// ─── 错误形态 ─────────────────────────────────────────────────────────────

/** def.save/import 全量校验失败（BAD_PARAMS）：message 为「; 」join 的逐项错误列表。 */
export class WorkflowValidationError extends Error {
  readonly items: string[]

  constructor(message: string, items: string[]) {
    super(message)
    this.name = 'WorkflowValidationError'
    this.items = items
  }
}

/** err → 可读 message（rpc err 形态 {code,message}，非 Error 时兜底 String）。 */
export function rpcErrorMessage(err: unknown): string {
  if (err instanceof Error) return err.message
  if (err && typeof err === 'object' && 'message' in err) {
    return String((err as { message?: unknown }).message ?? err)
  }
  return String(err)
}

/** rpc 错误归一：BAD_PARAMS→校验错误（拆逐项）、NOT_FOUND→原样 message、其余透传。 */
function normalizeRpcError(err: unknown): never {
  const code = err && typeof err === 'object' && 'code' in err
    ? String((err as { code?: unknown }).code)
    : ''
  const message = rpcErrorMessage(err)
  if (code === 'BAD_PARAMS') {
    // 后端把校验错误列表 join("; ") 进 message（WorkflowStore.saveDefinition）——
    // 校验器单条错误不含 "; "，按分隔拆回逐项即可安全还原列表。
    const items = message.split(/;\s+/).map(s => s.trim()).filter(s => s.length > 0)
    throw new WorkflowValidationError(message, items.length > 0 ? items : [message])
  }
  throw err instanceof Error ? err : new Error(message)
}

/** 按稳定工作区 id 定向调用 workflow RPC（先解析归属 worker，失败回退当前 worker）。 */
async function rpcWorkflow<T>(
  workspaceId: string,
  method: string,
  params: Record<string, unknown>,
): Promise<T> {
  const ctx = getPluginContext()
  const ref = await resolveWorkspaceRef(workspaceId)
  const workerId = ref?.workerId ?? ctx.sdk.workerId
  try {
    return (await ctx.sdk.rpc(workerId, method, params)) as T
  } catch (err) {
    normalizeRpcError(err)
  }
}

/**
 * 按**当前 worker** 定向调用 workflow RPC（无 workspace 寻址的方法）：
 * - def.list / instance.list 缺省 workspace 过滤（=该 worker 视角的全部工作区）；
 * - instance.get/runLog/events/cancel/delete/decide 按全局唯一 instanceId 寻址
 *   （wire 无 workspace 参数；meta 自带 workspaceId 归户展示）。v1 单 worker 主场景
 *   直接定向 ctx.sdk.workerId；多 worker 精细路由（按实例归户反查）留待后续增强。
 */
async function rpcCurrentWorker<T>(
  method: string,
  params: Record<string, unknown>,
): Promise<T> {
  const ctx = getPluginContext()
  try {
    return (await ctx.sdk.rpc(ctx.sdk.workerId, method, params)) as T
  } catch (err) {
    normalizeRpcError(err)
  }
}

/** err → RPC 错误码（非结构化错误返回空串；订阅循环判 NOT_FOUND 终停用）。 */
export function rpcErrorCode(err: unknown): string {
  return err && typeof err === 'object' && 'code' in err
    ? String((err as { code?: unknown }).code ?? '')
    : ''
}

// ─── id / 标签工具 ────────────────────────────────────────────────────────

/**
 * 前端本地 id（节点 wn_ / 边 we_）：随机短盐即可——仅要求会话内唯一，
 * 后端校验只查非空/不重复（规范化的 wf_/wi_ 顶层 id 由后端生成）。
 */
export function localShortId(prefix: 'wn' | 'we'): string {
  return `${prefix}_${Math.random().toString(36).slice(2, 8)}`
}

/** 边条件的画布短标签（always 不显示）。 */
export function conditionLabel(c?: WorkflowCondition | null): string {
  if (!c || !c.type || c.type === 'always') return ''
  switch (c.type) {
    case 'onSuccess': return '成功'
    case 'onError': return '失败'
    case 'exitCode': return `退出码=${c.value ?? '?'}`
    case 'outputContains': return `含 ${c.text ?? '?'}`
    case 'outputNotContains': return `不含 ${c.text ?? '?'}`
    case 'outputMatches': return `匹配 ${c.pattern ?? '?'}`
    default: return c.type
  }
}

// ─── RPC 面 ───────────────────────────────────────────────────────────────

export const workflowGateway = {
  /** workflow.def.get {workspace, id} → {definition}：完整定义 JSON 原样。 */
  async defGet(workspace: string, id: string): Promise<WorkflowDefinition> {
    const result = await rpcWorkflow<{ definition?: WorkflowDefinition }>(workspace, 'workflow.def.get', {
      workspace,
      id,
    })
    if (!result || typeof result !== 'object' || !result.definition) {
      throw new Error('workflow.def.get 返回结构异常：缺少 definition')
    }
    return result.definition
  },

  /**
   * workflow.def.save {workspace, definition} → {id}：upsert（id 空=新建赋 wf id；
   * workspaceId 以参数为准回写）。校验失败抛 WorkflowValidationError（items=逐项错误）。
   */
  async defSave(workspace: string, definition: WorkflowDefinition): Promise<{ id: string }> {
    return rpcWorkflow<{ id: string }>(workspace, 'workflow.def.save', {
      workspace,
      definition,
    })
  },

  // ── 定义列表 / 删除 / 导入导出 / 运行（步骤 9 侧边栏消费） ──────────────

  /**
   * workflow.def.list {workspace?} → {defs:[摘要]}（updatedAt 倒序）。
   * workspace 缺省=当前 worker 视角的全部工作区（同 tasks.list 口径）。
   */
  async defList(workspace?: string): Promise<WorkflowDefSummary[]> {
    const result = workspace
      ? await rpcWorkflow<{ defs?: WorkflowDefSummary[] }>(workspace, 'workflow.def.list', { workspace })
      : await rpcCurrentWorker<{ defs?: WorkflowDefSummary[] }>('workflow.def.list', {})
    if (!result || typeof result !== 'object' || !Array.isArray(result.defs)) {
      throw new Error('workflow.def.list 返回结构异常：缺少 defs')
    }
    return result.defs
  },

  /** workflow.def.delete {workspace, id} → {ok:true}（历史实例持快照不受影响）。 */
  async defDelete(workspace: string, id: string): Promise<{ ok: boolean }> {
    return rpcWorkflow<{ ok: boolean }>(workspace, 'workflow.def.delete', { workspace, id })
  },

  /** workflow.def.export {workspace, id} → {definition}（前端转文件下载）。 */
  async defExport(workspace: string, id: string): Promise<WorkflowDefinition> {
    const result = await rpcWorkflow<{ definition?: WorkflowDefinition }>(workspace, 'workflow.def.export', {
      workspace,
      id,
    })
    if (!result || typeof result !== 'object' || !result.definition) {
      throw new Error('workflow.def.export 返回结构异常：缺少 definition')
    }
    return result.definition
  },

  /** workflow.def.import {workspace, definition} → {id}（重赋顶层 wf id；校验失败抛 WorkflowValidationError）。 */
  async defImport(workspace: string, definition: WorkflowDefinition): Promise<{ id: string }> {
    return rpcWorkflow<{ id: string }>(workspace, 'workflow.def.import', { workspace, definition })
  },

  /**
   * workflow.run {workspace, definitionId, variables?} → {instanceId}（立即创建实例并
   * 异步开跑）。BAD_PARAMS（缺 required 变量 / 模型配置不存在）以 message 原样抛出。
   */
  async run(
    workspace: string,
    definitionId: string,
    variables?: Record<string, string>,
  ): Promise<{ instanceId: string }> {
    return rpcWorkflow<{ instanceId: string }>(workspace, 'workflow.run', {
      workspace,
      definitionId,
      ...(variables && Object.keys(variables).length > 0 ? { variables } : {}),
    })
  },

  // ── 实例面（步骤 9 侧边栏消费列表/取消/删除；步骤 10 实例页消费详情/事件） ──

  /**
   * workflow.instance.list {workspace?, definitionId?} → {instances}（createdAt 倒序；
   * 含终态 nodeStates 投影与工作区缺失标记）。
   */
  async instanceList(filter?: { workspace?: string; definitionId?: string }): Promise<WorkflowInstanceSummary[]> {
    const params: Record<string, unknown> = {}
    if (filter?.workspace) params.workspace = filter.workspace
    if (filter?.definitionId) params.definitionId = filter.definitionId
    const result = filter?.workspace
      ? await rpcWorkflow<{ instances?: WorkflowInstanceSummary[] }>(filter.workspace, 'workflow.instance.list', params)
      : await rpcCurrentWorker<{ instances?: WorkflowInstanceSummary[] }>('workflow.instance.list', params)
    if (!result || typeof result !== 'object' || !Array.isArray(result.instances)) {
      throw new Error('workflow.instance.list 返回结构异常：缺少 instances')
    }
    return result.instances
  },

  /** workflow.instance.get {instanceId} → {instance}（meta 全量，含定义快照/pendingDecision）。 */
  async instanceGet(instanceId: string): Promise<WorkflowInstanceMeta> {
    const result = await rpcCurrentWorker<{ instance?: WorkflowInstanceMeta }>('workflow.instance.get', {
      instanceId,
    })
    if (!result || typeof result !== 'object' || !result.instance) {
      throw new Error('workflow.instance.get 返回结构异常：缺少 instance')
    }
    return result.instance
  },

  /** workflow.instance.runLog {instanceId} → {runs:[按 runNo 升序], nextRunNo}。 */
  async instanceRunLog(instanceId: string): Promise<{ runs: WorkflowRunRecord[]; nextRunNo: number }> {
    const result = await rpcCurrentWorker<{ runs?: WorkflowRunRecord[]; nextRunNo?: number }>(
      'workflow.instance.runLog',
      { instanceId },
    )
    if (!result || typeof result !== 'object' || !Array.isArray(result.runs)) {
      throw new Error('workflow.instance.runLog 返回结构异常：缺少 runs')
    }
    return { runs: result.runs, nextRunNo: typeof result.nextRunNo === 'number' ? result.nextRunNo : 0 }
  },

  /**
   * workflow.instance.events {instanceId, afterSeq?, waitMs?(≤15000), limit?} →
   * {events, nextSeq(string)}。单发拉取原语（waitMs 长轮询参数保留 wire 兼容，前端
   * 推送模型下不再使用）；持续消费请用 {@link subscribeInstanceEvents}（实例流频道
   * 推送 + 本方法回放/补齐按 seq 去重合流）。
   */
  async instanceEvents(
    instanceId: string,
    opts?: { afterSeq?: string; waitMs?: number; limit?: number },
  ): Promise<{ events: WorkflowInstanceEvent[]; nextSeq: string }> {
    const params: Record<string, unknown> = { instanceId }
    if (opts?.afterSeq != null && opts.afterSeq !== '') params.afterSeq = opts.afterSeq
    if (opts?.waitMs != null && opts.waitMs > 0) params.waitMs = opts.waitMs
    if (opts?.limit != null && opts.limit > 0) params.limit = opts.limit
    const result = await rpcCurrentWorker<{ events?: WorkflowInstanceEvent[]; nextSeq?: string }>(
      'workflow.instance.events',
      params,
    )
    if (!result || typeof result !== 'object' || !Array.isArray(result.events)) {
      throw new Error('workflow.instance.events 返回结构异常：缺少 events')
    }
    return {
      events: result.events,
      nextSeq: typeof result.nextSeq === 'string' ? result.nextSeq : '',
    }
  },

  /** workflow.instance.cancel {instanceId} → {ok:true}（运行中/挂起中断；已终态 BAD_PARAMS）。 */
  async instanceCancel(instanceId: string): Promise<{ ok: boolean }> {
    return rpcCurrentWorker<{ ok: boolean }>('workflow.instance.cancel', { instanceId })
  },

  /** workflow.instance.delete {instanceId} → {ok:true}（运行中/挂起拒绝；删整个实例目录）。 */
  async instanceDelete(instanceId: string): Promise<{ ok: boolean }> {
    return rpcCurrentWorker<{ ok: boolean }>('workflow.instance.delete', { instanceId })
  },

  /** workflow.instance.decide {instanceId, runNo, decision, input?} → {ok:true}（waiting-user 决策）。 */
  async instanceDecide(
    instanceId: string,
    runNo: number,
    decision: 'retry' | 'skip' | 'fail' | 'retryWithInput',
    input?: string,
  ): Promise<{ ok: boolean }> {
    return rpcCurrentWorker<{ ok: boolean }>('workflow.instance.decide', {
      instanceId,
      runNo,
      decision,
      ...(input != null ? { input } : {}),
    })
  },
}

// ─── instance 事件流订阅（步骤 10 实例页消费；gateway 层单点封装） ──

/**
 * 订阅纪律（对齐宿主任务流混合模型：实时=推送、历史/补齐=拉取，架构 §7.13 广播形态）：
 * - **先订阅后拉取**：先经 {@code ctx.sdk.stream.subInstanceStream} 订阅实例流频道
 *   （worker 侧 WorkflowStore.appendEvent 落盘后经 EventSink.fanout 扇出，帧 payload
 *   与 instance.events RPC 应答条目同形），再从初始游标全量拉取回放——两路重叠由
 *   seq 去重吸收，不重不漏。
 * - **seq 去重合流**：单实例内事件 seq（字符串雪花）严格递增（appendEvent 实例锁内
 *   取号，文件序=seq 序）；推送帧与拉取批次按 BigInt 比较 `> 游标` 过滤后按序合流。
 * - **订阅恢复即校准**：宿主在重连/worker 连接集变化/页面重新可见后重发订阅并回调
 *   onResubscribed——本订阅据此从游标重拉补洞（推送是无背压广播，漏帧以磁盘事实源
 *   + 拉取补齐为准；不设空转定时器）。
 * - **终态自动停**：观测到终态标记事件（wf.done / wf.cancelled / wf.status{终态}）
 *   即停并退订（后端不再产出新事件）；**他端删除**经 plugin-worker-event
 *   {kind:instance, action:deleted} 侦测（运行中实例不可删，删除只发生在终态后）。
 * - NOT_FOUND（订阅时实例已不存在）直接终停。
 */

/** 订阅状态：live=推送消费中；stopped=循环终停。 */
export type WorkflowEventsPollState =
  | { phase: 'live' }
  | { phase: 'stopped'; reason: 'terminal' | 'not-found' | 'closed' }

/** 订阅句柄：close() 幂等（退订频道、注销监听；在途结果会被丢弃）。 */
export interface WorkflowEventsHandle {
  close(): void
  /** 当前游标（字符串雪花 seq；终停后不再推进）。 */
  readonly cursor: string
}

export interface SubscribeWorkflowEventsOptions {
  /** 初始游标（缺省 '0' = 从头回放全部事件；翻历史场景由调用方传入已知游标）。 */
  afterSeq?: string
  /** 订阅状态回调（终停原因；缺省忽略）。 */
  onState?: (state: WorkflowEventsPollState) => void
}

/**
 * 事件是否实例**终态标记**：wf.done / wf.cancelled 事件本体，或 wf.status 事件的
 * payload.status ∈ {done, failed, cancelled}（failed 终态无专属事件、只落 wf.status
 * ——与引擎 transitionLocked 的发射序列一致）。
 */
export function isTerminalInstanceEvent(e: WorkflowInstanceEvent): boolean {
  if (e.event === 'wf.done' || e.event === 'wf.cancelled') return true
  if (e.event === 'wf.status') {
    const s = String((e.payload as { status?: unknown } | null)?.status ?? '')
    return s === 'done' || s === 'failed' || s === 'cancelled'
  }
  return false
}

/** 推送帧（PluginStreamEvent，payload 与 RPC 条目同形）→ WorkflowInstanceEvent。 */
function eventFromStreamFrame(e: {
  seq?: string
  ts?: number
  event: string
  payload: unknown
}): WorkflowInstanceEvent | null {
  const p = (e.payload ?? {}) as Partial<WorkflowInstanceEvent>
  const seq = typeof p.seq === 'string' && p.seq !== '' ? p.seq : (e.seq ?? '')
  if (seq === '') return null
  return {
    seq,
    ts: typeof p.ts === 'number' ? p.ts : (e.ts ?? 0),
    event: typeof p.event === 'string' && p.event !== '' ? p.event : e.event,
    payload: (p.payload ?? {}) as Record<string, unknown>,
  }
}

/**
 * 订阅实例事件流（后端主动推送 + 拉取补齐）。
 *
 * 每批事件经 onEvents 回调（**先投递再判终停**——终态标记事件保证送达消费者）；
 * 游标经返回句柄只读暴露。close() 后在途拉取/推送帧的结果被丢弃。
 */
export function subscribeInstanceEvents(
  instanceId: string,
  onEvents: (events: WorkflowInstanceEvent[]) => void,
  opts: SubscribeWorkflowEventsOptions = {},
): WorkflowEventsHandle {
  let closed = false
  let cursor = opts.afterSeq ?? '0'
  let retryTimer: ReturnType<typeof setTimeout> | null = null
  // worker 侧变更通知（他端删除侦测用；与 SidebarPanel.WORKFLOW_CHANGED_EVENT 同源）。
  const ctx = getPluginContext()
  const workerId = ctx.sdk.workerId

  const stop = (reason: 'terminal' | 'not-found' | 'closed'): void => {
    if (closed) return
    closed = true
    if (retryTimer != null) {
      clearTimeout(retryTimer)
      retryTimer = null
    }
    ctx.sdk.stream.unsubInstanceStream(workerId, instanceId)
    disposables.forEach(d => d.dispose())
    if (reason !== 'closed') {
      opts.onState?.({ phase: 'stopped', reason })
    }
  }

  /** 批量合流：严格递增 seq 过滤 → 按序投递 → 终态即停（先投递再判终停）。 */
  const accept = (events: WorkflowInstanceEvent[]): void => {
    if (closed || events.length === 0) return
    const cursorBig = BigInt(cursor)
    const fresh = events.filter(e => {
      try {
        return BigInt(e.seq) > cursorBig
      } catch {
        return false
      }
    })
    if (fresh.length === 0) return
    cursor = fresh[fresh.length - 1].seq
    onEvents(fresh)
    if (fresh.some(isTerminalInstanceEvent)) {
      stop('terminal')
    }
  }

  /**
   * 从游标拉取补齐（初始回放 / 订阅恢复校准；无 waitMs——单发快照式）。瞬态失败
   * 有界退避重试（1s→15s 共 5 档；终态/关闭即弃）；放弃后等下一次 onResubscribed
   * 信号再校准——不设周期性空转定时器。NOT_FOUND（实例不存在）直接终停。
   */
  const pull = async (): Promise<void> => {
    const delays = [1000, 2000, 4000, 8000, 15000]
    for (let attempt = 0; ; attempt += 1) {
      if (closed) return
      try {
        const res = await workflowGateway.instanceEvents(instanceId, { afterSeq: cursor })
        if (closed) return
        accept(res.events)
        return
      } catch (err) {
        if (closed) return
        if (rpcErrorCode(err) === 'NOT_FOUND') {
          stop('not-found')
          return
        }
        if (attempt >= delays.length) return
        const ms = delays[attempt]
        await new Promise<void>(resolve => {
          retryTimer = setTimeout(resolve, ms)
        })
        retryTimer = null
      }
    }
  }

  // 1) 先订阅（后续增量经推送到达；重复/历史帧由 seq 去重吸收）。
  ctx.sdk.stream.subInstanceStream(workerId, instanceId)

  // 2) 推送帧监听（全部实例流频道统一扇出，按 worker/subject 过滤）。
  const disposables = [
    ctx.sdk.stream.onInstanceStreamEvent(e => {
      if (closed || e.subjectId !== instanceId || e.workerId !== workerId) return
      const item = eventFromStreamFrame(e)
      if (item) accept([item])
    }),
    // 3) 订阅恢复（重连/可见性/worker 集变化）→ 从游标重拉补洞。
    ctx.sdk.stream.onResubscribed(() => {
      void pull()
    }),
    // 4) 他端删除侦测：plugin-worker-event{kind:instance, action:deleted, id} → 终停。
    ctx.events.on('plugin-worker-event', payload => {
      if (closed) return
      const p = payload as { event?: unknown; data?: { kind?: unknown; action?: unknown; id?: unknown } | null }
      if (p?.event !== 'plugin.workflow.changed') return
      if (p.data?.kind === 'instance' && p.data?.action === 'deleted' && p.data?.id === instanceId) {
        stop('not-found')
      }
    }),
  ]

  // 5) 全量回放（afterSeq='0' 起步；与并发推送帧 seq 去重合流）。
  void pull()
  opts.onState?.({ phase: 'live' })

  return {
    close: () => stop('closed'),
    get cursor() {
      return cursor
    },
  }
}
