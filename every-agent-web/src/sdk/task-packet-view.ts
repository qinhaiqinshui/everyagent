/**
 * TaskPacketView(架构 §6.2 数据包模式):单个任务的「数据包数组」视图。
 * 主线路由下,打开任务时的初始数据由上层负责加载(task.rounds 拉轮次索引 +
 * 未闭合末轮 task.roundTail 拉最后 50 条),此后仅靠 worker 定向推送(DataPusher,
 * 经 stream 频道)持续收流式数据;本视图不再自行发起任何 task.poll 首拉/轮询。
 *
 * 数据通路:
 * - open():仅订阅任务 stream 频道(hub 发 subscriber.join → worker 为该前端会话建定向
 *   推送器 DataPusher:回扫内存日志尾段 + 挂监听实时推,ext={target,operate,initial});
 *   初始数据由上层(task.rounds + task.roundTail)负责加载,推送回扫段与首拉重叠时
 *   TaskPacketBuffer 按 seq 去重吸收,不重不漏。
 * - 推送帧(stream 频道 msg):delta/thinking 走 operate=append,其余 replace;订阅时已在
 *   内存的回扫段标 ext.initial=true(历史回放语义,askStore 静默依赖)。折进缓冲并按形状
 *   增量回调 handler(形状同 TaskStreamEvent)。
 * - resync():重连校准 = open()(hub-client 重连自动重发 sub → hub 发新 join → 新推送器,
 *   旧推送器经 hub 断线清理销毁);重建 view 后重新 open,仅重订阅。
 * - loadBefore(beforeSeq):上滚分页,向前拉取 seq < beforeSeq 的更早事件(prepend,
 *   TaskPacketBuffer 按 seq 插入已支持),通知 handler,返回 {earliestSeq} 供 UI 判断是否还有更早。
 * - fetchRoundEvents(startSeq, endSeq):按轮次 seq 区间一次性拉全一轮原始事件(plan-rounds-jsonl
 *   步骤 5,rounds 轮详情用)——task.poll 的 afterSeq+beforeSeq 开区间查询,闭合轮 [startSeq,endSeq]
 *   两端各外扩 1 恰为整轮,未闭合轮以 Long.MAX 哨兵为上界拉到当前末尾;hasMore 时以批尾 seq 推进
 *   afterSeq 分批续拉,seq 升序去重后返回 {seq,event,agentId,payload,ts} 数组供折叠器消费。
 *   无副作用原始拉取:不折入本视图缓冲、不回调 handler、不动游标。
 *
 * 游标 lastSeq 用事件自带 seq(wire 字符串,精确)经 compareSeq 取大推进——推送帧与历史拉取批次
 * 共用;rpc.ok 的 result.lastSeq 是 JSON 数值(雪花 ID 超 2^53 丢精度),不作游标。
 */
import { channels } from './channels'
import { HubClient } from './hub-client'
import { STREAM_ACK, type MsgFrame } from './frames'
import { TaskPacketBuffer, compareSeq, type PacketFrame } from './task-packet-buffer'
import type { TaskPollEvent, TaskPollParams, TaskPollResult, TaskPollWireEvent } from './task-poll'

/** 视图投递给调用方的事件(seq 为 Snowflake ID,可为 number 或 string)。 */
export interface TaskStreamEvent {
  seq: number | string
  ts: number
  event: string
  agentId?: string | null
  /** 历史回放帧(上滚 / 重校准 / 推送回扫段 initial=true;askStore silent 依赖)。 */
  initial?: boolean
  payload: any
}

/** 历史拉取(loadBefore / fetchRoundEvents)单批条数上限。 */
const POLL_PAGE_LIMIT = 200
/** 前端回 stream.ack 的批量合并窗口(ms):高频帧按拍回报最新 creditIndex,减少上行。 */
const ACK_BATCH_MS = 100
/**
 * 轮次区间拉取(fetchRoundEvents)安全页数上限:worker 区间查询磁盘侧扫描上限 500k ÷ 单页 200,
 * 达上限即止并告警(防御性防自旋;正常一轮远小于此)。
 */
const ROUND_RANGE_MAX_PAGES = 2500
/**
 * 未闭合轮区间拉取的上界哨兵(Long.MAX_VALUE 字符串):wire 区间分支按 seq < beforeSeq 过滤,
 * 恒过——使未闭合轮也走「升序头部窗口」的区间分页(仅 afterSeq 的增量语义是尾部窗口,分页会漏中段)。
 */
const ROUND_RANGE_OPEN_BEFORE_SEQ = '9223372036854775807'

/** seq(字符串/数字)安全转 BigInt(雪花 ID 超 2^53 不能走 Number);空/非数值/负数返回 null。 */
function tryParseSeq(seq: number | string | null | undefined): bigint | null {
  if (seq == null) return null
  try {
    const v = BigInt(String(seq).trim())
    return v > 0n ? v : null
  } catch {
    return null
  }
}

/** 按 seq 去重(保持首次出现顺序;worker hasMore 为粗判,可能多报边界重叠)。 */
function dedupeBySeq(items: TaskPollWireEvent[]): TaskPollWireEvent[] {
  const seen = new Set<string>()
  const out: TaskPollWireEvent[] = []
  for (const item of items) {
    const key = String(item.seq)
    if (!seen.has(key)) {
      seen.add(key)
      out.push(item)
    }
  }
  return out
}

export class TaskPacketView {
  private buffer = new TaskPacketBuffer()
  private handler: ((e: TaskStreamEvent) => void) | null = null
  private k: string
  /** 任务 stream 频道(订阅 worker 定向推送)。 */
  private readonly streamCh: string
  /** 已挂推送消费(sub + 消息监听)。 */
  private wired = false
  /** 推送裸帧回调(close 时摘除,防残留重复消费)。 */
  private onRawFrame: ((frame: MsgFrame) => void) | null = null
  /** 拉取游标:已见到的最新 seq(推送帧与历史拉取批次共用 compareSeq 取大;空任务 null)。 */
  private lastSeq: number | string | null = null
  /**
   * 生成号:open/resync/close 时自增,使在途 poll 的 rpc.data / 结果失效
   * (防止重连中旧响应与新一轮交织)。
   */
  private generation = 0
  private closed = true
  /** 批量 ack:待回报的最新 creditIndex(合并窗口内取大)。 */
  private pendingAckIndex: number | null = null
  private ackTimer: ReturnType<typeof setTimeout> | null = null

  constructor(
    private readonly client: HubClient,
    readonly workerId: string,
    readonly taskId: string,
  ) {
    this.k = client.k
    this.streamCh = channels.taskStream(this.k, taskId)
  }

  onEvent(cb: (e: TaskStreamEvent) => void): void {
    this.handler = cb
  }

  /**
   * 打开任务流:仅订阅 stream 频道(hub 发 subscriber.join → worker 建定向推送器,
   * 回扫内存日志尾段 + 实时推)收流式数据;初始数据加载(task.rounds + task.roundTail)
   * 由上层负责,本视图不发起任何 task.poll。
   * 可重复调用(重连/校准后重建 view 重新 open;close() 后再 open 重新订阅从头收)。
   */
  async open(): Promise<void> {
    this.closed = false
    this.generation++
    this.subscribeStream()
  }

  /** 关闭任务流:退订阅/摘监听、清缓冲、释放游标。open() 可再拉起。 */
  close(): void {
    this.closed = true
    this.generation++
    this.teardownStream()
    this.buffer.clear()
    this.lastSeq = null
    if (this.ackTimer) {
      clearTimeout(this.ackTimer)
      this.ackTimer = null
    }
    this.pendingAckIndex = null
  }

  /**
   * 上滚分页(loadBefore):向前拉取 seq < beforeSeq 的更早事件(prepend 进缓冲,按 seq 幂等去重),
   * 事件标记 initial=true(历史回放)。返回 {earliestSeq}(该批最早事件 seq)供 UI 判断是否还有更早。
   * 注意用 mode:'events'(默认)而非 rounds:worker 的 rounds 分支只按 count 从尾部定位轮次,
   * 会忽略 beforeSeq 导致上滚永远落到尾段;events+beforeSeq 才是向后翻页的精确窗口。
   * limit 可选(缺省 POLL_PAGE_LIMIT),透传给 task.poll 的单批条数上限。
   */
  async loadBefore(beforeSeq: number | string, limit?: number): Promise<{ earliestSeq: number | string | null }> {
    const res = await this.pollOnce(
      { beforeSeq, limit: limit && limit > 0 ? limit : POLL_PAGE_LIMIT },
      { initial: true },
    )
    if (res == null) return { earliestSeq: null }
    return { earliestSeq: res.result.firstSeq ?? null }
  }

  /**
   * 懒加载·单页前向拉取(轮过程内容):task.poll 区间查询 (afterSeq, beforeSeq) 开区间、
   * 升序取头部 limit 条。首拉调用方传 afterSeq=startSeq-1;续拉传上一页批尾 seq。
   * endSeq 非空(闭合轮)→ beforeSeq=endSeq+1 恰含权威最终回复;endSeq 为空(未闭合)→
   * Long.MAX 哨兵取到当前末尾。无副作用:不折入本视图缓冲、不回调 handler、不动游标。
   * 返回去重后的升序事件、批尾 seq(续拉游标)与 hasMore(worker 粗判)。
   */
  async loadForwardPage(opts: {
    startSeq: number | string
    endSeq?: number | string | null
    afterSeq: number | string
    limit?: number
  }): Promise<{ events: TaskPollWireEvent[]; lastSeq: number | string; hasMore: boolean }> {
    const after = tryParseSeq(opts.afterSeq)
    const afterCursor = after != null && after > 0n ? String(after) : '1'
    const end = tryParseSeq(opts.endSeq)
    const beforeCursor = end != null ? String(end + 1n) : ROUND_RANGE_OPEN_BEFORE_SEQ
    const limit = opts.limit && opts.limit > 0 ? opts.limit : POLL_PAGE_LIMIT
    const batch: TaskPollWireEvent[] = []
    const result = (await this.client.rpc(this.workerId, 'task.poll', {
      taskId: this.taskId,
      afterSeq: afterCursor,
      beforeSeq: beforeCursor,
      limit,
    }, {
      onData: (items: TaskPollWireEvent[]) => {
        batch.push(...items)
      },
    })) as TaskPollResult
    const events = dedupeBySeq(batch)
    const lastSeq = events.length > 0 ? events[events.length - 1].seq : opts.afterSeq
    return { events, lastSeq, hasMore: result.hasMore && events.length > 0 }
  }

  /**
   * 懒加载·单页后向拉取(运行中尾轮往上翻历史):task.poll beforeSeq 向前翻页,返回
   * seq < beforeSeq 的「最近 limit 条」(升序)。reachedStart = 本页已含 seq <= startSeq
   * 的起点 user.message(往后拉到轮起点即可停)。无副作用,同 loadForwardPage。
   */
  async loadBackwardPage(opts: {
    startSeq: number | string
    beforeSeq: number | string
    limit?: number
  }): Promise<{ events: TaskPollWireEvent[]; firstSeq: number | string; reachedStart: boolean }> {
    const limit = opts.limit && opts.limit > 0 ? opts.limit : POLL_PAGE_LIMIT
    const batch: TaskPollWireEvent[] = []
    await this.client.rpc(this.workerId, 'task.poll', {
      taskId: this.taskId,
      beforeSeq: opts.beforeSeq,
      limit,
    }, {
      onData: (items: TaskPollWireEvent[]) => {
        batch.push(...items)
      },
    })
    const events = dedupeBySeq(batch)
    const firstSeq = events.length > 0 ? events[0].seq : opts.beforeSeq
    const reachedStart = events.some((e) => compareSeq(e.seq, opts.startSeq) <= 0)
    return { events, firstSeq, reachedStart }
  }

  /**
   * 按区间拉取一轮完整事件(plan-rounds-jsonl 步骤 5,rounds 轮详情用):
   * 基于 task.poll 的 afterSeq+beforeSeq 开区间查询((afterSeq, beforeSeq) 两端不含),
   * 闭合轮闭区间 [startSeq, endSeq] 两端各外扩 1(雪花 ID 远离 0/上限,±1 安全)后恰好取整轮
   * (含轮起点的 user.message、轮终点的最终回复与全部子 Agent 事件);
   * endSeq 为空串/null/0(未闭合轮)→ 以 Long.MAX 哨兵为上界拉到当前末尾,之后的新事件由调用方走实时增量。
   * 内部处理 hasMore 分批续拉:以批尾 wire 事件的 seq(字符串)推进 afterSeq——result.lastSeq 是
   * JSON 数值,雪花 ID 超 2^53 会丢精度,不能用;单页 limit 沿用历史拉取页上限(POLL_PAGE_LIMIT)。
   * 跨页按 seq 去重(worker hasMore 为粗判,可能多报一页空批;空批即取完,防自旋)。
   * 无副作用原始拉取:不折入本视图缓冲、不回调 handler、不动游标;事件按 seq 升序返回
   * ({seq,event,agentId,payload,ts} wire 形态)供轮详情折叠器消费。
   * 任务不存在 → RpcError(NOT_FOUND);传输错误原样抛出。
   */
  async fetchRoundEvents(
    startSeq: number | string,
    endSeq?: number | string | null,
  ): Promise<TaskPollWireEvent[]> {
    const start = tryParseSeq(startSeq)
    const end = tryParseSeq(endSeq)
    // 闭合轮两端外扩 1;start 非法(防御,正常恒为合法雪花字串)钳到 1 保证走区间分支(afterSeq>0)
    let afterCursor = start != null ? String(start - 1n) : '1'
    const beforeCursor = end != null ? String(end + 1n) : ROUND_RANGE_OPEN_BEFORE_SEQ
    const bySeq = new Map<string, TaskPollWireEvent>()
    let page = 0
    for (; page < ROUND_RANGE_MAX_PAGES; page++) {
      const batch: TaskPollWireEvent[] = []
      const result = (await this.client.rpc(this.workerId, 'task.poll', {
        taskId: this.taskId,
        afterSeq: afterCursor,
        beforeSeq: beforeCursor,
        limit: POLL_PAGE_LIMIT,
      }, {
        onData: (items: TaskPollWireEvent[]) => {
          batch.push(...items)
        },
      })) as TaskPollResult
      for (const item of batch) {
        const key = String(item.seq)
        if (!bySeq.has(key)) bySeq.set(key, item) // 跨页按 seq 去重,保持升序
      }
      // hasMore 为粗判可能多报:空批即取完,防自旋
      if (!result.hasMore || batch.length === 0) break
      // hasMore=true 且本页非空 → 以批尾事件 seq(字符串)推进游标续拉
      afterCursor = String(batch[batch.length - 1].seq)
    }
    if (page >= ROUND_RANGE_MAX_PAGES) {
      console.warn(
        `[taskStream] 轮次区间拉取达页数上限(${this.taskId}):seq>${afterCursor} 之后的事件可能缺失`,
      )
    }
    return Array.from(bySeq.values())
  }

  /**
   * 按轮次 ID 拉取一轮完整事件(rounds.jsonl roundId 分支):
   * 基于 task.poll 的 taskId+roundId+limit(events 模式)查询,worker roundId 分支已按轮
   * 返回该轮全部事件(含轮起点 user.message 与轮终点最终回复 message,seq 升序、hasMore
   * 分批),不再需要旧 seq 区间的 ±1 端点外扩哨兵;未闭合轮同样按 roundId 取到当前末尾。
   * 内部处理 hasMore 分批续拉:以批尾 wire 事件的 seq(字符串)推进 afterSeq——result.lastSeq
   * 是 JSON 数值,雪花 ID 超 2^53 会丢精度,不能用;单页 limit 沿用历史拉取页上限
   * (POLL_PAGE_LIMIT)。跨页按 seq 去重(worker hasMore 为粗判,可能多报一页空批;空批即取完,
   * 防自旋)。无副作用原始拉取:不折入本视图缓冲、不回调 handler、不动游标;事件按 seq 升序返回
   * ({seq,event,agentId,payload,ts} wire 形态)供轮详情折叠器消费。
   * 任务或轮次不存在 → RpcError(NOT_FOUND);传输错误原样抛出。
   */
  async fetchRoundEventsByRoundId(roundId: string): Promise<TaskPollWireEvent[]> {
    const bySeq = new Map<string, TaskPollWireEvent>()
    let afterCursor: string | null = null
    let page = 0
    for (; page < ROUND_RANGE_MAX_PAGES; page++) {
      const batch: TaskPollWireEvent[] = []
      const result = (await this.client.rpc(this.workerId, 'task.poll', {
        taskId: this.taskId,
        roundId,
        ...(afterCursor != null ? { afterSeq: afterCursor } : {}),
        limit: POLL_PAGE_LIMIT,
      }, {
        onData: (items: TaskPollWireEvent[]) => {
          batch.push(...items)
        },
      })) as TaskPollResult
      for (const item of batch) {
        const key = String(item.seq)
        if (!bySeq.has(key)) bySeq.set(key, item) // 跨页按 seq 去重,保持升序
      }
      // hasMore 为粗判可能多报:空批即取完,防自旋
      if (!result.hasMore || batch.length === 0) break
      // hasMore=true 且本页非空 → 以批尾事件 seq(字符串)推进游标续拉
      afterCursor = String(batch[batch.length - 1].seq)
    }
    if (page >= ROUND_RANGE_MAX_PAGES) {
      console.warn(
        `[taskStream] 按轮次 ID 拉取达页数上限(${this.taskId}/round ${roundId}):seq>${afterCursor} 之后的事件可能缺失`,
      )
    }
    return Array.from(bySeq.values())
  }

  /** 重连校准:重开(= open();hub-client 已自动重发 sub,旧推送器经 hub 断线清理销毁)。 */
  async resync(): Promise<void> {
    await this.open()
  }

  /** 已见到的最新 seq(拉取/推送共用游标;空任务为 null)。 */
  get lastSeqValue(): number | string | null {
    return this.lastSeq
  }

  /** 已见到的最早 seq(缓冲数据包数组首包;供上滚分页判断是否还有更早)。 */
  get earliestSeq(): number | string | null {
    const first = this.buffer.packets[0]
    return first ? first.seq : null
  }

  /** 任务轮注入(worker 级输入频道,taskId 入 payload;终态任务 = 冷启动再运行)。 */
  sendInput(text: string, rawContent?: string): void {
    this.client.pub(channels.workerInput(this.k, this.workerId), 'task.input',
      { taskId: this.taskId, text, ...(rawContent ? { rawContent } : {}) })
  }

  /** 抢答挂起中的 ask。 */
  replyAsk(askId: string, answer: string): void {
    this.client.pub(channels.workerInput(this.k, this.workerId), 'ask.reply', { askId, answer })
  }

  async cancel(): Promise<any> {
    return this.client.rpc(this.workerId, 'task.cancel', { taskId: this.taskId })
  }

  // ---- 内部:推送消费 ----

  /** 订阅 stream 频道并挂消息监听(幂等;先挂监听再 sub,订阅后的推送帧即被消费)。 */
  private subscribeStream(): void {
    if (this.wired || this.closed) return
    this.wired = true
    this.onRawFrame = (frame) => this.consumePushedFrame(frame)
    this.client.addMessageListener(this.onRawFrame)
    this.client.sub(this.streamCh)
  }

  /** 退订阅 stream 频道并摘消息监听(close 用;防残留监听重复消费)。 */
  private teardownStream(): void {
    if (this.onRawFrame) {
      this.client.removeMessageListener(this.onRawFrame)
      this.onRawFrame = null
    }
    if (this.wired) {
      this.client.unsub(this.streamCh)
      this.wired = false
    }
  }

  /**
   * 消费一条推送帧(stream 频道 msg):转 PacketFrame 折进缓冲、按形状增量回调 handler、
   * 推进游标。ext.operate 为 worker 推送器的 append/replace 语义,ext.initial=true 为
   * 订阅时已在内存的回放段(askStore 静默依赖)。
   */
  private consumePushedFrame(frame: MsgFrame): void {
    if (this.closed || !this.wired) return
    if (frame.channel !== this.streamCh || frame.seq == null) return
    const ext = (frame.ext ?? {}) as Record<string, unknown>
    const payload = (frame.payload ?? {}) as Record<string, unknown>
    const initial = ext.initial === true
    const pf: PacketFrame = {
      seq: frame.seq,
      ts: frame.ts ?? Date.now(),
      event: frame.event,
      // 子 agent 事件 worker 把 agentId 注入 payload.agentId(wireEvent 口径)
      agentId: (payload.agentId as string | null | undefined) ?? null,
      operate: (ext.operate as string | undefined) ?? frame.operate,
      initial,
      payload,
    }
    this.advanceLastSeq(frame.seq)
    this.emitFrame(pf, initial)
    // 背压回报:缓冲消费后即回 ack(不等待 React 渲染),批量合并 100ms。
    if (ext.credit === true && typeof ext.creditIndex === 'number') {
      this.scheduleAck(ext.creditIndex)
    }
  }

  // ---- 内部:拉取 ----

  /**
   * 批量回 ack:合并窗口内只保留最新 creditIndex,到点后经 worker 输入频道发 stream.ack。
   * 老前端不识别 ext.credit 则不会走到这里,worker 端走 ACK_TIMEOUT_MS 超时降级,向后兼容。
   */
  private scheduleAck(creditIndex: number): void {
    this.pendingAckIndex = this.pendingAckIndex == null
      ? creditIndex
      : Math.max(this.pendingAckIndex, creditIndex)
    if (this.ackTimer) return
    this.ackTimer = setTimeout(() => {
      this.ackTimer = null
      const idx = this.pendingAckIndex
      this.pendingAckIndex = null
      if (idx == null) return
      this.client.pub(
        channels.workerInput(this.k, this.workerId),
        STREAM_ACK,
        { taskId: this.taskId, creditIndex: idx },
      )
    }, ACK_BATCH_MS)
  }

  /**
   * 发一次 task.poll:rpc.data 批中的事件逐条折叠进缓冲并回调 handler;
   * rpc.ok 的 result(TaskPollResult)为返回值。返回 null 表示本次 poll 已被
   * close()/新一轮 open()/resync() 取代(事件已丢弃,不折叠不回调)。
   * opts.advance:推进 lastSeq 游标(历史拉取向前语境;loadBefore 向后翻页不推进)。
   */
  private async pollOnce(
    params: Omit<TaskPollParams, 'taskId'>,
    opts: { initial: boolean; advance?: boolean },
  ): Promise<{ result: TaskPollResult; gainedAny: boolean } | null> {
    const gen = this.generation
    let gainedAny = false
    const result = (await this.client.rpc(this.workerId, 'task.poll', {
      taskId: this.taskId,
      ...params,
    }, {
      onData: (batch: any[]) => {
        if (gen !== this.generation) return // 过时响应:整批丢弃
        for (const item of batch) {
          if (this.closed || gen !== this.generation) break
          if (this.consumeEvent(item, opts.initial)) gainedAny = true
          if (opts.advance && item.seq != null) this.advanceLastSeq(item.seq)
        }
      },
    })) as TaskPollResult
    if (gen !== this.generation) return null
    return { result, gainedAny }
  }

  /** 推进拉取游标(compareSeq 取大;推送帧与历史拉取批次共用,loadBefore 不走此路径)。 */
  private advanceLastSeq(seq: number | string): void {
    if (this.lastSeq == null || compareSeq(seq, this.lastSeq) > 0) {
      this.lastSeq = seq
    }
  }

  /**
   * 消费一条轮询事件:转成 PacketFrame 折进缓冲,并按形状增量回调 handler。
   * 返回是否产生内容(用于日志/调试)。
   */
  private consumeEvent(item: TaskPollEvent, initial: boolean): boolean {
    const payload = (item.payload ?? {}) as Record<string, unknown>
    const frame: PacketFrame = {
      seq: item.seq,
      ts: (item as TaskPollEvent & { ts?: number }).ts ?? Date.now(),
      event: item.event,
      // 子 agent 事件 worker 把 agentId 注入 payload.agentId;顶层字段为类型兼容占位。
      agentId: item.agentId ?? (payload.agentId as string | null | undefined) ?? null,
      // 流式 append 语义:轮询事件若携带 operate(append/replace)则透传,缺省 worker 按 replace。
      operate: (item as TaskPollEvent & { operate?: string }).operate,
      payload,
    }
    return this.emitFrame(frame, initial)
  }

  private emitFrame(frame: PacketFrame, initial: boolean): boolean {
    const update = this.buffer.apply(frame)
    if (!update.changed) return false
    const { packet, appended } = update
    const ts = frame.ts ?? Date.now()
    if (!this.handler) return true
    if (appended) {
      // append 帧:按追加字段映射为 thinking/delta 增量事件(文本已入包,此处只给片段)
      const eventName = appended.field === 'thinking' ? 'thinking' : 'delta'
      this.handler({
        seq: packet.seq,
        ts,
        event: eventName,
        agentId: packet.agentId,
        initial,
        payload: { text: appended.text },
      })
      return true
    }
    // replace/新建:整包事件(thinking/delta 已定型,message 为权威整轮等)
    this.handler({
      seq: packet.seq,
      ts,
      event: packet.event,
      agentId: packet.agentId,
      initial,
      payload: packet.payload,
    })
    return true
  }
}
