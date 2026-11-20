import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * task.dataDir 预取缓存:任务列表「复制任务路径」必须先拿到路径再进点击回调
 * (非安全上下文下写剪贴板依赖用户手势,跨 await 的取数会把手势吃掉)。
 * 这里钉住预取语义:落定后可同步取用、失败不留缓存、过期失效。
 */

const { rpcTo } = vi.hoisted(() => ({ rpcTo: vi.fn() }))

vi.mock('@/hub/session', () => ({ hubSession: { rpcTo } }))
vi.mock('@/hub/workspaceRegistry', () => ({ workspaceRegistry: { current: null, subscribe: () => () => {} } }))
vi.mock('@/task/taskStore', () => ({
  taskStore: {
    get: () => ({ workerId: 'w1' }),
    list: () => [],
    subscribe: () => () => {},
  },
}))

import { taskQueryService } from './taskQueryService'

/** 等预取的 promise 链跑完(微任务多轮:then + catch)。 */
const flush = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('taskQueryService 任务路径预取', () => {
  beforeEach(() => {
    rpcTo.mockReset()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('未预取时同步取值为 null(调用方据此退让到等待取数)', () => {
    expect(taskQueryService.getPrefetchedTaskDataDir('t-never-prefetched')).toBeNull()
  })

  it('预取落定后可在点击回调里同步取到路径', async () => {
    rpcTo.mockResolvedValue({ path: '/data/workspaces/ws/tasks/t1' })

    taskQueryService.prefetchTaskDataDir('t-hit')
    await flush()

    expect(rpcTo).toHaveBeenCalledWith('w1', 'task.dataDir', { taskId: 't-hit' })
    expect(taskQueryService.getPrefetchedTaskDataDir('t-hit')).toBe('/data/workspaces/ws/tasks/t1')
  })

  it('预取失败不留缓存(点击时重新取,错误由调用方提示),且不冒 unhandled rejection', async () => {
    rpcTo.mockRejectedValue(new Error('worker w1 未连接'))

    taskQueryService.prefetchTaskDataDir('t-fail')
    await flush()

    expect(taskQueryService.getPrefetchedTaskDataDir('t-fail')).toBeNull()
  })

  it('预取中不重复发起(同一任务只跑一次 RPC)', async () => {
    rpcTo.mockResolvedValue({ path: '/p' })

    taskQueryService.prefetchTaskDataDir('t-dedupe')
    taskQueryService.prefetchTaskDataDir('t-dedupe')
    await flush()

    expect(rpcTo).toHaveBeenCalledTimes(1)
  })

  it('过期条目失效(worker 数据目录迁移后不复用陈旧路径)', async () => {
    const now = vi.spyOn(Date, 'now')
    now.mockReturnValue(1_000)
    rpcTo.mockResolvedValue({ path: '/old' })

    taskQueryService.prefetchTaskDataDir('t-ttl')
    await flush()
    expect(taskQueryService.getPrefetchedTaskDataDir('t-ttl')).toBe('/old')

    // 超过 TTL(5 分钟)后再取:失效,触发下一次预取。
    now.mockReturnValue(1_000 + 5 * 60 * 1000)
    expect(taskQueryService.getPrefetchedTaskDataDir('t-ttl')).toBeNull()

    rpcTo.mockResolvedValue({ path: '/new' })
    taskQueryService.prefetchTaskDataDir('t-ttl')
    await flush()
    expect(taskQueryService.getPrefetchedTaskDataDir('t-ttl')).toBe('/new')
  })
})