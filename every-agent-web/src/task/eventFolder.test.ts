/**
 * TaskEventFolder 特征化测试(Phase 0 行为护栏,计划「任务线程统一条目与画板纯化」步骤 1)。
 *
 * 目的:锁定折叠器 + 事件注册表的**现状行为**(乱序 seq 归位/重放去重/流式聚合与定稿/
 * 截断/agent 台账镜像(folder.ledger)/isMain 判定/defaultHandler 兜底),为后续重构提供
 * 行为基线——先锁后删,重构时同步更新断言而非绕过(foldRole 打标断言已随步骤 5「删除
 * foldRole 体系」移除;台账断言已随步骤 6「agentMeta/agentStates 拆出 eventFolder」改指向
 * 独立台账 store folder.ledger——事件仍经 fold() 同一入口喂入,锁的是 registry handler →
 * 台账 store 的真实写路径,语义不变;foldRound 合成折入/upgradeSyntheticUser 骨架升级/
 * metadata.synthetic 标记用例已随步骤 7「rounds 骨架出画板」删除;步骤 8「ThreadItem
 * 五字段统一」起断言改为五字段形状——seq 即 id(m-/t- 前缀 id 体系废除)、payload 按
 * kind 类型化读取,行为语义保持)。
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
import type { FoldableTaskEvent } from './eventFolder'
import type { ThreadItem, TracePayload, MessagePayload } from './threadItem'
import { payloadAs } from './threadItem'
import { getToolStarted } from './toolStartedRuntime'
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

/** items 的 seq 序列(seq 即 id,items 恒按 seq 升序)。 */
function seqsOf(folder: TaskEventFolder): string[] {
  return folder.state.items.map((it) => it.seq)
}

/** 取 items[index] 且校验 kind(五字段统一后条目无 type 判别,kind 即分类)。 */
function itemAt(folder: TaskEventFolder, index: number, kind: string): ThreadItem {
  const item = folder.state.items[index]
  if (!item || item.kind !== kind) {
    throw new Error(`items[${index}] 不是 kind=${kind}: ${JSON.stringify(item)}`)
  }
  return item
}

/** 五字段键集合(锁定条目只有 seq/ts/agentId/kind/payload,无折叠标记等额外字段)。 */
function fiveKeysOf(item: ThreadItem): string[] {
  return Object.keys(item).sort()
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
    expect(seqsOf(folder)).toEqual([SEQ_100, SEQ_200])
    // 中间 seq 迟到,插入中间位置
    folder.fold(ev(SEQ_150, 'user.message', { content: '中间条' }))
    expect(seqsOf(folder)).toEqual([SEQ_100, SEQ_150, SEQ_200])
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

  it('tool.result 折入 kind=tool.result + payload{callId, content, status, toolName, startedAt?, durationMs?}', () => {
    const folder = newFolder(MAIN)
    // 先下发 toolCalls(callId → 工具名注册表回填 toolName)
    folder.fold(ev(SEQ_150, 'message', {
      content: '',
      data: { toolCalls: [{ id: 'call-1', name: 'toolA', arguments: '{"a":1}' }] },
    }))
    folder.fold(ev(SEQ_200, 'tool.result', {
      content: '工具输出',
      data: { callId: 'call-1', status: 'error', startedAt: 12345, durationMs: 678 },
    }))
    const item = itemAt(folder, 1, 'tool.result')
    expect(fiveKeysOf(item)).toEqual(['agentId', 'kind', 'payload', 'seq', 'ts'])
    const p = payloadAs(item, 'tool.result')!
    expect(p.callId).toBe('call-1')
    expect(p.content).toBe('工具输出')
    expect(p.status).toBe('error')
    expect(p.startedAt).toBe(12345)
    expect(p.durationMs).toBe(678)
    expect(p.toolName).toBe('toolA')
    // 无 status/计时字段的老事件:status 缺省 success,可选字段 undefined
    folder.fold(ev(SEQ_300, 'tool.result', { content: '旧事件输出', data: { callId: 'call-x' } }))
    const oldP = payloadAs(itemAt(folder, 2, 'tool.result'), 'tool.result')!
    expect(oldP.status).toBe('success')
    expect(oldP.startedAt).toBeUndefined()
    expect(oldP.durationMs).toBeUndefined()
    expect(oldP.toolName).toBeUndefined()
  })

  it('轮事件(message)同 seq 重复 fold 复用同一条 item(不新增、内容覆盖)', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'message', { content: '回复' }))
    folder.fold(ev(SEQ_100, 'message', { content: '回复(重放)' }))
    expect(folder.state.items).toHaveLength(1)
    expect(payloadAs(itemAt(folder, 0, 'message'), 'message')?.content).toBe('回复(重放)')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 3. thinking/delta 流式聚合与 message 权威定稿
// ─────────────────────────────────────────────────────────────────────────────

describe('thinking/delta 流式聚合与 message 权威定稿', () => {
  it('同 seq 增量帧聚合进同一条 message 项 payload,随后 message 权威替换并关流', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'thinking', { content: '思考A' }, MAIN, 1100))
    folder.fold(ev(SEQ_100, 'thinking', { content: '思考B' }, MAIN, 1200))
    folder.fold(ev(SEQ_100, 'delta', { content: '正文A' }, MAIN, 1300))
    folder.fold(ev(SEQ_100, 'delta', { content: '正文B' }, MAIN, 1400))
    expect(folder.state.items).toHaveLength(1)
    const streaming = itemAt(folder, 0, 'message')
    expect(streaming.kind).toBe('message')
    const sp = payloadAs(streaming, 'message')!
    expect(sp.thinking).toBe('思考A思考B')
    expect(sp.content).toBe('正文A正文B')
    expect(sp.streaming).toBe(true)
    // 正文首达且已有思考 → 思考阶段结束标记置真(驱动前端思考块自动折叠)
    expect(sp.reasoningDone).toBe(true)

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
    const finalized = itemAt(folder, 0, 'message')
    const fp = payloadAs(finalized, 'message')!
    expect(fp.content).toBe('权威正文')
    expect(fp.thinking).toBe('权威思考')
    expect(fp.toolCalls?.map((c) => c.id)).toEqual(['call-1', 'call-2'])
    expect(fp.toolCalls?.[0]?.function.name).toBe('toolA')
    expect(fp.streaming).toBe(false)
    // 带工具调用的定稿:线程项为五字段,无折叠标记
    expect(fiveKeysOf(finalized)).toEqual(['agentId', 'kind', 'payload', 'seq', 'ts'])

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
    const refolded = payloadAs(itemAt(folder, 0, 'message'), 'message')!
    expect(refolded.content).toBe('权威正文2')
    expect(refolded.thinking).toBe('权威思考')
    expect(refolded.toolCalls?.map((c) => c.id)).toEqual(['call-1', 'call-2', 'call-3'])
  })

  it('message 定稿后迟到的同 seq 增量帧被忽略(不叠坏权威内容)', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'delta', { content: '流式' }, MAIN, 1100))
    folder.fold(ev(SEQ_100, 'message', { content: '权威' }, MAIN, 1200))
    folder.fold(ev(SEQ_100, 'delta', { content: '(迟到)' }, MAIN, 1300))
    expect(payloadAs(itemAt(folder, 0, 'message'), 'message')?.content).toBe('权威')
  })

  it('思考耗时:起点取首个 thinking 帧 ts;正文首达冻结「结束帧 - 起点」;思考续增作废兜底值', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'thinking', { content: '思考A' }, MAIN, 1100))
    folder.fold(ev(SEQ_100, 'thinking', { content: '思考B' }, MAIN, 1400))
    let p = payloadAs(itemAt(folder, 0, 'message'), 'message')!
    // 起点 = 首个 thinking 帧 ts(不是后续帧),实时读秒基准
    expect(p.thinkingStartedAt).toBe(1100)
    expect(p.thinkingDurationMs).toBeUndefined()
    // 正文首达 → 冻结兜底耗时(结束帧 ts - 起点)
    folder.fold(ev(SEQ_100, 'delta', { content: '正文' }, MAIN, 2000))
    p = payloadAs(itemAt(folder, 0, 'message'), 'message')!
    expect(p.reasoningDone).toBe(true)
    expect(p.thinkingDurationMs).toBe(900)
    // 思考续增(交织)→ 标记回退 + 兜底耗时作废,读秒续算
    folder.fold(ev(SEQ_100, 'thinking', { content: '思考C' }, MAIN, 2500))
    p = payloadAs(itemAt(folder, 0, 'message'), 'message')!
    expect(p.reasoningDone).toBe(false)
    expect(p.thinkingDurationMs).toBeUndefined()
  })

  it('思考耗时:message 权威值覆盖前端兜底(messageStartedAt/DurationMs 来自 worker)', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'thinking', { content: '思考' }, MAIN, 1100))
    folder.fold(ev(SEQ_100, 'delta', { content: '正文' }, MAIN, 2000))
    folder.fold(ev(SEQ_100, 'message', {
      content: '权威正文',
      data: { thinking: '权威思考', thinkingStartedAt: 1050, thinkingDurationMs: 4200 },
    }, MAIN, 2100))
    const p = payloadAs(itemAt(folder, 0, 'message'), 'message')!
    expect(p.thinkingStartedAt).toBe(1050)
    expect(p.thinkingDurationMs).toBe(4200)
  })

  it('思考耗时:历史回放(无瞬态帧)由里程碑 message 的权威值还原;无值时不臆造', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'message', {
      content: '正文',
      data: { thinking: '历史思考', thinkingStartedAt: 1000, thinkingDurationMs: 3300 },
    }, MAIN, 5000))
    const p = payloadAs(itemAt(folder, 0, 'message'), 'message')!
    expect(p.thinkingStartedAt).toBe(1000)
    expect(p.thinkingDurationMs).toBe(3300)

    // 老任务(无该两字段):不臆造耗时/起点
    const legacy = newFolder(MAIN)
    legacy.fold(ev(SEQ_100, 'message', { content: '正文', data: { thinking: '老思考' } }, MAIN, 5000))
    const lp = payloadAs(itemAt(legacy, 0, 'message'), 'message')!
    expect(lp.thinkingStartedAt).toBeUndefined()
    expect(lp.thinkingDurationMs).toBeUndefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 4. roundRealFloor / roundRealCeiling 懒加载缓存判定(只计消息族 kind)
// ─────────────────────────────────────────────────────────────────────────────

describe('roundRealFloor / roundRealCeiling 懒加载缓存判定', () => {
  it('roundRealFloor:区间 (startSeq, endSeq] 内消息族条目的最大 seq;endSeq null 上界 ∞;不含起点 user', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'user.message', { content: '用户' }, MAIN))
    folder.fold(ev(SEQ_150, 'message', { content: '过程' }, MAIN))
    folder.fold(ev(SEQ_200, 'message', { content: '最终' }, MAIN))
    // 前向缓存判定:maxReal >= endSeq 即整轮已完整覆盖
    expect(folder.roundRealFloor(SEQ_100, SEQ_200)).toBe(SEQ_200)
    expect(folder.roundRealFloor(SEQ_100, SEQ_150)).toBe(SEQ_150)
    // endSeq null(未闭合轮)上界 ∞
    expect(folder.roundRealFloor(SEQ_100, null)).toBe(SEQ_200)
    // 区间内无命中 → null(起点 user 因 seq <= startSeq 不计入)
    expect(folder.roundRealFloor(SEQ_200, null)).toBeNull()
    expect(folder.roundRealFloor(SEQ_300, null)).toBeNull()
  })

  it('roundRealFloor:trace 族条目不参与(只计 user.message/message/tool.result);大数 seq 精确比较', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'user.message', { content: '用户' }, MAIN))
    folder.fold(ev(SEQ_150, 'future.kind', { content: 'x' }, MAIN)) // trace 族(defaultHandler)
    folder.fold(ev(SEQ_200, 'message', { content: '最终' }, MAIN))
    expect(Number(SEQ_150)).toBe(Number(SEQ_200)) // 前置:Number 已坍缩,比较必须走字符串
    // trace 不算覆盖终点;终点条目缺失 → 区间内最大真实 message 仍为 200
    expect(folder.roundRealFloor(SEQ_100, SEQ_150)).toBeNull()
    expect(folder.roundRealFloor(SEQ_100, SEQ_200)).toBe(SEQ_200)
  })

  it('roundRealCeiling:区间 [startSeq, beforeSeq) 内消息族条目的最小 seq(起点 user 计入)', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'user.message', { content: '用户' }, MAIN))
    folder.fold(ev(SEQ_150, 'message', { content: '过程' }, MAIN))
    folder.fold(ev(SEQ_200, 'message', { content: '最终' }, MAIN))
    // backward 缓存判定:minReal <= startKey 即已连到轮起点(起点 user 在区间内)
    expect(folder.roundRealCeiling(SEQ_100, SEQ_200)).toBe(SEQ_100)
    expect(folder.roundRealCeiling(SEQ_100, SEQ_500)).toBe(SEQ_100)
    expect(folder.roundRealCeiling(SEQ_150, SEQ_500)).toBe(SEQ_150)
    // beforeSeq 自身不含:区间空 → null
    expect(folder.roundRealCeiling(SEQ_150, SEQ_150)).toBeNull()
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
    expect(seqsOf(folder)).toEqual([SEQ_100])

    // bySeq 已清理:同 seq 的新事件不判重,可重新折入
    expect(folder.fold(ev(SEQ_200, 'message', { content: '重发后的回复' }, MAIN))).toBe(true)
    expect(seqsOf(folder)).toEqual([SEQ_100, SEQ_200])
    expect(payloadAs(itemAt(folder, 1, 'message'), 'message')?.content).toBe('重发后的回复')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 6. agent 台账镜像(独立 store folder.ledger:seedAgents / usage 合并 / agentStates)
// ─────────────────────────────────────────────────────────────────────────────

describe('agent 台账镜像(folder.ledger)', () => {
  function ledgerItem(overrides: Partial<TaskAgentLedgerItem> = {}): TaskAgentLedgerItem {
    return { agentId: SUB, title: '子代理', createdAt: 222, ...overrides }
  }

  it('seedAgents 灌入基线(title/usage/context);主 agent 状态不入台账状态表', () => {
    const folder = newFolder(MAIN)
    const changed = folder.ledger.seedAgents([
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

    const mainMeta = folder.ledger.agentMeta[MAIN]
    expect(mainMeta?.title).toBe('主代理')
    expect(mainMeta?.inputTokens).toBe(10)
    expect(mainMeta?.outputTokens).toBe(5)
    expect(mainMeta?.totalTokens).toBe(15)
    expect(mainMeta?.contextUsed).toBe(100)
    expect(mainMeta?.contextWindow).toBe(1000)
    expect(mainMeta?.model).toBe('model-a')
    expect(mainMeta?.updatedAt).toBe(500)
    expect(folder.ledger.agentMeta[SUB]?.title).toBe('子代理')
    expect(folder.ledger.agentMeta[SUB]?.totalTokens).toBe(33)

    // 主 agent 状态不被台账写入;子 agent 终态写入 agentStates
    expect(folder.ledger.agentStates[MAIN]).toBeUndefined()
    expect(folder.ledger.agentStates[SUB]).toBe('completed')
  })

  it('usage 事件字段级合并(undefined 不覆盖已有值,updatedAt 取大)', () => {
    const folder = newFolder(MAIN)
    folder.ledger.seedAgents([ledgerItem({
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
    const meta = folder.ledger.agentMeta[MAIN]
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
    const meta2 = folder.ledger.agentMeta[MAIN]
    expect(meta2?.updatedAt).toBe(800)
    expect(meta2?.model).toBe('model-b')
    expect(meta2?.contextWindow).toBe(4000)
    expect(meta2?.inputTokens).toBe(200)
    expect(meta2?.contextUsed).toBe(60)

    // usage 是非 item 事件,不进线程
    expect(folder.state.items).toHaveLength(0)
  })

  it('agent.status 事件更新台账 agentStates(权威);子 agent 终态台账覆盖、非终态不覆盖;主 agent 状态不被台账覆盖', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'agent.status', { status: 'waiting-user' }, SUB, 1200))
    expect(folder.ledger.agentStates[SUB]).toBe('waiting-user')

    // 非终态台账不覆盖流事件实时状态
    folder.ledger.seedAgents([ledgerItem({ status: 'running' })], MAIN)
    expect(folder.ledger.agentStates[SUB]).toBe('waiting-user')
    // 终态台账覆盖(DataPusher 与 finish 驱逐竞态丢失 done 事件的兜底权威)
    folder.ledger.seedAgents([ledgerItem({ status: 'completed' })], MAIN)
    expect(folder.ledger.agentStates[SUB]).toBe('completed')

    // 主 agent:流事件状态不被台账终态按回(主 agent 状态是 per-run 的,交流事件)
    folder.fold(ev(SEQ_150, 'agent.status', { status: 'running' }, MAIN, 1300))
    expect(folder.ledger.agentStates[MAIN]).toBe('running')
    folder.ledger.seedAgents([ledgerItem({ agentId: MAIN, status: 'completed' })], MAIN)
    expect(folder.ledger.agentStates[MAIN]).toBe('running')
  })

  it('folder.reset() 只清线程项,不清台账;自增 resetEpoch(渲染层失效分页状态信号)', () => {
    const folder = newFolder(MAIN)
    folder.ledger.seedAgents([ledgerItem({ status: 'completed' })], MAIN)
    folder.fold(ev(SEQ_100, 'user.message', { content: '用户' }, MAIN))
    const epochBefore = folder.resetEpoch
    folder.reset()
    expect(folder.state.items).toHaveLength(0)
    expect(folder.resetEpoch).toBe(epochBefore + 1)
    expect(folder.ledger.agentStates[SUB]).toBe('completed')
    expect(folder.ledger.agentMeta[SUB]?.title).toBe('子代理')
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
// 8. 主/子 agent user.message 插入(五字段条目)
// ─────────────────────────────────────────────────────────────────────────────

describe('主/子 agent user.message 插入(五字段条目)', () => {
  it('主/子 agent 的 user.message 均 kind=user.message 插入,payload{content, rawContent}', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'user.message', { content: '主输入' }, MAIN))
    folder.fold(ev(SEQ_200, 'user.message', { content: '子输入' }, SUB))
    const mainItem = itemAt(folder, 0, 'user.message')
    const subItem = itemAt(folder, 1, 'user.message')
    expect(mainItem.agentId).toBe(MAIN)
    expect(mainItem.seq).toBe(SEQ_100)
    expect(mainItem.ts).toBe(1000)
    expect(mainItem.payload).toEqual({ content: '主输入', rawContent: '主输入' })
    expect(fiveKeysOf(mainItem)).toEqual(['agentId', 'kind', 'payload', 'seq', 'ts'])
    expect(subItem.agentId).toBe(SUB)
    expect(subItem.payload).toEqual({ content: '子输入', rawContent: '子输入' })
    expect(fiveKeysOf(subItem)).toEqual(['agentId', 'kind', 'payload', 'seq', 'ts'])
  })

  it('data.rawContent 优先进 payload(含 opaque token 串,回放还原胶囊用)', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'user.message', {
      content: 'AI 可见明文',
      data: { rawContent: '原始输入(token 串)' },
    }, MAIN))
    expect(itemAt(folder, 0, 'user.message').payload)
      .toEqual({ content: 'AI 可见明文', rawContent: '原始输入(token 串)' })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 9. defaultHandler 兜底(未注册 kind)
// ─────────────────────────────────────────────────────────────────────────────

describe('defaultHandler 兜底(未注册 kind)', () => {
  it('按 trace 族折入(kind=事件名,payload{title,summary,content,data} 宽松读取)', () => {
    const folder = newFolder(MAIN)
    expect(folder.fold(ev(SEQ_100, 'future.kind', {
      title: '未来事件',
      summary: '摘要',
      content: '内容',
      data: { foo: 'bar' },
    }, MAIN, 1700))).toBe(true)
    expect(folder.state.items).toHaveLength(1)
    const item = folder.state.items[0]!
    expect(item.kind).toBe('future.kind')
    expect(item.seq).toBe(SEQ_100)
    expect(item.agentId).toBe(MAIN)
    expect(item.ts).toBe(1700)
    const trace = item.payload as TracePayload
    expect(trace.title).toBe('未来事件')
    expect(trace.summary).toBe('摘要')
    expect(trace.content).toBe('内容')
    expect(trace.data).toEqual({ foo: 'bar' })
  })

  it('同 seq 重复 upsert 原地覆盖,不新增线程项', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'future.kind', { content: '第一版' }, MAIN, 1700))
    folder.fold(ev(SEQ_100, 'future.kind', { content: '第二版' }, MAIN, 1800))
    expect(folder.state.items).toHaveLength(1)
    const item = folder.state.items[0]!
    expect((item.payload as TracePayload).content).toBe('第二版')
    expect(item.ts).toBe(1800)
  })

  it('字段缺失时宽松降级(title 空串;summary/content/data undefined)', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'future.kind', {}, MAIN, 1700))
    const trace = folder.state.items[0]!.payload as TracePayload
    expect(trace.title).toBe('')
    expect(trace.summary).toBeUndefined()
    expect(trace.content).toBeUndefined()
    expect(trace.data).toBeUndefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 10. error / cancelled 折入(kind=error/cancelled,payload=trace 形状)
// ─────────────────────────────────────────────────────────────────────────────

describe('error / cancelled 折入', () => {
  it('error → kind=error,payload{title,summary,content};title 主/子按 isMain 判定', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'error', { content: 'boom' }, MAIN))
    folder.fold(ev(SEQ_200, 'error', { message: 'sub boom' }, SUB))
    const mainErr = itemAt(folder, 0, 'error')
    expect(mainErr.agentId).toBe(MAIN)
    expect(mainErr.payload).toEqual({ title: '任务出错', summary: 'boom', content: 'boom' })
    const subErr = itemAt(folder, 1, 'error')
    expect(subErr.agentId).toBe(SUB)
    expect(subErr.payload).toEqual({ title: '子任务出错', summary: 'sub boom', content: 'sub boom' })
  })

  it('cancelled → kind=cancelled,payload{title,summary};归属空串(消费方归一主 agent)', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'cancelled', { data: { by: 'haigui' } }, MAIN))
    const item = itemAt(folder, 0, 'cancelled')
    expect(item.agentId).toBe('')
    expect(item.payload).toEqual({ title: '任务已取消', summary: '由 haigui 取消' })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 11. message 定稿后线程项为五字段(markFinalReply 已删)
// ─────────────────────────────────────────────────────────────────────────────

describe('message 定稿后线程项为五字段', () => {
  it('主 agent 无工具调用且有正文的 message 定稿 → 五字段条目', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'user.message', { content: '用户' }, MAIN))
    folder.fold(ev(SEQ_200, 'delta', { content: '流式正文' }, MAIN, 1200))
    folder.fold(ev(SEQ_200, 'message', { content: '最终回复正文' }, MAIN, 1300))
    const item = itemAt(folder, 1, 'message')
    const p = payloadAs(item, 'message') as MessagePayload
    expect(p.content).toBe('最终回复正文')
    expect(p.streaming).toBe(false)
    expect(fiveKeysOf(item)).toEqual(['agentId', 'kind', 'payload', 'seq', 'ts'])
  })

  it('带 toolCalls 的 message 定稿:toolCalls 记录在 payload,条目五字段', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_200, 'message', {
      content: '先调用工具',
      data: { toolCalls: [{ id: 'call-1', name: 'toolA', arguments: '{}' }] },
    }, MAIN, 1300))
    const item = itemAt(folder, 0, 'message')
    expect(payloadAs(item, 'message')?.toolCalls?.map((c) => c.id)).toEqual(['call-1'])
    expect(fiveKeysOf(item)).toEqual(['agentId', 'kind', 'payload', 'seq', 'ts'])
  })

  it('空正文与子 agent 的 message 定稿同样插入且为五字段', () => {
    const folder = newFolder(MAIN)
    folder.fold(ev(SEQ_100, 'message', { content: '' }, MAIN, 1300))
    folder.fold(ev(SEQ_200, 'message', { content: '子代理回复' }, SUB, 1400))
    expect(fiveKeysOf(itemAt(folder, 0, 'message'))).toEqual(['agentId', 'kind', 'payload', 'seq', 'ts'])
    expect(fiveKeysOf(itemAt(folder, 1, 'message'))).toEqual(['agentId', 'kind', 'payload', 'seq', 'ts'])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 12. tool.started 瞬态运行时映射(步骤 10:不进 items,记入 toolStartedRuntime)
// ─────────────────────────────────────────────────────────────────────────────

describe('tool.started 瞬态运行时映射(不进 items)', () => {
  it('fold 不产生线程项,handler 记入瞬态映射并返回 true(驱动重渲染)', () => {
    const folder = new TaskEventFolder(emptyThreadState('task-toolstarted-a'))
    const changed = folder.fold(ev(SEQ_100, 'tool.started', {
      content: '',
      data: { callId: 'call-1', name: 'read_file', arguments: '{"path":"a.md"}', startedAt: 12345 },
    }, MAIN, 1100))
    expect(changed).toBe(true)
    expect(folder.state.items).toHaveLength(0)
    const started = getToolStarted('task-toolstarted-a', 'call-1')
    expect(started?.name).toBe('read_file')
    expect(started?.arguments).toBe('{"path":"a.md"}')
    expect(started?.startedAt).toBe(12345)
  })

  it('缺 callId 的事件丢弃(返回 false);startedAt 缺失回退事件 ts', () => {
    const folder = new TaskEventFolder(emptyThreadState('task-toolstarted-b'))
    expect(folder.fold(ev(SEQ_100, 'tool.started', { content: '' }, MAIN, 1100))).toBe(false)
    folder.fold(ev(SEQ_200, 'tool.started', { content: '', data: { callId: 'call-9', name: 'x' } }, MAIN, 2400))
    expect(getToolStarted('task-toolstarted-b', 'call-9')?.startedAt).toBe(2400)
  })

  it('reset 清空该任务的瞬态映射(编辑重发/全量重载);同 callId 重复到达覆盖', () => {
    const folder = new TaskEventFolder(emptyThreadState('task-toolstarted-c'))
    folder.fold(ev(SEQ_100, 'tool.started', {
      content: '', data: { callId: 'call-1', name: 'a', startedAt: 1 },
    }, MAIN, 1100))
    folder.fold(ev(SEQ_150, 'tool.started', {
      content: '', data: { callId: 'call-1', name: 'a2', startedAt: 2 },
    }, MAIN, 1200))
    expect(getToolStarted('task-toolstarted-c', 'call-1')?.name).toBe('a2')
    folder.fold(ev(SEQ_200, 'user.message', { content: '输入' }, MAIN, 1300))
    folder.reset()
    expect(getToolStarted('task-toolstarted-c', 'call-1')).toBeUndefined()
    // reset 后 items 清空(既有语义回归确认)
    expect(folder.state.items).toHaveLength(0)
  })
})
