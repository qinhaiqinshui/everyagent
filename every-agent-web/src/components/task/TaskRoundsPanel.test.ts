/**
 * TaskRoundsPanel / RoundDetail 轮次切片与过滤纯逻辑特征化测试
 * (Phase 0 行为护栏,计划「任务线程统一条目与画板纯化」步骤 2;步骤 7 起为
 * seq 区间比较口径;步骤 8 起条目为五字段 ThreadItem——seq 即 id,切片/游标
 * 直接用 item.seq,断言里 `m-`/`t-` 前缀 id 退役)。
 *
 * 目的:锁定轮次视图纯函数的行为——闭合轮切片 (startSeq, endSeq](按 seq 区间
 * 比较过滤,不依赖端点条目在 items 在场;items 含其他轮条目不误切)、尾轮切片
 * (tailStartSeq, ∞) 区间口径(起点 user 气泡由 rounds 快照合成单独渲染、按区间排除,
 * 不再需要 afterUser 剥离后处理;起点之后的子 agent 首条 user.message 天然保留——
 * ec050499 回归口径)、agent 过滤键归一与谓词交互、firstTailEventSeq 区间口径
 * (起点 user 不在场也不误命中更早轮次)、合成 user 记录(闭合轮 RoundSummary 与
 * open 轮快照同口径)、闭合轮/终态尾轮筛选。骨架出画板(rounds 快照不进 items)后
 * 「端点条目不在场」是常态而非兜底分支。
 *
 * 纪律:被测函数经 roundsView.ts 从组件模块**原位提取**(提取不改行为,组件侧等价
 * 委托),测试不 import 任何 React 组件链(组件模块挂着 pluginDispatcher/bridge 等
 * 副作用导入,node 环境无法安全加载)。
 */
import { describe, it, expect } from 'vitest'
import { compareSeq } from '@/task/eventFolder'
import type { ThreadItem } from '@/task/threadItem'
import type { RoundSummary } from '@/task/types'
import type { AgentMessageRecord } from '@/types'
import {
  closedRoundsOf,
  firstTailEventSeq,
  itemAgentKey,
  matchesAgentFilter,
  seqGeq,
  sliceRound,
  sliceTailItems,
  syntheticUserRecord,
  terminalTailOf,
} from './roundsView'

// Snowflake 形态 seq(19 位十进制,> 2^53):Number() 会把仅末位不同的两个大数坍缩为
// 同一 double(与 eventFolder.test.ts 同款实证),用于验证 seq 比较走精确路径。
const SEQ_50 = '7380000000000000050'
const SEQ_60 = '7380000000000000060'
const SEQ_80 = '7380000000000000080'
const SEQ_100 = '7380000000000000100'
const SEQ_105 = '7380000000000000105'
const SEQ_110 = '7380000000000000110'
const SEQ_150 = '7380000000000000150'
const SEQ_200 = '7380000000000000200'
const SEQ_500 = '7380000000000000500'

const MAIN = 'agent-main-001'
const SUB = 'agent-sub-001'

/** kind:message 条目(assistant;payload 可覆盖 content/toolCalls/streaming 等)。 */
function messageItem(seq: string, payload: Record<string, unknown> = {}, agentId = MAIN): ThreadItem {
  return {
    seq,
    ts: 0,
    agentId,
    kind: 'message',
    payload: { role: 'assistant', content: '', ...payload },
  }
}

/** kind:user.message 条目(主/子 agent 用户输入)。 */
function userItem(seq: string, agentId = MAIN): ThreadItem {
  return { seq, ts: 0, agentId, kind: 'user.message', payload: { content: '' } }
}

/** trace 族条目(defaultHandler 兜底形状;agentId 缺省空串 = 未归属)。 */
function traceItem(seq: string, agentId = ''): ThreadItem {
  return { seq, ts: 0, agentId, kind: 'error', payload: { title: 'trace' } }
}

/** items 的 seq 序列(seq 即 id)。 */
function seqs(items: ThreadItem[]): string[] {
  return items.map((it) => it.seq)
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
  return (it: ThreadItem) => matchesAgentFilter(it, filterAgentId, mainAgentId)
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. sliceRound 闭合轮切片(RoundDetail 展开态)
// ─────────────────────────────────────────────────────────────────────────────

describe('sliceRound 闭合轮切片 (startSeq, endSeq]', () => {
  it('含终点不含起点:剥掉起点 user,终点 final 保留;trace 族条目按其 seq 参与区间判定;返回项原对象直通、五字段', () => {
    const items: ThreadItem[] = [
      userItem(SEQ_100),
      messageItem(SEQ_110, { content: '过程回复' }),
      traceItem(SEQ_150),
      messageItem(SEQ_200, { content: '最终回复' }),
    ]
    const slice = sliceRound(items, round())
    expect(seqs(slice)).toEqual([SEQ_110, SEQ_150, SEQ_200])
    // 返回项为原对象直通(不再克隆剥标),且为五字段
    expect(slice[0]).toBe(items[1])
    expect(slice[1]).toBe(items[2])
    expect(slice[2]).toBe(items[3])
    for (const it of slice) expect(Object.keys(it).sort()).toEqual(['agentId', 'kind', 'payload', 'seq', 'ts'])
    // 纯函数:入参数组不被修改
    expect(items).toHaveLength(4)
  })

  it('起点 user 不在 items(骨架出画板常态):按 seq 区间比较,不误切进其他轮已加载条目', () => {
    const items: ThreadItem[] = [
      messageItem(SEQ_50, { content: '上一轮 final' }),
      messageItem(SEQ_110, { content: '过程回复' }),
      messageItem(SEQ_200, { content: '最终回复' }),
    ]
    // 旧 findIndex 口径的 from=0 兜底会把上一轮条目误切进来;区间比较只取 (100, 200]
    expect(seqs(sliceRound(items, round()))).toEqual([SEQ_110, SEQ_200])
  })

  it('两个端点条目均不在 items,且 items 已含后续轮条目:不误切前后轮内容', () => {
    const items: ThreadItem[] = [
      messageItem(SEQ_50, { content: '上一轮 final' }),
      messageItem(SEQ_110, { content: '过程1' }),
      traceItem(SEQ_150),
      userItem(SEQ_200 + '0'), // seq=2000 > endSeq(下一轮输入)
      messageItem(SEQ_500, { content: '下一轮回复' }),
    ]
    expect(seqs(sliceRound(items, round()))).toEqual([SEQ_110, SEQ_150])
  })

  it('endSeq 找不到(items 未含终点条目)切到 items 末尾', () => {
    const items: ThreadItem[] = [
      userItem(SEQ_100),
      messageItem(SEQ_110),
      messageItem(SEQ_150),
    ]
    expect(seqs(sliceRound(items, round({ endSeq: SEQ_500 })))).toEqual([SEQ_110, SEQ_150])
  })

  it('endSeq 为空串(未闭合轮)同样切到 items 末尾', () => {
    const items: ThreadItem[] = [userItem(SEQ_100), messageItem(SEQ_110)]
    expect(seqs(sliceRound(items, round({ endSeq: '' })))).toEqual([SEQ_110])
  })

  it('items 为空返回空数组', () => {
    expect(sliceRound([], round())).toEqual([])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 2. agent 过滤(itemAgentKey 归一 + matches 谓词交互)
// ─────────────────────────────────────────────────────────────────────────────

describe('agent 过滤(itemAgentKey 归一 + matches 谓词交互)', () => {
  it('itemAgentKey:item.agentId 直取(空串归一 mainAgentId;trace 族未归属同归一)', () => {
    expect(itemAgentKey(messageItem(SEQ_100, {}, SUB), MAIN)).toBe(SUB)
    expect(itemAgentKey(messageItem(SEQ_100, {}, ''), MAIN)).toBe(MAIN)
    expect(itemAgentKey(traceItem(SEQ_110, SUB), MAIN)).toBe(SUB)
    expect(itemAgentKey(traceItem(SEQ_110, ''), MAIN)).toBe(MAIN)
    // mainAgentId 未灌入(open 极早期):归一为空串
    expect(itemAgentKey(messageItem(SEQ_100, {}, ''), '')).toBe('')
  })

  it("filterAgentId='' 全过:主/子消息与未归属 trace 均放行", () => {
    const items = [
      userItem(SEQ_100, ''),
      messageItem(SEQ_110, {}, SUB),
      traceItem(SEQ_150, ''),
    ]
    expect(items.every(matchesOf('', MAIN))).toBe(true)
  })

  it('过滤子 agent:主 agent 项被滤掉,子 agent 消息/trace 放行(反向过滤主 agent 亦然)', () => {
    const items = [
      userItem(SEQ_100, ''),                    // 归一 MAIN
      messageItem(SEQ_110, {}, MAIN),
      userItem(SEQ_150, SUB),                   // 子 agent 首条 user.message
      traceItem(SEQ_200, SUB),
    ]
    expect(items.map(matchesOf(SUB, MAIN))).toEqual([false, false, true, true])
    expect(items.map(matchesOf(MAIN, MAIN))).toEqual([true, true, false, false])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 3. sliceTailItems 尾轮切片(TaskRoundsPanel tailItems;起点 user 剥离内建于区间口径)
// ─────────────────────────────────────────────────────────────────────────────

describe('sliceTailItems 尾轮切片 (tailStartSeq, ∞)', () => {
  it('区间排除起点 user 气泡(由 rounds 快照合成单独渲染,不在切片内);起点后的 trace 与消息按 seq 参与判定(原对象直通)', () => {
    const items: ThreadItem[] = [
      messageItem(SEQ_50, { content: '上一轮 final' }),   // 上一轮条目不进尾轮切片
      userItem(SEQ_100),                                   // 起点user:seq == tailStartSeq,区间排除
      traceItem(SEQ_105),                                  // 起点后首条是 trace:按 seq 命中区间,保留
      messageItem(SEQ_110),
      messageItem(SEQ_150, { content: '回复' }),
    ]
    const tail = sliceTailItems(items, SEQ_100, matchesOf('', MAIN))
    expect(seqs(tail)).toEqual([SEQ_105, SEQ_110, SEQ_150])
    // 切片返回原对象直通(不再克隆剥标),且为五字段
    expect(tail[0]).toBe(items[2])
    expect(tail[1]).toBe(items[3])
    for (const it of tail) expect(Object.keys(it).sort()).toEqual(['agentId', 'kind', 'payload', 'seq', 'ts'])
  })

  it('回归(ec050499 口径):过滤子 agent 时起点 user(主)被滤出,子 agent 首条 user.message(起点之后)不丢', () => {
    const items: ThreadItem[] = [
      messageItem(SEQ_50, { content: '上一轮' }),                  // 上一轮尾部(items 含其他轮)
      userItem(SEQ_100, MAIN),                                     // 尾轮起点 user(主)
      userItem(SEQ_110, SUB),                                      // 子 agent 首条输入(给子 agent 的指令)
      messageItem(SEQ_150, {}, SUB),                               // 子回复
      messageItem(SEQ_200, { content: '主回复' }, MAIN),
    ]
    // 无过滤:起点 user 已被区间排除(不再需要 afterUser 剥离后处理),其余保留
    expect(seqs(sliceTailItems(items, SEQ_100, matchesOf('', MAIN))))
      .toEqual([SEQ_110, SEQ_150, SEQ_200])
    // 过滤子 agent:起点 user(主)不在切片内,子 agent 首条 user.message 保留(修复前按 role 误剥丢失)
    expect(seqs(sliceTailItems(items, SEQ_100, matchesOf(SUB, MAIN))))
      .toEqual([SEQ_110, SEQ_150])
  })

  it('起点 user 不在场(骨架出画板常态):更早轮条目不进切片,首个命中从区间语义定位', () => {
    const items: ThreadItem[] = [
      messageItem(SEQ_50),   // 上一轮条目:seq < start,不命中
      traceItem(SEQ_80),     // trace:seq < start,不命中
      messageItem(SEQ_110),  // 首个 seq > start 的条目
      traceItem(SEQ_150),
      messageItem(SEQ_200),
    ]
    expect(seqs(sliceTailItems(items, SEQ_100, matchesOf('', MAIN))))
      .toEqual([SEQ_110, SEQ_150, SEQ_200])
  })

  it('区间比较走精确比较:Number 坍缩的相邻大数不会被误定位', () => {
    expect(Number(SEQ_100)).toBe(Number(SEQ_200)) // 前置:Number 已坍缩,比较必须走精确路径
    const items: ThreadItem[] = [messageItem(SEQ_100), messageItem(SEQ_200)]
    // tailStartSeq=SEQ_150:判 SEQ_100 < 150 < SEQ_200,只有 SEQ_200 命中(Number 比较会误判 SEQ_100 >= 150)
    expect(seqs(sliceTailItems(items, SEQ_150, matchesOf('', MAIN)))).toEqual([SEQ_200])
  })

  it('tailStartSeq 空串返回空数组;全部 seq 更小无命中返回空数组', () => {
    expect(sliceTailItems([messageItem(SEQ_100)], '', matchesOf('', MAIN))).toEqual([])
    expect(sliceTailItems([messageItem(SEQ_50), traceItem(SEQ_60)], SEQ_100, matchesOf('', MAIN))).toEqual([])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 4. firstTailEventSeq 尾轮首过程事件 seq(backward 首拉 beforeSeq)
// ─────────────────────────────────────────────────────────────────────────────

describe('firstTailEventSeq 尾轮 user 之后第一条消息族条目的 seq', () => {
  it('跳过起点 user 与 trace 族,返回其后第一条消息族条目的 seq', () => {
    const items: ThreadItem[] = [
      messageItem(SEQ_50),
      userItem(SEQ_100),
      traceItem(SEQ_110),
      messageItem(SEQ_150),
    ]
    expect(firstTailEventSeq(items, SEQ_100)).toBe(SEQ_150)
  })

  it('起点 user 不在场(骨架出画板常态)时按区间语义:不误命中更早轮次的消息族条目', () => {
    // 旧 findIndex 口径的 startIdx=-1 从 0 扫会命中上一轮条目;区间比较只取 (100, ∞)
    const items: ThreadItem[] = [messageItem(SEQ_50), traceItem(SEQ_80), messageItem(SEQ_150)]
    expect(firstTailEventSeq(items, SEQ_100)).toBe(SEQ_150)
  })

  it('起点之后无消息族条目(仅 trace/仅起点/空 items)返回 null', () => {
    expect(firstTailEventSeq([userItem(SEQ_100), traceItem(SEQ_110)], SEQ_100)).toBeNull()
    expect(firstTailEventSeq([userItem(SEQ_100)], SEQ_100)).toBeNull()
    expect(firstTailEventSeq([], SEQ_100)).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 5. seqGeq 十进制大数比较 + item.seq 直取(seq 即 id)
// ─────────────────────────────────────────────────────────────────────────────

describe('seqGeq 十进制大整数字符串比较', () => {
  it('精确比较:Number 坍缩的相邻大数仍正确分大小', () => {
    expect(Number(SEQ_100)).toBe(Number(SEQ_200)) // 前置:Number 已坍缩,比较必须走 BigInt
    expect(seqGeq(SEQ_200, SEQ_100)).toBe(true)
    expect(seqGeq(SEQ_100, SEQ_200)).toBe(false)
    expect(seqGeq(SEQ_100, SEQ_100)).toBe(true)
  })

  it('a 为空串恒 false(防御分支)', () => {
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

describe('item.seq 直取(seq 即 id,m-/t- 前缀反解体系已废)', () => {
  it('切片与游标直接消费 item.seq:消息族与 trace 族条目的 seq 字段原样参与区间判定', () => {
    const items: ThreadItem[] = [userItem(SEQ_100), messageItem(SEQ_110), traceItem(SEQ_150)]
    expect(items.map((it) => it.seq)).toEqual([SEQ_100, SEQ_110, SEQ_150])
    expect(items.map((it) => it.kind)).toEqual(['user.message', 'message', 'error'])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 6. syntheticUserRecord 合成轮 user 消息(rounds 快照 → AgentMessageRecord)
// ─────────────────────────────────────────────────────────────────────────────

describe('syntheticUserRecord 合成轮 user 消息', () => {
  it('userMessage.data.rawContent 优先;messageId=startSeq(seq 即 id)、agentId 为空串、role=user', () => {
    const rec = syntheticUserRecord(round({
      user: '明文摘要',
      userMessage: { content: '完整明文', data: { rawContent: '原始输入(token 串)' } },
    }))
    expect(rec.messageId).toBe(SEQ_100)
    expect(rec.agentId).toBe('')
    expect(rec.role).toBe('user')
    // 口径:content 恒取 round.user(不取 userMessage.content),仅 rawContent 优先——锁定
    expect(rec.content).toBe('明文摘要')
    expect(rec.rawContent).toBe('原始输入(token 串)')
    expect(rec.historyMode).toBe('thread_only')
    expect(rec.createdAt).toBe(0)
    expect(rec.updatedAt).toBe(0)
    expect(rec.sequence).toBe(Number(SEQ_100))
  })

  it('接受 open 轮快照结构({startSeq, user, userMessage}):与闭合轮同口径(尾轮 user 气泡不再依赖 items)', () => {
    const rec = syntheticUserRecord({
      startSeq: SEQ_200,
      user: 'open 轮输入',
      userMessage: { content: '完整明文', data: { rawContent: '原始输入' } },
    })
    expect(rec.messageId).toBe(SEQ_200)
    expect(rec.role).toBe('user')
    expect(rec.agentId).toBe('')
    expect(rec.content).toBe('open 轮输入')
    expect(rec.rawContent).toBe('原始输入')
    // user 缺省(防御):content/rawContent 回退空串,不抛错
    const bare = syntheticUserRecord({ startSeq: SEQ_200 })
    expect(bare.content).toBe('')
    expect(bare.rawContent).toBe('')
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
// 7. 闭合轮/终态尾轮筛选(纯数据面)
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
