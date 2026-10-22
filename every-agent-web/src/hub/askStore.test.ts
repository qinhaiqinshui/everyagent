/**
 * askStore 终态清理与复活语义回归测试。
 *
 * 起因(bug:t_tb2g):任务曾失败(error 事件落盘)后重跑再 ask_user,前端上翻分页/
 * 展开旧轮从磁盘折入历史 error 事件 → isTerminalEvent → clearAsksOfTask 无差别清掉
 * 当前挂起 ask,且 settledAskIds 永久压制 → 弹窗自动隐藏、无入口重开,30s 的
 * ask.state(pending) 重发也无法复活。
 *
 * 修复语义:
 * - clearAsksOfTask 携带终态事件 seq 时只结算 seq 早于该事件的 ask;
 * - worker 已确认落定(resolved/超时/作答回执)的 ask,迟到实时帧不复活(防 30s
 *   refresher 与作答的竞态尾帧);
 * - 本地失效清理(worker 连接集合变化等,无 worker 确认)误清的 ask,由 worker 实时
 *   ask.create/ask.state(pending) 复活(worker 仍在等待即应可见可答,G4);
 * - 回放帧(initial)永不复活已落定 ask(防已完成任务回放闪烁);
 * - 无 seq 的 ask(旧数据兼容)保持「任务终态清全部」原语义。
 */
import { describe, expect, it, vi } from 'vitest'

// notifyApp 走 antd notification(node 测试环境无 DOM),mock 成空实现。
vi.mock('@/utils/appNotifications', () => ({
  notifyApp: vi.fn(() => 'test-notification'),
  removeAppNotification: vi.fn(),
}))

import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import {
  clearAsksOfTask,
  getPendingRuntimeUserInteractions,
  settleAsk,
  upsertPendingAsk,
  type WorkerAskPayload,
} from './askStore'

/** 构造最小 ask payload(新协议 questions 形状)。 */
function askPayload(askId: string): WorkerAskPayload {
  return {
    askId,
    questions: [{
      id: `${askId}_0`,
      prompt: '要继续吗?',
      options: [{ label: '是', value: '是', type: 'radio' }],
    }],
  }
}

/** 等领域事件总线(queueMicrotask 派发)冲刷一拍。 */
async function flushBus(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0))
}

/** 挂起中的 askId 列表。 */
function pendingIds(): string[] {
  return getPendingRuntimeUserInteractions().map((r) => r.id)
}

describe('askStore 终态清理按 seq 判定', () => {
  it('历史终态事件(seq 早于 ask.create)不清当前挂起 ask', () => {
    // 重跑后新一轮 ask(seq=500);上翻分页折入旧 error 轮(seq=100)。
    upsertPendingAsk('t_hist', askPayload('q_hist'), { seq: '500' })
    expect(pendingIds()).toContain('q_hist')
    clearAsksOfTask('t_hist', '100')
    expect(pendingIds()).toContain('q_hist')
  })

  it('真终态事件(seq 晚于 ask.create)清挂起 ask 并广播 CLEARED', async () => {
    const cleared: string[] = []
    const unsubscribe = domainEventBus.subscribe(
      DOMAIN_EVENTS.USER_INTERACTION_CLEARED,
      ({ interactionId }) => { cleared.push(interactionId) },
    )
    upsertPendingAsk('t_term', askPayload('q_term'), { seq: '500' })
    clearAsksOfTask('t_term', '600')
    expect(pendingIds()).not.toContain('q_term')
    await flushBus()
    expect(cleared).toContain('q_term')
    unsubscribe()
  })

  it('不携带 seq 的任务级清理(worker 连接失效)仍清全部', () => {
    upsertPendingAsk('t_inval', askPayload('q_inval'), { seq: '500' })
    clearAsksOfTask('t_inval')
    expect(pendingIds()).not.toContain('q_inval')
  })

  it('无 seq 的 ask(旧数据兼容)保持原清理语义', () => {
    upsertPendingAsk('t_legacy', askPayload('q_legacy'))
    clearAsksOfTask('t_legacy', '999')
    expect(pendingIds()).not.toContain('q_legacy')
  })

  it('只清同任务的 ask,其它任务不受影响', () => {
    upsertPendingAsk('t_a', askPayload('q_a'), { seq: '500' })
    upsertPendingAsk('t_b', askPayload('q_b'), { seq: '500' })
    clearAsksOfTask('t_a', '600')
    expect(pendingIds()).not.toContain('q_a')
    expect(pendingIds()).toContain('q_b')
  })
})

describe('askStore 已落定 ask 的复活语义', () => {
  it('worker 已确认落定(ask.resolved)的 ask,迟到实时帧不复活', () => {
    upsertPendingAsk('t_res', askPayload('q_res'), { seq: '500' })
    settleAsk('q_res', { workerConfirmed: true })
    expect(pendingIds()).not.toContain('q_res')
    // refresher 竞态尾帧:实时(非回放)ask.state(pending) 迟到到达。
    upsertPendingAsk('t_res', askPayload('q_res'), { seq: '700' })
    expect(pendingIds()).not.toContain('q_res')
  })

  it('本地失效清理误清的 ask,由实时 ask.state(pending) 复活(G4)', async () => {
    const requested: string[] = []
    const unsubscribe = domainEventBus.subscribe(
      DOMAIN_EVENTS.USER_INTERACTION_REQUESTED,
      ({ request }) => { requested.push(request.id) },
    )
    upsertPendingAsk('t_revive', askPayload('q_revive'), { seq: '500' })
    clearAsksOfTask('t_revive') // worker 连接集合变化误清(无 worker 确认)
    expect(pendingIds()).not.toContain('q_revive')
    // worker 仍在等待:30s refresher 的实时重发(非回放帧)→ 复活弹窗/悬浮入口。
    upsertPendingAsk('t_revive', askPayload('q_revive'), { seq: '800' })
    expect(pendingIds()).toContain('q_revive')
    await flushBus()
    expect(requested).toContain('q_revive')
    unsubscribe()
  })

  it('回放帧(initial)不复活已落定 ask(防已完成任务回放闪烁)', () => {
    upsertPendingAsk('t_replay', askPayload('q_replay'), { seq: '500' })
    clearAsksOfTask('t_replay')
    upsertPendingAsk('t_replay', askPayload('q_replay'), { seq: '500', initial: true })
    expect(pendingIds()).not.toContain('q_replay')
  })

  it('回放注册的 ask 在 flush 前被真终态事件(按 seq)结算时不弹出', () => {
    // 回放(静默注册,emitted=false)→ 历史回放内晚于 ask 的终态事件仍应结算它
    // (已完成任务:ask.create 后任务结束)。
    upsertPendingAsk('t_done', askPayload('q_done'), { seq: '500', initial: true })
    clearAsksOfTask('t_done', '600')
    expect(pendingIds()).not.toContain('q_done')
  })
})
