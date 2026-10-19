/**
 * agent 台账镜像 store(自 eventFolder 拆出的独立状态;「任务线程统一条目与画板纯化」步骤 6)。
 *
 * 职责边界:eventFolder 只负责把任务流事件折叠成线程项(items);agent 的状态/元数据
 * 镜像(输入框上方 agent 胶囊列表/悬停卡片/上下文用量线的数据源)不进线程、不随线程
 * items 重置,由本 store 自持。挂接方式 = 组合:eventFolder 持有 `ledger` 实例,
 * 事件仍从同一入口 fold() 查表分发,eventRegistry 的台账类 handler(usage/agent.started/
 * agent.done/agent.status/error/cancelled/ask.*)直接改写 `folder.ledger`,台账变更经
 * handler 返回 true 走与 items 折叠同一合并 notify 驱动重渲染(taskStream)。
 *
 * 生命周期:跟任务不跟 items 重置——eventFolder.reset()(resync/编辑重发重拉)只清线程项,
 * **不清台账**(与 slashTokens/mainAgentId 同理):流事件/台账 seed 按字段级合并幂等,
 * 重放自动回填;清了反而让胶囊列表/用量在重置窗口期整体塌掉。
 *
 * 数据来源(两路灌入,后到覆盖先到、字段级合并):
 * - task.agents 台账(seedAgents,open 时一次,taskStream 调用)灌基线;
 * - 流事件实时覆盖:usage(用量/上下文快照)、agent.started(标题/创建时间)、
 *   agent.done(收口用量)、agent.status(权威状态)、error/cancelled/ask.*(状态兜底)。
 */
import type { AgentStatus } from '@/types'
import type { TaskAgentLedgerItem } from './task-poll'

/**
 * 子 agent/主 agent 元数据快照(task.agents 台账 + 流事件实时覆盖;键:主 agent = mainAgentId,子 = 子 id)。
 * 基线由 {@link AgentLedger#seedAgents} 灌入(open 时一次),实时 usage/agent.started/
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

/**
 * worker 状态值 → 前端 AgentStatus。覆盖两张词表:
 * - agent.status 事件(Events.AgentStatus):running / waiting-user / done / failed / stopped
 *   (主 agent 终态 done/failed/stopped 由 TaskManager.agentStatusOf 发出);
 * - task.agents 台账 status(SubAgentManager.effectiveStatus):completed / stopped / error /
 *   running / waiting-user(工具契约词表,终态词与前端同名)。
 * 前端枚举:idle / running / waiting-user / completed / stopped / error
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

/** agent 台账镜像 store(可变实例;由 TaskEventFolder 组合持有,与其同生命周期)。 */
export class AgentLedger {
  /**
   * agent 状态表:agentId(主 agent = mainAgentId,task 层传入的固定 id) → 当前状态。
   * 事件源:worker 的 agent.status(权威,持久);旧磁盘回放无该事件时由
   * agent.started/done/error/cancelled/ask.* 兜底推导。驱动输入框上方 agent 圆列表。
   */
  agentStates: Record<string, AgentStatus> = {}
  /**
   * 子 agent/主 agent 元数据快照表:键语义同 agentStates(主 agent = mainAgentId,子 = 子 id)。
   * 基线由 task.agents 台账(seedAgents,open 时一次)灌入,实时流事件
   * (usage/agent.started/agent.done)字段级覆盖;驱动子 agent 胶囊列表/悬停卡片。
   */
  agentMeta: Record<string, AgentMetaSnapshot> = {}
  /** agentId → 子 agent 标题(agent.started 流事件注入;回放/懒加载重放时重建)。 */
  private subTitles = new Map<string, string>()

  /** 状态写入(eventRegistry 台账类 handler 调用:agent.status/started/done/error/cancelled/ask.*)。 */
  setStatus(agentKey: string, status: AgentStatus): void {
    this.agentStates[agentKey] = status
  }

  /** 设置子 agent 标题锚点(agent.started 事件注入)。 */
  setSubTitle(agentId: string, title: string): void {
    if (agentId) this.subTitles.set(agentId, title)
  }

  /** agent 状态查询(agent 圆列表用)。key 语义:主 agent = mainAgentId,子 agent = 子 id;缺省 idle。 */
  resolveAgentStatus(agentId: string): AgentStatus {
    return this.agentStates[agentId] ?? 'idle'
  }

  /**
   * 子 agent 标题查询(agent 列表展示用):优先流事件 subTitles(agent.started),
   * 兜底 agentMeta 的 title(task.agents 台账 seed;历史任务无 started 重放时命中)。
   */
  resolveAgentTitle(agentId: string): string | undefined {
    return this.subTitles.get(agentId) ?? this.agentMeta[agentId]?.title
  }

  /**
   * agentMeta 幂等合并(字段级:undefined 不覆盖已有值;updatedAt 双方都有时取较大者,
   * 保证 task.agents 台账 seed 不回退流事件已写入的更新时间)。键语义同 agentStates/
   * 线程项:主 agent = mainAgentId,子 agent = 子 id。返回 true 表示该键快照有变化。
   */
  mergeAgentMeta(agentKey: string, patch: Partial<AgentMetaSnapshot>): boolean {
    const prev = this.agentMeta[agentKey]
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
    this.agentMeta[agentKey] = next
    return true
  }

  /**
   * 灌入 task.agents 台账基线(open 建骨架时调用,实时事件后到可覆盖):
   * 把应答 agents 幂等灌入 agentMeta。每项按 agentId 归键(主 agent 条目即 mainAgentId,
   * 子 = 子 id)。合并策略 = 字段级(undefined 不覆盖已有值)、updatedAt 取较大者;同一 open
   * 周期内台账在尾段事件之后读取(天然不旧于已折入数据),实时事件随后仍可继续覆盖。
   * mainAgentId 参数 = task 层传入的主 agent 固定 id,仅用于「主 agent 状态不入台账状态表」
   * 例外判定(折叠器侧 state.mainAgentId 由 taskStream 经 setMainAgentId 同步灌入)。
   * 返回 true 表示状态有变化。
   */
  seedAgents(agents: TaskAgentLedgerItem[], mainAgentId: string): boolean {
    let changed = false
    for (const item of agents) {
      if (!item || !item.agentId) continue
      const key = item.agentId
      // 状态兜底:历史任务打开时流事件(agent.status)不随 rounds 骨架折入,agentStates
      // 无该键 → 子 agent 胶囊落回灰色 idle。台账 status 落盘即权威(live 内存实时),
      // 终态(completed/stopped/error)始终覆盖——流推送可能因 DataPusher 与 finish
      // 驱逐的竞态丢失子 agent done 事件,台账是兜底权威;
      // 非终态(running/waiting-user)仅在 agentStates 尚无值时填入(不覆盖流事件实时状态)。
      //
      // 主 agent 例外:agent 状态是 **per-run()** 的(一条输入一轮,每轮
      // started→…→done,见 worker §7.20.1),队列连着多条输入时台账里主 agent 会是上一轮的
      // completed 而新一轮正在 running。台账快照可能晚于实时事件到达(主 agent 终态会触发
      // 重拉 task.agents),用它覆盖就会把正在跑的主胶囊按回「已完成」直到本轮结束。
      // 主 agent 的状态交给流事件,兜底由 TaskChat 用任务态(entry.status)做——那里已有
      // `states[mainAgentId] ?? entryStatus ?? 'idle'`;台账只为主 agent 提供 title/usage/context 基线。
      if (item.status && key !== mainAgentId) {
        const mapped = mapAgentStatus(String(item.status))
        if (mapped && (isTerminalAgentStatus(mapped) || this.agentStates[key] === undefined)) {
          if (this.agentStates[key] !== mapped) {
            this.agentStates[key] = mapped
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
