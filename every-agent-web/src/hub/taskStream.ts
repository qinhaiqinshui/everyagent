/**
 * 任务流管理(单一线程真相源):TaskPacketView(仅订阅 worker 定向推送,不再轮询)×
 * eventFolder(折叠成线程)× askStore(卡片)。
 *
 * 打开任务(open)时一次性建齐骨架:
 * - task.rounds 全量轮次 → folder.foldRound 折入 user(rounds.jsonl userMessage)与闭合轮合成 final;
 * - 运行中未闭合尾轮 → task.roundTail 拉「最后一页」(200)与流式增量接上;
 * - task.agents 拉子 agent 台账建 agentMeta(胶囊列表/悬停卡片数据源);
 * - 之后 worker 定向推送(stream 频道)的流式增量(delta/thinking/message/tool/子 agent 等)
 *   实时折入同一 folder。
 * 线程数据唯一真相源 = folder.state.items(TaskThreadItem[]),按 seq 去重/排序,
 * 前端 TaskThread 直接消费,不做本地持久化(对话历史由 worker 落盘)。过程内容一律懒加载:
 * 闭合轮展开 / 终态未闭合尾轮走 loadRoundForward 前向分页;运行中尾轮往上翻历史走
 * loadRoundBackward 后向分页。
 *
 * 轮次开启/闭合由后端推送 round.opened/round.closed 信号事件(不落盘、不折入 items),
 * 收到后仅触发 rounds 快照刷新(重新 task.rounds + 幂等 foldRound),前端不在本地判开/闭。
 *
 * 实时信号链(同一折叠器状态):agentStates(agent 列表)/agentMeta(子 agent 台账+用量快照)/
 * contextUsage(上下文电池)/
 * ask 登记(askStore)/taskModel;输入/控制:sendInput(task.input 入队)/cancel(task.cancel)/replyAsk。
 * 重连:hubSession.onReconnect → 对所有活跃句柄只重拉数据校准(rounds+尾段+子 agent 台账);瞬态重连
 * HubClient 实例不变、view 监听器仍有效、desiredSubs 已自动重发,无需重建 view。
 * 渲染节流:折叠推进合并为 50ms 一拍,防止大任务历史回放时逐事件触发重渲染。
 */
import {
  TaskPacketView,
  fetchTaskAgents,
  fetchTaskRounds,
  fetchTaskRoundTail,
  type TaskStreamEvent,
  type TaskPollWireEvent,
} from '@every-agent/client'
import {
  emptyThreadState,
  TaskEventFolder,
  type FoldableTaskEvent,
  type TaskThreadState,
} from '@/task/eventFolder'
import type { RoundSummary, TaskRoundsResult } from '@/types'
import { hubSession } from './session'
import { taskStore } from './taskStore'
import { clearAsksOfTask, settleAsk, upsertPendingAsk, type WorkerAskPayload } from './askStore'
import { registerAskReplySender } from './askStore'

/** 折叠推进 → 重渲染的合并窗口(ms):流式高频事件按拍合并。 */
const NOTIFY_COALESCE_MS = 50

/** 大整数 seq 精确比较(雪花 ID 超 2^53,不可走 Number)。 */
function cmpSeq(a: number | string, b: number | string): number {
  let sa: string
  let sb: string
  try {
    const ba = BigInt(String(a).trim())
    const bb = BigInt(String(b).trim())
    return ba < bb ? -1 : ba > bb ? 1 : 0
  } catch {
    sa = String(a)
    sb = String(b)
  }
  const na = sa[0] === '-'
  const nb = sb[0] === '-'
  if (na !== nb) return na ? -1 : 1
  const da = na ? sa.slice(1) : sa
  const db = nb ? sb.slice(1) : sb
  if (da.length !== db.length) return (da.length - db.length) * (na ? -1 : 1)
  for (let i = 0; i < da.length; i++) {
    if (da[i] !== db[i]) return (da[i] < db[i] ? -1 : 1) * (na ? -1 : 1)
  }
  return 0
}

export interface TaskStreamHandle {
  taskId: string
  workerId: string
  /** 当前折叠状态(可变引用,变更经 subscribe 通知重渲染)。 */
  state: TaskThreadState
  /** 线程变更订阅(折叠推进/缓存回放)。 */
  subscribe(fn: () => void): () => void
  /** 发送新一轮用户输入(task.input;rawContent 为原始输入,可选)。 */
  sendInput(text: string, rawContent?: string): void
  /** 取消任务(task.cancel RPC)。 */
  cancel(): Promise<any>
  /** 手动触发校准:重拉尾段并续轮询。 */
  resync(): Promise<void>
  /** 子 agent 标题解析(线程 more 栏)。 */
  resolveAgentTitle(agentId: string): string | undefined
  /** 是否存在流式中的消息(本轮内容仍在增长,跟随滚动用;详见 TaskEventFolder.hasStreaming)。 */
  hasStreaming(): boolean
  /** 任务轮次索引快照(task.rounds 最近一次应答;null=尚未拉取)。 */
  rounds(): TaskRoundsResult | null
  /** 任务轮次索引最近一次拉取错误(null=无错误)。 */
  roundsError(): string | null
  /** 重新拉取任务轮次索引(重试/校准)。 */
  reloadRounds(): Promise<void>
  /**
   * 懒加载·前向续拉一轮过程(闭合轮展开 / 终态未闭合尾轮):**先查 items 缓存**——该轮区间
   * (afterSeq, endSeq] 已由真实事件完整覆盖则直接返回 fromCache(不 RPC);部分覆盖则自动把
   * afterSeq 提升到已覆盖点从缺口续拉。返回 {lastSeq, hasMore, fromCache} 供 UI 更新游标。
   */
  loadRoundForward(page: {
    startSeq: number | string
    endSeq?: number | string | null
    afterSeq: number | string
    limit?: number
  }): Promise<{ lastSeq: number | string; hasMore: boolean; fromCache: boolean }>
  /**
   * 懒加载·后向续拉(运行中尾轮往上翻历史):**先查 items 缓存**——[startSeq, beforeSeq) 已连到
   * 轮起点则直接返回 fromCache+reachedStart(不 RPC);部分覆盖则从缺口继续向前。返回
   * {firstSeq, reachedStart, fromCache}。
   */
  loadRoundBackward(page: {
    startSeq: number | string
    beforeSeq: number | string
    limit?: number
  }): Promise<{ firstSeq: number | string; reachedStart: boolean; fromCache: boolean }>
  close(): void
}

class ManagedStream {
  view: TaskPacketView | null = null
  /** view 绑定的 HubClient(仅致命错误替换实例时换新,ensureView 检测后自动重建 view)。 */
  boundClient: import('@every-agent/client').HubClient | null = null
  folder: TaskEventFolder
  listeners = new Set<() => void>()
  notifyTimer: ReturnType<typeof setTimeout> | null = null
  /** 当前 open 进行中(防重入)。 */
  opening: Promise<void> | null = null
  /** rounds 索引快照(task.rounds 应答;刷新除重拉外供消费方读)。 */
  rounds: TaskRoundsResult | null = null
  /** rounds 索引最近一次拉取错误(null=无错误)。 */
  roundsError: string | null = null
  /** task.agents 台账本 open 周期内已拉取(open() 进入时复位 → 重连 resync 再次 open 允许重拉)。 */
  agentsSeeded = false

  constructor(public taskId: string, public workerId: string) {
    this.folder = new TaskEventFolder(emptyThreadState(taskId))
  }

  /** 变更通知(50ms 合并):流式 delta 高频到达时按拍重渲染。 */
  notify(): void {
    if (this.notifyTimer) return
    this.notifyTimer = setTimeout(() => {
      this.notifyTimer = null
      for (const fn of this.listeners) fn()
    }, NOTIFY_COALESCE_MS)
  }

  ensureView(): TaskPacketView {
    const client = hubSession.workerClient(this.workerId)
    if (!client) throw new Error('worker ' + this.workerId + ' 未连接')
    if (this.view && this.boundClient === client) {
      return this.view
    }
    // 换 view(如 worker 连接重建、client 实例变化)前先关旧 view,摘干净它的监听器。
    this.view?.close()
    const view = new TaskPacketView(client, this.workerId, this.taskId)
    view.onEvent((event: TaskStreamEvent) => this.onEvent(event))
    this.view = view
    this.boundClient = client
    return view
  }

  private onEvent(event: TaskStreamEvent): void {
    // round.opened / round.closed 是信号事件,不折入 items;仅触发 rounds 快照刷新(重新 task.rounds + 幂等 foldRound)。
    if (event.event === 'round.opened' || event.event === 'round.closed') {
      void this.refreshRounds()
      return
    }
    // 主 agent 终态兜底:任务收口后再拉一次 rounds 快照,保证 durationMs/闭合行等最终落盘
    // 数据在前端最终一致——round.closed 触发的拉取可能早于耗时写入(旁路/兜底回填场景),
    // 终态是天然的最终一致校准点;历史回放(initial)时 open 已建齐骨架,跳过以免重复拉取。
    if (event.event === 'agent.status' && !event.initial) {
      const agentKey = event.agentId ?? (event.payload?.agentId as string | undefined) ?? ''
      const status = String(event.payload?.status ?? '')
      if (!agentKey && (status === 'done' || status === 'failed' || status === 'stopped')) {
        void this.refreshRounds()
      }
    }
    // ask 事件单独走卡片,不进线程。
    if (event.event === 'ask.create' || event.event === 'ask.state' || event.event === 'ask.resolved') {
      this.dispatchAskEvent(event)
      this.folder.fold(event) // 非 item 事件幂等处理(ask.* 只更新 agentStates,不进线程)
      return
    }
    const changed = this.folder.fold(event)
    if (isTerminalEvent(event.event)) {
      this.folder.finalize()
      clearAsksOfTask(this.taskId)
    }
    if (changed) this.notify()
  }

  private dispatchAskEvent(event: { event: string; payload: any; initial?: boolean }): void {
    const payload = event.payload ?? {}
    const askId = String(payload.askId ?? '')
    if (!askId) return
    if (event.event === 'ask.resolved') {
      settleAsk(askId)
      return
    }
    if (event.event === 'ask.state' && payload.status !== 'pending') {
      settleAsk(askId)
      return
    }
    // 历史回放(initial=true)的 ask.create 静默注册,由 askStore debounce flush 决定是否弹出:
    // 已完成任务回放时 ask.resolved 紧随其后,flush 前 ask 被 settle → 跳过 emit,悬浮窗不闪;
    // 运行中任务刷新页面时 50ms 内无新帧 → flush 统一 emit,立即弹出弹窗/悬浮窗。
    // 实时(非 initial)ask.create 走完整弹出路径。
    upsertPendingAsk(this.taskId, payload as WorkerAskPayload, { initial: event.initial ?? false })
  }

  /**
   * rounds 快照刷新(round.opened/round.closed 信号触发):重新拉取 task.rounds + 未闭合尾轮
   * 尾部事件,全部幂等折入同一 folder;失败不阻断(记 warn)。
   */
  private async refreshRounds(): Promise<void> {
    try {
      await this.loadRoundsIntoFolder()
    } catch (error) {
      this.roundsError = error instanceof Error ? error.message : String(error)
      this.notify()
      console.warn(`[taskStream] 刷新任务轮次失败(${this.taskId}):`, error)
    }
  }

  /** rounds 快照只读访问。 */
  roundsSnapshot(): TaskRoundsResult | null {
    return this.rounds
  }

  /** rounds 最近一次错误。 */
  roundsErrorText(): string | null {
    return this.roundsError
  }

  /** 重新拉取轮次索引(重试/校准)。 */
  async reloadRounds(): Promise<void> {
    await this.refreshRounds()
  }

  /**
   * 懒加载·前向续拉一轮过程:等 open 完成后,<b>先查 items 缓存</b>(该轮区间已由真实事件
   * 完整覆盖则 fromCache 返回,不 RPC;部分覆盖则提升 afterSeq 从缺口续拉),再经
   * view.loadForwardPage 单页区间拉取折入。返回 {lastSeq, hasMore, fromCache}。
   */
  async loadRoundForward(page: {
    startSeq: number | string
    endSeq?: number | string | null
    afterSeq: number | string
    limit?: number
  }): Promise<{ lastSeq: number | string; hasMore: boolean; fromCache: boolean }> {
    if (this.opening) await this.opening
    const view = this.view
    if (!view) return { lastSeq: page.afterSeq, hasMore: false, fromCache: false }
    const startKey = String(page.startSeq)
    const endKey = page.endSeq == null || String(page.endSeq) === '' ? null : String(page.endSeq)
    // ① 查缓存:该轮 (startSeq, endSeq] 已折入的最大真实 seq。
    const maxReal = this.folder.roundRealFloor(startKey, endKey)
    let afterSeq = page.afterSeq
    if (maxReal != null) {
      // 完整覆盖:权威最终回复已在 items(真实) → 整轮已拉完,不 RPC。
      if (endKey != null && cmpSeq(maxReal, endKey) >= 0) {
        return { lastSeq: maxReal, hasMore: false, fromCache: true }
      }
      // 部分覆盖:把 afterSeq 提升到已覆盖点,从缺口续拉(不整页重拉)。
      if (cmpSeq(maxReal, afterSeq) > 0) {
        afterSeq = maxReal
      }
    }
    const { events, lastSeq, hasMore } = await view.loadForwardPage({ ...page, afterSeq })
    for (const item of events) {
      this.foldWireEvent(item)
    }
    this.notify()
    return { lastSeq, hasMore, fromCache: false }
  }

  /**
   * 懒加载·后向续拉(运行中尾轮往上翻历史):<b>先查 items 缓存</b>([startSeq, beforeSeq) 已连到
   * 轮起点则 fromCache+reachedStart 返回;有更早真实数据则把 beforeSeq 提升到最小已覆盖点
   * 从缺口继续向前),再经 view.loadBackwardPage 单页拉取折入。返回 {firstSeq, reachedStart, fromCache}。
   */
  async loadRoundBackward(page: {
    startSeq: number | string
    beforeSeq: number | string
    limit?: number
  }): Promise<{ firstSeq: number | string; reachedStart: boolean; fromCache: boolean }> {
    if (this.opening) await this.opening
    const view = this.view
    if (!view) return { firstSeq: page.beforeSeq, reachedStart: false, fromCache: false }
    const startKey = String(page.startSeq)
    // ① 查缓存:[startSeq, beforeSeq) 内已折入的最小真实 seq。
    const minReal = this.folder.roundRealCeiling(startKey, String(page.beforeSeq))
    let beforeSeq = page.beforeSeq
    if (minReal != null) {
      // 已连到轮起点:无需再往前拉。
      if (cmpSeq(minReal, startKey) <= 0) {
        return { firstSeq: minReal, reachedStart: true, fromCache: true }
      }
      // items 已有更早真实数据:把 beforeSeq 提升到最小已覆盖点,从缺口继续向前。
      if (cmpSeq(minReal, beforeSeq) < 0) {
        beforeSeq = minReal
      }
    }
    const { events, firstSeq, reachedStart } = await view.loadBackwardPage({ startSeq: page.startSeq, beforeSeq, limit: page.limit })
    for (const item of events) {
      this.foldWireEvent(item)
    }
    this.notify()
    return { firstSeq, reachedStart, fromCache: false }
  }

  /**
   * 建齐「单一线程真相源」骨架:task.rounds 全量轮次(user 真实 userMessage + 闭合轮合成
   * final)折入 items,过程内容一律由懒加载按需拉取(不再一次性全量)。运行中任务额外拉
   * 未闭合尾轮「最后一页」(task.roundTail limit=200)与流式增量接上;终态未闭合尾轮不在此
   * 拉,由 TaskRoundsPanel 常开视图懒加载。完成后 50ms 合并通知一次。
   */
  private async loadRoundsIntoFolder(): Promise<void> {
    const client = hubSession.workerClient(this.workerId)
    if (!client) return
    const res = await fetchTaskRounds(client, this.workerId, { taskId: this.taskId })
    this.rounds = res
    this.roundsError = null
    // 全部轮折入骨架:user(foldRound 用 round.userMessage)+ 闭合轮合成 final(文本摘要);
    // 后续懒加载把过程事件 / 权威 message 折入同一 items,按 seq 去重且不重复 user/final 项。
    for (const round of res.rounds) {
      this.folder.foldRound(round)
    }
    const lastRound: RoundSummary | undefined = res.rounds[res.rounds.length - 1]
    const tailStartSeq = lastRound?.endSeq === ''
      ? lastRound.startSeq
      : (res.open?.startSeq ?? null)
    // 运行中 open 轮不在 rounds.jsonl 行时(旧任务首次 task.rounds 惰性重建会跳过运行中未闭合轮,
    // 只落盘已闭合轮)→ rounds[] 无该轮 user 骨架,用 res.open 的 userMessage/user 兜底补一条,
    // 保证运行中尾轮 user 气泡恒在(方案 5.7「或 res.open 的 userMessage」)。
    if (tailStartSeq && res.live && res.open) {
      const hasUserItem = this.folder.state.items.some(
        (it) => it.type === 'agent_message' && it.message.messageId === `m-${tailStartSeq}`,
      )
      if (!hasUserItem) {
        this.folder.foldRound({
          roundId: 'open',
          index: 0,
          startSeq: tailStartSeq,
          endSeq: '',
          user: res.open.user,
          finalReply: '',
          subs: [],
          userMessage: res.open.userMessage,
        })
      }
    }
    if (tailStartSeq && res.live) {
      try {
        const { events } = await fetchTaskRoundTail(client, this.workerId, {
          taskId: this.taskId,
          startSeq: tailStartSeq,
          limit: 200,
        })
        for (const item of events) {
          this.foldWireEvent(item)
        }
      } catch (error) {
        console.warn(`[taskStream] 拉取未闭合尾轮尾部事件失败(${this.taskId}):`, error)
      }
    } else if (!res.live && lastRound?.startSeq) {
      // 终态任务:hidden 期间 ask_user 超时的 ask.resolved 可能未被推送(DataPusher 已停),
      // 拉最后一轮尾部事件补分发到 askStore,确保弹窗被 settle 关闭。
      try {
        const { events } = await fetchTaskRoundTail(client, this.workerId, {
          taskId: this.taskId,
          startSeq: lastRound.startSeq,
          limit: 200,
        })
        for (const item of events) {
          this.foldWireEvent(item)
        }
      } catch (error) {
        console.warn(`[taskStream] 拉取终态任务尾轮尾部事件失败(${this.taskId}):`, error)
      }
    }
    this.notify()
  }

  /**
   * task.agents 拉子 agent 台账建 agentMeta(胶囊列表/悬停卡片数据源):loadRoundsIntoFolder
   * 之后顺带拉一次,seedAgents 幂等(字段级合并,实时事件后到可覆盖);失败 warn 不阻断。
   * agentsSeeded 节流:同一 open 周期内只拉一次。
   */
  private async loadAgentsIntoFolder(): Promise<void> {
    if (this.agentsSeeded) return
    this.agentsSeeded = true
    const client = hubSession.workerClient(this.workerId)
    if (!client) return
    try {
      const res = await fetchTaskAgents(client, this.workerId, { taskId: this.taskId })
      if (this.folder.seedAgents(res.agents, res.mainAgentId)) this.notify()
    } catch (error) {
      console.warn(`[taskStream] 拉取子 agent 台账失败(${this.taskId}):`, error)
    }
  }

  /**
   * wire 事件 → 折叠器事件(seq/ts/event/agentId/payload,口径与推送/轮询路径一致)。
   * 转为 FoldableTaskEvent(不含 initial,折叠器不区分来源)。
   */
  private toFoldableEvent(item: TaskPollWireEvent): FoldableTaskEvent {
    return {
      seq: item.seq,
      ts: item.ts ?? Date.now(),
      event: item.event,
      agentId: item.agentId ?? (item.payload?.agentId as string | undefined) ?? null,
      payload: item.payload ?? {},
    }
  }

  /**
   * 折叠一条 wire 事件(历史/懒加载路径):
   * 与实时推送 {@link #onEvent} 同口径处理——ask 事件分发到 askStore(卡片生命周期)、
   * 终态事件清残留 ask;否则 hidden 期间错过的 ask.resolved(如 timeout)不会被 settle,
   * 弹窗残留不关闭。标 initial=true:ask.create 静默注册(防已完成任务回放闪烁),
   * agent.status 终态兜底跳过(历史加载已含 rounds)。
   */
  private foldWireEvent(item: TaskPollWireEvent): void {
    const event = this.toFoldableEvent(item)
    if (event.event === 'ask.create' || event.event === 'ask.state' || event.event === 'ask.resolved') {
      this.dispatchAskEvent({ ...event, initial: true })
    }
    const changed = this.folder.fold(event)
    if (isTerminalEvent(event.event)) {
      this.folder.finalize()
      clearAsksOfTask(this.taskId)
    }
    if (changed) this.notify()
  }

  async open(): Promise<void> {
    if (this.opening) return this.opening
    const opening = (async () => {
      // open 周期复位:重连 resync 再次 open 时允许重拉 task.agents 校准台账。
      this.agentsSeeded = false
      if (!this.workerId) {
        // 分页窗口外的老任务 / 重连后仍开的旧标签:镜像缺失时定向补齐归属 worker。
        const entry = await taskStore.ensureLoaded(this.taskId)
        if (!entry?.workerId) {
          throw new Error('无法确定任务所属 worker(任务数据不可用)')
        }
        this.workerId = entry.workerId
      }
      if (!hubSession.connected) return
      // 订阅推送与 rounds 快照建齐都是「尽力而为」:失败记 warn,不阻断打开。
      try {
        await this.ensureView().open()
      } catch (error) {
        console.warn(`[taskStream] 订阅任务流失败(${this.taskId}):`, error)
      }
      try {
        await this.loadRoundsIntoFolder()
      } catch (error) {
        this.roundsError = error instanceof Error ? error.message : String(error)
        this.notify()
        console.warn(`[taskStream] 加载任务轮次失败(${this.taskId}):`, error)
      }
      // task.agents 拉子 agent 台账建 agentMeta(胶囊列表/悬停卡片数据源);失败 warn 不阻断。
      await this.loadAgentsIntoFolder()
    })()
    this.opening = opening
    try {
      await opening
    } finally {
      if (this.opening === opening) this.opening = null
    }
  }

  /** 关闭流:停轮询定时器、清缓冲、释放句柄。 */
  close(): void {
    this.view?.close()
    this.view = null
    this.boundClient = null
    if (this.notifyTimer) {
      clearTimeout(this.notifyTimer)
      this.notifyTimer = null
    }
  }
}

function isTerminalEvent(eventName: string): boolean {
  return eventName === 'error' || eventName === 'cancelled'
}


class TaskStreamManager {
  private streams = new Map<string, ManagedStream>()
  private reconnectWired = false

  constructor() {
    registerAskReplySender((taskId, askId, answer) => {
      const stream = this.streams.get(taskId)
      if (stream?.view) {
        stream.view.replyAsk(askId, answer)
      } else {
        console.warn(`[taskStream] ask 回复失败:任务 ${taskId} 无活跃流`)
      }
    })
  }

  get(taskId: string): TaskStreamHandle {
    let stream = this.streams.get(taskId)
    if (!stream) {
      // workerId 可暂缺:open() 首步经 taskStore.ensureLoaded 定向补齐后再订阅。
      stream = new ManagedStream(taskId, taskStore.get(taskId)?.workerId ?? '')
      this.streams.set(taskId, stream)
      this.wireReconnect()
      void stream.open().catch((error) => {
        console.warn(`[taskStream] 打开任务流失败(${taskId}):`, error)
      })
    }
    return this.toHandle(stream)
  }

  /** 只读窥探已打开流的折叠状态(不新建流、不发起订阅);任务列表电池等轻量消费。 */
  peekState(taskId: string): TaskThreadState | null {
    return this.streams.get(taskId)?.folder.state ?? null
  }

  private toHandle(stream: ManagedStream): TaskStreamHandle {
    const self = this
    return {
      taskId: stream.taskId,
      get workerId() { return stream.workerId },
      get state() { return stream.folder.state },
      subscribe: (fn) => {
        stream.listeners.add(fn)
        return () => stream.listeners.delete(fn)
      },
      sendInput: (text, rawContent) => {
        stream.ensureView().sendInput(text, rawContent)
      },
      cancel: () => stream.ensureView().cancel(),
      resync: () => stream.open(),
      resolveAgentTitle: (agentId) => stream.folder.resolveAgentTitle(agentId),
      hasStreaming: () => stream.folder.hasStreaming(),
      rounds: () => stream.roundsSnapshot(),
      roundsError: () => stream.roundsErrorText(),
      reloadRounds: () => stream.reloadRounds(),
      loadRoundForward: (page) => stream.loadRoundForward(page),
      loadRoundBackward: (page) => stream.loadRoundBackward(page),
      close: () => {
        stream.close()
        self.streams.delete(stream.taskId)
      },
    }
  }

  /**
   * 关闭任务标签页时调用(由 Layout 在关闭 task:* 标签时触发):
   * 停轮询定时器、清空缓冲并释放句柄资源。
   */
  close(taskId: string): void {
    const stream = this.streams.get(taskId)
    if (!stream) return
    stream.close()
    this.streams.delete(taskId)
  }

  private wireReconnect(): void {
    if (this.reconnectWired) return
    this.reconnectWired = true
    hubSession.onReconnect(() => {
      // 重连后实例不变,view 监听器仍有效,desiredSubs 已重发;此处只重拉 rounds+尾段
      // 校准断连期间错过的数据(open 幂等:ensureView 复用既有 view,TaskPacketView.open
      // 的 wired 守卫防重复订阅);仅致命错误替换实例时 ensureView 会因
      // boundClient !== client 自动重建 view。
      for (const stream of this.streams.values()) {
        void stream.open().catch((error) => {
          console.warn(`[taskStream] 重连校准失败(${stream.taskId}):`, error)
        })
      }
    })
  }
}

export const taskStreamManager = new TaskStreamManager()

