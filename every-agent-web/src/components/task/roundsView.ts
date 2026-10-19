/**
 * 轮次视图纯逻辑(TaskRoundsPanel / RoundDetail 共用的切片/过滤/合成纯函数)。
 *
 * 步骤 2 特征化测试的可测化提取(从 TaskRoundsPanel.tsx / RoundDetail.tsx 原位提取,
 * 组件侧等价委托) + 步骤 7「rounds 骨架出画板」后的口径:切片一律按 **seq 区间比较**
 * (`startSeq < seq <= endSeq`,BigInt 精确)——rounds 快照不再折入 items(画板 = 纯 wire
 * 折叠产物,§8.3),`m-${startSeq}` 起点 user / `m-${endSeq}` 终点 final 端点锚点条目
 * 可能不在 items,findIndex 端点定位(其 from=0/to=length 兜底)在 items 含其他轮条目时
 * 会误切,已弃用。本模块只含纯函数、不 import 任何 React 组件链(组件模块挂着
 * pluginDispatcher/bridge 等副作用导入,测试无法在 node 环境安全加载),供
 * TaskRoundsPanel.test.ts 特征化测试直测,锁定切片/过滤行为。
 */
import type { AgentMessageRecord } from '@/types'
import type { RoundSummary, UserMessagePayload } from '@/task/types'
import type { TaskThreadItem } from '@/task/eventFolder'
import { compareSeq } from '@/task/eventFolder'

// ─── 切片 ────────────────────────────────────────────────────────────────────

/**
 * 从 items 切出 (startSeq, endSeq] 的线程项(原对象直通;含终点 final、不含起点 user)。
 * 按 seq 区间比较过滤,不依赖端点条目在 items 在场(骨架出画板后端点锚点可能不在);
 * endSeq 为空串(未闭合轮)时上界为 ∞(切到 items 末尾)。task_trace 按其 traceId
 * (`t-${seq}` 前缀)反推 seq 参与区间判定(步骤 8 统一五字段前的过渡口径)。
 */
export function sliceRound(items: TaskThreadItem[], round: RoundSummary): TaskThreadItem[] {
  const startSeq = String(round.startSeq)
  const endSeq = round.endSeq ? String(round.endSeq) : null
  return items.filter((it) => inSeqInterval(itemSeqOf(it), startSeq, endSeq))
}

/**
 * 尾轮线程项切片:(tailStartSeq, ∞) 的全部线程项(含 task_trace),切片后按归属过滤。
 * 起点 user 气泡按区间**排除在外**——它由上层从 rounds 快照合成单独渲染
 * (TaskRoundsPanel 的 tailUserMessage),留在切片里会重复;起点之后的子 agent 首条
 * user.message(seq 严格大于起点)天然保留(ec050499 回归口径)。
 * TaskRoundsPanel 的 tailItems useMemo 等价委托此处。
 */
export function sliceTailItems(
  items: TaskThreadItem[],
  tailStartSeq: string,
  matches: (item: TaskThreadItem) => boolean,
): TaskThreadItem[] {
  if (!tailStartSeq) return []
  return items.filter(
    (it) => inSeqInterval(itemSeqOf(it), tailStartSeq, null) && matches(it),
  )
}

/**
 * 尾轮 user 之后第一条 agent_message 的排序 seq(backward 首拉 beforeSeq);无则 null。
 * 按 seq 区间比较取 (startSeq, ∞) 内首个 agent_message——不依赖起点 user 条目在 items
 * 在场(骨架出画板后常态缺失),也不会误命中更早轮次的条目。
 */
export function firstTailEventSeq(items: TaskThreadItem[], startSeq: string): string | null {
  for (const it of items) {
    if (it.type !== 'agent_message') continue
    const seq = messageSeqOf(it)
    if (seq && compareSeq(seq, startSeq) > 0) return seq
  }
  return null
}

// ─── agent 过滤 ──────────────────────────────────────────────────────────────

/** 线程项归属 agent：主 agent 消息与无主 trace 的 agentId 为空串（缺省=主线程），
 * 归一到 mainAgentId 后再比较；task_trace.agentId 字段语义即「用于前端按 agent 过滤」。 */
export function itemAgentKey(item: TaskThreadItem, mainAgentId: string): string {
  if (item.type === 'agent_message') return item.message.agentId || mainAgentId
  return item.trace.agentId || mainAgentId
}

/** agent 过滤谓词（「只看该 agent」）：'' = 不过滤原样全过；非空 = 仅放行归属该 agent 的项。
 * TaskRoundsPanel 的 matches 回调等价委托此处（提取不改行为）。 */
export function matchesAgentFilter(
  item: TaskThreadItem,
  filterAgentId: string,
  mainAgentId: string,
): boolean {
  return !filterAgentId || itemAgentKey(item, mainAgentId) === filterAgentId
}

// ─── seq 工具 ────────────────────────────────────────────────────────────────

/** 从线程项提取精确排序 seq 字符串（agent_message 的 messageId 形如 `m-${seq}`；task_trace 无 messageId）。 */
export function messageSeqOf(item: TaskThreadItem): string {
  if (item.type !== 'agent_message') return ''
  const mid = item.message.messageId
  return mid.startsWith('m-') ? mid.slice(2) : ''
}

/**
 * 线程项的排序 seq(agent_message 剥 `m-` 前缀;task_trace 剥 `t-` 前缀;无前缀返回空串)。
 * 与 eventFolder.seqOfItem 同口径;空串在区间判定中恒不命中(trace 族必有 `t-` 前缀,
 * 空串只出现在防御分支)。步骤 8 五字段统一后 seq 为一等字段,此反推口径退役。
 */
function itemSeqOf(item: TaskThreadItem): string {
  if (item.type === 'agent_message') return messageSeqOf(item)
  const tid = item.trace.traceId
  return tid.startsWith('t-') ? tid.slice(2) : ''
}

/** seq 区间判定:startSeq < seq <= endSeq(endSeq null = 上界 ∞);seq 空串恒不命中。 */
function inSeqInterval(seq: string, startSeq: string, endSeq: string | null): boolean {
  if (!seq) return false
  if (compareSeq(seq, startSeq) <= 0) return false
  if (endSeq != null && compareSeq(seq, endSeq) > 0) return false
  return true
}

/** 十进制大整数字符串精确比较（雪花 ID 超 Number 安全整数，不能走 Number 近似）。 */
export function seqGeq(a: string, b: string): boolean {
  if (!a) return false
  try {
    return BigInt(a) >= BigInt(b)
  } catch {
    return a >= b
  }
}

// ─── 合成记录 ────────────────────────────────────────────────────────────────

/** 合成轮 user 消息(rounds 快照 → AgentMessageRecord;渲染投影,不进 items)。
 * 参数为结构化最小形状:闭合轮传 RoundSummary,运行中尾轮传 open 轮快照
 * ({startSeq, user, userMessage})——两处 user 气泡同一口径(步骤 7 骨架出画板,
 * 尾轮不再依赖 items 含起点行)。 */
export function syntheticUserRecord(round: {
  startSeq: string
  user?: string
  userMessage?: UserMessagePayload
}): AgentMessageRecord {
  // rawContent 优先取 userMessage.data.rawContent(含 opaque token 串,回放还原 @文件胶囊);
  // 缺失(旧行/纯文本输入)回退 user 纯文本摘要——与 wire user.message 事件的口径一致。
  // 曾直接用 round.user,闭合轮折叠态渲染丢胶囊(2026-10 排查)。
  const user = round.user ?? ''
  const raw = round.userMessage?.data?.rawContent
  return {
    messageId: `m-${round.startSeq}`,
    agentId: '',
    role: 'user',
    content: user,
    rawContent: raw && raw.length ? raw : user,
    historyMode: 'thread_only',
    createdAt: 0,
    updatedAt: 0,
    sequence: approximateSeq(round.startSeq),
  }
}

/** 合成轮最终回复消息（仅闭合轮调用）。 */
export function syntheticFinalRecord(round: RoundSummary): AgentMessageRecord {
  return {
    messageId: `m-${round.endSeq}`,
    agentId: '',
    role: 'assistant',
    content: round.finalReply,
    historyMode: 'thread_only',
    createdAt: 0,
    updatedAt: 0,
    sequence: approximateSeq(round.endSeq),
  }
}

/** seq 字符串 → Number（雪花大数精度近似，仅消息展示序号用，不作去重/排序键）。 */
function approximateSeq(seq: string): number {
  const value = Number(seq)
  return Number.isFinite(value) ? value : 0
}

// ─── 轮次筛选 ────────────────────────────────────────────────────────────────

/** 已闭合轮（endSeq 非空）。TaskRoundsPanel 的 closedRounds useMemo 等价委托此处（提取不改行为）。 */
export function closedRoundsOf(rounds: RoundSummary[]): RoundSummary[] {
  return rounds.filter((r) => r.endSeq !== '')
}

/** 终态遗留的未闭合尾轮（open 为 null 且末行 endSeq=''；live 或空列表为 null）。
 * TaskRoundsPanel 的 terminalTail useMemo 等价委托此处（提取不改行为）。 */
export function terminalTailOf(rounds: RoundSummary[], live: boolean): RoundSummary | null {
  if (live || rounds.length === 0) return null
  const last = rounds[rounds.length - 1]
  return last.endSeq === '' ? last : null
}
