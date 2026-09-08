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

/** 任务列表默认每页条数:首屏只拉最近 PAGE_SIZE 个,滑动触底再续拉下一页。 */
const PAGE_SIZE = 10

class TaskStore {
  /** taskId → 条目。 */
  private tasks = new Map<string, TaskListEntry>()
  private listeners = new Set<ChangeListener>()
  private started = false
  /** 已发起的列表刷新(防重入)。 */
  private refreshing: Promise<void> | null = null
  /** 每台 worker 已拉取的任务数(下一页 offset)。 */
  private workerOffsets = new Map<string, number>()
  /** 每台 worker 是否还有更多(hasMore)。 */
  private workerHasMore = new Map<string, boolean>()
  /** 续拉更多分页(防重入)。 */
  private loadingMore = false

  start(): void {
    if (this.started) return
    this.started = true
    hubSession.onFrame((frame) => {
      // worker 上线(presence 目录帧):若该 worker 的前端连接早已建立(连接先于 worker 就绪,
      // 如 desktop 启动时序竞态或 worker 重启后重连),初始 tasks.list 会落在 worker 尚未
      // 订阅 cmd 频道的窗口而落空,且之后无 resync 补救——此处主动全量校准。若已有刷新在途
      // (极可能是 worker 未就绪时的失败刷新),等它结束后再校准一次,避免命中去重返回旧 promise。
      if (hubSession.isDirectoryFrame(frame) && frame.event === 'worker.online') {
        const inflight = this.refreshing
        if (inflight) {
          void inflight.then(() => void this.refresh())
        } else {
          void this.refresh()
        }
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
  }

  /** 全量校准:遍历所有已连 worker 并发 tasks.list 拉**首页**(最近 PAGE_SIZE 个)合并(以 worker 为准,清掉本地多出的条目)。 */
  async refresh(): Promise<void> {
    if (this.refreshing) return this.refreshing
    this.refreshing = (async () => {
      try {
        this.workerOffsets.clear()
        this.workerHasMore.clear()
        const entries: TaskListEntry[] = []
        const pending: Array<Promise<void>> = []
        hubSession.forEachConnectedWorker((workerId, client) => {
          pending.push((async () => {
            try {
              const result = await client.rpc(workerId, 'tasks.list', { limit: PAGE_SIZE, offset: 0 })
              const incoming = (result?.tasks ?? []) as WorkerTaskSummary[]
              this.workerOffsets.set(workerId, incoming.length)
              this.workerHasMore.set(workerId, Boolean(result?.hasMore))
              for (const summary of incoming) {
                const entry = toEntry(summary)
                if (!entry.workerId) entry.workerId = workerId
                entries.push(entry)
              }
            } catch (error) {
              // 单台 worker 失败不影响整体合并(保留其余 worker 数据)。
              console.warn(`[taskStore] tasks.list 失败(${workerId}):`, error)
            }
          })())
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
      }
    })()
    return this.refreshing
  }

  /** 还有更多可分页拉取的任务(任意 worker hasMore)。 */
  hasMore(): boolean {
    for (const has of this.workerHasMore.values()) {
      if (has) return true
    }
    return false
  }

  /**
   * 滑动触底续拉:对还有更多的 worker 逐台取下一页(offset=已拉条数)合并进镜像。
   * 幂等防重入;单台失败保留其 hasMore 下次滑动重试。
   */
  async loadMore(): Promise<void> {
    if (this.loadingMore || !this.hasMore()) return
    this.loadingMore = true
    try {
      const pending: Array<Promise<void>> = []
      hubSession.forEachConnectedWorker((workerId, client) => {
        if (!this.workerHasMore.get(workerId)) return
        pending.push((async () => {
          try {
            const offset = this.workerOffsets.get(workerId) ?? 0
            const result = await client.rpc(workerId, 'tasks.list', { limit: PAGE_SIZE, offset })
            const incoming = (result?.tasks ?? []) as WorkerTaskSummary[]
            this.workerOffsets.set(workerId, offset + incoming.length)
            this.workerHasMore.set(workerId, Boolean(result?.hasMore))
            for (const summary of incoming) {
              const entry = toEntry(summary)
              if (!entry.workerId) entry.workerId = workerId
              this.tasks.set(entry.taskId, entry)
            }
          } catch (error) {
            // 单台失败不影响整体;hasMore 保持 true,下次滑动重试。
            console.warn(`[taskStore] tasks.list 分页失败(${workerId}):`, error)
          }
        })())
      })
      await Promise.all(pending)
      this.sortAndNotify()
    } finally {
      this.loadingMore = false
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
