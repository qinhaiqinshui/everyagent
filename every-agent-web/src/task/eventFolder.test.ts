/**
 * TaskEventFolder 特征化测试(Phase 0 行为护栏,计划「任务线程统一条目与画板纯化」步骤 1)。
 *
 * 目的:锁定折叠器 + 事件注册表的**现状行为**(乱序 seq 归位/重放去重/流式聚合与定稿/
 * rounds 骨架折入升级/截断/agentMeta 台账/isMain 判定/foldRole 打标/defaultHandler 兜底/
 * final_reply 终点),为后续步骤 5/6/7(删 foldRole/拆台账/骨架出画板)提供行为基线——
 * 先锁后删,重构时同步更新断言而非绕过。
 *
 * 纪律:只断言现状,不改源码;事件经 fold(FoldableTaskEvent) 喂入,handler 查表分发
 * (eventRegistry.ts),不 mock 网络。
 */
import { describe, it, expect } from 'vitest'
import {
  TaskEventFolder,
  compareSeq,
  emptyThreadState,
} from './eventFolder'
import type { FoldableTaskEvent, TaskThreadItem } from './eventFolder'
import type { RoundSummary } from './types'
import type { TaskAgentLedgerItem } from './task-poll'

// Snowflake 形态 seq(19 位十进制,> 2^53):Number() 会把仅末位不同的两个大数坍缩为
// 同一 double,用于验证排序/去重键走原始字符串比较(compareSeq)。
const SEQ_100 = '7380000000000000100'
const SEQ_150 = '7380000000000000150'
const SEQ_200 = '7380000000000000200'
const SEQ_300 = '7380000000000000300'
const SEQ_500 = '7380000000000000500'

const MAIN = 'agent-main-001'
const SUB = 'agent-sub-001'

function newFolder(mainAgentId = ''): TaskEventFolder {
  const folder = new TaskEventFolder(emptyThreadState('task-1'))
  if (mainAgentId) folder.setMainAgentId(mainAgentId)
  return folder
}

function ev(
  seq: number | string,
  event: string,
  payload: Record<string, unknown> = {},
  agentId: string | null = MAIN,
  ts = 1000,
): FoldableTaskEvent {
  return { seq, ts, event, agentId, payload }
}

/** items 的 id 序列(agent_message 取 messageId,task_trace 取 traceId)。 */
function itemIds(folder: TaskEventFolder): string[] {
  return folder.state.items.map((it) => (it.type === 'agent_message' ? it.message.messageId : it.trace.traceId))
}

function agentMessageAt(folder: TaskEventFolder, index: number): Extract<TaskThreadItem, { type: 'agent_message' }> {
  const item = folder.state.items[index]
  if (!item || item.type !== 'agent_message') {
    throw new Error(`items[${index}] 不是 agent_message: ${JSON.stringify(item)}`)
  }
  return item
}

function findByMessageId(folder: TaskEventFolder, messageId: string): Extract<TaskThreadItem, { type: 'agent_message' }> {
  const item = folder.state.items.find((it) => it.type === 'agent_message' && it.message.messageId === messageId)
  if (!item || item.type !== 'agent_message') {
    throw new Error(`未找到 messageId=${messageId}`)
  }
  return item
}

// ─────────────────────────────────────────────────────────────────────────────
// 1. 乱序到达按 seq 归位(大数精度)
// ─────────────────────────────────────────────────────────────────────────────

describe('乱序到达按 seq 归位(大数精度不坍缩)', () => {
  it('后到的更小 seq 排到前面,items 恒按 seq 升序', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_200, 'user.message', { content: '第二条' }))
    folder.fold(ev(SEQ_100, 'user.message', { content: '第一条' }))
    expect(folder.state.items).toHaveLength(2)
    expect(itemIds(folder)).toEqual([`m-${SEQ_100}`, `m-${SEQ_200}`])
    // 中间 seq 迟到,插入中间位置
    folder.fold(ev(SEQ_150, 'user.message', { content: '中间条' }))
    expect(itemIds(folder)).toEqual([`m-${SEQ_100}`, `m-${SEQ_150}`, `m-${SEQ_200}`])
  })

  it('仅末位数字不同的雪花 seq 不因 Number 精度坍缩而判重', () => {
    const folder = newFolder(MAIN)
    // Number() 对这两个大数给出同一 double(坍缩实证),但字符串键不同 → 两条独立 item
    expect(Number(SEQ_100)).toBe(Number(SEQ_200))
    folder.fold(ev(SEQ_100, 'user.message', { content: 'a' }))
    folder.fold(ev(SEQ_200, 'user.message', { content: 'b' }))
    expect(folder.state.items).toHaveLength(2)
    expect(compareSeq(SEQ_100, SEQ_200)).toBeLessThan(0)
    expect(compareSeq(SEQ_200, SEQ_100)).toBeGreaterThan(0)
    expect(compareSeq(SEQ_100, SEQ_100)).toBe(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 2. 重连重放去重(seq 键控)
// ─────────────────────────────────────────────────────────────────────────────

describe('重连重放去重(seq 键控)', () => {
  it('非轮事件(主 agent user.message)同 seq 重复 fold 被忽略(返回 false)', () => {
    const folder = newFolder(MAIN)
    expect(folder.fold(ev(SEQ_100, 'user.message', { content: '用户' }))).toBe(true)
    expect(folder.fold(ev(SEQ_100, 'user.message', { content: '用户' }))).toBe(false)
    expect(folder.state.items).toHaveLength(1)
  })

  it('非轮事件(子 agent user.message / tool.result)同 seq 重复 fold 被忽略', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'user.message', { content: '子输入' }, SUB))
    expect(folder.fold(ev(SEQ_100, 'user.message', { content: '子输入' }, SUB))).toBe(false)
    const toolPayload = { content: '工具输出', data: { callId: 'call-1' } }
    expect(folder.fold(ev(SEQ_200, 'tool.result', toolPayload))).toBe(true)
    expect(folder.fold(ev(SEQ_200, 'tool.result', toolPayload))).toBe(false)
    expect(folder.state.items).toHaveLength(2)
  })

  it('轮事件(message)同 seq 重复 fold 复用同一条 item(不新增、内容覆盖)', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'message', { content: '回复' }))
    folder.fold(ev(SEQ_100, 'message', { content: '回复(重放)' }))
    expect(folder.state.items).toHaveLength(1)
    expect(findByMessageId(folder, `m-${SEQ_100}`).message.content).toBe('回复(重放)')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 3. thinking/delta 流式聚合与 message 权威定稿
// ─────────────────────────────────────────────────────────────────────────────

describe('thinking/delta 流式聚合与 message 权威定稿', () => {
  it('同 seq 增量追加到同一条 agent_message,随后 message 权威替换并关流', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'thinking', { content: '思考A' }, MAIN, 1100))
    folder.fold(ev(SEQ_100, 'thinking', { content: '思考B' }, MAIN, 1200))
    folder.fold(ev(SEQ_100, 'delta', { content: '正文A' }, MAIN, 1300))
    folder.fold(ev(SEQ_100, 'delta', { content: '正文B' }, MAIN, 1400))
    expect(folder.state.items).toHaveLength(1)
    const streaming = agentMessageAt(folder, 0)
    expect(streaming.message.reasoning).toBe('思考A思考B')
    expect(streaming.message.content).toBe('正文A正文B')
    expect(streaming.message.metadata?.streaming).toBe(true)

    // message 权威定稿:content/thinking 替换、toolCalls 记录、streaming 翻 false
    folder.fold(ev(SEQ_100, 'message', {
      content: '权威正文',
      data: {
        thinking: '权威思考',
        toolCalls: [
          { id: 'call-1', name: 'toolA', arguments: '{"a":1}' },
          { id: 'call-2', name: 'toolB', arguments: '{}' },
        ],
      },
    }, MAIN, 1500))
    expect(folder.state.items).toHaveLength(1)
    const finalized = agentMessageAt(folder, 0)
    expect(finalized.message.content).toBe('权威正文')
    expect(finalized.message.reasoning).toBe('权威思考')
    expect(finalized.message.toolCalls?.map((c) => c.id)).toEqual(['call-1', 'call-2'])
    expect(finalized.message.toolCalls?.[0]?.function.name).toBe('toolA')
    expect(finalized.message.metadata?.streaming).toBe(false)
    // 带工具调用的定稿不是折叠窗口终点
    expect(finalized.foldRole).toBeUndefined()

    // message 重复帧:toolCalls 按 callId 去重追加,内容替换;thinking 缺省不覆盖
    folder.fold(ev(SEQ_100, 'message', {
      content: '权威正文2',
      data: {
        toolCalls: [
          { id: 'call-1', name: 'toolA', arguments: '{}' },
          { id: 'call-3', name: 'toolC', arguments: '{}' },
        ],
      },
    }, MAIN, 1600))
    expect(folder.state.items).toHaveLength(1)
    const refolded = agentMessageAt(folder, 0)
    expect(refolded.message.content).toBe('权威正文2')
    expect(refolded.message.reasoning).toBe('权威思考')
    expect(refolded.message.toolCalls?.map((c) => c.id)).toEqual(['call-1', 'call-2', 'call-3'])
  })

  it('message 定稿后迟到的同 seq 增量帧被忽略(不叠坏权威内容)', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'delta', { content: '流式' }, MAIN, 1100))
    folder.fold(ev(SEQ_100, 'message', { content: '权威' }, MAIN, 1200))
    folder.fold(ev(SEQ_100, 'delta', { content: '(迟到)' }, MAIN, 1300))
    expect(agentMessageAt(folder, 0).message.content).toBe('权威')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 4. foldRound 骨架折入与升级
// ─────────────────────────────────────────────────────────────────────────────

describe('foldRound 骨架折入与升级', () => {
  function round(overrides: Partial<RoundSummary> = {}): RoundSummary {
    return {
      roundId: 'round-1',
      index: 1,
      startSeq: SEQ_100,
      endSeq: SEQ_500,
      user: '骨架用户输入',
      finalReply: '骨架最终回复',
      subs: [],
      ...overrides,
    }
  }

  it('闭合轮折入合成 user + 合成 final(synthetic 标记 + foldRole 打标,按 seq 升序)', () => {
    const folder = newFolder(MAIN)
    expect(folder.foldRound(round())).toBe(true)
    expect(folder.state.items).toHaveLength(2)
    const user = findByMessageId(folder, `m-${SEQ_100}`)
    expect(user.message.role).toBe('user')
    expect(user.message.content).toBe('骨架用户输入')
    expect(user.message.metadata?.synthetic).toBe(true)
    expect(user.foldRole).toBe('user')
    const final = findByMessageId(folder, `m-${SEQ_500}`)
    expect(final.message.role).toBe('assistant')
    expect(final.message.content).toBe('骨架最终回复')
    expect(final.message.metadata?.synthetic).toBe(true)
    expect(final.foldRole).toBe('final_reply')
    expect(folder.state.items.indexOf(user)).toBeLessThan(folder.state.items.indexOf(final))
    // 幂等:重复折入同轮不变化
    expect(folder.foldRound(round())).toBe(false)
    expect(folder.state.items).toHaveLength(2)
  })

  it('userMessage payload 优先于 user 文本摘要(content/rawContent)', () => {
    const folder = newFolder(MAIN)
    folder.foldRound(round({ userMessage: { content: '完整明文', data: { rawContent: '原始输入' } } }))
    const user = findByMessageId(folder, `m-${SEQ_100}`)
    expect(user.message.content).toBe('完整明文')
    expect(user.message.rawContent).toBe('原始输入')
  })

  it('未闭合轮(endSeq 空串)只折入合成 user', () => {
    const folder = newFolder(MAIN)
    folder.foldRound(round({ endSeq: '' }))
    expect(folder.state.items).toHaveLength(1)
    expect(findByMessageId(folder, `m-${SEQ_100}`).message.metadata?.synthetic).toBe(true)
  })

  it('真实 user.message 到达时 upgradeSyntheticUser 原地升级(不重复插入)', () => {
    const folder = newFolder(MAIN)
    folder.foldRound(round())
    expect(folder.fold(ev(SEQ_100, 'user.message', {
      content: '真实用户输入',
      data: { rawContent: '原始输入' },
    }, MAIN, 2000))).toBe(true)
    expect(folder.state.items).toHaveLength(2)
    const upgraded = findByMessageId(folder, `m-${SEQ_100}`)
    expect(upgraded.message.content).toBe('真实用户输入')
    expect(upgraded.message.rawContent).toBe('原始输入')
    expect(upgraded.message.metadata?.synthetic).toBe(false)
    expect(upgraded.foldRole).toBe('user')
    // 升级完成后再到的同 seq 事件 = 纯重复,返回 false
    expect(folder.fold(ev(SEQ_100, 'user.message', { content: '真实用户输入' }, MAIN, 2100))).toBe(false)
    expect(folder.state.items).toHaveLength(2)
  })

  it('合成 final 被真实 message 覆盖(清除 synthetic,保持 final_reply)', () => {
    const folder = newFolder(MAIN)
    folder.foldRound(round())
    folder.fold(ev(SEQ_500, 'message', { content: '真实最终回复' }, MAIN, 3000))
    expect(folder.state.items).toHaveLength(2)
    const real = findByMessageId(folder, `m-${SEQ_500}`)
    expect(real.message.content).toBe('真实最终回复')
    expect(real.message.metadata?.synthetic).toBe(false)
    expect(real.foldRole).toBe('final_reply')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 5. truncateAfterSeq 截断
// ─────────────────────────────────────────────────────────────────────────────

describe('truncateAfterSeq 截断', () => {
  it('移除 seq >= target 的项,bySeq 同步清理(截断后同 seq 可重新折入)', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'user.message', { content: 'user' }, MAIN))
    folder.fold(ev(SEQ_200, 'message', { content: '回复1' }, MAIN))
    folder.fold(ev(SEQ_300, 'message', { content: '回复2' }, MAIN))
    expect(folder.state.items).toHaveLength(3)

    folder.truncateAfterSeq(SEQ_200)
    expect(itemIds(folder)).toEqual([`m-${SEQ_100}`])

    // bySeq 已清理:同 seq 的新事件不判重,可重新折入
    expect(folder.fold(ev(SEQ_200, 'message', { content: '重发后的回复' }, MAIN))).toBe(true)
    expect(itemIds(folder)).toEqual([`m-${SEQ_100}`, `m-${SEQ_200}`])
    expect(findByMessageId(folder, `m-${SEQ_200}`).message.content).toBe('重发后的回复')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 6. agentMeta 台账(seedAgents / usage 合并 / agentStates)
// ─────────────────────────────────────────────────────────────────────────────

describe('agentMeta 台账', () => {
  function ledgerItem(overrides: Partial<TaskAgentLedgerItem> = {}): TaskAgentLedgerItem {
    return { agentId: SUB, title: '子代理', createdAt: 222, ...overrides }
  }

  it('seedAgents 灌入基线(title/usage/context)并设定 mainAgentId;主 agent 状态不入台账状态表', () => {
    const folder = newFolder()
    const changed = folder.seedAgents([
      ledgerItem({
        agentId: MAIN,
        title: '主代理',
        status: 'completed',
        usage: { inputTokens: 10, outputTokens: 5, totalTokens: 15 },
        context: { inputTokens: 100, contextWindowTokens: 1000, model: 'model-a' },
        latestActivity: { updatedAt: 500 },
      }),
      ledgerItem({
        status: 'completed',
        usage: { inputTokens: 30, outputTokens: 3, totalTokens: 33 },
      }),
    ], MAIN)
    expect(changed).toBe(true)
    expect(folder.state.mainAgentId).toBe(MAIN)

    const mainMeta = folder.state.agentMeta[MAIN]
    expect(mainMeta?.title).toBe('主代理')
    expect(mainMeta?.inputTokens).toBe(10)
    expect(mainMeta?.outputTokens).toBe(5)
    expect(mainMeta?.totalTokens).toBe(15)
    expect(mainMeta?.contextUsed).toBe(100)
    expect(mainMeta?.contextWindow).toBe(1000)
    expect(mainMeta?.model).toBe('model-a')
    expect(mainMeta?.updatedAt).toBe(500)
    expect(folder.state.agentMeta[SUB]?.title).toBe('子代理')
    expect(folder.state.agentMeta[SUB]?.totalTokens).toBe(33)

    // 主 agent 状态不被台账写入;子 agent 终态写入 agentStates
    expect(folder.state.agentStates[MAIN]).toBeUndefined()
    expect(folder.state.agentStates[SUB]).toBe('completed')
  })

  it('usage 事件字段级合并(undefined 不覆盖已有值,updatedAt 取大)', () => {
    const folder = newFolder(MAIN)
    folder.seedAgents([ledgerItem({
      agentId: MAIN,
      usage: { inputTokens: 10, outputTokens: 5, totalTokens: 15 },
      context: { inputTokens: 100, contextWindowTokens: 1000, model: 'model-a' },
      latestActivity: { updatedAt: 500 },
    })], MAIN)

    folder.fold(ev(SEQ_100, 'usage', {
      data: {
        round: { inputTokens: 50, outputTokens: 5, totalTokens: 55 },
        total: { inputTokens: 200, outputTokens: 20, totalTokens: 220 },
        contextWindowTokens: 4000,
        model: 'model-b',
      },
    }, MAIN, 800))
    const meta = folder.state.agentMeta[MAIN]
    expect(meta?.contextUsed).toBe(50)
    expect(meta?.contextWindow).toBe(4000)
    expect(meta?.model).toBe('model-b')
    expect(meta?.inputTokens).toBe(200)
    expect(meta?.outputTokens).toBe(20)
    expect(meta?.totalTokens).toBe(220)
    expect(meta?.updatedAt).toBe(800)

    // 更旧时间戳的 usage:updatedAt 不回退;model 空串(readStr → undefined)不覆盖;
    // 缺省字段(contextWindow/total)不覆盖已有值
    folder.fold(ev(SEQ_150, 'usage', {
      data: { round: { inputTokens: 60, outputTokens: 6, totalTokens: 66 }, model: '' },
    }, MAIN, 300))
    const meta2 = folder.state.agentMeta[MAIN]
    expect(meta2?.updatedAt).toBe(800)
    expect(meta2?.model).toBe('model-b')
    expect(meta2?.contextWindow).toBe(4000)
    expect(meta2?.inputTokens).toBe(200)
    expect(meta2?.contextUsed).toBe(60)

    // usage 是非 item 事件,不进线程
    expect(folder.state.items).toHaveLength(0)
  })

  it('agent.status 事件更新 agentStates(权威);子 agent 终态台账覆盖、非终态不覆盖;主 agent 状态不被台账覆盖', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'agent.status', { status: 'waiting-user' }, SUB, 1200))
    expect(folder.state.agentStates[SUB]).toBe('waiting-user')

    // 非终态台账不覆盖流事件实时状态
    folder.seedAgents([ledgerItem({ status: 'running' })], MAIN)
    expect(folder.state.agentStates[SUB]).toBe('waiting-user')
    // 终态台账覆盖(DataPusher 与 finish 驱逐竞态丢失 done 事件的兜底权威)
    folder.seedAgents([ledgerItem({ status: 'completed' })], MAIN)
    expect(folder.state.agentStates[SUB]).toBe('completed')

    // 主 agent:流事件状态不被台账终态按回(主 agent 状态是 per-run 的,交流事件)
    folder.fold(ev(SEQ_150, 'agent.status', { status: 'running' }, MAIN, 1300))
    expect(folder.state.agentStates[MAIN]).toBe('running')
    folder.seedAgents([ledgerItem({ agentId: MAIN, status: 'completed' })], MAIN)
    expect(folder.state.agentStates[MAIN]).toBe('running')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 7. isMain 判定与 agentKeyOf 归一
// ─────────────────────────────────────────────────────────────────────────────

describe('isMain 判定与 agentKeyOf 归一', () => {
  it('mainAgentId 未灌入(空串):回退旧形态空串判据', () => {
    const folder = newFolder()
    expect(folder.state.mainAgentId).toBe('')
    expect(folder.isMain('')).toBe(true)
    expect(folder.isMain(MAIN)).toBe(false)
    expect(folder.isMain('sub_x')).toBe(false)
  })

  it('mainAgentId 灌入后:唯一判据 = 键 === mainAgentId;空值不回退', () => {
    const folder = newFolder(MAIN)
    expect(folder.isMain(MAIN)).toBe(true)
    expect(folder.isMain('')).toBe(false)
    expect(folder.isMain('sub_x')).toBe(false)
    folder.setMainAgentId('')
    expect(folder.state.mainAgentId).toBe(MAIN)
  })

  it('agentKeyOf:缺 agentId 的事件归一到 mainAgentId;payload.agentId 次优先', () => {
    const folder = newFolder()
    // 未灌入 → 归一为空串(旧形态键兼容)
    expect(folder.agentKeyOf({ seq: 1, ts: 1000, event: 'x', agentId: undefined, payload: {} })).toBe('')
    expect(folder.agentKeyOf({ seq: 1, ts: 1000, event: 'x', agentId: null, payload: {} })).toBe('')
    folder.setMainAgentId(MAIN)
    // 灌入后 → 缺失归一到 mainAgentId
    expect(folder.agentKeyOf({ seq: 1, ts: 1000, event: 'x', agentId: undefined, payload: {} })).toBe(MAIN)
    expect(folder.agentKeyOf({ seq: 1, ts: 1000, event: 'x', agentId: null, payload: {} })).toBe(MAIN)
    // payload.agentId 兜底优先于归一;显式 agentId 原样
    expect(folder.agentKeyOf({ seq: 1, ts: 1000, event: 'x', agentId: undefined, payload: { agentId: SUB } })).toBe(SUB)
    expect(folder.agentKeyOf({ seq: 1, ts: 1000, event: 'x', agentId: SUB, payload: {} })).toBe(SUB)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 8. 主/子 agent user.message 打标差异
// ─────────────────────────────────────────────────────────────────────────────

describe('主/子 agent user.message 打标差异', () => {
  it('主 agent 的 user.message 标 foldRole=user(折叠窗口起点);子 agent 不打标(过程内容)', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'user.message', { content: '主输入' }, MAIN))
    folder.fold(ev(SEQ_200, 'user.message', { content: '子输入' }, SUB))
    const mainItem = findByMessageId(folder, `m-${SEQ_100}`)
    const subItem = findByMessageId(folder, `m-${SEQ_200}`)
    expect(mainItem.foldRole).toBe('user')
    expect(mainItem.message.agentId).toBe(MAIN)
    expect(mainItem.message.role).toBe('user')
    expect(subItem.foldRole).toBeUndefined()
    expect(subItem.message.agentId).toBe(SUB)
    expect(subItem.message.role).toBe('user')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 9. defaultHandler 兜底(未注册 kind)
// ─────────────────────────────────────────────────────────────────────────────

describe('defaultHandler 兜底(未注册 kind)', () => {
  it('按 trace 形状折入(traceId=t-seq,title/summary/content 宽松读取,data→metadata)', () => {
    const folder = newFolder(MAIN)
    expect(folder.fold(ev(SEQ_100, 'future.kind', {
      title: '未来事件',
      summary: '摘要',
      content: '内容',
      data: { foo: 'bar' },
    }, MAIN, 1700))).toBe(true)
    expect(folder.state.items).toHaveLength(1)
    const trace = folder.state.items[0]
    expect(trace?.type).toBe('task_trace')
    if (trace?.type === 'task_trace') {
      expect(trace.trace.traceId).toBe(`t-${SEQ_100}`)
      expect(trace.trace.kind).toBe('future.kind')
      expect(trace.trace.title).toBe('未来事件')
      expect(trace.trace.summary).toBe('摘要')
      expect(trace.trace.content).toBe('内容')
      expect(trace.trace.metadata).toEqual({ foo: 'bar' })
      expect(trace.trace.agentId).toBe(MAIN)
    }
  })

  it('同 seq 重复 upsert 原地覆盖,不新增线程项', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'future.kind', { content: '第一版' }, MAIN, 1700))
    folder.fold(ev(SEQ_100, 'future.kind', { content: '第二版' }, MAIN, 1800))
    expect(folder.state.items).toHaveLength(1)
    const trace = folder.state.items[0]
    if (trace?.type === 'task_trace') {
      expect(trace.trace.content).toBe('第二版')
      expect(trace.createdAt).toBe(1800)
    }
  })

  it('字段缺失时宽松降级(title 空串/summary 与 content undefined)', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'future.kind', {}, MAIN, 1700))
    const trace = folder.state.items[0]
    if (trace?.type === 'task_trace') {
      expect(trace.trace.title).toBe('')
      expect(trace.trace.summary).toBeUndefined()
      expect(trace.trace.content).toBeUndefined()
      expect(trace.trace.metadata).toBeUndefined()
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 10. markFinalReply 折叠窗口终点
// ─────────────────────────────────────────────────────────────────────────────

describe('markFinalReply 折叠窗口终点', () => {
  it('主 agent 无工具调用且有正文的 message 定稿 → foldRole=final_reply', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'user.message', { content: '用户' }, MAIN))
    folder.fold(ev(SEQ_200, 'delta', { content: '流式正文' }, MAIN, 1200))
    folder.fold(ev(SEQ_200, 'message', { content: '最终回复正文' }, MAIN, 1300))
    const item = findByMessageId(folder, `m-${SEQ_200}`)
    expect(item.foldRole).toBe('final_reply')
    expect(item.message.content).toBe('最终回复正文')
    expect(item.message.metadata?.streaming).toBe(false)
  })

  it('带 toolCalls 的 message 不定稿(折叠窗口继续)', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_200, 'message', {
      content: '先调用工具',
      data: { toolCalls: [{ id: 'call-1', name: 'toolA', arguments: '{}' }] },
    }, MAIN, 1300))
    expect(findByMessageId(folder, `m-${SEQ_200}`).foldRole).toBeUndefined()
  })

  it('空正文与子 agent 的 message 不打 final_reply', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'message', { content: '' }, MAIN, 1300))
    folder.fold(ev(SEQ_200, 'message', { content: '子代理回复' }, SUB, 1400))
    expect(findByMessageId(folder, `m-${SEQ_100}`).foldRole).toBeUndefined()
    expect(findByMessageId(folder, `m-${SEQ_200}`).foldRole).toBeUndefined()
  })
})
