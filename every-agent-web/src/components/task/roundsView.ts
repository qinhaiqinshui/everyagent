/**
 * 轮次视图纯逻辑(TaskRoundsPanel / RoundDetail 共用的切片/过滤/合成纯函数)。
 *
 * 「任务线程统一条目与画板纯化」步骤 2 特征化测试的可测化改造:以下函数从
 * TaskRoundsPanel.tsx / RoundDetail.tsx **原位提取**(提取不改行为,组件侧改等价委托),
 * 本模块只含纯函数、不 import 任何 React 组件链(组件模块挂着 pluginDispatcher/
 * bridge 等副作用导入,测试无法在 node 环境安全加载),供 TaskRoundsPanel.test.ts
 * 特征化测试直测,锁定切片/过滤现状行为,作为步骤 7「rounds 骨架出画板」重构的行为护栏。
 */
import type { AgentMessageRecord } from '@/types'
import type { RoundSummary } from '@/task/types'
import type { TaskThreadItem } from '@/task/eventFolder'

// ─── 切片 ────────────────────────────────────────────────────────────────────

/** 从 items 切出 [startSeq 之后, endSeq]（含终点、不含起点）的线程项，并剥掉 foldRole。 */
export function sliceRound(items: TaskThreadItem[], round: RoundSummary): TaskThreadItem[] {
  const startIdx = items.findIndex(
    (it) => it.type === 'agent_message' && it.message.messageId === `m-${round.startSeq}`,
  )
  let endIdx = -1
  if (round.endSeq) {
    endIdx = items.findIndex(
      (it) => it.type === 'agent_message' && it.message.messageId === `m-${round.endSeq}`,
    )
  }
  const from = startIdx >= 0 ? startIdx + 1 : 0
  const to = endIdx >= 0 ? endIdx + 1 : items.length
  return items.slice(from, to).map(stripFoldRole)
}

/** 尾轮线程项切片：从 items 中 tailStartSeq 起点开始（含 user 气泡，剥 foldRole 防折叠）。
 * 过滤态：切片后按归属过滤（子 agent 过滤会滤掉 user 气泡，但外层 tailUserItem 恒显，
 * 视觉上 user 气泡不丢）。TaskRoundsPanel 的 tailItems useMemo 等价委托此处（提取不改行为）。 */
export function sliceTailItems(
  items: TaskThreadItem[],
  tailStartSeq: string,
  matches: (item: TaskThreadItem) => boolean,
): TaskThreadItem[] {
  if (!tailStartSeq) return []
  let idx = items.findIndex(
    (it) => it.type === 'agent_message' && it.message.messageId === `m-${tailStartSeq}`,
  )
  if (idx < 0) {
    // 兜底:起点 user.message 缺失时,退化为「第一个排序 seq >= tailStartSeq 的 agent_message」。
    idx = items.findIndex(
      (it) => it.type === 'agent_message' && seqGeq(messageSeqOf(it), tailStartSeq),
    )
  }
  if (idx < 0) return []
  return items.slice(idx).filter(matches).map(stripFoldRole)
}

/** 剥掉尾轮起点 user 气泡：它已由上层 userItem(tailUserItem) 单独渲染，留在 tailItems
 * 里会重复。但只剥「本轮起点那条」(messageId === userItem)，不能只看 role==='user' ——
 * agent 过滤态下起点 user 气泡(主 agent)被 matches 滤出 tailItems，此时 tailItems[0]
 * 可能是子 agent 的首条 user.message(刚启动时给该 agent 的指令)，按 role 误剥就会
 * 把它丢掉(后续续跑的 user.message 不在 [0] 故不受影响、正常显示)。
 * (ec050499 修复;TailRoundView 的 afterUser 等价委托此处,提取不改行为。) */
export function tailItemsAfterUser(
  tailItems: TaskThreadItem[],
  userItem?: TaskThreadItem,
): TaskThreadItem[] {
  return tailItems.length > 0
    && tailItems[0].type === 'agent_message'
    && tailItems[0].message.role === 'user'
    && userItem?.type === 'agent_message'
    && tailItems[0].message.messageId === userItem.message.messageId
      ? tailItems.slice(1)
      : tailItems
}

/** 尾轮 user 之后第一条 agent_message 的排序 seq（backward 首拉 beforeSeq）；无则 null。 */
export function firstTailEventSeq(items: TaskThreadItem[], startSeq: string): string | null {
  const startIdx = items.findIndex(
    (it) => it.type === 'agent_message' && it.message.messageId === `m-${startSeq}`,
  )
  for (let i = startIdx + 1; i < items.length; i++) {
    const it = items[i]
    if (it.type !== 'agent_message') continue
    const mid = it.message.messageId
    if (mid.startsWith('m-')) return mid.slice(2)
  }
  return null
}

/** 剥掉线程项的 foldRole 折叠窗口标记（轮详情/尾轮实时视图要求过程内容不被折叠）。 */
export function stripFoldRole(item: TaskThreadItem): TaskThreadItem {
  return item.type === 'agent_message' && item.foldRole
    ? { ...item, foldRole: undefined }
    : item
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

/** 合成轮 user 消息（rounds.jsonl 的 user 文本 → AgentMessageRecord）。 */
export function syntheticUserRecord(round: RoundSummary): AgentMessageRecord {
  // rawContent 优先取 userMessage.data.rawContent(含 opaque token 串,回放还原 @文件胶囊);
  // 缺失(旧行/纯文本输入)回退 round.user 纯文本摘要——与 eventFolder.foldRound 口径一致。
  // 曾直接用 round.user,闭合轮折叠态渲染丢胶囊(2026-10 排查)。
  const raw = round.userMessage?.data?.rawContent
  return {
    messageId: `m-${round.startSeq}`,
    agentId: '',
    role: 'user',
    content: round.user,
    rawContent: raw && raw.length ? raw : round.user,
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
