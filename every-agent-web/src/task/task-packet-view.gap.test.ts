/**
 * 推送丢帧检测(§7.13 降级语义)单测:TaskPacketView 按 ext.creditIndex 跳号检出
 * worker→hub/hub→前端链路丢弃的帧并回调 onFramesLost;creditIndex 回退(新推送器会话)
 * 与 open()/close() 重置基线不误报。
 */
import { describe, expect, it, vi } from 'vitest'
import type { HubClient } from '../sdk/hub-client'
import { channels } from '../sdk/channels'
import type { MsgFrame } from '../sdk/frames'
import { TaskPacketView } from './task-packet-view'

const K = 'test-owner-key'
const WORKER = 'wk-1'
const TASK = 't_test'

interface FakeClient {
  k: string
  listener: ((frame: MsgFrame) => void) | null
  pubs: Array<{ channel: string; event: string; payload: unknown }>
}

function makeClient(): { client: HubClient; fake: FakeClient } {
  const fake: FakeClient = { k: K, listener: null, pubs: [] }
  const client = {
    k: K,
    addMessageListener: (fn: (frame: MsgFrame) => void) => { fake.listener = fn },
    removeMessageListener: () => { fake.listener = null },
    sub: vi.fn(),
    unsub: vi.fn(),
    pub: (channel: string, event: string, payload?: unknown) => {
      fake.pubs.push({ channel, event, payload })
    },
    rpc: vi.fn(),
  } as unknown as HubClient
  return { client, fake }
}

function makeView(client: HubClient) {
  return new TaskPacketView(client, WORKER, TASK)
}

/** 构造一条 stream 推送帧(默认带 credit)。 */
function frame(view: TaskPacketView, seq: string, event = 'message', creditIndex?: number, extExtra?: Record<string, unknown>): MsgFrame {
  const streamCh = channels.taskStream(K, WORKER, TASK)
  // 借道视图内部同款频道名构造:直接拼不如复用(视图构造时已冻结同一字符串)
  const ch = (view as unknown as { streamCh: string }).streamCh ?? streamCh
  return {
    type: 'msg',
    channel: ch,
    event,
    seq,
    ts: 1,
    payload: { content: 'x', agentId: 'a_1' },
    ext: { credit: true, creditIndex, operate: 'replace', ...extExtra },
  } as MsgFrame
}

describe('TaskPacketView 推送丢帧检测', () => {
  it('顺序 creditIndex 不触发丢帧回调', () => {
    const { client, fake } = makeClient()
    const view = makeView(client)
    const lost = vi.fn()
    view.onFramesLost(lost)
    view.onEvent(() => {})
    void view.open()

    fake.listener?.(frame(view, '100', 'message', 0))
    fake.listener?.(frame(view, '200', 'message', 1))
    fake.listener?.(frame(view, '300', 'delta', 2))
    expect(lost).not.toHaveBeenCalled()
    view.close()
  })

  it('creditIndex 跳号触发丢帧回调(缺口前一帧 seq → 缺口后首帧 seq)', () => {
    const { client, fake } = makeClient()
    const view = makeView(client)
    const lost = vi.fn()
    view.onFramesLost(lost)
    view.onEvent(() => {})
    void view.open()

    fake.listener?.(frame(view, '100', 'message', 0))
    fake.listener?.(frame(view, '200', 'message', 1))
    // creditIndex 2 丢失:下一帧 3
    fake.listener?.(frame(view, '400', 'message', 3))
    expect(lost).toHaveBeenCalledTimes(1)
    // fromSeq = 缺口前一帧(200),toSeq = 缺口后首帧(400)
    expect(lost).toHaveBeenCalledWith('200', '400')
    view.close()
  })

  it('连续丢多帧只报一个缺口区间', () => {
    const { client, fake } = makeClient()
    const view = makeView(client)
    const lost = vi.fn()
    view.onFramesLost(lost)
    view.onEvent(() => {})
    void view.open()

    fake.listener?.(frame(view, '100', 'message', 0))
    fake.listener?.(frame(view, '200', 'message', 1))
    fake.listener?.(frame(view, '500', 'message', 9))
    expect(lost).toHaveBeenCalledTimes(1)
    expect(lost).toHaveBeenCalledWith('200', '500')
    view.close()
  })

  it('creditIndex 回退(新推送器会话)重置基线不误报', () => {
    const { client, fake } = makeClient()
    const view = makeView(client)
    const lost = vi.fn()
    view.onFramesLost(lost)
    view.onEvent(() => {})
    void view.open()

    fake.listener?.(frame(view, '100', 'message', 0))
    fake.listener?.(frame(view, '200', 'message', 1))
    fake.listener?.(frame(view, '300', 'message', 2))
    // 新推送器会话(重连/visible 重订):从 0 重新编号
    fake.listener?.(frame(view, '400', 'message', 0))
    fake.listener?.(frame(view, '500', 'message', 1))
    expect(lost).not.toHaveBeenCalled()
    // 新会话内继续顺序推进
    fake.listener?.(frame(view, '600', 'message', 2))
    expect(lost).not.toHaveBeenCalled()
    view.close()
  })

  it('open() 重订阅后基线重置(新推送器会话)', () => {
    const { client, fake } = makeClient()
    const view = makeView(client)
    const lost = vi.fn()
    view.onFramesLost(lost)
    view.onEvent(() => {})
    void view.open()
    fake.listener?.(frame(view, '100', 'message', 5))
    void view.open() // resync 重订阅:推送器重建,编号从 0 起
    fake.listener?.(frame(view, '200', 'message', 0))
    expect(lost).not.toHaveBeenCalled()
    view.close()
  })

  it('message.edited 帧同样参与连续性对账', () => {
    const { client, fake } = makeClient()
    const view = makeView(client)
    const lost = vi.fn()
    view.onFramesLost(lost)
    view.onEvent(() => {})
    void view.open()

    fake.listener?.(frame(view, '100', 'message', 0))
    // message.edited 提前 return 的分支:必须在分支前完成对账(基线推进到 1)
    fake.listener?.(frame(view, '150', 'message.edited', 1))
    // creditIndex 2 丢失
    fake.listener?.(frame(view, '300', 'message', 3))
    expect(lost).toHaveBeenCalledTimes(1)
    expect(lost).toHaveBeenCalledWith('150', '300')
    view.close()
  })

  it('非 stream 频道帧不参与对账', () => {
    const { client, fake } = makeClient()
    const view = makeView(client)
    const lost = vi.fn()
    view.onFramesLost(lost)
    view.onEvent(() => {})
    void view.open()

    fake.listener?.({
      type: 'msg',
      channel: 'u.other.task.t_x.stream',
      event: 'message',
      seq: '999',
      ts: 1,
      payload: {},
      ext: { credit: true, creditIndex: 50 },
    } as MsgFrame)
    fake.listener?.(frame(view, '100', 'message', 0))
    expect(lost).not.toHaveBeenCalled()
    view.close()
  })
})
