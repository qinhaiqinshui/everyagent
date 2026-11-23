/**
 * 插件 stream 频道桥接（`ctx.sdk.stream` 面，架构 §7.13 广播形态）。
 *
 * 职责：为插件维护「期望订阅的实例流频道」注册表（workerId × subjectId，频道
 * `u.<K>.worker.<wid>.wf.<subjectId>.stream`），在 worker 连接建立/连接集变化时补发
 * 订阅（HubClient 自身经 desiredSubs 处理断线重连与 hidden 降载/visible 恢复）；把到达的
 * 实例流 msg 帧转成回调事件；重连/可见性恢复/worker 集变化后触发 onResubscribed——
 * 订阅方据此重拉校准（无背压广播的漏帧以 worker 磁盘事实源 + 插件自有 RPC 补齐）。
 *
 * 与 pluginWorkerEvents 的分工：那是**固定频道**（worker 级 plugin 通知）的一次性桥接；
 * 本模块是插件**按需订阅**的动态频道，频道名知识收敛在此（插件不接触 ownerKey，
 * 对齐 §8.2「禁止按 ownerKey 前缀猜测」——归属由宿主解析）。
 *
 * 惰性接线：首个订阅/监听注册时挂上 hubSession 钩子（幂等）；插件无人使用时零开销。
 */
import { hubSession } from '@/hub/session'
import { channels } from '@/sdk'
import type { Disposable, PluginStreamEvent } from '@everyagent/plugin-api'

/** 期望订阅表：workerId → subjectId 集合。 */
const desired = new Map<string, Set<string>>()

type StreamEventHandler = (e: PluginStreamEvent) => void
type ResubscribedHandler = () => void

const streamEventHandlers = new Set<StreamEventHandler>()
const resubscribedHandlers = new Set<ResubscribedHandler>()

let wired = false

/**
 * 解析实例流频道名（`u.<K>.worker.<wid>.wf.<subjectId>.stream`）→ {workerId, subjectId}。
 *
 * 从右往左解析：workerId 可含点（hub clientId 字符集 [a-zA-Z0-9._-]），subjectId 不含点
 * （后端 ShortIds 短 id）；非该形态返回 null。
 */
export function parseInstanceStreamChannel(channel: string): { workerId: string; subjectId: string } | null {
  if (!channel.endsWith('.stream')) return null
  const body = channel.slice(0, -'.stream'.length)
  const idx = body.lastIndexOf('.wf.')
  if (idx < 0) return null
  const subjectId = body.slice(idx + '.wf.'.length)
  const prefix = body.slice(0, idx)
  const workerMark = prefix.lastIndexOf('.worker.')
  if (workerMark < 0) return null
  const workerId = prefix.slice(workerMark + '.worker.'.length)
  if (!workerId || !subjectId) return null
  return { workerId, subjectId }
}

/** 把当前期望集里的频道在已存在的 worker 连接上补发订阅；返回是否有任何期望订阅。 */
function resubscribeAll(): boolean {
  let any = false
  for (const [workerId, subjects] of desired) {
    const client = hubSession.workerClients.get(workerId)
    if (!client?.k) continue
    for (const subjectId of subjects) {
      client.sub(channels.workflowStream(client.k, workerId, subjectId))
      any = true
    }
  }
  return any
}

/** 惰性接线（幂等）：hub 帧扇出 + 重连/worker 集/可见性恢复钩子。 */
function ensureWired(): void {
  if (wired) return
  wired = true

  hubSession.onFrame((frame) => {
    const parsed = parseInstanceStreamChannel(frame.channel ?? '')
    if (!parsed) return
    const seq = frame.seq != null ? String(frame.seq) : undefined
    const e: PluginStreamEvent = {
      workerId: parsed.workerId,
      subjectId: parsed.subjectId,
      event: frame.event,
      seq,
      ts: typeof frame.ts === 'number' ? frame.ts : undefined,
      payload: frame.payload,
    }
    for (const handler of streamEventHandlers) {
      try {
        handler(e)
      } catch (err) {
        console.warn('[plugin-stream] 实例流事件处理器抛错:', err)
      }
    }
  })

  // 断线重连：HubClient 自身已重发 desiredSubs；这里只触发消费方重拉校准。
  hubSession.onReconnect(() => {
    if (desired.size === 0) return
    for (const handler of resubscribedHandlers) {
      try {
        handler()
      } catch (err) {
        console.warn('[plugin-stream] onResubscribed 处理器抛错:', err)
      }
    }
  })

  // worker 连接集变化：新出现的连接补发订阅（sub 时连接尚不存在的场景），并触发校准。
  hubSession.onWorkerConnectionsChanged(() => {
    if (desired.size === 0) return
    resubscribeAll()
    for (const handler of resubscribedHandlers) {
      try {
        handler()
      } catch (err) {
        console.warn('[plugin-stream] onResubscribed 处理器抛错:', err)
      }
    }
  })

  // 页面重新可见：HubClient 已重发被 hidden 降载退订的 .stream 频道；触发消费方校准。
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState !== 'visible') return
    if (desired.size === 0) return
    for (const handler of resubscribedHandlers) {
      try {
        handler()
      } catch (err) {
        console.warn('[plugin-stream] onResubscribed 处理器抛错:', err)
      }
    }
  })
}

/** 订阅实例流频道（幂等；连接未建立时挂入期望集，连接出现后自动补发）。 */
export function subInstanceStream(workerId: string, subjectId: string): void {
  if (!workerId || !subjectId) return
  ensureWired()
  let subjects = desired.get(workerId)
  if (!subjects) {
    subjects = new Set()
    desired.set(workerId, subjects)
  }
  const isNew = !subjects.has(subjectId)
  subjects.add(subjectId)
  if (isNew) resubscribeAll()
}

/** 退订实例流频道（幂等；同时移出期望集，不再随连接变化补发）。 */
export function unsubInstanceStream(workerId: string, subjectId: string): void {
  const subjects = desired.get(workerId)
  if (!subjects) return
  if (!subjects.delete(subjectId)) return
  if (subjects.size === 0) desired.delete(workerId)
  const client = hubSession.workerClients.get(workerId)
  if (client?.k) {
    client.unsub(channels.workflowStream(client.k, workerId, subjectId))
  }
}

/** 注册实例流推送帧监听（统一扇出，调用方按 workerId/subjectId 过滤）。 */
export function onInstanceStreamEvent(handler: StreamEventHandler): Disposable {
  ensureWired()
  streamEventHandlers.add(handler)
  return { dispose: () => streamEventHandlers.delete(handler) }
}

/** 注册订阅恢复监听（重连/worker 集变化/页面重新可见后触发，消费方重拉校准）。 */
export function onResubscribed(handler: ResubscribedHandler): Disposable {
  ensureWired()
  resubscribedHandlers.add(handler)
  return { dispose: () => resubscribedHandlers.delete(handler) }
}
