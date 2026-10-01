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
 * - user.message           → role:'user' 消息(payload.content + payload.data.rawContent)
 * - thinking / delta       → 当前流式 assistant 消息的 reasoning / content 追加(瞬态,payload.content)
 * - message                → 一轮权威终结:完整 content/data.thinking/data.toolCalls(真实 toolCall id)
 *                           定稿该 agent 的流式消息
 * - usage                  → 主 agent 上下文用量快照(contextUsage,不进线程);主/子统一的
 *                           累计用量/上下文快照同时维护 agentMeta(不进线程)
 * - tool.result            → role:'tool' 消息(TaskChat 按 callId 合并进下发块渲染)
 * - agent.started/done     → 子 agent trace(spawn 生命周期,必带 agentId);同时合并 agentMeta
 *                           (标题/创建时间/收口累计用量)
 * - error                  → trace(带 agentId = 子任务出错;缺省 = 任务出错)
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
  AgentStatus,
  ContextMonitorSnapshot,
  LLMToolCall,
  RoundSummary,
  TaskStatus,
  TaskTraceRecord,
} from '@/types'
import type { TaskAgentLedgerItem } from '@/sdk/task-poll'
import { getHandler, defaultHandler } from './eventRegistry'

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
  /**
   * 子 agent/主 agent 元数据快照表:键语义同 agentStates(主 agent = 空串 '',子 = 子 id)。
   * 基线由 task.agents 台账(seedAgents,open 时一次)灌入,实时流事件
   * (usage/agent.started/agent.done)字段级覆盖;驱动子 agent 胶囊列表/悬停卡片。
   */
  agentMeta: Record<string, AgentMetaSnapshot>
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

/**
 * 子 agent/主 agent 元数据快照(task.agents 台账 + 流事件实时覆盖;键:主 agent='',子=子 id)。
 * 基线由 {@link TaskEventFolder#seedAgents} 灌入(open 时一次),实时 usage/agent.started/
 * agent.done 事件字段级覆盖;驱动子 agent 胶囊列表/悬停卡片(标题 + 累计用量 + 上下文占用)。
 */
export interface AgentMetaSnapshot {
  agentId: string
  title?: string
  createdAt?: number
  model?: string
  inputTokens?: number      // 累计
  outputTokens?: number
  totalTokens?: number      // 累计
  contextUsed?: number      // 最近一轮 prompt tokens
  contextWindow?: number
  updatedAt?: number
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
   *   非轮 item 事件同 seq 已存在 → 忽略(重连重放去重);trace 族事件自身 seq 也登记于此。
   * - 值随 wrapper 替换(如 foldRole 打标、trace 原地 upsert)同步刷新为 items 中的当前对象。
   */
  private bySeq = new Map<string, TaskThreadItem>()


  constructor(public state: TaskThreadState) {
    // 防御:旧 state 形状可能缺 agentMeta(本项目前端不做持久化缓存,理论上无旧数据,
    // 折叠器实例仍可能被灌入外部构造的旧形状 state,这里兜底补空表)。
    if (!state.agentMeta) state.agentMeta = {}
  }

  /**
   * 重置:清空全部状态(items/bySeq/anchors)。
   * resync 时重新从 task.rounds 拉取并 foldRound 前调用,
   * 避免旧 items(编辑前的轮次,seq 已过期)与新轮次混排。
   * 保留 taskId,重置其余字段到空态。
   */
  reset(): void {
    this.state.items = []
    this.state.agentStates = {}
    this.state.agentMeta = {}
    this.state.contextUsage = null
    this.state.taskModel = null
    this.bySeq.clear()
    this.anchors.streaming.clear()
    this.anchors.toolNames.clear()
    this.anchors.subTitles.clear()
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
      const userText = round.userMessage?.content ?? round.user
      const userRaw = (round.userMessage?.data?.rawContent && round.userMessage.data.rawContent.length)
        ? round.userMessage.data.rawContent
        : userText
      // [uref] @文件引用胶囊丢失排查:rounds.jsonl 骨架折入 user 消息的 rawContent 解析
      // (userMessage.data.rawContent 缺失 → 回退 userText 纯文本 → 胶囊丢失)。
      console.debug(
        '[uref] foldRound user 骨架 startSeq=', startSeqKey,
        'userLen=', userText?.length ?? -1,
        'userMessagePresent=', round.userMessage != null,
        'userMessageDataRawGiven=', Boolean(round.userMessage?.data?.rawContent?.length),
        'resolvedRawLen=', userRaw?.length ?? -1,
        'resolvedRawHasToken=', userRaw?.includes('[[[[') ?? false,
      )
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

  /**
   * 灌入 task.agents 台账基线(open 建骨架时调用,实时事件后到可覆盖):
   * 把应答 agents 幂等灌入 state.agentMeta。每项按 agentId 归键(worker 台账只含子
   * agent,主 agent 键 = 空串;防御 legacy meta.agents 混入主 agent 项时归一到 '')。
   * 合并策略 = 字段级(undefined 不覆盖已有值)、updatedAt 取较大者;同一 open 周期内
   * 台账在尾段事件之后读取(天然不旧于已折入数据),实时事件随后仍可继续覆盖。
   * 返回 true 表示状态有变化。
   */
  seedAgents(agents: TaskAgentLedgerItem[], mainAgentId: string): boolean {
    let changed = false
    for (const item of agents) {
      if (!item || !item.agentId) continue
      const key = item.agentId === mainAgentId ? '' : item.agentId
      // 状态兜底:历史任务打开时流事件(agent.status)不随 rounds 骨架折入,agentStates
      // 无该键 → 子 agent 胶囊落回灰色 idle。台账 status 落盘即权威(live 内存实时),
      // 终态(completed/stopped/error)始终覆盖——流推送可能因 DataPusher 与 finish
      // 驱逐的竞态丢失子 agent done 事件,台账是兜底权威;
      // 非终态(running/waiting-user)仅在 agentStates 尚无值时填入(不覆盖流事件实时状态)。
      if (item.status) {
        const mapped = mapAgentStatus(String(item.status))
        if (mapped && (isTerminalAgentStatus(mapped) || this.state.agentStates[key] === undefined)) {
          if (this.state.agentStates[key] !== mapped) {
            this.state.agentStates[key] = mapped
            changed = true
          }
        }
      }
      const usage = readUsage(item.usage)
      const context = item.context
      if (this.mergeAgentMeta(key, {
        agentId: key,
        title: item.title && item.title.length > 0 ? item.title : undefined,
        createdAt: item.createdAt,
        inputTokens: usage?.inputTokens,
        outputTokens: usage?.outputTokens,
        totalTokens: usage?.totalTokens,
        contextUsed: typeof context?.inputTokens === 'number' ? context.inputTokens : undefined,
        contextWindow: typeof context?.contextWindowTokens === 'number' ? context.contextWindowTokens : undefined,
        model: context && typeof context.model === 'string' && context.model.length > 0 ? context.model : undefined,
        updatedAt: item.latestActivity?.updatedAt,
      })) {
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

  /**
   * 子 agent 标题查询(agent 列表展示用):优先流事件 anchors.subTitles(agent.started),
   * 兜底 state.agentMeta 的 title(task.agents 台账 seed;历史任务无 started 重放时命中)。
   */
  resolveAgentTitle(agentId: string): string | undefined {
    return this.anchors.subTitles.get(agentId) ?? this.state.agentMeta[agentId]?.title
  }

  /** agent 状态查询(agent 圆列表用)。key 语义同线程项:主 agent = 空串,子 agent = 子 id。 */
  resolveAgentStatus(agentId: string): AgentStatus {
    return this.state.agentStates[agentId] ?? 'idle'
  }

  // ---- 公共 helper（供 eventRegistry handler 调用） ----

  /** 检查某 seq 是否已折入（去重判定）。 */
  hasSeq(seqKey: string): boolean {
    return this.bySeq.has(seqKey)
  }

  /**
   * 真实 user.message 事件到达但同 seq 已有项时,尝试把「rounds.jsonl 合成骨架」
   * (metadata.synthetic=true 的 user 消息)原地升级为真实 payload(content/rawContent 权威)。
   * <p>背景:loadRoundsIntoFolder(full) 的 reset() 可能清掉订阅后已实时折入的真实事件,
   * 骨架随后重插;若无升级路径,后续权威数据会被 seq 去重永远挡在门外(拉取层 foldWireEvent
   * 对同 seq 一律跳过),坏骨架永久占据该 seq——@文件胶囊即在此丢失(2026-10 排查实锤)。
   * 与 completeMessage 对合成 final 的覆盖口径一致:真实事件清除 synthetic 标记。
   * @return true 表示已升级(状态有变化);false = 无骨架可升级(真重复帧/非 user 项)。
   */
  upgradeSyntheticUser(seqKey: string, message: {
    content: string
    rawContent: string
    agentId: string
    ts: number
  }): boolean {
    const item = this.bySeq.get(seqKey)
    if (!item || item.type !== 'agent_message') return false
    const msg = item.message
    if (msg.role !== 'user' || msg.metadata?.synthetic !== true) return false
    msg.content = message.content
    msg.rawContent = message.rawContent
    msg.createdAt = msg.createdAt || message.ts
    msg.updatedAt = message.ts
    msg.metadata = { ...(msg.metadata ?? {}), synthetic: false }
    return true
  }

  /** 获取工具名（callId → name，由 message 事件的 toolCalls 注册）。 */
  getToolName(callId: string): string {
    return this.anchors.toolNames.get(callId) ?? ''
  }

  /** 设置子 agent 标题（agent.started 事件注入）。 */
  setSubTitle(agentId: string, title: string): void {
    if (agentId) this.anchors.subTitles.set(agentId, title)
  }

  // ---- 内部 ----

  /**
   * agentMeta 幂等合并(字段级:undefined 不覆盖已有值;updatedAt 双方都有时取较大者,
   * 保证 task.agents 台账 seed 不回退流事件已写入的更新时间)。键语义同 agentStates/
   * 线程项:主 agent = 空串,子 agent = 子 id。返回 true 表示该键快照有变化。
   */
  mergeAgentMeta(agentKey: string, patch: Partial<AgentMetaSnapshot>): boolean {
    const prev = this.state.agentMeta[agentKey]
    const next: AgentMetaSnapshot = {
      agentId: patch.agentId ?? prev?.agentId ?? agentKey,
      title: patch.title ?? prev?.title,
      createdAt: patch.createdAt ?? prev?.createdAt,
      model: patch.model ?? prev?.model,
      inputTokens: patch.inputTokens ?? prev?.inputTokens,
      outputTokens: patch.outputTokens ?? prev?.outputTokens,
      totalTokens: patch.totalTokens ?? prev?.totalTokens,
      contextUsed: patch.contextUsed ?? prev?.contextUsed,
      contextWindow: patch.contextWindow ?? prev?.contextWindow,
      updatedAt: prev?.updatedAt != null && patch.updatedAt != null
        ? Math.max(prev.updatedAt, patch.updatedAt)
        : (patch.updatedAt ?? prev?.updatedAt),
    }
    if (sameAgentMeta(prev, next)) return false
    this.state.agentMeta[agentKey] = next
    return true
  }

  /**
   * 插入一条 agent_message 到 items(按原始 seq 精确升序定位;bySeq 键用调用方传入的
   * 原始 seq 字符串 seqKey——Snowflake 大数转 Number 有精度损失,去重键与排序键都必须用原始串)。
   * 用户回合边界关闭该 agent 的流式锚点;折叠扫描:用户消息标 foldRole='user'(折叠窗口起点,右侧一条)。
   */
  insertMessage(agentKey: string, message: AgentMessageRecord, seqKey: string): void {
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

  /** wrapper 被替换(如 foldRole 打标、trace 原地 upsert)后,把 bySeq 同步到 items 中的当前对象。 */
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
   * 完成后关闭该轮流式锚点;主 agent 无工具调用且有正文 → foldRole='final_reply' 打标。
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

/** 窗口上限缺省值(与 worker ContextOverflow.DEFAULT_CONTEXT_WINDOW_TOKENS 一致):数据源未配置时兜底,避免显示 0。 */
export const DEFAULT_CONTEXT_WINDOW_TOKENS = 256_000

export function readNum(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : undefined
}

/** worker Usage DTO:{inputTokens,outputTokens,totalTokens}(零值视为缺失)。 */
export function readUsage(value: unknown): { inputTokens: number; outputTokens: number; totalTokens: number } | null {
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
  return { taskId, items: [], agentStates: {}, agentMeta: {} }
}

/** agentMeta 快照字段级相等判定(mergeAgentMeta 变更检测;undefined 与缺失视为同值)。 */
function sameAgentMeta(a: AgentMetaSnapshot | undefined, b: AgentMetaSnapshot): boolean {
  if (!a) return false
  return a.agentId === b.agentId
    && a.title === b.title
    && a.createdAt === b.createdAt
    && a.model === b.model
    && a.inputTokens === b.inputTokens
    && a.outputTokens === b.outputTokens
    && a.totalTokens === b.totalTokens
    && a.contextUsed === b.contextUsed
    && a.contextWindow === b.contextWindow
    && a.updatedAt === b.updatedAt
}

/**
 * worker 状态值 → n 前端 AgentStatus。覆盖两张词表:
 * - agent.status 事件(Events.AgentStatus):running / waiting-user / done / failed / stopped
 *   (主 agent 终态 done/failed/stopped 由 TaskManager.agentStatusOf 发出);
 * - task.agents 台账 status(SubAgentManager.effectiveStatus):completed / stopped / error /
 *   running / waiting-user(工具契约词表,终态词与前端同名)。
 * n 前端枚举:idle / running / waiting-user / completed / stopped / error
 * (与 taskStore.mapWorkerStatus 的映射约定一致:done→completed、failed→error)。
 * 未知/非法值返回 null → 丢弃,不污染状态表。
 */
export function mapAgentStatus(value: string): AgentStatus | null {
  switch (value) {
    case 'idle':
    case 'running':
    case 'waiting-user':
    case 'stopped':
    case 'completed':
    case 'error':
      return value
    case 'done':
      return 'completed'
    case 'failed':
      return 'error'
    default:
      return null
  }
}

/** 判断 AgentStatus 是否为终态(completed/stopped/error)。供 seedAgents 决定是否覆盖。 */
export function isTerminalAgentStatus(s: AgentStatus): boolean {
  return s === 'completed' || s === 'stopped' || s === 'error'
}
