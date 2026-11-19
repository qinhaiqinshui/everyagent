/**
 * file-change 插件 roundChangesStore 自愈重拉测试。
 *
 * 运行(仓库根):
 *   every-agent-web\node_modules\.bin\vitest run --config every-agent-plugins\file-change\vitest.config.ts
 *
 * 背景事故:t_dlp3 后两轮 update_file 已正确落盘/可 RPC 拉取,但轮末「文件变更」面板为空——
 * 瞬态直推错过 + ensure 的 loaded 门首拉后永不再拉,该轮在会话内恒缺,只能整页刷新找回。
 * 本测试钉住自愈语义:已 loaded 且某闭合轮缺数据 → 绕过 loaded 门节流强制重拉并 REPLACE 缓存。
 *
 * store 的模块级缓存(Map)按测试隔离:每例 vi.resetModules + 动态 import 全新实例;
 * PluginContext 以最小桩注入(只需 sdk.rpc / sdk.workerId)。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { PluginContext } from '@everyagent/plugin-api'

/** 造一个可控 rpc 桩:按调用序返回预设应答,记录调用参数。 */
function makeSdk(responses: Array<Record<string, unknown> | Error>) {
  const rpc = vi.fn(async (_worker: string, _method: string, _params: unknown) => {
    const r = responses[Math.min(rpc.mock.calls.length - 1, responses.length - 1)]
    if (r instanceof Error) throw r
    return r
  })
  return { sdk: { rpc, workerId: 'w1' } as unknown as PluginContext['sdk'], rpc }
}

/** 复制 svg-renderer 模式:每例全新模块实例,避免模块级缓存串例。 */
async function freshStore(sdk: PluginContext['sdk']) {
  const mod = await import('./web/roundChangesStore')
  const runtime = await import('./web/pluginRuntime')
  runtime.setPluginContext({ sdk } as PluginContext)
  return mod
}

/** 排空微任务:rpc 桩立即 resolve,一个宏拍足够让拉取链(.then/.finally)全部落定。 */
async function flush(): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0))
}

const R1: Array<{ roundId: string; changes: unknown[] }> = [
  { roundId: 'round_a', changes: [{ filePath: '/a.md', fileName: 'a.md', changeType: 'created', saveCount: 1 }] },
]

const R1_R2 = [
  ...R1,
  { roundId: 'round_b', changes: [{ filePath: '/a.md', fileName: 'a.md', changeType: 'updated', saveCount: 2 }] },
]

describe('roundChangesStore 自愈重拉', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.spyOn(console, 'debug').mockImplementation(() => {})
    vi.spyOn(console, 'warn').mockImplementation(() => {})
  })

  it('ensure 首拉建缓存;loaded 后再 ensure 不再发 RPC', async () => {
    const { sdk, rpc } = makeSdk([{ rounds: R1 }])
    const store = await freshStore(sdk)
    store.ensureRoundChanges('t1')
    await flush()
    expect(rpc).toHaveBeenCalledTimes(1)
    expect(store.getRoundChanges('t1', 'round_a')).toHaveLength(1)
    store.ensureRoundChanges('t1') // loaded 门:幂等直返
    expect(rpc).toHaveBeenCalledTimes(1)
  })

  it('闭合轮缺数据(直推错过)→ maybeRefreshMissingRound 绕过 loaded 门重拉补齐', async () => {
    const { sdk, rpc } = makeSdk([{ rounds: R1 }, { rounds: R1_R2 }])
    const store = await freshStore(sdk)
    store.ensureRoundChanges('t1')
    await flush()
    expect(rpc).toHaveBeenCalledTimes(1)

    // 模拟直推错过:round_b 不在缓存,但缓存已 loaded
    store.maybeRefreshMissingRound('t1', 'round_b')
    await flush()
    expect(rpc).toHaveBeenCalledTimes(2)
    expect(store.getRoundChanges('t1', 'round_b')).toHaveLength(1)
    // 已有数据的轮不再触发
    store.maybeRefreshMissingRound('t1', 'round_a')
    await flush()
    expect(rpc).toHaveBeenCalledTimes(2)
  })

  it('节流:窗口内多个缺失轮只强制重拉一次', async () => {
    const { sdk, rpc } = makeSdk([{ rounds: R1 }, { rounds: R1_R2 }])
    const store = await freshStore(sdk)
    store.ensureRoundChanges('t1')
    await flush()

    store.maybeRefreshMissingRound('t1', 'round_b') // 触发强制重拉
    store.maybeRefreshMissingRound('t1', 'round_c') // 窗口内:被节流吞掉
    await flush()
    expect(rpc).toHaveBeenCalledTimes(2)
    // 重拉已把 round_b 补上;round_c 仍缺但不再拉(节流兜住,不连环)
    expect(store.getRoundChanges('t1', 'round_b')).toHaveLength(1)
    expect(store.getRoundChanges('t1', 'round_c')).toBeUndefined()
  })

  it('未 loaded(首拉失败)时不自愈,留给 ensure 常规重试', async () => {
    const { sdk, rpc } = makeSdk([new Error('worker 不可达'), { rounds: R1 }])
    const store = await freshStore(sdk)
    store.ensureRoundChanges('t1') // 失败:loaded 仍 false
    await flush()
    expect(rpc).toHaveBeenCalledTimes(1)
    store.maybeRefreshMissingRound('t1', 'round_a') // 未 loaded 直接跳过
    await flush()
    expect(rpc).toHaveBeenCalledTimes(1)
    store.ensureRoundChanges('t1') // ensure 常规路径重试成功
    await flush()
    expect(rpc).toHaveBeenCalledTimes(2)
    expect(store.getRoundChanges('t1', 'round_a')).toHaveLength(1)
  })

  it('自愈重拉失败回滚节流:下次挂载可立即再试', async () => {
    const rx = [{ roundId: 'round_x', changes: [{ filePath: '/x.md', fileName: 'x.md', changeType: 'created', saveCount: 1 }] }]
    const rxy = [...rx, { roundId: 'round_y', changes: [{ filePath: '/y.md', fileName: 'y.md', changeType: 'updated', saveCount: 1 }] }]
    const { sdk, rpc } = makeSdk([{ rounds: rx }, new Error('boom'), { rounds: rxy }])
    const store = await freshStore(sdk)
    store.ensureRoundChanges('t1') // 首拉成功 → loaded
    await flush()
    store.maybeRefreshMissingRound('t1', 'round_y') // force → rpc 抛错,节流回滚
    await flush()
    expect(rpc).toHaveBeenCalledTimes(2)
    store.maybeRefreshMissingRound('t1', 'round_y') // 回滚后可立即再试 → 成功
    await flush()
    expect(rpc).toHaveBeenCalledTimes(3)
    expect(store.getRoundChanges('t1', 'round_y')).toHaveLength(1)
    expect(store.getRoundChanges('t1', 'round_x')).toHaveLength(1)
  })

  it('作废缓存同时清理节流时间戳', async () => {
    const { sdk, rpc } = makeSdk([{ rounds: R1 }, { rounds: R1 }, { rounds: R1 }, { rounds: R1 }])
    const store = await freshStore(sdk)
    store.ensureRoundChanges('t1')
    await flush()
    store.maybeRefreshMissingRound('t1', 'round_b') // force#1(占住节流窗)
    await flush()
    expect(rpc).toHaveBeenCalledTimes(2)
    store.maybeRefreshMissingRound('t1', 'round_b') // 窗口内:被节流
    await flush()
    expect(rpc).toHaveBeenCalledTimes(2)
    store.invalidateRoundChanges('t1')
    store.ensureRoundChanges('t1') // 重新首拉
    await flush()
    expect(rpc).toHaveBeenCalledTimes(3)
    store.maybeRefreshMissingRound('t1', 'round_b') // 作废清了节流:可再拉
    await flush()
    expect(rpc).toHaveBeenCalledTimes(4)
  })
})
