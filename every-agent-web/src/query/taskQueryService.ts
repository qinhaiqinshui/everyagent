/**
 * 任务查询服务(hub 版)。
 *
 * n 分支同位置的服务从浏览器仓储(taskRepository 等)聚合;本前端的任务
 * 真相源在 worker:列表来自 taskStore(tasks.list + tasks 频道镜像),
 * 线程来自 taskStream(事件折叠器产物)。本服务只做快照整形,保持
 * TasksPanel / TaskChat 消费的签名不变。
 */
import { formatTaskStatus, resolveTaskStatusTone, type TaskStatusTone } from '@/task/taskStatusPresentation'
import type { TaskThreadItem } from '@/task/eventFolder'
import type { AgentMessageRecord, ContextMonitorSnapshot, TaskStatus, TaskTraceRecord } from '@/types'
import { taskStore, type TaskListEntry } from '@/hub/taskStore'
import { taskStreamManager } from '@/hub/taskStream'
import { hubSession } from '@/hub/session'
import { workspaceRegistry } from '@/hub/workspaceRegistry'
import { channels } from '@every-agent/client'

export type { TaskThreadItem }

/** 聊天页头部展示快照。 */
export interface TaskChatHeaderSnapshot {
  displayTitle: string
  /** 任务挂靠的工作区根(头部工作区 chip)。 */
  workspace?: string
}

/** Task 聊天页最小运行快照。 */
export interface TaskChatTaskSnapshot {
  taskId: string
  status: TaskStatus
  /** 模型配置(worker 侧解析,前端只读展示)。 */
  llmConfigSnapshot?: { provider: string; model: string; reasoningEffort?: string }
  metadata?: Record<string, unknown>
}

/** Task 聊天页聚合快照。 */
export interface TaskChatSnapshot {
  task: TaskChatTaskSnapshot
  header: TaskChatHeaderSnapshot
  thread: TaskThreadItem[]
  contextUsage: ContextMonitorSnapshot | null
}

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
  /** 任务挂靠的工作区根(TasksPanel 按工作区分组)。 */
  workspace?: string
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
    // 直接消费 worker TaskSummary.usage(任务列表随 tasks.list/task.updated 携带,
    // 不再依赖打开聊天页建流;聊天页流折叠到实时值后优先实时)。
    contextUsage: taskStreamManager.peekState(entry.taskId)?.contextUsage ?? entry.contextUsage ?? null,
    workspace: entry.workspace,
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

  /** 统一显示线程(折叠器产物,缓存秒开 + sync 增量)。 */
  getTaskThread(taskId: string): TaskThreadItem[] {
    return taskStreamManager.get(taskId).state.items
  },

  /** 任务下全部可展示的 agent messages(从线程抽取)。 */
  getTaskAgentMessages(taskId: string): AgentMessageRecord[] {
    return this.getTaskThread(taskId)
      .filter((item): item is Extract<TaskThreadItem, { type: 'agent_message' }> => item.type === 'agent_message')
      .map((item) => item.message)
      .filter((message) => message.role !== 'system' && message.historyMode !== 'hidden')
  },

  /** 任务下全部 trace(从线程抽取)。 */
  getTaskTraces(taskId: string): TaskTraceRecord[] {
    return this.getTaskThread(taskId)
      .filter((item): item is Extract<TaskThreadItem, { type: 'task_trace' }> => item.type === 'task_trace')
      .map((item) => item.trace)
  },

  /** 聊天页聚合快照。 */
  getTaskChatSnapshot(taskId: string): TaskChatSnapshot {
    const entry = taskStore.get(taskId)
    if (!entry) {
      throw new Error(`任务不存在:${taskId}(worker 可能已重启,任务为内存态)`)
    }
    return {
      task: {
        taskId,
        status: entry.status,
        metadata: { workerId: entry.workerId, error: entry.error },
      },
      header: { displayTitle: entry.title, workspace: entry.workspace },
      thread: this.getTaskThread(taskId),
      contextUsage: taskStreamManager.peekState(taskId)?.contextUsage ?? entry.contextUsage ?? null,
    }
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
    /** 任务级 slash token(仅新建时传;worker 写入 meta.slashTaskTokens 并触发建后回调)。 */
    taskTokens?: string[]
    /** 原始输入(含 opaque token 串,仅用于 user.message 回放还原胶囊;缺省=纯文本输入)。 */
    rawContent?: string
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
      })
      return opts.taskId
    }
    if (!opts?.workerId) {
      throw new Error('请先选择 worker')
    }
    const workspace = opts?.workspace?.trim() || await resolveWorkspaceRoot()
    const taskTokens = opts?.taskTokens?.filter((token) => token?.length > 0) ?? []
    const result = await hubSession.rpcTo(opts.workerId, 'task.run', {
      input,
      title: opts?.title,
      idempotencyKey: opts?.idempotencyKey,
      workspace,
      configId: opts?.configId || undefined,
      // 仅新建分支携带;为空不传,保持与现状一致。
      ...(taskTokens.length > 0 ? { taskTokens } : {}),
      ...(opts.rawContent ? { rawContent: opts.rawContent } : {}),
    })
    const taskId = String(result?.taskId ?? '')
    if (!taskId) throw new Error('worker 未返回 taskId')
    taskStore.trackCreated(taskId, opts?.title || input.slice(0, 40), workspace)
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

  /**
   * 把任务队列第 index 条用户输入立即插入到正在进行的 AI 对话循环
   * (worker 级 input 频道 `task.dialogInsert` 事件,fire-and-forget)。
   * worker 侧 DialogInsertAdvisor 收到后,会在工具循环把工具结果交回 AI 时
   * 把该输入以 role=user 随工具结果一并提交给模型,并发射 user.message 事件
   * (前端右侧用户消息区可见、落盘回放完整);worker 同时按 index 从 pendingInputs 移除该项。
   * 成功无返回值;失败抛可读错误。
   */
  async insertQueuedInput(taskId: string, index: number, text: string): Promise<void> {
    try {
      const ownerWorkerId = taskStore.get(taskId)?.workerId
      if (!ownerWorkerId) {
        throw new Error('无法确定任务所属 worker(任务数据不可用)')
      }
      const client = hubSession.clientFor(ownerWorkerId)
      if (!client) {
        throw new Error('worker ' + ownerWorkerId + ' 未连接')
      }
      client.pub(
        channels.workerInput(client.k, ownerWorkerId),
        'task.dialogInsert',
        { taskId, index, text },
      )
    } catch (e) {
      throw new Error(e instanceof Error ? e.message : '队列插入失败')
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
