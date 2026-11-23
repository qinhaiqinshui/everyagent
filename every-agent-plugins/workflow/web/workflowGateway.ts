/**
 * workflow 远程网关：一切 workflow.* RPC 经 ctx.sdk.rpc 定向 worker（git 插件
 * gitGateway 同款范式——本文件不引用宿主 web 模块）。
 *
 * workerId 解析：workflow RPC 的 workspace 参数是**稳定工作区 id**（wire 契约，
 * 后端落位 workspaces/<wsId>/workflows/），经 pluginRuntime.resolveWorkspaceRef
 * 从注册表反查归属 worker；注册表无该 id 时回退 ctx.sdk.workerId（单 worker 兜底）。
 *
 * 步骤 9 起全量实现：def.* / run / instance.* 全部直连 + instance.events 的
 * **可取消长轮询订阅循环**（subscribeInstanceEvents，afterSeq 游标推进 + waitMs
 * + 断线退避重拉）。方法签名与「RPC 参数契约」表一致——步骤 10 只消费不再改本文件。
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
   * {events, nextSeq(string)}。长轮询原语（单发）；持续消费请用
   * {@link subscribeInstanceEvents}（游标推进 + 退避重拉的订阅循环）。
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

// ─── instance.events 长轮询订阅循环（步骤 10 实例页消费；gateway 层单点封装） ──

/**
 * 节流策略（对齐宿主 task.poll 前端消费纪律，见 task-packet-view.ts）：
 * - **游标只用服务端 nextSeq（字符串，未过滤推进口径）**——批内事件 seq 仅作缺省回退；
 *   空批时 nextSeq=afterSeq（活实例=等待超时无新事件，游标原地续拉；终态=已读到尾）。
 * - 长轮询 waitMs=8000（后端钳制 ≤15000；sdk.rpc 默认 30s 超时留半程余量）——服务端
 *   挂起即天然节流，成功路径**无额外客户端定时器**，拿到应答立即续拉。
 * - 断线/异常：指数退避 1s→2s→4s→8s→15s 封顶重拉（成功即清零），经 onState 回调
 *   暴露给 UI 做连接状态提示；NOT_FOUND（实例被删）直接终停。
 * - **终态自动停**：观测到终态标记事件（wf.done / wf.cancelled / wf.status{终态}）
 *   即停——后端对终态实例立即返回当前尾，不停会退化成无 waitMs 的热转。
 */
const DEFAULT_EVENT_WAIT_MS = 8000

/** 退避档位（ms；成功一次即清零回 0 档）。 */
const EVENT_BACKOFF_SCHEDULE_MS = [1000, 2000, 4000, 8000, 15000]

/** 订阅轮询状态：polling=正常长轮询中；backoff=异常退避等待；stopped=循环终停。 */
export type WorkflowEventsPollState =
  | { phase: 'polling' }
  | { phase: 'backoff'; ms: number; error: string }
  | { phase: 'stopped'; reason: 'terminal' | 'not-found' | 'closed' }

/** 订阅句柄：close() 幂等（在途 RPC 的结果会被丢弃）。 */
export interface WorkflowEventsHandle {
  close(): void
  /** 当前游标（字符串雪花 seq；终停后不再推进）。 */
  readonly cursor: string
}

export interface SubscribeWorkflowEventsOptions {
  /** 初始游标（缺省 '0' = 从头回放全部事件；翻历史场景由调用方传入已知游标）。 */
  afterSeq?: string
  /** 长轮询等待毫秒（缺省 8000；勿超后端 15000 上限与 sdk.rpc 30s 超时的半程）。 */
  waitMs?: number
  /** 单批条数上限（缺省不传=后端缺省）。 */
  limit?: number
  /** 轮询状态回调（退避提示/终停原因；缺省忽略）。 */
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

/**
 * 订阅实例事件流（可取消的长轮询循环）。
 *
 * 每批事件经 onEvents 回调（**先投递再判终停**——终态标记事件保证送达消费者）；
 * 游标经返回句柄只读暴露。close() 后在途 RPC/退避 sleep 的结果被丢弃（sleep 定时器
 * 被 clearTimeout，挂起的 Promise 永不 resolve、随闭包一并回收——标准可取消范式）。
 */
export function subscribeInstanceEvents(
  instanceId: string,
  onEvents: (events: WorkflowInstanceEvent[]) => void,
  opts: SubscribeWorkflowEventsOptions = {},
): WorkflowEventsHandle {
  let closed = false
  let cursor = opts.afterSeq ?? '0'
  let backoffIndex = 0
  let timer: ReturnType<typeof setTimeout> | null = null

  const stop = (reason: 'terminal' | 'not-found' | 'closed'): void => {
    if (closed) return
    closed = true
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
    opts.onState?.({ phase: 'stopped', reason })
  }

  const sleep = (ms: number): Promise<void> =>
    new Promise<void>(resolve => {
      timer = setTimeout(resolve, ms)
    })

  void (async () => {
    while (!closed) {
      try {
        const res = await workflowGateway.instanceEvents(instanceId, {
          afterSeq: cursor,
          waitMs: opts.waitMs ?? DEFAULT_EVENT_WAIT_MS,
          ...(opts.limit != null ? { limit: opts.limit } : {}),
        })
        if (closed) return
        if (backoffIndex > 0) {
          backoffIndex = 0
          opts.onState?.({ phase: 'polling' })
        }
        if (res.events.length > 0) {
          // 未过滤口径游标优先（task.poll 同款纪律）；缺省回退批尾事件 seq。
          cursor = res.nextSeq || res.events[res.events.length - 1].seq
          onEvents(res.events)
          if (res.events.some(isTerminalInstanceEvent)) {
            stop('terminal')
            return
          }
        } else if (res.nextSeq) {
          // 空批推进（通常等于原游标；显式采纳服务端口径）。
          cursor = res.nextSeq
        }
        // 立即续拉——服务端 waitMs 挂起即节流。
      } catch (err) {
        if (closed) return
        if (rpcErrorCode(err) === 'NOT_FOUND') {
          stop('not-found')
          return
        }
        const ms = EVENT_BACKOFF_SCHEDULE_MS[Math.min(backoffIndex, EVENT_BACKOFF_SCHEDULE_MS.length - 1)]
        backoffIndex += 1
        opts.onState?.({ phase: 'backoff', ms, error: rpcErrorMessage(err) })
        await sleep(ms)
      }
    }
  })()

  return {
    close: () => stop('closed'),
    get cursor() {
      return cursor
    },
  }
}
