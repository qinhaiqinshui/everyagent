/**
 * 事件折叠器(纯函数状态机):worker 任务流事件 → n 前端线程项。
 *
 * n 的持久化层(agentMessageRepository/taskTraceRepository)在浏览器里逐条写入;
 * 本前端没有本地持久化,改为把 worker 的数据包事件流「折叠」成同样的
 * AgentMessageRecord / TaskTraceRecord 形状,让 AgentMessageThread /
 * TraceItem 等组件零改动复用。
 *
 * agent 台账镜像(agentStates/agentMeta)不属于线程折叠:独立 store agentLedger.ts
 * (本类以组合持有 ledger 实例,台账类事件 handler 直接改写,reset 不清台账)。
 *
 * 折叠规则(与 n 的写入语义对齐):
 * 事件名不分主/子 agent——同一名事件,归属由 agentId 字段决定(空 = 主线程);
 * - user.message           → role:'user' 消息(payload.content + payload.data.rawContent);
 *                           主/子 agent 共用同一事件名,均以消息插入、无折叠标记
 *                           (折叠窗口语义归 rounds.jsonl 唯一权威);渲染层按 agentId 区分样式与扩展点
 * - thinking / delta       → 当前流式 assistant 消息的 reasoning / content 追加(瞬态,payload.content)
 * - message                → 一轮权威终结:完整 content/data.thinking/data.toolCalls(真实 toolCall id)
 *                           定稿该 agent 的流式消息
 * - usage                  → 每 agent 最近一轮占用/累计用量/上下文快照维护台账镜像
 *                           ledger.agentMeta(不进线程);任务级上下文电池不在流内维护——
 *                           统一消费 taskStore 的 TaskSummary.usage(worker usage 投影器聚合同步,task.updated 携带)
 * - tool.result            → role:'tool' 消息(TaskChat 按 callId 合并进下发块渲染)
 * - agent.started/done     → 子 agent trace(spawn 生命周期,必带 agentId);同时合并台账镜像
 *                           ledger(标题/创建时间/收口累计用量/状态)
 * - error                  → trace(agentId = 事件真实归属;主/子标题按 isMain(agentKey === mainAgentId) 判定)
 * - cancelled              → trace
 * - retry / context.compression / model.failover / model.switch / auth.review / system.notice
 *                           → 统一纯显示 trace:按 seq 原地 upsert(有则覆盖内容、无则新增),
 *                           见 {@link #upsertTraceBySeq}
 * - ask.*                  → 不进线程(卡片由 askStore 单独驱动)
 *
 * 数据包协议:一轮 AI 回复 = 1 个 seq,同轮 thinking/delta/message 同 seq 连续到达(占同一位置),
 * 其余每个事件各占独立 seq。seq 是「全任务唯一时间序」,排序/去重按键控(不区分 agent)。
 * fold 不再维护全局水位防线,改为按 seq 键控的增量 upsert(见 {@link #fold}):
 * 并发子 agent / 重连重放下事件到达顺序可与 seq 顺序不一致,只要按 seq 定位置,
 * 任何合法旧轮(seq 更小)后到都不会被误丢,轮事件同 seq 更新同一条 item、非轮事件同 seq 去重。
 */
import type {
  AgentMessageRecord,
  LLMToolCall,
} from '@/types'
import type {
  TaskTraceRecord,
} from './types'
import { getHandler, defaultHandler } from './eventRegistry'
import { AgentLedger } from './agentLedger'

/**
 * 统一线程项(与 n 的 taskQueryService 同形,聊天页聚合展示单位)。
 * 无折叠标记:折叠窗口语义归 rounds.jsonl 唯一权威(§7.15.1),线程项只是渲染素材,
 * 轮折叠/展开由轮次骨架(TaskRoundsPanel)驱动。
 */
export type TaskThreadItem =
  | { type: 'agent_message'; createdAt: number; message: AgentMessageRecord }
  | { type: 'task_trace'; createdAt: number; trace: TaskTraceRecord }

/** 任务冻结的模型快照(历史上由 task.started 事件携带;当前 worker 不再发送该事件,此字段保留为兼容占位,通常为空)。 */
export interface TaskModelInfo {
  configId?: string
  name?: string
  model?: string
}

/** 折叠器的状态结构(JSON 可序列化;当前前端不做 localStorage 缓存——历史由 worker 磁盘落盘、
 * 打开任务时经 DataPusher 全量回放重建,见 taskStream;保留可序列化形态以兼容多端/续折)。
 * agent 状态/元数据台账镜像不在本状态:独立 store folder.ledger(agentLedger.ts),不随 reset 清。 */
export interface TaskThreadState {
  taskId: string
  items: TaskThreadItem[]
  /** 任务冻结的模型信息(当前不再由流事件填充,通常为空,见 TaskModelInfo)。 */
  taskModel?: TaskModelInfo | null
  /** slash 任务级 token(slash.tokens.changed 事件 REPLACE 更新;null=未收到)。 */
  slashTokens?: string[] | null
  /**
   * 主 agent 固定 id(task 层传入:task.agents 应答 / TaskSummary.mainAgentId)。
   * 一切「主 agent」判定唯一依据(事件 agentId 归一、user.message 骨架升级判定、error 主/子
   * 标题等;台账 seedAgents 的主 agent 状态例外由调用方把 mainAgentId 作参数传入台账 store)
   * ——不使用「空串=主」魔法。open 早期未灌入时折叠器以
   * isMain 的空串兼容回退兜底;事件缺 agentId(旧 wire 形态)归一到 mainAgentId。
   */
  mainAgentId: string
}

/** worker 流事件(SDK TaskEvent 同形;数据包模式下 seq 可为 Snowflake string)。 */
export interface FoldableTaskEvent {
  seq: number | string
  ts: number
  event: string
  agentId?: string | null
  payload: Record<string, unknown>
}

/** 折叠锚点(不序列化,重放时从 items 重建或留空)。 */
interface FoldAnchors {
  /** seq(String(event.seq)) → 该轮流式中的 assistant 消息。轮事件同 seq 复用同一锚点/同一条线程项。 */
  streaming: Map<string, AgentMessageRecord>
  /** callId → 工具名(tool.result 消息回填 toolName)。 */
  toolNames: Map<string, string>
}

export class TaskEventFolder {
  /**
   * agent 台账镜像 store(组合持有;步骤 6 自本类拆出的独立状态):线程折叠器不持有
   * agent 状态——usage、agent 生命周期事件、error、cancelled、ask 族等台账类事件
   * handler 经 eventRegistry 查表分发后直接改写本实例,task.agents 台账 seed 也灌入
   * 这里(taskStream 调用)。reset() 不清台账:台账生命周期跟任务不跟 items 重置
   * (与 slashTokens/mainAgentId 同理)。
   */
  readonly ledger: AgentLedger = new AgentLedger()
  /**
   * reset 计数(每次 reset 自增,不随 reset 清零):渲染层(轮面板)观测 items 被整体
   * 清空的信号——骨架出画板后 resync 重建来源 = wire 回放 + 分页状态失效促重拉
   * (taskStream full 重载 reset 后,轮面板据此清空分页状态、已展开轮重新发起首页拉取,
   * 避免 reset 误清已展开轮内容后无重拉路径,§8.3)。
   */
  resetEpoch = 0
  private anchors: FoldAnchors = { streaming: new Map(), toolNames: new Map() }
  /**
   * seq 键控索引(键 = String(event.seq)) → 线程项:
   * - 去重/定位主键:items 保持按 seq 升序,新 seq 插入按序定位;同 seq 更新原地、不移动位置。
   * - 轮事件(thinking/delta/message)同 seq 共享同一条 agent_message item,只能更新不可重复插入;
   *   非轮 item 事件同 seq 已存在 → 忽略(重连重放去重);trace 族事件自身 seq 也登记于此。
   * - 值随 wrapper 替换(如 trace 原地 upsert)同步刷新为 items 中的当前对象。
   */
  private bySeq = new Map<string, TaskThreadItem>()


  constructor(public state: TaskThreadState) {
    // 防御:外部构造的旧形状 state 可能缺 mainAgentId(本项目前端不做持久化缓存,
    // 理论上无旧数据),兜底补空串(open 早期空串兼容回退)。
    if (!state.mainAgentId) state.mainAgentId = ''
  }

  /**
   * 灌入主 agent 固定 id(task 层传入;open 流程尽早调用——TaskStream 构造/resolved
   * 时从 TaskSummary.mainAgentId、task.agents 应答拉取时各灌一次,幂等)。
   * 空值不覆盖(防陈旧回退);一经灌入即作为 isMain 的唯一判据。
   */
  setMainAgentId(mainAgentId: string): void {
    if (mainAgentId && mainAgentId !== this.state.mainAgentId) {
      this.state.mainAgentId = mainAgentId
    }
  }

  /** 主 agent 键(mainAgentId 灌入前为空串,与旧形态键兼容)。 */
  mainKey(): string {
    return this.state.mainAgentId || ''
  }

  /**
   * 是否主 agent:键 === mainAgentId(task 层传入的固定 id)。
   * mainAgentId 尚未灌入(open 极早期)时回退旧形态空串判据,兜住先到的骨架/事件。
   */
  isMain(agentKey: string): boolean {
    if (this.state.mainAgentId) return agentKey === this.state.mainAgentId
    return agentKey === ''
  }

  /**
   * 事件的 agent 键:payload.agentId 缺失(旧 wire 形态主 agent 事件不带 agentId)时
   * 归一到 mainAgentId——新 wire 全事件必带真实 agentId(主=mainAgentId),旧数据经
   * 此归一后与新数据键域一致。
   */
  agentKeyOf(event: FoldableTaskEvent): string {
    return event.agentId
      ?? (event.payload?.agentId as string | undefined)
      ?? this.mainKey()
  }

  /**
   * 重置:清空全部线程状态(items/bySeq/anchors;agent 台账镜像 ledger 不清,见类字段注释),
   * 并自增 resetEpoch(渲染层观测信号,见字段注释)。
   * resync/编辑重发的 full 重载时调用——旧 items(编辑前轮次,seq 已过期)必须清除,否则
   * 新旧轮次按 seq 混排会顺序错乱;骨架出画板后 items 重建来源 = 尾段 wire 回放 + 实时推送 +
   * 渲染层分页状态失效促重拉(不再有 rounds.jsonl 合成骨架折入兜底)。
   * 保留 taskId,重置其余字段到空态。
   * <p>slashTokens 不参与重置:它不是 rounds 折叠产物,真相源是 worker 持久化 +
   * slash.tokens.changed 事件通道(订阅初始回放会重推最新值)。若在此清空,
   * 初始打开流程中"先到的回放事件会被随后的 reset 抹掉",deps 回到 null→null
   * 消费端既不重跑也不重拉,任务级胶囊即丢失(新建任务 AI 审议胶囊消失实锤)。
   * <p>agent 台账镜像(ledger)同理不参与重置:台账生命周期跟任务不跟 items 重置
   * (流事件/台账 seed 按字段级合并幂等,重放自动回填),详见 agentLedger.ts。
   */
  reset(): void {
    this.state.items = []
    this.state.taskModel = null
    this.resetEpoch++
    // mainAgentId 与 slashTokens 同理不参与重置:任务级身份,由 task 层(TaskSummary/
    // task.agents)传入,reset(编辑重发重拉)后不变;清掉会让随后折叠的事件回退
    // 空串键、isMain 误判。
    this.bySeq.clear()
    this.anchors.streaming.clear()
    this.anchors.toolNames.clear()
  }

  /**
   * 折叠一个事件：查表分发到注册 handler，未注册 kind 走 defaultHandler。
   *
   * 数据包协议:一轮 AI 回复 = 1 个 seq,同轮 thinking/delta/message 同 seq(占同一条线程项);
   * 其余事件各占独立 seq。折叠器按 seq 键控增量 upsert:
   * - 轮事件(thinking/delta/message):同 seq 已存在 → 更新同一条 item;不存在 → 新建流式
   *   assistant 消息并按 seq 插入。message 是该轮权威整轮(定稿 content/reasoning/toolCalls)。
   * - 非轮 item 事件(user.message/tool.result/agent.started/agent.done/error/cancelled):
   *   同 seq 已存在 → 忽略(重连重放去重);不存在 → 新建并按 seq 插入。
   * - trace 族(retry/context.compression/model.failover/model.switch/auth.review/system.notice):
   *   按 seq upsert(同 seq 覆盖内容、不新增线程项)。
   * - usage/agent.status/ask.* 等非 item 事件幂等处理,不参与 seq 去重。
   *
   * 返回 true 表示状态有变化(UI 需重渲染)。
   */
  fold(event: FoldableTaskEvent): boolean {
    const handler = getHandler(event.event)
    if (handler) return handler.handle(event, this.state, this)
    return defaultHandler(event, this.state, this)
  }

  /** 折叠一批事件,返回是否有变化。 */
  foldAll(events: FoldableTaskEvent[]): boolean {
    let changed = false
    for (const event of events) {
      if (this.fold(event)) changed = true
    }
    return changed
  }

  /** 任务终态:定稿全部流式消息。 */
  finalize(): void {
    for (const seqKey of Array.from(this.anchors.streaming.keys())) {
      this.closeStreamingBySeq(seqKey)
    }
  }

  /** 是否存在流式中的消息锚点(本轮内容仍在增长;供跟随滚动的"内容在飞"判定)。 */
  hasStreaming(): boolean {
    return this.anchors.streaming.size > 0
  }

  /**
   * 线程最早一条线程项的排序 seq(供上滚续拉判断是否还有更早)。
   * 空线程返回 null;否则取 items[0](items 恒按 seq 升序)经 seqOfItem 反解精确排序 seq
   * (agent_message 优先从 messageId 的 `m-` 前缀取原始串,保留 Snowflake 大数精度)。
   */
  earliestSeq(): number | string | null {
    return this.state.items.length === 0 ? null : this.seqOfItem(this.state.items[0])
  }

  /**
   * 该轮区间 (startSeq, endSeq] 内已折入的 agent_message 的最大 seq(字符串)。
   * endSeq 传 null → 上界为 ∞(未闭合轮)。无命中返回 null。
   * 供拉取层在 RPC 前查 items 缓存:maxReal >= endSeq 即整轮已完整覆盖
   * (骨架出画板后区间内条目皆 wire 真实事件,无合成项跳过分支)。
   */
  roundRealFloor(startSeq: string, endSeq: string | null): string | null {
    let floor: string | null = null
    for (const item of this.state.items) {
      if (item.type !== 'agent_message') continue
      const seq = seqFromMessageId(item.message.messageId)
      if (!seq) continue
      if (compareSeq(seq, startSeq) <= 0) continue // 不含起点 user
      if (endSeq != null && compareSeq(seq, endSeq) > 0) continue // 越过终点
      if (floor == null || compareSeq(seq, floor) > 0) floor = seq
    }
    return floor
  }

  /**
   * 该轮 [startSeq, beforeSeq) 内已折入的 agent_message 的最小 seq(字符串)。
   * 无命中返回 null。供拉取层 backward 查缓存:minReal <= startSeq 即已连到轮起点
   * (无需再往前拉)。
   */
  roundRealCeiling(startSeq: string, beforeSeq: string): string | null {
    let ceiling: string | null = null
    for (const item of this.state.items) {
      if (item.type !== 'agent_message') continue
      const seq = seqFromMessageId(item.message.messageId)
      if (!seq) continue
      if (compareSeq(seq, startSeq) < 0) continue // 越过起点(不含 user)
      if (compareSeq(seq, beforeSeq) >= 0) continue // 不含 beforeSeq 自身
      if (ceiling == null || compareSeq(seq, ceiling) < 0) ceiling = seq
    }
    return ceiling
  }

  // ---- 公共 helper（供 eventRegistry handler 调用） ----

  /** 检查某 seq 是否已折入（去重判定）。 */
  hasSeq(seqKey: string): boolean {
    return this.bySeq.has(seqKey)
  }

  /** 获取工具名（callId → name，由 message 事件的 toolCalls 注册）。 */
  getToolName(callId: string): string {
    return this.anchors.toolNames.get(callId) ?? ''
  }

  // ---- 内部 ----

  /**
   * 插入一条 agent_message 到 items(按原始 seq 精确升序定位;bySeq 键用调用方传入的
   * 原始 seq 字符串 seqKey——Snowflake 大数转 Number 有精度损失,去重键与排序键都必须用原始串)。
   * 用户回合边界关闭该 agent 的流式锚点。线程项无折叠标记(折叠窗口语义归 rounds.jsonl)。
   */
  insertMessage(agentKey: string, message: AgentMessageRecord, seqKey: string): void {
    if (message.role === 'user') {
      this.closeStreamingByAgent(agentKey)
    }
    const item: TaskThreadItem = {
      type: 'agent_message',
      createdAt: message.createdAt,
      message,
    }
    this.insertItemBySeq(item, seqKey, seqKey)
  }

  /**
   * 截断:移除所有 seq >= targetSeq 的线程项(消息编辑重发时,worker 已截断磁盘,
   * 前端同步移除本地线程中后续的 AI 回复/工具调用/trace 等)。同时清理 bySeq 索引。
   */
  truncateAfterSeq(targetSeq: string): void {
    const items = this.state.items
    let i = 0
    while (i < items.length) {
      const itemSeq = this.seqOfItem(items[i])
      if (compareSeq(itemSeq, targetSeq) >= 0) {
        // 移除该项
        const removed = items.splice(i, 1)[0]
        // 清理 bySeq(该项的 seqKey)
        const seqKey = String(itemSeq)
        if (this.bySeq.get(seqKey) === removed) {
          this.bySeq.delete(seqKey)
        }
      } else {
        i++
      }
    }
  }

  /** 插入一条 task_trace 到 items(按 seq 升序定位)。 */
  insertTrace(ts: number, trace: TaskTraceRecord, seq: number | string): TaskThreadItem {
    const item: TaskThreadItem = { type: 'task_trace', createdAt: ts, trace }
    this.insertItemBySeq(item, seq, String(seq))
    return item
  }

  /** 按 seq 升序把 item 插入 items,并登记 bySeq(seqKey → item)。 */
  private insertItemBySeq(item: TaskThreadItem, orderSeq: number | string, seqKey: string): void {
    this.state.items.splice(this.insertIndexBySeq(orderSeq), 0, item)
    this.bySeq.set(seqKey, item)
  }

  /**
   * 线性定位首个 seq > target 的下标(数据量不大,正确性优先);无则返回末尾。
   * 比较走 compareSeq:seq 可为 Snowflake 大数字符串,Number() 会在 2^53 之上丢失
   * 精度(相邻两个大数坍缩为同一 double),必须按大整数精确比较,否则乱序到达会被排错。
   */
  private insertIndexBySeq(seq: number | string): number {
    const items = this.state.items
    for (let i = 0; i < items.length; i++) {
      if (compareSeq(this.seqOfItem(items[i]), seq) > 0) return i
    }
    return items.length
  }

  /**
   * 线程项的精确排序 seq:agent_message 优先从 messageId(`m-` 前缀 + 原始 seq 串)反推,
   * 保留 Snowflake 大数精度(message.sequence 只是 Number 近似值);task_trace 从 traceId
   * (`t-` 前缀 + 原始 seq 串)反推。返回类型为 number | string,比较用 compareSeq。
   */
  seqOfItem(item: TaskThreadItem): number | string {
    if (item.type === 'agent_message') {
      const mid = item.message.messageId
      if (mid && mid.startsWith('m-')) return mid.slice(2)
      return item.message.sequence ?? 0
    }
    const tid = item.trace.traceId
    if (tid && tid.startsWith('t-')) return tid.slice(2)
    return 0
  }

  /** wrapper 被替换(如 trace 原地 upsert)后,把 bySeq 同步到 items 中的当前对象。 */
  syncBySeqItem(item: TaskThreadItem): void {
    if (item.type === 'agent_message') {
      // messageId 恒为 `m-${原始 seq}`:从 messageId 反推,保留 Snowflake 大数字符串精度;
      // message.sequence 只是 Number 近似值(仅展示),去重键与排序键都走原始串(见 seqOfItem)。
      const seqKey = item.message.messageId.startsWith('m-')
        ? item.message.messageId.slice(2)
        : String(item.message.sequence)
      this.bySeq.set(seqKey, item)
      return
    }
    // task_trace:从 traceId(`t-${seq}`)反推 seqKey
    const tid = item.trace.traceId
    if (tid && tid.startsWith('t-')) {
      this.bySeq.set(tid.slice(2), item)
    }
  }

  /**
   * trace 事件折叠:按 seq 原地 upsert。
   * 同一 seq 的后续事件(如 retry.progress 每秒刷新)只覆盖内容,不新增线程项,
   * 位置由首达 seq 固定不变;title/summary/content/status/data 由 worker 逐阶段更新。
   * kind 现在直接用 event.event(不再从 payload.kind 读取);metadata → data(payload.data)。
   * 返回当前线程项(新建或原地更新的同一对象)。
   */
  upsertTraceBySeq(
    agentKey: string,
    eventKind: string,
    payload: Record<string, unknown>,
    ts: number,
    seq: number | string,
  ): TaskThreadItem | undefined {
    const seqKey = String(seq)
    const traceId = `t-${seq}`
    const data = (payload?.data != null && typeof payload.data === 'object' && !Array.isArray(payload.data))
      ? (payload.data as Record<string, unknown>)
      : undefined
    const rawContent = payload?.content
    const trace: TaskTraceRecord = {
      traceId,
      taskId: this.state.taskId,
      agentId: agentKey || undefined,
      kind: eventKind,
      title: readStr(payload?.title) ?? '',
      summary: readStr(payload?.summary),
      content: rawContent != null ? String(rawContent) : undefined,
      createdAt: ts,
      metadata: data,
    }
    // 按 seq upsert:同 seq 已有 trace → 原地更新;新 seq → 插入。
    const existing = this.bySeq.get(seqKey)
    if (existing && existing.type === 'task_trace') {
      const idx = this.state.items.indexOf(existing)
      if (idx >= 0) {
        const updated: TaskThreadItem = { type: 'task_trace', createdAt: ts, trace }
        this.state.items[idx] = updated
        this.bySeq.set(seqKey, updated)
        return updated
      }
    }
    // 也检查 items 中是否有同 traceId 的 trace（防御:bySeq 可能因外部操作未同步）
    const traceIdx = this.state.items.findIndex(
      (it) => it.type === 'task_trace' && it.trace.traceId === traceId,
    )
    if (traceIdx >= 0) {
      const updated: TaskThreadItem = { type: 'task_trace', createdAt: ts, trace }
      this.state.items[traceIdx] = updated
      this.syncBySeqItem(updated)
      return updated
    }
    return this.insertTrace(ts, trace, seq)
  }

  /**
   * thinking/delta(轮事件增量帧):同一 seq 只创建/更新同一条线程项,绝不重复插入。
   * - 该 seq 有流式锚点(仍在聚合) → 原地追加 reasoning/content;
   * - 该 seq 已有线程项但无锚点(message 已权威定稿,或回放先于流式到达) → 忽略迟到增量帧,
   *   避免把权威整轮内容叠坏;
   * - 该 seq 尚无线程项 → 新建流式 assistant 消息按 seq 插入,并登记 seq 锚点。
   */
  appendRoundText(
    kind: 'thinking' | 'delta',
    agentKey: string,
    ts: number,
    seq: number | string,
    text: string,
  ): void {
    const seqKey = String(seq)
    const existing = this.bySeq.get(seqKey)
    if (existing) {
      if (existing.type !== 'agent_message') return
      if (this.anchors.streaming.get(seqKey) === existing.message) {
        const message = existing.message
        if (kind === 'thinking') {
          message.reasoning = (message.reasoning ?? '') + text
          // 思考仍在增长:清除「思考已结束」标记。推理模型若 reasoning/content 交织,
          // 正文(delta)先到会把 reasoningDone 置真,此处随后的 thinking 增量将其回退,
          // 避免把思考区提前折叠(只有思考真正停止后才折叠)。
          this.markReasoningDone(message, false)
        } else {
          message.content = (message.content ?? '') + text
          // 正文首达且已有思考 → 视为思考阶段结束,驱动前端思考块自动折叠。
          // 若后续又有 thinking 增量,appendRoundText 会回退该标记(见上)。
          if ((message.reasoning ?? '').trim().length > 0) {
            this.markReasoningDone(message, true)
          }
        }
        message.updatedAt = ts
      }
      return
    }
    const message: AgentMessageRecord = {
      messageId: `m-${seq}`,
      agentId: agentKey,
      role: 'assistant',
      content: '',
      historyMode: 'thread_only',
      createdAt: ts,
      updatedAt: ts,
      sequence: Number(seq),
      metadata: { streaming: true },
    }
    if (kind === 'thinking') {
      message.reasoning = (message.reasoning ?? '') + text
    } else {
      message.content = (message.content ?? '') + text
    }
    this.anchors.streaming.set(seqKey, message)
    this.insertMessage(agentKey, message, seqKey)
  }

  /**
   * 标记/清除「思考阶段已结束」:正文(delta)首达且已有思考时置真,思考(thinking)继续增长时回退为假。
   * 前端据此在思考完成后自动折叠思考块(正文仍继续流式),而不必等到整条消息定稿。
   */
  private markReasoningDone(message: AgentMessageRecord, done: boolean): void {
    if (done) {
      message.metadata = { ...(message.metadata ?? {}), reasoningDone: true }
    } else if (message.metadata?.reasoningDone === true) {
      message.metadata = { ...message.metadata, reasoningDone: false }
    }
  }

  /**
   * 关闭指定 seq 的流式锚点(message 定稿 / finalize 调用):标记 streaming=false 并移除锚点。
   * 线程项与 bySeq 索引不删除,后续迟到帧(如有)仍按同一条更新。
   */
  private closeStreamingBySeq(seqKey: string): void {
    const message = this.anchors.streaming.get(seqKey)
    if (!message) return
    this.anchors.streaming.delete(seqKey)
    if (message.metadata) {
      message.metadata = { ...message.metadata, streaming: false }
    }
  }

  /**
   * 关闭某 agent 的全部流式锚点(用户回合边界 / tool.result / agent.done / error / cancelled 兜底)。
   * 锚点按 seq 键控后同一 agent 平时至多一条在飞;即便提前关闭锚点,线程项与 bySeq 索引仍保留,
   * 后续迟到的同 seq message 会经 bySeq 复用同一条,不会重复插入。
   */
  closeStreamingByAgent(agentKey: string): void {
    for (const seqKey of Array.from(this.anchors.streaming.keys())) {
      const message = this.anchors.streaming.get(seqKey)
      if (message && (message.agentId ?? '') === agentKey) {
        this.anchors.streaming.delete(seqKey)
        if (message.metadata) {
          message.metadata = { ...message.metadata, streaming: false }
        }
      }
    }
  }

  /**
   * message:一轮权威终结(落盘事件)。同 seq 定位复用,不重复插入:
   * - 有流式锚点(delta/thinking 聚合中) → 复用锚点定稿;
   * - 无锚点但有同 seq 线程项(迟到的重复 message 帧 / 回放先到) → 更新同一条;
   * - 两者皆无(回放/冷启动) → 新建完成态插入。
   * 完成后关闭该轮流式锚点。
   * thinking 是完整整轮文本(替换式),toolCalls 为模型真实 id,与 tool.result 按 callId 配对。
   */
  completeMessage(agentKey: string, payload: Record<string, unknown>, ts: number, seq: number | string): void {
    const seqKey = String(seq)
    const data = (payload?.data != null && typeof payload.data === 'object' && !Array.isArray(payload.data))
      ? (payload.data as Record<string, unknown>) : undefined
    const thinking = typeof data?.thinking === 'string' ? data.thinking : ''
    const text = typeof payload?.content === 'string' ? payload.content : ''
    const toolCalls = readToolCalls(data?.toolCalls)
    let message: AgentMessageRecord
    let isNew = false
    const streaming = this.anchors.streaming.get(seqKey)
    if (streaming) {
      // 同轮流式锚点:复用同一条线程项定稿。
      message = streaming
    } else {
      const existing = this.bySeq.get(seqKey)
      if (existing && existing.type === 'agent_message') {
        // 该轮 item 已存在(流式锚点被提前关闭,或 message 重复帧):更新同一条。
        message = existing.message
      } else {
        message = {
          messageId: `m-${seq}`,
          agentId: agentKey,
          role: 'assistant',
          content: '',
          historyMode: 'thread_only',
          createdAt: ts,
          updatedAt: ts,
          sequence: Number(seq),
          metadata: {},
        }
        isNew = true
      }
    }
    if (thinking) {
      // 权威值替换(流式差分之和应与整轮 thinking 一致;丢帧时以权威为准)。
      message.reasoning = thinking
    }
    message.content = text
    if (toolCalls.length > 0) {
      // 按 callId 去重追加(同一轮 message 重复帧幂等,不会产生重复 toolCalls)。
      const seen = new Set((message.toolCalls ?? []).map((c) => c.id))
      const fresh = toolCalls.filter((c) => !seen.has(c.id))
      if (fresh.length > 0) {
        message.toolCalls = [...(message.toolCalls ?? []), ...fresh]
        for (const call of fresh) {
          if (call.id) this.anchors.toolNames.set(call.id, call.function.name)
        }
      }
    }
    message.updatedAt = ts
    this.closeStreamingBySeq(seqKey)
    if (isNew) {
      this.insertMessage(agentKey, message, seqKey)
    }
  }
}

/**
 * 精确比较两个 seq(number 或数字字符串,含 Snowflake 大数)。
 * Number() 在 2^53 之上会丢失整数精度(相邻两个大数坍缩为同一 double),
 * 因此这里把 seq 统一按十进制大整数字符串比较:先比符号/位数,再比字典序。
 */
export function compareSeq(a: number | string, b: number | string): number {
  const sa = typeof a === 'number' ? String(a) : a
  const sb = typeof b === 'number' ? String(b) : b
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

/** messageId(形如 `m-${seq}`) → seq 字符串;非该形返回 null。 */
function seqFromMessageId(messageId: string): string | null {
  if (!messageId || !messageId.startsWith('m-')) return null
  const seq = messageId.slice(2)
  return seq.length > 0 ? seq : null
}

/** message.toolCalls 数组 → LLMToolCall[](模型真实 id;name 缺省补登记表)。 */
export function readToolCalls(value: unknown): LLMToolCall[] {
  if (!Array.isArray(value)) return []
  const out: LLMToolCall[] = []
  for (const item of value) {
    if (!item || typeof item !== 'object') continue
    const v = item as Record<string, unknown>
    const id = typeof v.id === 'string' ? v.id : ''
    const name = typeof v.name === 'string' ? v.name : ''
    if (!id || !name) continue
    const args = v.arguments
    out.push({
      id,
      type: 'function',
      function: {
        name,
        arguments: typeof args === 'string' ? args : safeJsonStringify(args),
      },
    })
  }
  return out
}

export function safeJsonStringify(value: unknown): string {
  if (value == null) return ''
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

export function readStr(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() !== '' ? value : undefined
}

/** 窗口上限缺省值(与 worker WorkerConfig.DEFAULT_CONTEXT_WINDOW_TOKENS 一致):数据源未配置时兜底,避免显示 0。 */
export const DEFAULT_CONTEXT_WINDOW_TOKENS = 256_000

export function readNum(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : undefined
}

/** 空状态。 */
export function emptyThreadState(taskId: string): TaskThreadState {
  return { taskId, items: [], slashTokens: null, mainAgentId: '' }
}
