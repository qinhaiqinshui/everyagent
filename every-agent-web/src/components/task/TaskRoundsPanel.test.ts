/**
 * TaskRoundsPanel / RoundDetail 轮次切片与过滤纯逻辑特征化测试
 * (Phase 0 行为护栏,计划「任务线程统一条目与画板纯化」步骤 2)。
 *
 * 目的:锁定轮次视图纯函数的**现状行为**——闭合轮切片 (startSeq, endSeq](startIdx
 * 找不到 from=0 兜底 / endSeq 找不到切到末尾)、尾轮切片(起点 user.message 定位 +
 * seq 大数兜底)、agent 过滤键归一与谓词交互、尾轮起点 user 气泡剥离判定(ec050499
 * 「按 agent 过滤时尾轮漏掉子 agent 首条 user.message」回归)、firstTailEventSeq
 * 兜底路径、合成 user 记录、闭合轮/终态尾轮筛选,为步骤 7「rounds 骨架出画板」
 * (切片改 seq 区间比较、findIndex 兜底删除)提供行为基线——先锁后删,重构时同步
 * 更新断言而非绕过。
 *
 * 纪律:只断言现状,不改源码;被测函数经 roundsView.ts 从组件模块**原位提取**
 * (提取不改行为,组件侧等价委托),测试不 import 任何 React 组件链(组件模块挂着
 * pluginDispatcher/bridge 等副作用导入,node 环境无法安全加载)。
 */
import { describe, it, expect } from 'vitest'
import { compareSeq } from '@/task/eventFolder'
import type { TaskThreadItem } from '@/task/eventFolder'
import type { RoundSummary } from '@/task/types'
import type { AgentMessageRecord } from '@/types'
import {
  closedRoundsOf,
  firstTailEventSeq,
  itemAgentKey,
  matchesAgentFilter,
  messageSeqOf,
  seqGeq,
  sliceRound,
  sliceTailItems,
  stripFoldRole,
  syntheticUserRecord,
  tailItemsAfterUser,
  terminalTailOf,
} from './roundsView'

// Snowflake 形态 seq(19 位十进制,> 2^53):Number() 会把仅末位不同的两个大数坍缩为
// 同一 double(与 eventFolder.test.ts 同款实证),用于验证 seq 比较走精确路径。
const SEQ_50 = '7380000000000000050'
const SEQ_60 = '7380000000000000060'
const SEQ_80 = '7380000000000000080'
const SEQ_100 = '7380000000000000100'
const SEQ_110 = '7380000000000000110'
const SEQ_150 = '7380000000000000150'
const SEQ_200 = '7380000000000000200'
const SEQ_500 = '7380000000000000500'

const MAIN = 'agent-main-001'
const SUB = 'agent-sub-001'

function msgItem(
  seq: string,
  overrides: Partial<AgentMessageRecord> = {},
  foldRole?: 'user' | 'final_reply',
): TaskThreadItem {
  const message: AgentMessageRecord = {
    messageId: `m-${seq}`,
    agentId: MAIN,
    role: 'assistant',
    content: '',
    historyMode: 'thread_only',
    createdAt: 0,
    updatedAt: 0,
    sequence: Number(seq),
    ...overrides,
  }
  return { type: 'agent_message', createdAt: 0, message, foldRole }
}

function traceItem(seq: string, agentId?: string): TaskThreadItem {
  return {
    type: 'task_trace',
    createdAt: 0,
    trace: { traceId: `t-${seq}`, taskId: 'task-1', agentId, kind: 'error', title: 'trace', createdAt: 0 },
  }
}

/** items 的 id 序列(agent_message 取 messageId,task_trace 取 traceId)。 */
function ids(items: TaskThreadItem[]): string[] {
  return items.map((it) => (it.type === 'agent_message' ? it.message.messageId : it.trace.traceId))
}

function asMsg(it: TaskThreadItem): AgentMessageRecord {
  if (it.type !== 'agent_message') throw new Error(`期望 agent_message,实际 ${it.type}`)
  return it.message
}

function foldRoleOf(it: TaskThreadItem): 'user' | 'final_reply' | undefined {
  return it.type === 'agent_message' ? it.foldRole : undefined
}

function round(overrides: Partial<RoundSummary> = {}): RoundSummary {
  return {
    roundId: 'round-1',
    index: 1,
    startSeq: SEQ_100,
    endSeq: SEQ_200,
    user: '用户输入',
    finalReply: '最终回复',
    subs: [],
    ...overrides,
  }
}

/** 与 TaskRoundsPanel 组件 matches 回调同式的谓词构造(matchesAgentFilter 等价委托)。 */
function matchesOf(filterAgentId: string, mainAgentId: string) {
  return (it: TaskThreadItem) => matchesAgentFilter(it, filterAgentId, mainAgentId)
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. sliceRound 闭合轮切片(RoundDetail 展开态)
// ─────────────────────────────────────────────────────────────────────────────

describe('sliceRound 闭合轮切片 (startSeq, endSeq]', () => {
  it('含终点不含起点:剥掉起点 user,终点 final 保留;返回项剥 foldRole,trace 原对象直通', () => {
    const items: TaskThreadItem[] = [
      msgItem(SEQ_100, { role: 'user', content: '用户输入' }, 'user'),
      msgItem(SEQ_110, { content: '过程回复' }),
      traceItem(SEQ_150),
      msgItem(SEQ_200, { content: '最终回复' }, 'final_reply'),
    ]
    const slice = sliceRound(items, round())
    expect(ids(slice)).toEqual([`m-${SEQ_110}`, `t-${SEQ_150}`, `m-${SEQ_200}`])
    for (const it of slice) expect(foldRoleOf(it)).toBeUndefined()
    // trace 项不经克隆(map 直通)
    expect(slice[1]).toBe(items[2])
    // 纯函数:入参数组与 foldRole 标记不被修改
    expect(items).toHaveLength(4)
    expect(foldRoleOf(items[0])).toBe('user')
    expect(foldRoleOf(items[3])).toBe('final_reply')
  })

  it('startIdx 找不到(起点 user.message 不在 items)兜底 from=0:连同其他轮已加载条目一并切进', () => {
    const items: TaskThreadItem[] = [
      msgItem(SEQ_50, { content: '上一轮 final' }, 'final_reply'),
      msgItem(SEQ_110, { content: '过程回复' }),
      msgItem(SEQ_200, { content: '最终回复' }, 'final_reply'),
    ]
    // 现状:from=0 兜底会把上一轮条目(m-50)误切进来——步骤 7 改按 seq 区间比较的动因,此处锁定
    expect(ids(sliceRound(items, round()))).toEqual([`m-${SEQ_50}`, `m-${SEQ_110}`, `m-${SEQ_200}`])
  })

  it('endSeq 找不到(items 未含终点条目)切到 items 末尾', () => {
    const items: TaskThreadItem[] = [
      msgItem(SEQ_100, { role: 'user' }, 'user'),
      msgItem(SEQ_110),
      msgItem(SEQ_150),
    ]
    expect(ids(sliceRound(items, round({ endSeq: SEQ_500 })))).toEqual([`m-${SEQ_110}`, `m-${SEQ_150}`])
  })

  it('endSeq 为空串(未闭合轮)同样切到 items 末尾', () => {
    const items: TaskThreadItem[] = [msgItem(SEQ_100, { role: 'user' }, 'user'), msgItem(SEQ_110)]
    expect(ids(sliceRound(items, round({ endSeq: '' })))).toEqual([`m-${SEQ_110}`])
  })

  it('items 为空返回空数组', () => {
    expect(sliceRound([], round())).toEqual([])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 2. stripFoldRole 剥折叠窗口标记
// ─────────────────────────────────────────────────────────────────────────────

describe('stripFoldRole 剥 foldRole', () => {
  it('agent_message 带标记 → 克隆剥标(message 对象保留);无标记/trace → 原对象直通', () => {
    const marked = msgItem(SEQ_100, { role: 'user' }, 'user')
    const stripped = stripFoldRole(marked)
    expect(stripped).not.toBe(marked)
    expect(foldRoleOf(stripped)).toBeUndefined()
    expect(asMsg(stripped)).toBe(asMsg(marked))
    const unmarked = msgItem(SEQ_110)
    expect(stripFoldRole(unmarked)).toBe(unmarked)
    const trace = traceItem(SEQ_150)
    expect(stripFoldRole(trace)).toBe(trace)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 3. agent 过滤(itemAgentKey 归一 + matches 谓词交互)
// ─────────────────────────────────────────────────────────────────────────────

describe('agent 过滤(itemAgentKey 归一 + matches 谓词交互)', () => {
  it('itemAgentKey:agent_message 取 message.agentId(缺失归一 mainAgentId);task_trace 取 trace.agentId(缺失同归一)', () => {
    expect(itemAgentKey(msgItem(SEQ_100, { agentId: SUB }), MAIN)).toBe(SUB)
    expect(itemAgentKey(msgItem(SEQ_100, { agentId: '' }), MAIN)).toBe(MAIN)
    expect(itemAgentKey(traceItem(SEQ_110, SUB), MAIN)).toBe(SUB)
    expect(itemAgentKey(traceItem(SEQ_110, undefined), MAIN)).toBe(MAIN)
    // mainAgentId 未灌入(open 极早期):归一为空串
    expect(itemAgentKey(msgItem(SEQ_100, { agentId: '' }), '')).toBe('')
  })

  it("filterAgentId='' 全过:主/子消息与无主 trace 均放行", () => {
    const items = [
      msgItem(SEQ_100, { agentId: '', role: 'user' }),
      msgItem(SEQ_110, { agentId: SUB }),
      traceItem(SEQ_150, undefined),
    ]
    expect(items.every(matchesOf('', MAIN))).toBe(true)
  })

  it('过滤子 agent:主 agent 项被滤掉,子 agent 消息/trace 放行(反向过滤主 agent 亦然)', () => {
    const items = [
      msgItem(SEQ_100, { agentId: '', role: 'user' }),    // 归一 MAIN
      msgItem(SEQ_110, { agentId: MAIN }),
      msgItem(SEQ_150, { agentId: SUB, role: 'user' }),   // 子 agent 首条 user.message
      traceItem(SEQ_200, SUB),
    ]
    expect(items.map(matchesOf(SUB, MAIN))).toEqual([false, false, true, true])
    expect(items.map(matchesOf(MAIN, MAIN))).toEqual([true, true, false, false])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 4. tailItemsAfterUser 尾轮起点 user 气泡剥离(ec050499 回归)
// ─────────────────────────────────────────────────────────────────────────────

describe('tailItemsAfterUser 尾轮起点 user 气泡剥离(ec050499 回归)', () => {
  const roundStartUser = msgItem(SEQ_100, { role: 'user' }, 'user')

  it('tailItems[0] 确为本轮起点 user 气泡(messageId 相等)才剥', () => {
    const tail = [roundStartUser, msgItem(SEQ_110), msgItem(SEQ_150)]
    expect(ids(tailItemsAfterUser(tail, roundStartUser))).toEqual([`m-${SEQ_110}`, `m-${SEQ_150}`])
  })

  it('回归:过滤态下子 agent 首条 user.message 落在 tailItems[0] 不被误剥(role 相同但 messageId 不同)', () => {
    const subFirstUser = msgItem(SEQ_110, { agentId: SUB, role: 'user', content: '给子 agent 的指令' })
    const tail = [subFirstUser, msgItem(SEQ_150, { agentId: SUB })]
    // 修复前(ec050499)只看 role==='user' 会把 subFirstUser 误剥丢失
    expect(ids(tailItemsAfterUser(tail, roundStartUser))).toEqual([`m-${SEQ_110}`, `m-${SEQ_150}`])
  })

  it('userItem 缺失(起点行不在 items)时 tailItems[0] 即便 role=user 也不剥', () => {
    const tail = [msgItem(SEQ_110, { agentId: SUB, role: 'user' }), msgItem(SEQ_150)]
    expect(tailItemsAfterUser(tail, undefined)).toBe(tail)
  })

  it('userItem 为 task_trace 形状时不剥(防御分支)', () => {
    const tail = [roundStartUser, msgItem(SEQ_110)]
    expect(tailItemsAfterUser(tail, traceItem(SEQ_100))).toBe(tail)
  })

  it('tailItems[0] 为 assistant(userItem 在场)不剥;空数组原样返回', () => {
    const tail = [msgItem(SEQ_110), msgItem(SEQ_150)]
    expect(tailItemsAfterUser(tail, roundStartUser)).toBe(tail)
    expect(tailItemsAfterUser([], roundStartUser)).toEqual([])
  })

  it('端到端管线:无过滤剥起点 user;过滤子 agent 后起点 user 已被滤出、子 agent 首条指令保留', () => {
    const items: TaskThreadItem[] = [
      msgItem(SEQ_50, { content: '上一轮' }, 'final_reply'),     // 上一轮尾部(items 含其他轮)
      msgItem(SEQ_100, { role: 'user', agentId: MAIN }, 'user'), // 尾轮起点 user
      msgItem(SEQ_110, { agentId: SUB, role: 'user', content: '子指令' }),
      msgItem(SEQ_150, { agentId: SUB, content: '子回复' }),
      msgItem(SEQ_200, { agentId: MAIN, content: '主回复' }, 'final_reply'),
    ]
    const tailUser = items[1]
    // 无过滤:切片含起点 user → afterUser 剥掉,其余保留
    const plainTail = sliceTailItems(items, SEQ_100, matchesOf('', MAIN))
    expect(ids(plainTail)).toEqual([`m-${SEQ_100}`, `m-${SEQ_110}`, `m-${SEQ_150}`, `m-${SEQ_200}`])
    expect(ids(tailItemsAfterUser(plainTail, tailUser))).toEqual([`m-${SEQ_110}`, `m-${SEQ_150}`, `m-${SEQ_200}`])
    // 过滤子 agent:起点 user(主)被滤出,tailItems[0] = 子 agent 首条 user.message → 不被误剥
    const subTail = sliceTailItems(items, SEQ_100, matchesOf(SUB, MAIN))
    expect(ids(subTail)).toEqual([`m-${SEQ_110}`, `m-${SEQ_150}`])
    expect(ids(tailItemsAfterUser(subTail, tailUser))).toEqual([`m-${SEQ_110}`, `m-${SEQ_150}`])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 5. sliceTailItems 尾轮切片(TaskRoundsPanel tailItems)
// ─────────────────────────────────────────────────────────────────────────────

describe('sliceTailItems 尾轮切片', () => {
  it('起点 user.message(m-${tailStartSeq})定位:从起点(含)切到 items 末尾并剥 foldRole', () => {
    const items: TaskThreadItem[] = [
      msgItem(SEQ_50, { content: '上一轮 final' }, 'final_reply'), // 上一轮条目不进尾轮切片
      msgItem(SEQ_100, { role: 'user' }, 'user'),
      msgItem(SEQ_110),
      msgItem(SEQ_150, { content: '回复' }, 'final_reply'),
    ]
    const tail = sliceTailItems(items, SEQ_100, matchesOf('', MAIN))
    expect(ids(tail)).toEqual([`m-${SEQ_100}`, `m-${SEQ_110}`, `m-${SEQ_150}`])
    for (const it of tail) expect(foldRoleOf(it)).toBeUndefined()
  })

  it('起点缺失兜底:第一个排序 seq >= tailStartSeq 的 agent_message(trace 不命中兜底)', () => {
    const items: TaskThreadItem[] = [
      msgItem(SEQ_50),    // 上一轮条目:seq < start,不命中
      traceItem(SEQ_80),  // trace:messageSeqOf='' → seqGeq 假,不命中
      msgItem(SEQ_110),   // 第一个 seq >= start 的 agent_message
      traceItem(SEQ_150),
      msgItem(SEQ_200),
    ]
    expect(ids(sliceTailItems(items, SEQ_100, matchesOf('', MAIN))))
      .toEqual([`m-${SEQ_110}`, `t-${SEQ_150}`, `m-${SEQ_200}`])
  })

  it('兜底走 BigInt 精确比较:Number 坍缩的相邻大数不会被误定位', () => {
    expect(Number(SEQ_100)).toBe(Number(SEQ_200)) // 前置:Number 已坍缩,比较必须走 BigInt
    const items: TaskThreadItem[] = [msgItem(SEQ_100), msgItem(SEQ_200)]
    // tailStartSeq=SEQ_150:BigInt 判 SEQ_100 < 150 < SEQ_200,定位到 m-200(Number 比较会误定位 m-100)
    expect(ids(sliceTailItems(items, SEQ_150, matchesOf('', MAIN)))).toEqual([`m-${SEQ_200}`])
  })

  it('切片后按 matches 过滤再剥 foldRole(过滤掉的项不占位)', () => {
    const items: TaskThreadItem[] = [
      msgItem(SEQ_100, { role: 'user', agentId: MAIN }, 'user'),
      msgItem(SEQ_110, { agentId: SUB }, 'final_reply'),
      msgItem(SEQ_150, { agentId: MAIN }),
    ]
    const tail = sliceTailItems(items, SEQ_100, matchesOf(SUB, MAIN))
    expect(ids(tail)).toEqual([`m-${SEQ_110}`])
    expect(foldRoleOf(tail[0])).toBeUndefined()
  })

  it('tailStartSeq 空串返回空数组', () => {
    expect(sliceTailItems([msgItem(SEQ_100)], '', matchesOf('', MAIN))).toEqual([])
  })

  it('起点与兜底均无命中(items 全部 seq 更小)返回空数组', () => {
    expect(sliceTailItems([msgItem(SEQ_50), traceItem(SEQ_60)], SEQ_100, matchesOf('', MAIN))).toEqual([])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 6. firstTailEventSeq 尾轮首过程事件 seq(backward 首拉 beforeSeq)
// ─────────────────────────────────────────────────────────────────────────────

describe('firstTailEventSeq 尾轮 user 之后第一条 agent_message 的 seq', () => {
  it('跳过起点 user 与 trace,返回其后第一条 agent_message 的 seq 字符串', () => {
    const items: TaskThreadItem[] = [
      msgItem(SEQ_50),
      msgItem(SEQ_100, { role: 'user' }, 'user'),
      traceItem(SEQ_110),
      msgItem(SEQ_150),
    ]
    expect(firstTailEventSeq(items, SEQ_100)).toBe(SEQ_150)
  })

  it('startIdx=-1(起点 user 不在 items)时从 0 扫:返回第一条 agent_message 的 seq', () => {
    // 现状:从 0 扫会命中更早轮次的 agent_message(如上一轮 final),此处锁定
    const items: TaskThreadItem[] = [msgItem(SEQ_50), traceItem(SEQ_80), msgItem(SEQ_150)]
    expect(firstTailEventSeq(items, SEQ_100)).toBe(SEQ_50)
  })

  it('起点之后无 agent_message(仅 trace/仅起点/空 items)返回 null', () => {
    expect(firstTailEventSeq([msgItem(SEQ_100, { role: 'user' }), traceItem(SEQ_110)], SEQ_100)).toBeNull()
    expect(firstTailEventSeq([msgItem(SEQ_100, { role: 'user' })], SEQ_100)).toBeNull()
    expect(firstTailEventSeq([], SEQ_100)).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 7. seqGeq 十进制大数比较 + messageSeqOf 提取
// ─────────────────────────────────────────────────────────────────────────────

describe('seqGeq 十进制大整数字符串比较', () => {
  it('BigInt 精确比较:Number 坍缩的相邻大数仍正确分大小', () => {
    expect(Number(SEQ_100)).toBe(Number(SEQ_200)) // 前置:Number 已坍缩,比较必须走 BigInt
    expect(seqGeq(SEQ_200, SEQ_100)).toBe(true)
    expect(seqGeq(SEQ_100, SEQ_200)).toBe(false)
    expect(seqGeq(SEQ_100, SEQ_100)).toBe(true)
  })

  it('a 为空串恒 false(task_trace 无 seq 的防御分支)', () => {
    expect(seqGeq('', SEQ_100)).toBe(false)
  })

  it('非法输入回退字符串字典序比较(BigInt 抛错时)', () => {
    expect(seqGeq('zzz', 'abc')).toBe(true) // BigInt 抛错 → 'zzz' >= 'abc'
    expect(seqGeq('abc', 'zzz')).toBe(false)
    expect(seqGeq('12', '1x')).toBe(false)  // '12' vs '1x':'2' < 'x'
  })

  it('与 eventFolder.compareSeq 口径对齐(有效十进制串,含负数)', () => {
    const pairs: Array<[string, string]> = [
      [SEQ_100, SEQ_150], [SEQ_200, SEQ_100], ['5', '10'], ['10', '10'],
      ['-5', '3'], ['3', '-5'], ['-5', '-5'],
    ]
    for (const [a, b] of pairs) {
      expect(seqGeq(a, b)).toBe(compareSeq(a, b) >= 0)
    }
  })
})

describe('messageSeqOf 线程项 seq 提取', () => {
  it('agent_message 剥 m- 前缀取原始 seq 串;无 m- 前缀/trace 返回空串', () => {
    expect(messageSeqOf(msgItem(SEQ_100))).toBe(SEQ_100)
    expect(messageSeqOf(msgItem(SEQ_100, { messageId: 'x-1' }))).toBe('')
    expect(messageSeqOf(traceItem(SEQ_110))).toBe('')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 8. syntheticUserRecord 合成轮 user 消息(rounds.jsonl → AgentMessageRecord)
// ─────────────────────────────────────────────────────────────────────────────

describe('syntheticUserRecord 合成轮 user 消息', () => {
  it('userMessage.data.rawContent 优先;messageId=m-${startSeq}、agentId 为空串、role=user', () => {
    const rec = syntheticUserRecord(round({
      user: '明文摘要',
      userMessage: { content: '完整明文', data: { rawContent: '原始输入(token 串)' } },
    }))
    expect(rec.messageId).toBe(`m-${SEQ_100}`)
    expect(rec.agentId).toBe('')
    expect(rec.role).toBe('user')
    // 现状口径:content 恒取 round.user(不取 userMessage.content),仅 rawContent 优先——锁定
    expect(rec.content).toBe('明文摘要')
    expect(rec.rawContent).toBe('原始输入(token 串)')
    expect(rec.historyMode).toBe('thread_only')
    expect(rec.createdAt).toBe(0)
    expect(rec.updatedAt).toBe(0)
    expect(rec.sequence).toBe(Number(SEQ_100))
  })

  it('rawContent 缺失(无 userMessage)或空串回退 round.user', () => {
    expect(syntheticUserRecord(round({ user: '纯文本' })).rawContent).toBe('纯文本')
    expect(syntheticUserRecord(round({ user: '纯文本', userMessage: { content: 'c' } })).rawContent).toBe('纯文本')
    expect(syntheticUserRecord(round({
      user: '空串回退',
      userMessage: { content: 'c', data: { rawContent: '' } },
    })).rawContent).toBe('空串回退')
  })

  it('sequence 为 Number 近似(雪花大数坍缩值;非数字 seq → 0)', () => {
    expect(syntheticUserRecord(round({ startSeq: SEQ_100 })).sequence).toBe(Number(SEQ_100))
    expect(syntheticUserRecord(round({ startSeq: '42' })).sequence).toBe(42)
    expect(syntheticUserRecord(round({ startSeq: 'abc' })).sequence).toBe(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 9. 闭合轮/终态尾轮筛选(纯数据面)
// ─────────────────────────────────────────────────────────────────────────────

describe('闭合轮与终态尾轮筛选', () => {
  const closed1 = round({ roundId: 'r1', index: 1, startSeq: SEQ_50, endSeq: SEQ_100 })
  const closed2 = round({ roundId: 'r2', index: 2, startSeq: SEQ_110, endSeq: SEQ_150 })
  const openTail = round({ roundId: 'r3', index: 3, startSeq: SEQ_200, endSeq: '' })

  it('closedRoundsOf 只留 endSeq 非空的行', () => {
    expect(closedRoundsOf([closed1, closed2, openTail]).map((r) => r.roundId)).toEqual(['r1', 'r2'])
    expect(closedRoundsOf([])).toEqual([])
  })

  it('terminalTailOf:非 live 且末行 endSeq 为空 → 该末行', () => {
    expect(terminalTailOf([closed1, closed2, openTail], false)).toBe(openTail)
  })

  it('terminalTailOf:live / 空列表 / 末行已闭合 → null', () => {
    expect(terminalTailOf([closed1, closed2, openTail], true)).toBeNull()
    expect(terminalTailOf([], false)).toBeNull()
    expect(terminalTailOf([closed1, closed2], false)).toBeNull()
  })
})
