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
    taskStore.trackCreated(taskId, opts?.title || input.slice(0, 40), workspace, opts.workerId)
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
