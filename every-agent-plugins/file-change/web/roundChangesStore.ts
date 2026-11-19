/**
 * 轮末「本轮文件变更」数据源（插件自持，架构 §7.15.2）。
 *
 * 真相源 = worker 任务目录下 `file-changes/<roundId>.json`（由本插件 FileChangeAdvisor +
 * RoundClosedListener 落盘）；取数口 = 本插件自注册的 `task.fileChanges` RPC：
 * - **省略 roundId** → 全任务各轮**轻量摘要**（一次拉全，避免逐轮 N 次 RPC）；
 * - **带 roundId** → 该轮**变更全文**（点开 diff 时按需拉，promise 缓存）。
 *
 * 增量到达走**数据直推**（§7.15.2）：轮闭合落盘成功后，worker 侧 provider 立即经 stream
 * 频道直推瞬态事件 `plugin.file-change.roundChanges`（data={roundId, changes}，不落盘），
 * 宿主 taskStream 对 plugin.* 前缀统一透传领域事件 `plugin-task-event`，由 index.ts 订阅后调
 * {@link upsertRoundChanges} 合入本缓存——不再经「作废→重拉」间接失效。时序保证:直推在
 * 分片落盘之后发出（provider 回调先写盘后推送），且早于 round.closed（refreshRounds 让轮末
 * 面板挂载）——面板挂载时缓存必已含本轮。初次打开/历史任务由 {@link ensureRoundChanges}
 * 全量拉取兜底（推送是瞬态的,重连间隙错过属可接受降级,真相始终可从磁盘反推）。
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

/**
 * 「闭合轮数据缺失」自愈重拉节流窗口(ms):同任务短窗口内多个面板同时挂载(或 version
 * bump 连环重渲染)只强制重拉一次;重拉后该轮仍缺失(= 真无变更)也不会连环触发
 * (下一次触发须等新一次渲染且已出窗口)。
 */
const FORCE_REFRESH_THROTTLE_MS = 5000
/** 每任务最近一次自愈重拉时刻(节流用)。 */
const lastForceRefreshAt = new Map<string, number>()

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
  pullAllRounds(taskId, workerId, 'ensure')
}

/**
 * 拉全任务各轮轻量摘要并 REPLACE 内存缓存(ensure 与自愈重拉共用):
 * ensure 语义 = 受 loaded 门幂等;force 语义 = 绕过 loaded 门(磁盘分片是事实源,
 * 整体重拉覆盖内存,漏掉的轮随拉取补齐)。失败时 force 回滚节流时间戳,允许下次挂载立刻重试。
 */
function pullAllRounds(taskId: string, workerId: string | undefined, mode: 'ensure' | 'force'): void {
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
      console.debug(`[file-change] 摘要已拉取(${mode}) task=${taskId} 有变更轮数=${byRound.size}`)
      bump()
    })
    .catch((error) => {
      // 失败不写死缓存:下次 ensure（重渲染/新一轮闭合）仍会重试;
      // force 失败同时回滚节流时间戳,自愈路径下次挂载即可立刻再试。
      console.warn(`[file-change] 拉取轮次文件变更摘要失败(${mode} ${taskId}):`, error)
      if (mode === 'force') {
        lastForceRefreshAt.delete(taskId)
      }
    })
    .finally(() => {
      inflight.delete(taskId)
    })
  inflight.set(taskId, task)
}

/**
 * 「闭合轮数据缺失」自愈重拉:轮末摘要的增量只来自瞬态直推(不落盘),错过
 * (重连间隙/后台冻结标签/会话内丢帧)后旧实现只能整页刷新找回——ensure 的 loaded 门
 * 在首次成功拉取后永不再拉,直推一旦错过,该轮面板在本次会话内恒为空。
 * 本入口在「已 loaded 且该轮仍缺数据」时绕过 loaded 门强制重拉一次(节流见上):
 * 面板挂载(轮闭合渲染)即触发,数据从磁盘分片反推,秒级自愈且无须刷新页面。
 */
export function maybeRefreshMissingRound(taskId: string, roundId: string, workerId?: string): void {
  if (!taskId || !roundId) return
  const existing = entries.get(taskId)
  if (!existing?.loaded) return // 未加载/首拉失败:ensure 常规路径会拉,不自愈
  if (existing.byRound.has(roundId)) return // 该轮已有数据(或直推已合入):无须自愈
  const now = Date.now()
  const last = lastForceRefreshAt.get(taskId) ?? 0
  if (now - last < FORCE_REFRESH_THROTTLE_MS) return
  lastForceRefreshAt.set(taskId, now)
  pullAllRounds(taskId, workerId, 'force')
}

/** 取某轮轻量摘要；未拉取/该轮无变更返回 undefined。 */
export function getRoundChanges(taskId: string, roundId: string): RoundFileChangeSummary[] | undefined {
  if (!roundId) return undefined
  return entries.get(taskId)?.byRound.get(roundId)
}

/**
 * 直推合入（领域事件 `plugin-task-event` 携带 `plugin.file-change.roundChanges` 时由 index.ts 调）:
 * 按 roundId REPLACE 该轮摘要。已加载条目只并入不重拉（loaded 不动,后续 ensure 幂等直返）;
 * 条目尚不存在时建未加载条目（loaded=false）——面板立即可见,历史轮仍由下一次
 * {@link ensureRoundChanges} 全量拉取补齐（拉取自磁盘重建,天然包含本轮,替换无损）。
 * changes 为空 = 该轮无变更:不建条目、已存在则删该轮键（无变更的轮不入表）。
 */
export function upsertRoundChanges(
  taskId: string,
  roundId: string,
  changes: RoundFileChangeSummary[],
): void {
  if (!taskId || !roundId) return
  const existing = entries.get(taskId)
  if (!existing) {
    if (!changes.length) return
    entries.set(taskId, { byRound: new Map([[roundId, changes]]), loaded: false })
    console.debug(`[file-change] 直推合入(新建条目) task=${taskId} round=${roundId} 变更数=${changes.length}`)
    bump()
    return
  }
  if (changes.length) {
    existing.byRound.set(roundId, changes)
  } else if (!existing.byRound.delete(roundId)) {
    return // 原本就无该轮键:无变化不 bump
  }
  console.debug(`[file-change] 直推合入 task=${taskId} round=${roundId} 变更数=${changes.length}`)
  bump()
}

/**
 * 作废某任务缓存（任务删除时调用,清理内存）。
 * 轮级增量已由直推 upsert 承载,不再在轮闭合时整缓存作废重拉。
 */
export function invalidateRoundChanges(taskId: string): void {
  if (!taskId) return
  if (!entries.delete(taskId)) return
  lastForceRefreshAt.delete(taskId)
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
