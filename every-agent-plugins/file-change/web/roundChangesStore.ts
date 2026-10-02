/**
 * 轮末「本轮文件变更」数据源（插件自持，架构 §7.15.2）。
 *
 * 真相源 = worker 任务目录下 `file-changes/<roundId>.json`（由本插件 FileChangeAdvisor +
 * RoundClosedListener 落盘）；取数口 = 本插件自注册的 `task.fileChanges` RPC：
 * - **省略 roundId** → 全任务各轮**轻量摘要**（一次拉全，避免逐轮 N 次 RPC）；
 * - **带 roundId** → 该轮**变更全文**（点开 diff 时按需拉，promise 缓存）。
 *
 * 摘要缓存按 taskId 一份；宿主收到瞬态 `round.closed` 信号时 emit 通用领域事件
 * `task-round-closed`（不含任何文件变更语义），由 index.ts 调 {@link invalidateRoundChanges}
 * 作废，组件重渲染时 {@link ensureRoundChanges} 重拉。
 *
 * 组件侧只认 version 快照（`useSyncExternalStore`），内部 Map 引用不外泄，避免撕裂渲染。
 */
import { getPluginContext } from './pluginRuntime'

/** 单轮文件变更轻量摘要（task.fileChanges 省略 roundId 应答的 changes 项）。 */
export interface RoundFileChangeSummary {
  filePath: string
  fileName: string
  changeType: 'created' | 'updated' | 'deleted'
  saveCount: number
}

/** 单轮文件变更全文项（task.fileChanges 带 roundId 应答的 changes 项）。 */
export interface RoundFileChangeFull extends RoundFileChangeSummary {
  beforeContent: string
  afterContent: string
}

/** task.fileChanges（省略 roundId）应答。 */
interface AllRoundsResult {
  rounds?: Array<{ roundId?: string; changes?: RoundFileChangeSummary[] }>
}

/** task.fileChanges（带 roundId）应答。 */
interface RoundFullResult {
  changes?: RoundFileChangeFull[]
}

/** 一个任务的摘要缓存条目。 */
interface TaskEntry {
  /** roundId → 该轮轻量摘要（无变更的轮不入表）。 */
  byRound: Map<string, RoundFileChangeSummary[]>
  /** 是否已成功拉取过一次（false = 未拉/拉失败,允许后续重拉）。 */
  loaded: boolean
}

const entries = new Map<string, TaskEntry>()
const inflight = new Map<string, Promise<void>>()
const fullCaches = new Map<string, Promise<RoundFileChangeFull[]>>()
const listeners = new Set<() => void>()

/** 快照版本号：任一任务缓存变化即自增,组件据此重渲染。 */
let version = 0

function bump(): void {
  version += 1
  for (const listener of [...listeners]) {
    listener()
  }
}

/** 订阅缓存版本变化（useSyncExternalStore 的 subscribe）。 */
export function subscribeRoundChanges(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** 当前缓存版本（useSyncExternalStore 的 getSnapshot,稳定值）。 */
export function getRoundChangesVersion(): number {
  return version
}

/**
 * 确保该任务的全轮轻量摘要已在缓存中（幂等、并发去重、失败允许下次重拉）。
 * 不返回 promise:拉取完成后 bump 版本,由 React 重渲染取数。
 */
export function ensureRoundChanges(taskId: string, workerId?: string): void {
  if (!taskId) return
  const existing = entries.get(taskId)
  if (existing?.loaded) return
  if (inflight.has(taskId)) return
  const ctx = getPluginContext()
  const targetWorker = workerId || ctx.sdk.workerId
  const task = ctx.sdk
    .rpc(targetWorker, 'task.fileChanges', { taskId })
    .then((result) => {
      const rounds = ((result as AllRoundsResult | undefined)?.rounds ?? []) as NonNullable<
        AllRoundsResult['rounds']
      >
      const byRound = new Map<string, RoundFileChangeSummary[]>()
      for (const item of rounds) {
        if (!item?.roundId || !Array.isArray(item.changes) || item.changes.length === 0) {
          continue
        }
        byRound.set(item.roundId, item.changes)
      }
      entries.set(taskId, { byRound, loaded: true })
      console.debug(`[file-change] 摘要已拉取 task=${taskId} 有变更轮数=${byRound.size}`)
      bump()
    })
    .catch((error) => {
      // 失败不写死缓存:下次 ensure（重渲染/新一轮闭合）仍会重试
      console.warn(`[file-change] 拉取轮次文件变更摘要失败(${taskId}):`, error)
    })
    .finally(() => {
      inflight.delete(taskId)
    })
  inflight.set(taskId, task)
}

/** 取某轮轻量摘要；未拉取/该轮无变更返回 undefined。 */
export function getRoundChanges(taskId: string, roundId: string): RoundFileChangeSummary[] | undefined {
  if (!roundId) return undefined
  return entries.get(taskId)?.byRound.get(roundId)
}

/**
 * 作废某任务缓存（宿主 `task-round-closed` 领域事件 / 任务删除时调用）。
 * 只作废不重拉:重拉由组件在下一次渲染的 effect 里 ensure。
 */
export function invalidateRoundChanges(taskId: string): void {
  if (!taskId) return
  if (!entries.delete(taskId)) return
  for (const key of [...fullCaches.keys()]) {
    if (key.startsWith(`${taskId}#`)) {
      fullCaches.delete(key)
    }
  }
  console.debug(`[file-change] 摘要缓存已作废 task=${taskId}`)
  bump()
}

/** 拉取（并缓存）某轮变更全文；同轮并发/重复点击共用一次 RPC。 */
export function loadRoundFullChanges(
  taskId: string,
  roundId: string,
  workerId?: string,
): Promise<RoundFileChangeFull[]> {
  const key = `${taskId}#${roundId}`
  const cached = fullCaches.get(key)
  if (cached) return cached
  const ctx = getPluginContext()
  const promise = ctx.sdk
    .rpc(workerId || ctx.sdk.workerId, 'task.fileChanges', { taskId, roundId })
    .then((result) => ((result as RoundFullResult | undefined)?.changes ?? []))
    .catch((error) => {
      fullCaches.delete(key) // 失败不缓存,允许重试
      throw error
    })
  fullCaches.set(key, promise)
  return promise
}
