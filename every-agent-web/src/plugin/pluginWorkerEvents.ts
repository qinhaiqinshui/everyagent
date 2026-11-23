/**
 * worker 级插件通知频道桥接（架构 §5.2 `u.<K>.worker.<id>.plugin`）。
 *
 * 职责：订阅每条已连 worker 的插件通知频道，把 `plugin.<pluginId>.<name>` 帧转成
 * 领域事件 `plugin-worker-event{workerId, event, data}`，供插件 web 端经
 * `ctx.events.on('plugin-worker-event', …)` 按 event 名滤取。
 *
 * 定位与 `plugin-task-event` 的分工：那是任务流内的瞬态直推（data 即增量）；
 * 本频道是 worker 级、非任务域的「变更已发生」通知——hub 零状态只路由，宿主零缓冲
 * 只转译，**不做镜像/合并**（故不入 taskStore）：数据真相源在 worker 磁盘，插件收到
 * 通知后经自家 RPC 全量重拉（与 WORKER_DATA_CHANGED 同一「失效→重拉」模型）。
 *
 * 生命周期与 taskStore 订阅 tasks 频道同一套：desiredSubs 保证连接建立/重连后自动
 * 发出订阅；worker 上线/连接集合变化时重调 sub（幂等）。归属只认频道的 worker 段
 * （`workerIdOfFrame`），猜不出来源就丢帧——与 §8.2「禁止按 ownerKey 前缀猜测」一致。
 */
import { hubSession } from '@/hub/session'
import { channels } from '@every-agent/client'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'

let wired = false

/** 订阅全部已连 worker 的插件通知频道（幂等；desiredSubs 机制，未 open 的连接在建立后自动发出）。 */
function subscribeAll(): void {
  for (const [workerId, client] of hubSession.workerClients) {
    if (client.k) client.sub(channels.plugins(client.k, workerId))
  }
}

/** 进程内只接一次（main.tsx 启动接线）。 */
export function wirePluginWorkerEvents(): void {
  if (wired) return
  wired = true
  subscribeAll()
  hubSession.onReconnect(subscribeAll)
  hubSession.onWorkerConnectionsChanged(subscribeAll)
  hubSession.onFrame((frame) => {
    if (hubSession.isDirectoryFrame(frame) && frame.event === 'worker.online') {
      // 连接先于 worker 就绪的时序竞态下初始订阅可能落空：上线时补订一次。
      subscribeAll()
      return
    }
    const workerId = hubSession.workerIdOfFrame(frame)
    if (!workerId) return
    const client = hubSession.workerClients.get(workerId)
    if (!client || !client.k) return
    if (frame.channel !== channels.plugins(client.k, workerId)) return
    if (!frame.event.startsWith('plugin.')) return
    domainEventBus.emit(DOMAIN_EVENTS.PLUGIN_WORKER_EVENT, {
      workerId,
      event: frame.event,
      data: frame.payload,
    })
  })
}
