/**
 * 事件折叠器(纯函数状态机):worker 任务流事件 → n 前端线程项。
 *
 * n 的持久化层(agentMessageRepository/taskTraceRepository)在浏览器里逐条写入;
 * 本前端没有本地持久化,改为把 worker 的数据包事件流「折叠」成同样的
 * AgentMessageRecord / TaskTraceRecord 形状,让 AgentMessageThread /
 * TraceItem 等组件零改动复用。
 *
 * 折叠规则(与 n 的写入语义对齐):
 * 事件名不分主/子 agent——同一名事件,归属由 agentId 字段决定(空 = 主线程);
 * - user.message           → role:'user' 消息
 * - thinking / delta       → 当前流式 assistant 消息的 reasoning / content 追加(瞬态)
 * - message                → 一轮权威终结:完整 reasoning/content/toolCalls(真实 toolCall id)
 *                           定稿该 agent 的流式消息
 * - usage                  → 主 agent 上下文用量快照(contextUsage,不进线程)
 * - tool.result            → role:'tool' 消息(TaskChat 按 callId 合并进下发块渲染)
 * - agent.started/done     → 子 agent trace(spawn 生命周期,必带 agentId)
 * - error                  → trace(带 agentId = 子任务出错;缺省 = 任务出错)
 * - cancelled              → trace
 * - task.trace             → 统一纯显示 trace(重试生命周期/任务耗时等):按 payload.traceId
 *                           原地 upsert(有则覆盖内容、无则新增),见 {@link #upsertTrace}
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
  AgentStatus,
  ContextMonitorSnapshot,
  LLMToolCall,
  RoundSummary,
  TaskStatus,
  TaskTraceRecord,
} from '@/types'

/**
 * 统一线程项(与 n 的 taskQueryService 同形,聊天页聚合展示单位)。
 *
 * foldRole:折叠扫描标记(一边接收一边打标,历史回放与实时输出同一路径写入)。
 * - 'user':用户消息(折叠窗口起点,界面上右侧一条)。
 * - 'final_reply':AI 最终回复(主 agent 的 assistant 消息,无工具调用且有正文,
 *   折叠窗口终点,界面上左侧一条)。
 * - 缺省:过程性内容(折叠窗口中间被收起的内容)。
 */
export type TaskThreadItem =
  | { type: 'agent_message'; createdAt: number; message: AgentMessageRecord; foldRole?: 'user' | 'final_reply' }
  | { type: 'task_trace'; createdAt: number; trace: TaskTraceRecord }

/** 任务冻结的模型快照(历史上由 task.started 事件携带;当前 worker 不再发送该事件,此字段保留为兼容占位,通常为空)。 */
export interface TaskModelInfo {
  configId?: string
  name?: string
  model?: string
}

/** 折叠器的状态结构(JSON 可序列化;当前前端不做 localStorage 缓存——历史由 worker 磁盘落盘、
 * 打开任务时经 DataPusher 全量回放重建,见 taskStream;保留可序列化形态以兼容多端/续折)。 */
export interface TaskThreadState {
  taskId: string
  items: TaskThreadItem[]
  /**
   * agent 状态表:agentId(主 agent = 空串 '') → 当前状态。
   * 事件源:worker 的 agent.status(权威,持久);旧磁盘回放无该事件时由
   * agent.started/done/error/cancelled/ask.* 兜底推导。驱动输入框上方 agent 圆列表。
   */
  agentStates: Record<string, AgentStatus>
  /** 主 agent 上下文用量(usage 事件滚动更新,驱动上下文电池)。 */
  contextUsage?: ContextMonitorSnapshot | null
  /** 任务冻结的模型信息(当前不再由流事件填充,通常为空,见 TaskModelInfo)。 */
  taskModel?: TaskModelInfo | null
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
  /** agentKey → 子 agent 标题(sub 消息的 more 栏展示)。 */
  subTitles: Map<string, string>
}

export class TaskEventFolder {
  private anchors: FoldAnchors = { streaming: new Map(), toolNames: new Map(), subTitles: new Map() }
  /**
   * seq 键控索引(键 = String(event.seq)) → 线程项:
   * - 去重/定位主键:items 保持按 seq 升序,新 seq 插入按序定位;同 seq 更新原地、不移动位置。
   * - 轮事件(thinking/delta/message)同 seq 共享同一条 agent_message item,只能更新不可重复插入;
   *   非轮 item 事件同 seq 已存在 → 忽略(重连重放去重);task.trace 事件自身 seq 也登记于此。
   * - 值随 wrapper 替换(如 foldRole 打标、trace 原地 upsert)同步刷新为 items 中的当前对象。
   */
  private bySeq = new Map<string, TaskThreadItem>()
  /** traceId → 首次到达的 seq(固定该 trace 线程项的排序位置;后续同 traceId 事件原地 upsert 不移动)。 */
  private traceSeq = new Map<string, number | string>()

  constructor(public state: TaskThreadState) {}

  /**
   * 折叠一个事件。
   *
   * 数据包协议:一轮 AI 回复 = 1 个 seq,同轮 thinking/delta/message 同 seq(占同一条线程项);
   * 其余事件各占独立 seq。折叠器按 seq 键控增量 upsert:
   * - 轮事件(thinking/delta/message):同 seq 已存在 → 更新同一条 item;不存在 → 新建流式
   *   assistant 消息并按 seq 插入。message 是该轮权威整轮(定稿 content/reasoning/toolCalls)。
   * - 非轮 item 事件(user.message/tool.result/agent.started/agent.done/error/cancelled):
   *   同 seq 已存在 → 忽略(重连重放去重);不存在 → 新建并按 seq 插入。
   * - task.trace:按 traceId 原地 upsert(同 traceId 覆盖内容、不新增线程项);事件自身 seq 去重。
   * - usage/agent.status/ask.* 等非 item 事件幂等处理,不参与 seq 去重。
   *
   * 返回 true 表示状态有变化(UI 需重渲染)。
   */
  fold(event: FoldableTaskEvent): boolean {
    const agentKey = event.agentId ?? (event.payload?.agentId as string | undefined) ?? ''
    const ts = event.ts || Date.now()
    const seqKey = String(event.seq)
    switch (event.event) {
      case 'user.message': {
        if (this.bySeq.has(seqKey)) return false
        // content 用 AI 可见明文（payload.text）；rawContent 优先用 payload.rawContent
        // （前端提交时随输入一并持久化的原始 opaque 串），缺失时退化为 text（纯文本输入/旧 worker）。
        // 回放只消费 rawContent 里的 opaque token 来还原胶囊，composerTokens 数组在此不带
        // （自包含解析，见 AgentMessageThread）。
        const text = String(event.payload?.text ?? '')
        const rawContent = typeof event.payload?.rawContent === 'string' && event.payload.rawContent.length
          ? event.payload.rawContent
          : text
        this.insertMessage(agentKey, {
          messageId: `m-${event.seq}`,
          agentId: agentKey,
          role: 'user',
          content: text,
          rawContent,
          historyMode: 'thread_only',
          createdAt: ts,
          updatedAt: ts,
          sequence: Number(event.seq),
        }, seqKey)
        return true
      }
      case 'thinking':
      case 'delta':
        this.appendRoundText(event.event, agentKey, ts, event.seq, String(event.payload?.text ?? ''))
        return true
      case 'message':
        this.completeMessage(agentKey, event.payload, ts, event.seq)
        return true
      case 'tool.result': {
        if (this.bySeq.has(seqKey)) return false
        const callId = String(event.payload?.callId ?? '')
        this.closeStreamingByAgent(agentKey)
        this.insertMessage(agentKey, {
          messageId: `m-${event.seq}`,
          agentId: agentKey,
          role: 'tool',
          content: String(event.payload?.summary ?? ''),
          historyMode: 'thread_only',
          createdAt: ts,
          updatedAt: ts,
          sequence: Number(event.seq),
          toolCallId: callId,
          toolName: this.anchors.toolNames.get(callId) ?? '',
          status: 'success',
          metadata: event.payload?.truncated ? { truncated: true } : undefined,
        }, seqKey)
        return true
      }
      case 'agent.started': {
        if (this.bySeq.has(seqKey)) return false
        const subAgentId = agentKey
        const title = String(event.payload?.title ?? '')
        if (subAgentId) this.anchors.subTitles.set(subAgentId, title)
        // 兜底:worker 已补发 agent.status(running);旧磁盘回放无该事件时由此推导
        if (subAgentId) this.state.agentStates[subAgentId] = 'running'
        return true
      }
      case 'agent.done': {
        if (this.bySeq.has(seqKey)) return false
        this.closeStreamingByAgent(agentKey)
        this.state.agentStates[agentKey] = 'completed' // 兜底(权威由 agent.status done 提供)
        return true
      }
      case 'agent.status': {
        // 主/子统一状态事件(权威):归属同 delta/message——主 agent payload 无 agentId(缺省=主线程),
        // 子 agent 由 wireEvent 注入 agentId。纯状态更新,不进线程 items。
        // worker 侧枚举是 running/waiting-user/done/failed/stopped(见 Events.AgentStatus),
        // 其中 done/failed 需映射到前端 vocabulary(completed/error),否则主 agent 终态
        // 会被丢弃、永远停在 running(子 agent 靠 agent.done 兜底才有 completed)。
        const mapped = mapAgentStatus(String(event.payload?.status ?? ''))
        if (mapped) {
          this.state.agentStates[agentKey] = mapped
        }
        return true
      }
      case 'usage': {
        // 主 agent(agentId 空)的单轮实测用量驱动任务级上下文电池;子 agent 用量忽略。
        if (!agentKey) {
          const round = readUsage(event.payload?.round)
          if (round) {
            const total = readUsage(event.payload?.total)
            const maxTokens = readNum(event.payload?.contextWindowTokens) ?? DEFAULT_CONTEXT_WINDOW_TOKENS
            const model = readStr(event.payload?.model)
            this.state.contextUsage = {
              promptTokens: round.inputTokens,
              completionTokens: round.outputTokens,
              totalTokens: total?.totalTokens ?? round.totalTokens,
              maxTokens,
              usageRatio: maxTokens > 0 ? round.inputTokens / maxTokens : 0,
              lastUpdatedAt: ts,
              requestType: 'chatStream',
              model: model ?? '',
            }
          }
        }
        return true
      }
      case 'error': {
        if (this.bySeq.has(seqKey)) return false
        // 同名事件,归属由 agentId 决定:带 agentId = 子 agent 失败,缺省 = 任务失败。
        const message = String(event.payload?.message ?? '')
        this.closeStreamingByAgent(agentKey)
        this.state.agentStates[agentKey] = 'error' // 兜底(权威由 agent.status failed 提供)
        this.insertTrace(ts, {
          traceId: `t-${event.seq}`,
          taskId: this.state.taskId,
          agentId: agentKey || undefined,
          kind: 'run_error',
          title: agentKey ? '子任务出错' : '任务出错',
          summary: message,
          content: message,
          createdAt: ts,
        }, event.seq)
        return true
      }
      case 'cancelled': {
        if (this.bySeq.has(seqKey)) return false
        this.closeStreamingByAgent('')
        this.state.agentStates[''] = 'stopped' // 兜底:任务取消 = 主 agent 停止
        this.insertTrace(ts, {
          traceId: `t-${event.seq}`,
          taskId: this.state.taskId,
          kind: 'system_notice',
          title: '任务已取消',
          summary: `由 ${String(event.payload?.by ?? '用户')} 取消`,
          createdAt: ts,
        }, event.seq)
        return true
      }
      case 'task.trace':
        // 统一纯显示 trace(重试生命周期/任务耗时等):按 traceId 原地 upsert——
        // 已有同 id 则覆盖内容(同一波重试的 attempt/progress/resolved 共用一条),无则新增。
        // 同时该事件自身的 seq 也去重:同一 seq 重复到达不重复 upsert。
        if (this.bySeq.has(seqKey)) return false
        {
          const item = this.upsertTrace(agentKey, event.payload, ts, event.seq)
          if (item) this.bySeq.set(seqKey, item)
        }
        return true
      case 'ask.create':
        // 兜底:该 agent(主/子)挂起等待用户(权威由 agent.status waiting-user 提供;ask 事件不进线程)
        this.state.agentStates[agentKey] = 'waiting-user'
        return true
      case 'ask.resolved': {
        // 兜底:回答/超时后该 agent 恢复执行(权威由 agent.status running 提供);
        // cancelled 不动——随后任务 cancelled 事件会把主 agent 置 stopped。
        const resolvedStatus = String(event.payload?.status ?? '')
        if (resolvedStatus !== 'cancelled') {
          this.state.agentStates[agentKey] = 'running'
        }
        return true
      }
      default:
        // ask.state 等其余事件由 askStore 驱动卡片,不进线程。
        return true
    }
  }

  /** 折叠一批事件,返回是否有变化。 */
  foldAll(events: FoldableTaskEvent[]): boolean {
    let changed = false
    for (const event of events) {
      if (this.fold(event)) changed = true
    }
    return changed
  }

  /**
   * 折叠一个轮次摘要(rounds.jsonl 的一行)为线程项:纯函数式数据入口,不引入轮询/网络。
   * 与原始 stream 事件按 seq 幂等去重,不产生重复项、items 恒按 seq 升序:
   * - 轮起点折入合成 user 消息(messageId=`m-${startSeq}`,role='user',foldRole='user',
   *   content/rawContent=round.user),由 {@link #insertMessage} 按 seq 升序定位插入;
   * - 已闭合轮(endSeq 非空)折入合成 AI 最终回复(messageId=`m-${endSeq}`,role='assistant',
   *   content=round.finalReply,foldRole='final_reply')。
   * bySeq 已有同 seq 项(原始 user.message / 权威 message / 已合成项)则跳过,绝不重复插入;
   * 二次调用同 startSeq 幂等(不改变已有项内容)。返回 true 表示状态有变化。
   */
  foldRound(round: RoundSummary): boolean {
    const startSeqKey = String(round.startSeq)
    if (!startSeqKey) return false
    const now = Date.now()
    let changed = false
    if (!this.bySeq.has(startSeqKey)) {
      // 完整 userMessage payload 优先(懒加载骨架起点);旧行缺失回退 user 文本摘要。
      const userText = round.userMessage?.text ?? round.user
      const userRaw = (round.userMessage?.rawContent && round.userMessage.rawContent.length)
        ? round.userMessage.rawContent
        : userText
      const userMessage: AgentMessageRecord = {
        messageId: `m-${round.startSeq}`,
        agentId: '',
        role: 'user',
        content: userText,
        rawContent: userRaw,
        historyMode: 'thread_only',
        createdAt: now,
        updatedAt: now,
        sequence: Number(round.startSeq),
        // 合成骨架项标记:缓存覆盖判定时不算「真实已拉取数据」(展开要看 thinking)。
        metadata: { synthetic: true },
      }
      this.insertMessage('', userMessage, startSeqKey)
      changed = true
    }
    if (round.endSeq) {
      const endSeqKey = String(round.endSeq)
      if (!this.bySeq.has(endSeqKey)) {
        const finalMessage: AgentMessageRecord = {
          messageId: `m-${round.endSeq}`,
          agentId: '',
          role: 'assistant',
          content: round.finalReply,
          rawContent: round.finalReply,
          historyMode: 'thread_only',
          createdAt: now,
          updatedAt: now,
          sequence: Number(round.endSeq),
          // 合成 final 标记:不顶真实权威 message(真实 message 折入后会被清除)。
          metadata: { synthetic: true },
        }
        // 合成 final_reply:与 markFinalReply 后的构造方式一致,直接标 foldRole='final_reply',
        // 并复用 insertItemBySeq 按 seq 升序精确定位、登记 bySeq。
        const item: TaskThreadItem = {
          type: 'agent_message',
          createdAt: now,
          message: finalMessage,
          foldRole: 'final_reply',
        }
        this.insertItemBySeq(item, round.endSeq, endSeqKey)
        changed = true
      }
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
   * 该轮区间 (startSeq, endSeq] 内已折入的「真实」agent_message 的最大 seq(字符串)。
   * endSeq 传 null → 上界为 ∞(未闭合轮)。「真实」= 非 rounds.jsonl 合成骨架项
   * (message.metadata.synthetic !== true)。无真实项返回 null。
   * 供拉取层在 RPC 前查 items 缓存:maxReal >= endSeq 即整轮已完整覆盖。
   */
  roundRealFloor(startSeq: string, endSeq: string | null): string | null {
    let floor: string | null = null
    for (const item of this.state.items) {
      if (item.type !== 'agent_message') continue
      if (item.message.metadata?.synthetic === true) continue // 合成骨架不顶真实数据
      const seq = seqFromMessageId(item.message.messageId)
      if (!seq) continue
      if (compareSeq(seq, startSeq) <= 0) continue // 不含起点 user
      if (endSeq != null && compareSeq(seq, endSeq) > 0) continue // 越过终点
      if (floor == null || compareSeq(seq, floor) > 0) floor = seq
    }
    return floor
  }

  /**
   * 该轮 [startSeq, beforeSeq) 内已折入的「真实」agent_message 的最小 seq(字符串)。
   * 「真实」= 非合成骨架项。无真实项返回 null。
   * 供拉取层 backward 查缓存:minReal <= startSeq 即已连到轮起点(无需再往前拉)。
   */
  roundRealCeiling(startSeq: string, beforeSeq: string): string | null {
    let ceiling: string | null = null
    for (const item of this.state.items) {
      if (item.type !== 'agent_message') continue
      if (item.message.metadata?.synthetic === true) continue
      const seq = seqFromMessageId(item.message.messageId)
      if (!seq) continue
      if (compareSeq(seq, startSeq) < 0) continue // 越过起点(不含 user)
      if (compareSeq(seq, beforeSeq) >= 0) continue // 不含 beforeSeq 自身
      if (ceiling == null || compareSeq(seq, ceiling) < 0) ceiling = seq
    }
    return ceiling
  }

  /** 子 agent 标题查询(agent 列表展示用)。 */
  resolveAgentTitle(agentId: string): string | undefined {
    return this.anchors.subTitles.get(agentId)
  }

  /** agent 状态查询(agent 圆列表用)。key 语义同线程项:主 agent = 空串,子 agent = 子 id。 */
  resolveAgentStatus(agentId: string): AgentStatus {
    return this.state.agentStates[agentId] ?? 'idle'
  }

  // ---- 内部 ----

  /**
   * 插入一条 agent_message 到 items(按原始 seq 精确升序定位;bySeq 键用调用方传入的
   * 原始 seq 字符串 seqKey——Snowflake 大数转 Number 有精度损失,去重键与排序键都必须用原始串)。
   * 用户回合边界关闭该 agent 的流式锚点;折叠扫描:用户消息标 foldRole='user'(折叠窗口起点,右侧一条)。
   */
  private insertMessage(agentKey: string, message: AgentMessageRecord, seqKey: string): void {
    if (message.role === 'user') {
      this.closeStreamingByAgent(agentKey)
    }
    const item: TaskThreadItem = {
      type: 'agent_message',
      createdAt: message.createdAt,
      message,
      foldRole: message.role === 'user' ? 'user' : undefined,
    }
    this.insertItemBySeq(item, seqKey, seqKey)
  }

  /** 插入一条 task_trace 到 items(按 seq 升序定位),并登记 traceId → 原始 seq(固定位置)。 */
  private insertTrace(ts: number, trace: TaskTraceRecord, seq: number | string): TaskThreadItem {
    const item: TaskThreadItem = { type: 'task_trace', createdAt: ts, trace }
    this.traceSeq.set(trace.traceId, seq)
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
   * 保留 Snowflake 大数精度(message.sequence 只是 Number 近似值);task_trace 用首达 seq
   * (traceSeq 表,按原始 number|string 保存)。返回类型为 number | string,比较用 compareSeq。
   */
  private seqOfItem(item: TaskThreadItem): number | string {
    if (item.type === 'agent_message') {
      const mid = item.message.messageId
      if (mid && mid.startsWith('m-')) return mid.slice(2)
      return item.message.sequence ?? 0
    }
    const seq = this.traceSeq.get(item.trace.traceId)
    return seq == null ? 0 : seq
  }

  /** wrapper 被替换(如 foldRole 打标、trace 原地 upsert)后,把 bySeq 同步到 items 中的当前对象。 */
  private syncBySeqItem(item: TaskThreadItem): void {
    if (item.type === 'agent_message') {
      // messageId 恒为 `m-${原始 seq}`:从 messageId 反推,保留 Snowflake 大数字符串精度;
      // message.sequence 只是 Number 近似值(仅展示),去重键与排序键都走原始串(见 seqOfItem)。
      const seqKey = item.message.messageId.startsWith('m-')
        ? item.message.messageId.slice(2)
        : String(item.message.sequence)
      this.bySeq.set(seqKey, item)
      return
    }
    const seq = this.traceSeq.get(item.trace.traceId)
    if (seq != null) this.bySeq.set(String(seq), item)
  }

  /**
   * task.trace 事件折叠:按 payload.traceId 原地 upsert(仿 node 侧 append/updateTaskTrace)。
   * 同一 traceId 的后续事件(如 retry.progress 每秒刷新)只覆盖内容,不新增线程项,
   * 位置由首达 seq 固定(traceSeq 表)不变;status/title/summary 由 worker 逐阶段更新。
   * 事件自身 seq 的去重由调用方(fold 的 task.trace 分支)完成。
   * 返回当前线程项(新建或原地更新的同一对象),供调用方登记 bySeq。
   */
  private upsertTrace(
    agentKey: string,
    payload: Record<string, unknown>,
    ts: number,
    seq: number | string,
  ): TaskThreadItem | undefined {
    const traceId = String(payload?.traceId ?? '')
    if (!traceId) {
      return undefined
    }
    const rawContent = payload?.content
    const trace: TaskTraceRecord = {
      traceId,
      taskId: this.state.taskId,
      agentId: agentKey || undefined,
      kind: String(payload?.kind ?? 'system_notice'),
      title: readStr(payload?.title) ?? '',
      summary: readStr(payload?.summary),
      content: rawContent != null ? String(rawContent) : undefined,
      createdAt: Number(payload?.createdAt ?? ts),
      metadata:
        payload?.metadata && typeof payload.metadata === 'object'
          ? (payload.metadata as Record<string, unknown>)
          : undefined,
    }
    const idx = this.state.items.findIndex(
      (it) => it.type === 'task_trace' && it.trace.traceId === traceId,
    )
    if (idx >= 0) {
      // 原地替换同 id trace(位置不动,TaskThread 按 items 顺序渲染即跟随更新)。
      const updated: TaskThreadItem = { type: 'task_trace', createdAt: trace.createdAt ?? ts, trace }
      this.state.items[idx] = updated
      this.syncBySeqItem(updated) // 该 trace 首达 seq 的索引同步到新 wrapper
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
  private appendRoundText(
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
  private closeStreamingByAgent(agentKey: string): void {
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
   * 完成后关闭该轮流式锚点;主 agent 无工具调用且有正文 → foldRole='final_reply' 打标。
   * thinking 是完整整轮文本(替换式),toolCalls 为模型真实 id,与 tool.result 按 callId 配对。
   */
  private completeMessage(agentKey: string, payload: Record<string, unknown>, ts: number, seq: number | string): void {
    const seqKey = String(seq)
    const thinking = typeof payload?.thinking === 'string' ? payload.thinking : ''
    const text = typeof payload?.text === 'string' ? payload.text : ''
    const toolCalls = readToolCalls(payload?.toolCalls)
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
        // 若该 item 是 rounds.jsonl 折入的合成 final(metadata.synthetic),现在被真实
        // message 覆盖 → 清除 synthetic 标记,使其在缓存覆盖判定中算「真实已拉取」。
        if (message.metadata?.synthetic) {
          message.metadata = { ...message.metadata, synthetic: false }
        }
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
    // 折叠扫描:权威 message 定稿后判定 AI 最终回复——
    // 主 agent 的 assistant 消息,无工具调用且有正文 = 折叠窗口终点(界面上左侧一条)。
    if (agentKey === '' && toolCalls.length === 0 && (message.content ?? '').trim().length > 0) {
      this.markFinalReply(message)
    }
  }

  /**
   * 折叠扫描:把定稿的 AI 最终回复消息对应线程项标记为 foldRole='final_reply'。
   * 打标后同步刷新 bySeq 索引(wrapper 已被替换为新对象)。
   *
   * 定位策略:
   * 1. 优先引用匹配(item.message === message)——实时流式路径(appendRoundText 建锚点后
   *    insertMessage 存同一对象)与回放新建路径(completeMessage 新建对象后 insertMessage 存
   *    同一对象)均保持同一引用,精确无歧义;
   * 2. 兜底按 messageId(形如 `m-${seq}`)匹配,覆盖 state 经序列化/深拷贝还原后引用
   *    丢失的场景。兜底条件带上 agentId(避免不同 agent 同 seq 误配)与 role:'assistant'
   *    (只打 AI 回复,不打 user/tool),保证与 markFinalReply 的调用口径一致。
   * 从尾部向前扫,扫到即停。
   */
  private markFinalReply(message: AgentMessageRecord): void {
    const agentKey = message.agentId ?? ''
    for (let i = this.state.items.length - 1; i >= 0; i--) {
      const item = this.state.items[i]
      if (item.type !== 'agent_message') continue
      // 引用优先:两条写入路径下恒为同一对象。
      if (item.message === message) {
        const updated: TaskThreadItem = { ...item, foldRole: 'final_reply' }
        this.state.items[i] = updated
        this.syncBySeqItem(updated)
        return
      }
      // 兜底:引用失效(如序列化还原)时按 messageId+agentId 定位同一条权威消息。
      if (
        (item.message.agentId ?? '') === agentKey
        && item.message.messageId === message.messageId
        && item.message.role === 'assistant'
      ) {
        const updated: TaskThreadItem = { ...item, foldRole: 'final_reply' }
        this.state.items[i] = updated
        this.syncBySeqItem(updated)
        return
      }
    }
  }
}

/**
 * 精确比较两个 seq(number 或数字字符串,含 Snowflake 大数)。
 * Number() 在 2^53 之上会丢失整数精度(相邻两个大数坍缩为同一 double),
 * 因此这里把 seq 统一按十进制大整数字符串比较:先比符号/位数,再比字典序。
 */
function compareSeq(a: number | string, b: number | string): number {
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
function readToolCalls(value: unknown): LLMToolCall[] {
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

function safeJsonStringify(value: unknown): string {
  if (value == null) return ''
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

function readStr(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() !== '' ? value : undefined
}

/** 窗口上限缺省值(与 worker ContextOverflow.DEFAULT_CONTEXT_WINDOW_TOKENS 一致):数据源未配置时兜底,避免显示 0。 */
const DEFAULT_CONTEXT_WINDOW_TOKENS = 256_000

function readNum(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : undefined
}

/** worker Usage DTO:{inputTokens,outputTokens,totalTokens}(零值视为缺失)。 */
function readUsage(value: unknown): { inputTokens: number; outputTokens: number; totalTokens: number } | null {
  if (!value || typeof value !== 'object') {
    return null
  }
  const v = value as Record<string, unknown>
  const inputTokens = typeof v.inputTokens === 'number' ? v.inputTokens : 0
  const outputTokens = typeof v.outputTokens === 'number' ? v.outputTokens : 0
  const totalTokens = typeof v.totalTokens === 'number' ? v.totalTokens : inputTokens + outputTokens
  if (inputTokens <= 0 && outputTokens <= 0) {
    return null
  }
  return { inputTokens, outputTokens, totalTokens }
}

/** 空状态。 */
export function emptyThreadState(taskId: string): TaskThreadState {
  return { taskId, items: [], agentStates: {} }
}

/**
 * worker agent.status 值 → n 前端 AgentStatus。
 * worker 侧枚举:running / waiting-user / done / failed / stopped
 * (见 Events.AgentStatus;主 agent 终态 done/failed/stopped 由 TaskManager.agentStatusOf 发出)。
 * n 前端枚举:idle / running / waiting-user / completed / stopped / error
 * (与 taskStore.mapWorkerStatus 的映射约定一致:done→completed、failed→error)。
 * 未知/非法值返回 null → 丢弃,不污染状态表。
 */
function mapAgentStatus(value: string): AgentStatus | null {
  switch (value) {
    case 'idle':
    case 'running':
    case 'waiting-user':
    case 'stopped':
      return value
    case 'done':
      return 'completed'
    case 'failed':
      return 'error'
    default:
      return null
  }
}
