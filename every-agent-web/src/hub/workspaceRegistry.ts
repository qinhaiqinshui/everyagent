/**
 * 工作区注册表服务(架构 §5.9/D16):多工作区并行,本服务是注册表的前端唯一事实源。
 *
 * 多 worker 模型:遍历所有已连 worker 并发 workspaces.list 合并(条目带来源 workerId);
 * workspaces.changed 广播按帧来源 worker 定向更新;禁用/断开 worker 后其工作区移除。
 *
 * 注册表变化时广播 WORKSPACE_REGISTRY_CHANGED 领域事件(资源管理器/任务页/git
 * 徽标等订阅方自行消费);前端无全局"当前工作区"概念(D16):调用方显式携带目标
 * 工作区根,未指定时用 primaryRoot 兜底。
 */
import { channels } from '@every-agent/client'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import { hubSession } from './session'

/** 注册表条目(workspaces.list 应答单项;workerId 为来源 worker)。 */
export interface WorkspaceEntry {
  /** 工作区根(worker 机器上的绝对路径,即身份键)。 */
  root: string
  /** 注册时间(ms)。 */
  addedAt: number
  /** 来源 worker(多 worker 合并后区分归属)。 */
  workerId: string
}

/** workspaces.list 应答(worker 端形状,workerId 由前端补)。 */
export interface WorkspaceRegistry {
  defaultRoot: string
  workspaces: WorkspaceEntry[]
}

type RegistryListener = (registry: WorkspaceRegistry) => void

/** presence 快照是每台 worker 一条的事件突发,刷新做短窗合并。 */
const REFRESH_COALESCE_MS = 200

class WorkspaceRegistryService {
  current: WorkspaceRegistry | null = null

  /** workerId → 该 worker 最近一次注册表(合并前的原始镜像)。 */
  private byWorker = new Map<string, WorkspaceRegistry>()

  private listeners = new Set<RegistryListener>()
  private wired = false
  private refreshTimer: ReturnType<typeof setTimeout> | null = null
  private refreshInFlight: Promise<void> | null = null
  /** 广播/add/remove 的变更版本号:refresh 拉取期间若变化,说明其快照可能陈旧,须丢弃覆盖。 */
  private mutation = 0

  /** 订阅注册表更新(含未变化的应用幂等刷新)。 */
  subscribe(fn: RegistryListener): () => void {
    this.listeners.add(fn)
    if (this.current) {
      fn(this.current)
    }
    return () => {
      this.listeners.delete(fn)
    }
  }

  /** 首个注册工作区(调用方未显式指定时的确定性别默认,如 task.run 新建/git 页)。 */
  primaryRoot(): string | null {
    return this.current?.workspaces[0]?.root ?? null
  }

  /** 按工作区根反查来源 worker(多 worker 合并后按归属定向 RPC);未找到返回 null。 */
  workerIdOfRoot(root: string): string | null {
    for (const [workerId, registry] of this.byWorker) {
      if (registry.workspaces.some((entry) => entry.root === root)) {
        return workerId
      }
    }
    return null
  }

  /** 指定 worker 自己注册的工作区列表(多 worker 合并后按 worker 过滤);无则空数组。 */
  workspacesOf(workerId: string): WorkspaceEntry[] {
    const registry = this.byWorker.get(workerId)
    if (!registry || !Array.isArray(registry.workspaces)) return []
    return registry.workspaces
  }

  /** 进程内接线一次(main.tsx 调用):监听 workspaces.changed / 重连 / presence。 */
  wire(): void {
    if (this.wired) return
    this.wired = true
    try {
      localStorage.removeItem('ea.web.workspace')
    } catch {
      // 忽略:隐私模式等场景
    }
    hubSession.onFrame((frame) => {
      if (frame.event !== 'workspaces.changed') return
      const workerId = hubSession.workerIdOfFrame(frame)
      if (!workerId) return
      const client = hubSession.workerClients.get(workerId)
      if (!client || !client.k) return
      // 只收该 worker 自己的 evt 频道广播。
      if (frame.channel !== channels.workerEvt(client.k, workerId)) return
      // 广播即权威变更:标记版本,使在途 refresh 的陈旧快照不再覆盖。
      this.mutation++
      this.applyFor(workerId, frame.payload as WorkspaceRegistry | null)
    })
    hubSession.onResync(() => {
      void this.refresh()
    })
    hubSession.onWorkers(() => {
      this.scheduleRefresh()
    })
    this.scheduleRefresh()
  }

  /** 立即遍历所有已连 worker 校准并合并注册表。 */
  async refresh(): Promise<void> {
    if (this.refreshInFlight) return this.refreshInFlight
    const stamp = this.mutation
    this.refreshInFlight = (async () => {
      try {
        const results = new Map<string, WorkspaceRegistry | null>()
        const pending: Array<Promise<void>> = []
        hubSession.forEachConnectedWorker((workerId, client) => {
          pending.push((async () => {
            try {
              const registry = await client.rpc(workerId, 'workspaces.list') as WorkspaceRegistry
              results.set(workerId, registry)
            } catch {
              results.set(workerId, null)
            }
          })())
        })
        await Promise.all(pending)
        // 移除已断开的 worker 的旧镜像(保持与 forEachConnectedWorker 一致)。
        const connected = new Set<string>()
        hubSession.forEachConnectedWorker((workerId) => connected.add(workerId))
        for (const workerId of this.byWorker.keys()) {
          if (!connected.has(workerId)) {
            this.byWorker.delete(workerId)
          }
        }
        // 拉取期间收到过广播/add/remove:本次快照可能陈旧(例如另一端新建工作区后
        // 广播已应用,而这里的 list 应答仍是不含新工作区的旧快照),丢弃覆盖,
        // 保留广播已落定的最新状态。
        if (this.mutation !== stamp) return
        for (const [workerId, registry] of results) {
          this.applyFor(workerId, registry)
        }
      } catch {
        // worker 离线/不支持:保留上次注册表,下次校准
      } finally {
        this.refreshInFlight = null
      }
    })()
    return this.refreshInFlight
  }

  /** 注册新工作区(资源管理器侧栏显式入口;成功即应用并广播)。 */
  async add(workerId: string, path: string): Promise<WorkspaceRegistry & { addedRoot?: string }> {
    const registry = await hubSession.rpcTo(workerId, 'workspaces.add', { path }) as WorkspaceRegistry & { addedRoot?: string }
    this.mutation++
    this.applyFor(workerId, registry)
    return registry
  }

  /** 移除注册(不删磁盘文件);成功即应用并广播。 */
  async remove(workerId: string, root: string): Promise<WorkspaceRegistry> {
    const registry = await hubSession.rpcTo(workerId, 'workspaces.remove', { root }) as WorkspaceRegistry
    this.mutation++
    this.applyFor(workerId, registry)
    return registry
  }

  private scheduleRefresh(): void {
    if (this.refreshTimer) return
    this.refreshTimer = setTimeout(() => {
      this.refreshTimer = null
      void this.refresh()
    }, REFRESH_COALESCE_MS)
  }

  /** 应用单台 worker 的注册表并重建合并视图。 */
  private applyFor(workerId: string, registry: WorkspaceRegistry | null): void {
    if (!registry || !Array.isArray(registry.workspaces) || typeof registry.defaultRoot !== 'string') {
      this.byWorker.delete(workerId)
      this.rebuild()
      return
    }
    this.byWorker.set(workerId, {
      defaultRoot: registry.defaultRoot,
      workspaces: registry.workspaces.map((entry) => ({
        ...entry,
        workerId: entry.workerId || workerId,
      })),
    })
    this.rebuild()
  }

  /** 合并全部 worker 注册表:按启用顺序依次取,workspaces 跨 worker 去重并保留来源。 */
  private rebuild(): void {
    const ordered = hubSession.directory
      .filter((w) => w.enabled)
      .map((w) => w.workerId)
    const workspaces: WorkspaceEntry[] = []
    const seen = new Set<string>()
    let defaultRoot = ''
    for (const workerId of ordered) {
      const registry = this.byWorker.get(workerId)
      if (!registry) continue
      if (!defaultRoot && registry.defaultRoot) {
        defaultRoot = registry.defaultRoot
      }
      for (const entry of registry.workspaces) {
        if (seen.has(entry.root)) continue
        seen.add(entry.root)
        workspaces.push({ ...entry, workerId })
      }
    }
    // 顺序外但已连接的 worker 也纳入(目录尚未刷新时兜底)。
    for (const [workerId, registry] of this.byWorker) {
      if (ordered.includes(workerId)) continue
      if (!defaultRoot && registry.defaultRoot) {
        defaultRoot = registry.defaultRoot
      }
      for (const entry of registry.workspaces) {
        if (seen.has(entry.root)) continue
        seen.add(entry.root)
        workspaces.push({ ...entry, workerId })
      }
    }
    const prev = this.current
    this.current = { defaultRoot, workspaces }
    for (const fn of this.listeners) {
      fn(this.current)
    }
    const prevRoots = new Set(prev?.workspaces.map((entry) => entry.root))
    const nextRoots = workspaces.map((entry) => entry.root)
    if (!prev || prevRoots.size !== nextRoots.length || nextRoots.some((root) => !prevRoots.has(root))) {
      domainEventBus.emit(DOMAIN_EVENTS.WORKSPACE_REGISTRY_CHANGED, this.current)
    }
  }
}

export const workspaceRegistry = new WorkspaceRegistryService()