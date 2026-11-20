/**
 * 任务查询服务(hub 版)。
 *
 * n 分支同位置的服务从浏览器仓储(taskRepository 等)聚合线程;本前端的任务
 * 真相源在 worker:列表来自 taskStore(tasks.list + tasks 频道镜像),
 * 线程来自 taskStream(事件折叠器产物,消费方直接持 TaskStreamHandle/useTaskStream,
 * 「与 n 同形」的线程快照 API 已随步骤 8 五字段统一删除)。本服务只做任务列表
 * 快照整形与 task.run/队列遥控,保持 TasksPanel / TaskChat 消费的签名不变。
 */
import { formatTaskStatus, resolveTaskStatusTone, type TaskStatusTone } from '@/task/taskStatusPresentation'
import type { ContextMonitorSnapshot } from '@/types'
import type { TaskStatus } from '@/task/types'
import { taskStore, type TaskListEntry } from '@/task/taskStore'
import { hubSession } from '@/hub/session'
import { workspaceRegistry } from '@/hub/workspaceRegistry'

/** Task 列表展示快照(与 n 同形,attachedSkill* 恒为空)。 */
export interface TaskListItemSnapshot {
  taskId: string
  status: TaskStatus
  displayTitle: string
  updatedAt: number
  metadata?: Record<string, unknown>
  attachedSkillCount: number
  attachedSkillLabel: string
  statusLabel: string
  statusTone: TaskStatusTone
  contextUsage?: ContextMonitorSnapshot | null
  /** 任务创建时间(worker TaskSummary.createdAt;电池详情卡「创建时间」行数据源)。 */
  createdAt: number
  /** 任务挂靠的工作区根(TasksPanel 按工作区分组)。 */
  workspace?: string
  /** 任务归属 worker(TasksPanel 组内「加载更多」按 worker 定向续拉)。 */
  workerId?: string
}

/** Task 壳层摘要快照。 */
export interface TaskSummarySnapshot {
  status: TaskStatus
  displayTitle: string
}

function toListItem(entry: TaskListEntry): TaskListItemSnapshot {
  return {
    taskId: entry.taskId,
    status: entry.status,
    displayTitle: entry.title,
    updatedAt: entry.updatedAt,
    attachedSkillCount: 0,
    attachedSkillLabel: '',
    statusLabel: formatTaskStatus(entry.status),
    statusTone: resolveTaskStatusTone(entry.status),
    // 直接消费 worker TaskSummary.usage(usage 投影器聚合任务下所有 agent 最近一轮占用,
    // 随 tasks.list/task.updated 携带;不依赖打开聊天页建流,聊天页与列表页电池同源同值)。
    contextUsage: entry.contextUsage ?? null,
    createdAt: entry.createdAt,
    workspace: entry.workspace,
    workerId: entry.workerId,
  }
}

/**
 * task.dataDir 预取缓存(taskId → 预取条目)。
 *
 * 为什么需要:「复制任务路径」在**非安全上下文**(局域网 http 访问)下只能靠同步
 * `execCommand('copy')` 写剪贴板,而它要求点击手势仍在——先 await 一次 RPC 再复制,
 * 手势已被跨 task 的往返吃掉,复制必然失败。故路径必须在点击**之前**就绪:菜单打开时
 * 发出预取,点击时同步取用(`getPrefetchedTaskDataDir`)。
 *
 * 条目带 TTL:worker 数据目录迁移(换 homeDir 重启)后旧值会陈旧,过期即重新预取。
 */
interface TaskDataDirCacheEntry {
  /** 预取发起时刻(用于 TTL 判定)。 */
  at: number
  /** 已解析的绝对路径;预取在飞/失败时为 null。 */
  resolved: string | null
}

const TASK_DATA_DIR_TTL_MS = 5 * 60 * 1000
const taskDataDirCache = new Map<string, TaskDataDirCacheEntry>()

/**
 * task.dataDir 取数本体(模块级函数:getTaskDataDir 与预取共用,不依赖 this,
 * 避免方法被解构调用时丢上下文)。
 */
async function fetchTaskDataDir(taskId: string): Promise<string> {
  const ownerWorkerId = taskStore.get(taskId)?.workerId
  if (!ownerWorkerId) {
    throw new Error('无法确定任务所属 worker(任务数据不可用)')
  }
  const result = await hubSession.rpcTo(ownerWorkerId, 'task.dataDir', { taskId })
  const path = String(result?.path ?? '')
  if (!path) {
    throw new Error('worker 未返回任务路径')
  }
  return path
}

export const taskQueryService = {
  /** 任务列表快照(来自 taskStore 镜像,调用方经 taskStore.subscribe 订阅变更)。 */
  listTaskListItems(): TaskListItemSnapshot[] {
    return taskStore.list().map(toListItem)
  },

  /** Task 壳层摘要。 */
  getTaskSummarySnapshot(taskId: string): TaskSummarySnapshot | null {
    const entry = taskStore.get(taskId)
    if (!entry) return null
    return { status: entry.status, displayTitle: entry.title }
  },

  /**
   * 拉取任务数据目录完整路径(task.dataDir RPC):workspaces/<workspaceId>/tasks/<taskId>/
   * 在 worker 宿主上的绝对路径。任务列表「复制任务路径」按需拉取,路径不进列表 wire。
   * 按任务归属 worker 定向调用;失败抛可读错误。
   */
  async getTaskDataDir(taskId: string): Promise<string> {
    return fetchTaskDataDir(taskId)
  },

  /**
   * 预取任务数据目录(菜单打开等「即将复制」的时机调用):fire-and-forget,
   * 结果落 {@link getPrefetchedTaskDataDir} 供点击时同步取用。失败静默(点击时才提示)。
   */
  prefetchTaskDataDir(taskId: string): void {
    const cached = taskDataDirCache.get(taskId)
    // 已有新鲜条目 = 在飞或已落定,不重复发起。
    if (cached && Date.now() - cached.at < TASK_DATA_DIR_TTL_MS) return
    const entry: TaskDataDirCacheEntry = { at: Date.now(), resolved: null }
    taskDataDirCache.set(taskId, entry)
    // 预取是「顺手准备」:失败从缓存移除(点击时重取并提示),且不冒 unhandled rejection
    // ——两个 handler 都不抛。
    fetchTaskDataDir(taskId).then(
      (path) => {
        entry.resolved = path
      },
      () => {
        if (taskDataDirCache.get(taskId) === entry) taskDataDirCache.delete(taskId)
      },
    )
  },

  /**
   * 同步取预取到的任务数据目录路径;尚未落定/已过期/不存在返回 null。
   * 调用点据此在**点击回调内同步写剪贴板**,不跨 await(保住 execCommand 需要的用户手势)。
   */
  getPrefetchedTaskDataDir(taskId: string): string | null {
    const entry = taskDataDirCache.get(taskId)
    if (!entry) return null
    if (Date.now() - entry.at >= TASK_DATA_DIR_TTL_MS) {
      taskDataDirCache.delete(taskId)
      return null
    }
    return entry.resolved
  },

  /**
   * 运行任务(task.run RPC,创建/续跑合一):
   * - 不传 taskId = 新建(workspace 必填,worker 侧强校验),worker 立即返回
   *   taskId 并开始执行,taskStore 由 task.created 事件校准(此处先落占位);
   * - 传 taskId = 运行中任务入队 / 终态任务载入历史续跑,返回原 taskId。
   */
  async runTask(input: string, opts?: {
    /** 已有任务:运行中入队,终态冷启动续跑;缺省=新建。 */
    taskId?: string
    /** 目标 worker(新建必填;多 worker 下 worker 是任务的显式归属,无全局默认)。 */
    workerId?: string
    title?: string
    idempotencyKey?: string
    configId?: string
    /** 任务挂靠的工作区根(新建必填;草稿选择器指定,缺省用注册表首选根)。 */
    workspace?: string
    /** 原始输入(含 opaque token 串,仅用于 user.message 回放还原胶囊;缺省=纯文本输入)。 */
    rawContent?: string
    /** 通用 metadata(透传到 task.run RPC params;如 { insert: true } 表示插入当前对话、{ editSeq } 表示编辑重发)。 */
    metadata?: Record<string, unknown>
  }): Promise<string> {
    if (opts?.taskId) {
      // 续跑/入队:透传当前选定的模型 configId(旧任务可切换模型);不传则 worker 沿用任务冻结模型。
      // 多 worker 下按任务归属 worker 定向(避免续跑/入队发错到当前选中 worker)。
      const ownerWorkerId = taskStore.get(opts.taskId)?.workerId
      if (!ownerWorkerId) {
        throw new Error('无法确定任务所属 worker(任务数据不可用)')
      }
      await hubSession.rpcTo(ownerWorkerId, 'task.run', {
        taskId: opts.taskId,
        input,
        configId: opts.configId || undefined,
        ...(opts.rawContent ? { rawContent: opts.rawContent } : {}),
        ...(opts.metadata ? { metadata: opts.metadata } : {}),
      })
      return opts.taskId
    }
    if (!opts?.workerId) {
      throw new Error('尚未连接 worker')
    }
    const workspace = opts?.workspace?.trim() || await resolveWorkspaceRoot()
    const result = await hubSession.rpcTo(opts.workerId, 'task.run', {
      input,
      title: opts?.title,
      idempotencyKey: opts?.idempotencyKey,
      workspace,
      configId: opts?.configId || undefined,
      ...(opts.rawContent ? { rawContent: opts.rawContent } : {}),
      ...(opts.metadata ? { metadata: opts.metadata } : {}),
    })
    const taskId = String(result?.taskId ?? '')
    if (!taskId) throw new Error('worker 未返回 taskId')
    taskStore.trackCreated(taskId, opts?.title || input, workspace, opts.workerId)
    return taskId
  },

  /**
   * 移除任务队列中第 index 条输入(task.queueRemove RPC)。
   * 成功无返回值;失败抛可读错误(worker RpcError message 原样透传)。
   */
  async removeQueuedInput(taskId: string, index: number): Promise<void> {
    try {
      const ownerWorkerId = taskStore.get(taskId)?.workerId
      if (!ownerWorkerId) {
        throw new Error('无法确定任务所属 worker(任务数据不可用)')
      }
      await hubSession.rpcTo(ownerWorkerId, 'task.queueRemove', { taskId, index })
    } catch (e) {
      throw new Error(e instanceof Error ? e.message : '队列操作失败')
    }
  },

  /**
   * 把任务队列中第 fromIndex 条输入移动到第 toIndex 条(task.queueMove RPC)。
   * 成功无返回值;失败抛可读错误(worker RpcError message 原样透传)。
   */
  async moveQueuedInput(taskId: string, fromIndex: number, toIndex: number): Promise<void> {
    try {
      const ownerWorkerId = taskStore.get(taskId)?.workerId
      if (!ownerWorkerId) {
        throw new Error('无法确定任务所属 worker(任务数据不可用)')
      }
      await hubSession.rpcTo(ownerWorkerId, 'task.queueMove', { taskId, fromIndex, toIndex })
    } catch (e) {
      throw new Error(e instanceof Error ? e.message : '队列操作失败')
    }
  },

}

/**
 * task.run 新建用的工作区根(必填项):注册表首选根 → defaultRoot,
 * 未校准时先补一次校准;仍缺失报错(worker 端 resolveAndRegister 会顺带注册)。
 */
async function resolveWorkspaceRoot(): Promise<string> {
  let root = workspaceRegistry.primaryRoot()
    || workspaceRegistry.current?.defaultRoot
    || ''
  if (!root) {
    await workspaceRegistry.refresh()
    root = workspaceRegistry.primaryRoot()
      || workspaceRegistry.current?.defaultRoot
      || ''
  }
  if (!root) {
    throw new Error('工作区信息不可用(worker 可能离线),请稍后重试')
  }
  return root
}
