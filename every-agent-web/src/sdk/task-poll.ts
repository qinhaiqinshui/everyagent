import type { HubClient } from './hub-client'
import type { TaskFileChangesResult, TaskRoundsResult } from '../types'

/** task.poll 参数 */
export interface TaskPollParams {
  taskId: string;
  /** 按轮 id 拉取该轮全部事件（后端解析成区间）。 */
  roundId?: string;
  /** 增量拉取：只返回 seq > afterSeq 的事件。 */
  afterSeq?: number | string;
  /** 向前翻页：只返回 seq < beforeSeq 的事件（上滚加载更早记录）。 */
  beforeSeq?: number | string;
  /** 单批条数上限。 */
  limit?: number;
  /** 'events' = 按条拉取；'rounds' = 按完整轮次拉取（默认打开任务用 rounds）。 */
  mode?: 'events' | 'rounds';
  /** rounds 模式下的轮次数（默认 1 = 最近一轮）。 */
  count?: number;
  /** 长轮询等待毫秒（>0 时 worker 无增量会挂起至新事件或超时）。 */
  waitMs?: number;
}

/** task.poll rpc.data 批次中的事件项（与 TaskStreamEvent 同形但独立类型）。 */
export interface TaskPollEvent {
  seq: number | string;
  event: string;
  agentId?: string | null;
  payload: Record<string, unknown>;
}

/** task.poll 最终 rpc.ok 的 result。 */
export interface TaskPollResult {
  hasMore: boolean;
  firstSeq: number | string;
  lastSeq: number | string;
  task?: { status: string };
  pendingAsk?: unknown;
  /** 任务是否运行中（前端据此决定是否继续轮询）。 */
  live: boolean;
}

/** task.poll rpc.data 批项的完整 wire 形态（TaskPollEvent + wire 恒携带的 ts；轮详情折叠器排序需要）。 */
export interface TaskPollWireEvent extends TaskPollEvent {
  /** 事件毫秒时间戳（worker wireEvent 恒写入；缺省时消费方自行兜底）。 */
  ts?: number;
}

/** task.rounds 参数。 */
export interface TaskRoundsParams {
  taskId: string;
}

/**
 * 拉取任务轮次索引（task.rounds，plan-rounds-jsonl 步骤 5）：一次应答携带 rounds.jsonl 全量
 * 已闭合轮（rounds）、运行中任务的当前未闭合轮起点（open）与任务状态/存活位；旧任务（无
 * rounds.jsonl）由 worker 惰性全量生成落盘后返回，前端不兼容旧数据、无回退路径。
 * 应答类型见 TaskRoundsResult（types/index.ts）。任务不存在 → hub-client 以 RpcError
 * （code='NOT_FOUND'）reject，沿用现有 RPC 错误处理模式（与 task.poll 同口径，由调用方捕获）。
 */
export async function fetchTaskRounds(
  client: HubClient,
  workerId: string,
  params: TaskRoundsParams,
): Promise<TaskRoundsResult> {
  return (await client.rpc(workerId, 'task.rounds', { taskId: params.taskId })) as TaskRoundsResult;
}

/** task.fileChanges 参数。 */
export interface TaskFileChangesParams {
  taskId: string;
  /** 目标轮次 ID（rounds.jsonl 每行 roundId）。 */
  roundId: string;
}

/**
 * 拉取指定轮次的文件变更全文（task.fileChanges）：一次应答携带该轮全部文件变更，
 * 每项含 beforeContent / afterContent。参数透传，与 fetchTaskRounds 同风格。
 * 应答类型见 TaskFileChangesResult（types/index.ts）。任务或轮次不存在 → hub-client
 * 以 RpcError（code='NOT_FOUND'）reject，沿用现有 RPC 错误处理模式。
 */
export async function fetchTaskFileChanges(
  client: HubClient,
  workerId: string,
  params: TaskFileChangesParams,
): Promise<TaskFileChangesResult> {
  return (await client.rpc(workerId, 'task.fileChanges', {
    taskId: params.taskId,
    roundId: params.roundId,
  })) as TaskFileChangesResult;
}

/** task.roundTail 参数。 */
export interface TaskRoundTailParams {
  taskId: string;
  /** 未闭合尾轮起点 seq（只拉取 seq >= startSeq 的事件）。 */
  startSeq: number | string;
  /** 单批条数上限（默认 50，上限 500）。 */
  limit?: number;
}

/**
 * 拉取未闭合尾轮尾部事件（task.roundTail）：一次应答携带 seq >= startSeq 的最后 limit 条事件
 * （rpc.data 分批推送，形状同 task.poll 的 wire 事件）。返回收集到的全部事件与 rpc.ok 的 result
 * （{hasMore, firstSeq, lastSeq, task:{status}, live}）。任务不存在 → hub-client 以 RpcError
 * （code='NOT_FOUND'）reject，沿用现有 RPC 错误处理模式（与 task.poll / task.rounds 同口径）。
 */
export async function fetchTaskRoundTail(
  client: HubClient,
  workerId: string,
  params: TaskRoundTailParams,
): Promise<{ events: TaskPollWireEvent[]; result: TaskPollResult }> {
  const events: TaskPollWireEvent[] = [];
  const result = (await client.rpc(workerId, 'task.roundTail', {
    taskId: params.taskId,
    startSeq: params.startSeq,
    limit: params.limit ?? 50,
  }, {
    onData: (batch: TaskPollWireEvent[]) => {
      events.push(...batch);
    },
  })) as TaskPollResult;
  return { events, result };
}