/**
 * 任务列表存储(worker TaskSummary 的前端镜像)。
 *
 * 数据来源:
 * - 全量:tasks.list RPC(选中 worker,磁盘 data/<ownerKey>/ 永久保留);
 * - 增量:u.K.tasks 频道的 task.created / task.updated / task.deleted 事件。
 *
 * 不做 localStorage 持久化:任务数据真相源在 worker 磁盘,
 * 前端缓存会造成多端视图漂移;断线时列表为空,重连即校准。
 *
 * worker 状态(created/running/waiting-user/done/failed/cancelled)在这里映射为
 * n 前端的 TaskStatus(idle/running/completed/stopped/error),UI 层不再感知 worker 枚举。
 */
import type { ContextMonitorSnapshot, TaskStatus } from '@/types'
import { hubSession } from './session'
import { workspaceRegistry } from './workspaceRegistry'
import { channels } from '@every-agent/client'

/** worker TaskDtos.UsageSummary 的前端形状(最近一轮主 agent 实测 usage + 窗口上限 + 模型)。 */
export interface WorkerUsageSummary {
  inputTokens?: number
  outputTokens?: number
  totalTokens?: number
  contextWindowTokens?: number | null
  model?: string | null
}

/** worker TaskDtos.TaskSummary 的前端形状(忽略未知字段)。 */
export interface WorkerTaskSummary {
  taskId: string
  /** 后端不再下发(workerId 由前端按帧来源/来源 worker 动态标注),仅作前端内存字段。 */
  workerId?: string
  title?: string
  status?: string
  createdAt?: number
  startedAt?: number | null
  endedAt?: number | null
  seqFirst?: number | null
  seqLast?: number | null
  trimmedFrom?: number | null
  summary?: string | null
  error?: string | null
  /** 任务挂靠的工作区根(工作区隔离的过滤键)。 */
  workspace?: string | null
  /** 任务挂靠工作区的稳定 id(新 worker 提供;旧 worker 无此字段时为 undefined)。 */
  workspaceId?: string | null
  /** 运行中任务的待消费输入快照(运行时态不落盘;终态/磁盘行无此字段=空)。 */
  pendingInputs?: string[]
  /** 任务创建时冻结的模型配置 ID(worker TaskSummary 已下发,前端据其反查模型信息)。 */
  configId?: string | null
  /** 主 agent 稳定 Id(a_… 短 ID;agent 列表首项与主 agent 状态归属标识)。 */
  mainAgentId?: string | null
  /** 最近一轮上下文用量快照(worker TaskSummary.usage;终态随 meta.json 持久化,无数据=缺省)。 */
  usage?: WorkerUsageSummary | null
  /** 任务级 slash token(worker 下发的一组自包含 opaque token 串,建后写入 meta.slashTaskTokens)。 */
  slashTaskTokens?: string[]
}

/** 映射后的任务列表条目(UI 直接消费)。 */
export interface TaskListEntry {
  taskId: string
  workerId: string
  title: string
  status: TaskStatus
  /** worker 原始状态串(排查用)。 */
  rawStatus: string
  createdAt: number
  updatedAt: number
  endedAt?: number | null
  seqLast: number
  trimmedFrom: number
  summary: string
  error: string
  /** 任务挂靠的工作区根(TasksPanel 按工作区分组)。 */
  workspace: string
  /** 任务挂靠工作区的稳定 id(新 worker 提供;旧数据/旧 worker 为 null)。 */
  workspaceId?: string | null
  /** 待消费输入快照(队列面板展示;空=无排队)。 */
  pendingInputs: string[]
  /** 任务创建时冻结的模型配置 ID(据其反查模型信息;空=未知)。 */
  configId: string
  /** 主 agent 稳定 Id(a_… 短 ID;空=未知/旧格式任务)。 */
  mainAgentId: string
  /** 最近一轮上下文用量(列表电池直接消费;无数据=null)。 */
  contextUsage: ContextMonitorSnapshot | null
  /** 任务级 slash token(自包含 opaque token 串;空=无)。 */
  slashTaskTokens: string[]
}

/** worker 状态 → n 前端 TaskStatus。 */
export function mapWorkerStatus(raw: string | undefined): TaskStatus {
  switch (raw) {
    case 'created':
      return 'idle'
    case 'running':
    case 'waiting-user':
    case 'cancelling':
      return 'running'
    case 'done':
      return 'completed'
    case 'failed':
      return 'error'
    case 'cancelled':
      return 'stopped'
    default:
      return 'idle'
  }
}

/** 窗口上限缺省值(与 worker ContextOverflow.DEFAULT_CONTEXT_WINDOW_TOKENS 一致):数据源未配置时兜底,避免显示 0。 */
const DEFAULT_CONTEXT_WINDOW_TOKENS = 256_000

/**
 * worker TaskSummary.usage → 列表电池快照。
 * 口径与聊天页 eventFolder 一致:最近一轮主 agent 实测 usage
 * (promptTokens=inputTokens,占比=inputTokens/maxTokens,窗口上限缺省回退默认窗口)。
 */
function toContextUsage(usage: WorkerUsageSummary, updatedAt: number): ContextMonitorSnapshot | null {
  const inputTokens = usage.inputTokens ?? 0
  if (inputTokens <= 0) {
    return null
  }
  const maxTokens = usage.contextWindowTokens && usage.contextWindowTokens > 0
    ? usage.contextWindowTokens
    : DEFAULT_CONTEXT_WINDOW_TOKENS
  return {
    promptTokens: inputTokens,
    completionTokens: usage.outputTokens ?? 0,
    totalTokens: usage.totalTokens ?? inputTokens,
    maxTokens,
    usageRatio: maxTokens > 0 ? inputTokens / maxTokens : 0,
    lastUpdatedAt: updatedAt,
    requestType: 'chatStream',
    model: usage.model ?? '',
  }
}

function toEntry(summary: WorkerTaskSummary): TaskListEntry {
  // 兜底:局部增量(如 taskStream.open 只带 status/seqLast)未提供 workspace/title/时间等字段时,
  // 沿用 store 中已有的正确值,避免被空值/默认值覆盖后任务名回到「任务 xxx」、时间回到 1970。
  const existing = summary.taskId ? taskStoreRef.get(summary.taskId) : undefined
  const workspace = summary.workspace && summary.workspace.length > 0
    ? summary.workspace
    : (existing?.workspace ?? '')
  // 稳定工作区 id(新 worker 提供;旧 worker/局部增量未携带时为 undefined/null,简单透传不做兜底)。
  const workspaceId = summary.workspaceId

  const title = summary.title?.trim() || existing?.title || `任务 ${summary.taskId.slice(0, 8)}`
  const createdAt = summary.createdAt ?? existing?.createdAt ?? 0
  const endedAt = summary.endedAt ?? existing?.endedAt ?? null
  const updatedAt = summary.endedAt ?? summary.startedAt ?? summary.createdAt
    ?? endedAt ?? createdAt ?? existing?.updatedAt ?? 0
  // 增量更新未携带 usage 时沿用既有快照,避免覆盖成 null。
  const contextUsage = summary.usage
    ? toContextUsage(summary.usage, updatedAt)
    : existing?.contextUsage ?? null

  const entry: TaskListEntry = {
    taskId: summary.taskId,
    workerId: summary.workerId ?? existing?.workerId ?? '',
    title,
    status: mapWorkerStatus(summary.status),
    rawStatus: summary.status ?? existing?.rawStatus ?? '',
    createdAt,
    updatedAt,
    endedAt,
    seqLast: summary.seqLast ?? existing?.seqLast ?? 0,
    trimmedFrom: summary.trimmedFrom ?? existing?.trimmedFrom ?? 0,
    summary: summary.summary ?? existing?.summary ?? '',
    error: summary.error ?? existing?.error ?? '',
    workspace,
    workspaceId,
    pendingInputs: summary.pendingInputs ?? existing?.pendingInputs ?? [],
    configId: summary.configId ?? existing?.configId ?? '',
    mainAgentId: summary.mainAgentId ?? existing?.mainAgentId ?? '',
    contextUsage,
    slashTaskTokens: summary.slashTaskTokens ?? existing?.slashTaskTokens ?? [],
  }
  // 任务归属 worker 由任务本身携带;workerId 缺失时保持空串,由后续严格校验兜底,不做隐式默认。
  return entry
}

type ChangeListener = (tasks: TaskListEntry[]) => void

/** 任务列表分页:首屏每个工作区只拉最近 INITIAL_PAGE_SIZE 个;组内点「加载更多」每次续拉 LOAD_MORE_PAGE_SIZE 个。 */
const INITIAL_PAGE_SIZE = 5
const LOAD_MORE_PAGE_SIZE = 10

/** 分页键:worker × workspace 过滤(空串=不过滤,兜底未挂靠工作区的旧任务);\u0001 控制符作分隔,不会出现在 workerId/路径中。 */
function pageKey(workerId: string, workspace: string): string {
  return `${workerId}\u0001${workspace}`
}

/** 单个分页键的游标状态。 */
interface PageState {
  /** 该过滤口径下已拉取条数(下一页 offset)。 */
  offset: number
  /** 该过滤口径下是否还有更多。 */
  hasMore: boolean
}

class TaskStore {
  /** taskId → 条目。 */
  private tasks = new Map<string, TaskListEntry>()
  private listeners = new Set<ChangeListener>()
  private started = false
  /** 已发起的列表刷新(防重入)。 */
  private refreshing: Promise<void> | null = null
  /** 在途刷新期间有新的刷新请求到达(workspaceRegistry 变更等);当前刷新完成后重跑一次。 */
  private refreshPending = false
  /** 各分页键(worker×workspace)的游标:同一 worker 的多个工作区各自独立分页,互不串联。 */
  private pageStates = new Map<string, PageState>()
  /** 正在续拉的分页键集合(逐键防重入)。 */
  private loadingPages = new Set<string>()
  /** 工作区注册表订阅退订函数(start 时注册)。 */
  private unsubscribeRegistry: (() => void) | null = null

  start(): void {
    if (this.started) return
    this.started = true
    hubSession.onFrame((frame) => {
      // worker 上线(presence 目录帧):若该 worker 的前端连接早已建立(连接先于 worker 就绪,
      // 如 desktop 启动时序竞态或 worker 重启后重连),初始 tasks.list 会落在 worker 尚未
      // 订阅 cmd 频道的窗口而落空,且之后无 resync 补救——此处主动全量校准。
      // refresh 内部已处理在途去重(refreshPending),直接调用即可。
      if (hubSession.isDirectoryFrame(frame) && frame.event === 'worker.online') {
        void this.refresh()
        return
      }
      // 帧来自哪台 worker:优先 payload.workerId,回退按 channel 匹配 worker 连接命名空间。
      const workerId = String((frame.payload as Record<string, unknown> | null)?.workerId ?? '')
        || hubSession.workerIdOfFrame(frame)
      if (!workerId) return
      const client = hubSession.workerClients.get(workerId)
      if (!client || !client.k) return
      if (frame.channel !== channels.tasks(client.k)) return
      if (frame.event === 'task.deleted') {
        this.remove(String((frame.payload as Record<string, unknown>)?.taskId ?? ''))
        return
      }
      if (frame.event !== 'task.created' && frame.event !== 'task.updated') return
      this.upsert(frame.payload as WorkerTaskSummary, workerId)
    })
    hubSession.onResync(() => {
      void this.refresh()
    })
    // 首次:若已连接立即拉全量;未连接时由 ensureConnected 后的 resync 触发。
    if (hubSession.connected) {
      void this.refresh()
    }
    // 工作区注册表就绪/变更后重新校准:refresh 按 worker×workspace 拉首页,
    // 注册表晚于连接到达(时序竞态)时补拉,保证每个工作区都有独立分页游标。
    if (!this.unsubscribeRegistry) {
      this.unsubscribeRegistry = workspaceRegistry.subscribe(() => {
        void this.refresh()
      })
    }
  }

  /**
   * 全量校准:遍历所有已连 worker,按「已注册工作区各拉一页首页(最近 INITIAL_PAGE_SIZE 个,
   * workspace 过滤)+ 一页未过滤首页(兜底未挂靠工作区的旧任务)」合并(以 worker 为准,
   * 清掉本地多出的条目)。分页游标按 worker×workspace 各自独立记录,组间互不串联。
   */
  async refresh(): Promise<void> {
    if (this.refreshing) {
      // 刷新进行中(workspaceRegistry 可能刚加载了更多工作区):
      // 标记 pending,当前刷新完成后自动重跑,避免用陈旧的工作区列表丢掉新数据。
      this.refreshPending = true
      return this.refreshing
    }
    this.refreshing = (async () => {
      try {
        this.pageStates.clear()
        const entries: TaskListEntry[] = []
        const pending: Array<Promise<void>> = []
        hubSession.forEachConnectedWorker((workerId, client) => {
          // 分页口径=该 worker 的每个已注册工作区 + 未过滤(空串);Set 去重。
          const scopes = new Set<string>([''])
          for (const ws of workspaceRegistry.workspacesOf(workerId)) {
            if (ws.root) scopes.add(ws.root)
          }
          for (const workspace of scopes) {
            pending.push((async () => {
              try {
                const result = await client.rpc(workerId, 'tasks.list', {
                  ...(workspace ? { workspace } : null),
                  limit: INITIAL_PAGE_SIZE,
                  offset: 0,
                })
                const incoming = (result?.tasks ?? []) as WorkerTaskSummary[]
                this.pageStates.set(pageKey(workerId, workspace), {
                  offset: incoming.length,
                  hasMore: Boolean(result?.hasMore),
                })
                for (const summary of incoming) {
                  const entry = toEntry(summary)
                  if (!entry.workerId) entry.workerId = workerId
                  entries.push(entry)
                }
              } catch (error) {
                // 单台 worker 失败不影响整体合并(保留其余 worker 数据)。
                console.warn(`[taskStore] tasks.list 失败(${workerId}${workspace ? `:${workspace}` : ''}):`, error)
              }
            })())
          }
        })
        await Promise.all(pending)
        this.tasks.clear()
        for (const entry of entries) {
          this.tasks.set(entry.taskId, entry)
        }
        this.sortAndNotify()
      } catch (error) {
        // 未连接/无 worker:保留已收到的镜像,不上抛(设置页会提示连接态)。
        console.warn('[taskStore] tasks.list 失败:', error)
      } finally {
        this.refreshing = null
        // 在途刷新期间 workspaceRegistry 变更等触发了新请求:重跑一次,
        // 用最新的工作区列表重新校准(否则只显示部分工作区/任务)。
        if (this.refreshPending) {
          this.refreshPending = false
          void this.refresh()
        }
      }
    })()
    return this.refreshing
  }

  /** 该 worker×workspace 口径下是否还有更多可分页拉取的任务。 */
  hasMore(workerId: string, workspace: string): boolean {
    return this.pageStates.get(pageKey(workerId, workspace))?.hasMore === true
  }

  /**
   * 点击组内「加载更多」续拉:按 worker×workspace 口径取下一页(offset=该口径已拉条数,
   * workspace 过滤)合并进镜像——同一 worker 的其他工作区分页不受影响,不会串联。
   * 单键防重入;失败保留该键 hasMore 下次点击重试。
   */
  async loadMore(workerId: string, workspace: string): Promise<void> {
    const key = pageKey(workerId, workspace)
    if (this.loadingPages.has(key)) return
    const state = this.pageStates.get(key)
    if (!state || !state.hasMore) return
    this.loadingPages.add(key)
    try {
      const client = hubSession.workerClients.get(workerId)
      if (client && client.k) {
        const result = await client.rpc(workerId, 'tasks.list', {
          ...(workspace ? { workspace } : null),
          limit: LOAD_MORE_PAGE_SIZE,
          offset: state.offset,
        })
        const incoming = (result?.tasks ?? []) as WorkerTaskSummary[]
        state.offset += incoming.length
        state.hasMore = Boolean(result?.hasMore)
        for (const summary of incoming) {
          const entry = toEntry(summary)
          if (!entry.workerId) entry.workerId = workerId
          this.tasks.set(entry.taskId, entry)
        }
      }
    } catch (error) {
      console.warn(`[taskStore] tasks.list 分页失败(${workerId}${workspace ? `:${workspace}` : ''}):`, error)
    } finally {
      this.loadingPages.delete(key)
      this.sortAndNotify()
    }
  }

  /**
   * 定向加载单个任务(分页窗口外的老任务/重连后仍在打开的旧任务标签):
   * 镜像缺失时按 taskIds 向各 worker 定向拉取并落位;已存在则直接返回。
   */
  async ensureLoaded(taskId: string): Promise<TaskListEntry | undefined> {
    const existing = this.tasks.get(taskId)
    if (existing) return existing
    const pending: Array<Promise<void>> = []
    hubSession.forEachConnectedWorker((workerId, client) => {
      pending.push((async () => {
        try {
          const result = await client.rpc(workerId, 'tasks.list', { taskIds: [taskId], limit: 1 })
          const incoming = (result?.tasks ?? []) as WorkerTaskSummary[]
          for (const summary of incoming) {
            const entry = toEntry(summary)
            if (!entry.workerId) entry.workerId = workerId
            this.tasks.set(entry.taskId, entry)
          }
        } catch (error) {
          console.warn(`[taskStore] tasks.list 定向拉取失败(${workerId}):`, error)
        }
      })())
    })
    await Promise.all(pending)
    if (this.tasks.has(taskId)) {
      this.sortAndNotify()
    }
    return this.tasks.get(taskId)
  }

  upsert(summary: WorkerTaskSummary, sourceWorkerId?: string): void {
    if (!summary?.taskId) return
    const entry = toEntry(summary)
    if (!entry.workerId) entry.workerId = sourceWorkerId || ''
    this.tasks.set(entry.taskId, entry)
    this.sortAndNotify()
  }

  /** task.deleted(用户主动删除,唯一删除路径)→ 移除条目。 */
  remove(taskId: string): void {
    if (!taskId) return
    if (this.tasks.delete(taskId)) {
      this.sortAndNotify()
    }
  }

  get(taskId: string): TaskListEntry | undefined {
    return this.tasks.get(taskId)
  }

  list(): TaskListEntry[] {
    return Array.from(this.tasks.values())
      .sort((left, right) => right.updatedAt - left.updatedAt)
  }

  subscribe(fn: ChangeListener): () => void {
    this.listeners.add(fn)
    fn(this.list())
    return () => this.listeners.delete(fn)
  }

  /** 用户主动创建任务成功后立即落一条占位(后续 task.created 会覆盖)。 */
  trackCreated(taskId: string, title: string, workspace: string): void {
    this.upsert({
      taskId,
      title,
      status: 'created',
      createdAt: Date.now(),
      workspace,
    })
  }

  private sortAndNotify(): void {
    const snapshot = this.list()
    for (const fn of this.listeners) fn(snapshot)
  }
}

/** toEntry 兜底继承既有 workspace 用的实例引用(在实例化后挂载)。 */
let taskStoreRef: TaskStore

export const taskStore = new TaskStore()
taskStoreRef = taskStore
