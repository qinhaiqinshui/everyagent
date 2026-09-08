/**
 * 模型配置服务(架构 §5.8):前端可见的各 worker 模型配置列表唯一事实源(按 worker 分份)。
 *
 * 数据由 worker 的 Spring 配置 worker.models 承载(jar 内默认 + ~/.everyagent/application-worker.yaml
 * 覆盖),本服务经 hub 管道定向读取:
 * - config.get(按 worker 定向:连接/重连/presence 变化后逐台校准;apiKey 恒为掩码,明文不出 worker);
 * - config.changed{keys:["models"]}(按 hubSession.workerIdOfFrame 判定来源 worker 定向刷新)。
 * 模型配置**只读**:前端无新增/编辑/删除入口,也无 config.set 写路径。
 *
 * 多 worker 语义:不同 worker 的模型配置各不相同,缓存为 workerId → ModelConfigInfo[]。
 * 新建任务选中的 configId 持久化在 localStorage(全局前端偏好,与 worker 无关),
 * worker 端任务创建时快照冻结。
 */
import { hubSession } from './session'

/** worker ModelConfig 形状(config.get 返回;apiKey 为掩码或空)。 */
export interface ModelConfigInfo {
  configId: string
  provider: string
  baseUrl?: string | null
  model: string
  apiKey?: string | null
  params?: Record<string, unknown> | null
  isDefault?: boolean | null
  /** 容灾池成员 configId 列表;仅 provider=model-pool 的条目有值,普通模型为 null/缺省。 */
  members?: string[] | null
}

/** 思考强度可选项(OpenAI 风格 value,worker params.reasoningEffort 原样透传模型层)。 */
export const REASONING_EFFORT_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'minimal', label: '极低' },
  { value: 'low', label: '低' },
  { value: 'medium', label: '中' },
  { value: 'high', label: '高' },
]

const SELECTED_CONFIG_KEY = 'ea.web.modelConfigId'
/** presence 快照是每台 worker 一条的事件突发,刷新做短窗合并(每 worker 各一个定时器)。 */
const REFRESH_COALESCE_MS = 200

type ModelConfigsListener = (models: ModelConfigInfo[]) => void

class ModelConfigsService {
  /** 按 worker 分份的最新配置列表(空数组 = 尚未校准或该 worker 无配置)。 */
  private byWorker = new Map<string, ModelConfigInfo[]>()

  private workerListeners = new Map<string, Set<ModelConfigsListener>>()
  /** @deprecated 旧全局订阅者(TaskChat 迁移到 subscribeFor 前保留)。 */
  private legacyListeners = new Set<ModelConfigsListener>()

  private wired = false
  private refreshTimers = new Map<string, ReturnType<typeof setTimeout>>()
  private refreshInFlight = new Map<string, Promise<void>>()

  // ---- 按 worker 读取/订阅 ----

  /** 指定 worker 的模型配置列表(无缓存返回空数组)。 */
  currentFor(workerId: string): ModelConfigInfo[] {
    return this.byWorker.get(workerId) ?? []
  }

  /**
   * @deprecated 兼容旧调用方(TaskChat 迁移到 currentFor(workerId) 前保留):
   * 取首个已连(open)worker 的列表;无已连 worker 时为空数组。
   */
  get current(): ModelConfigInfo[] {
    return this.currentFor(this.firstConnectedWorkerId())
  }

  /** 订阅指定 worker 的配置变化:立即回调一次当前列表,随后该 worker 每次校准后回调。 */
  subscribeFor(workerId: string, fn: ModelConfigsListener): () => void {
    let set = this.workerListeners.get(workerId)
    if (!set) {
      set = new Set()
      this.workerListeners.set(workerId, set)
    }
    set.add(fn)
    fn(this.currentFor(workerId))
    return () => {
      set.delete(fn)
      if (set.size === 0) this.workerListeners.delete(workerId)
    }
  }

  /**
   * @deprecated 兼容旧调用方(TaskChat 迁移到 subscribeFor 前保留):
   * 立即回调一次 current(首个已连 worker 列表),任一 worker 校准后都会再回调。
   */
  subscribe(fn: ModelConfigsListener): () => void {
    this.legacyListeners.add(fn)
    fn(this.current)
    return () => {
      this.legacyListeners.delete(fn)
    }
  }

  // ---- 校准 ----

  /** 进程内接线一次(main.tsx 调用):监听 config.changed / 重连 / presence / 连接就绪。 */
  wire(): void {
    if (this.wired) return
    this.wired = true
    hubSession.onFrame((frame) => {
      if (frame.event !== 'config.changed') return
      const keys: unknown = (frame.payload as Record<string, unknown> | null)?.keys
      if (!Array.isArray(keys) || !keys.includes('models')) return
      // 定向刷新来源 worker;判不出来源(如目录连接帧)则全量校准兜底。
      const workerId = hubSession.workerIdOfFrame(frame)
      if (workerId) {
        this.scheduleRefreshFor(workerId)
      } else {
        this.refreshAllConnected()
      }
    })
    hubSession.onResync(() => {
      this.refreshAllConnected()
    })
    hubSession.onWorkers(() => {
      this.refreshAllConnected()
    })
    // 连接就绪(含首次连接与每次重连)主动校准一次:避免初始 refresh 在 hub 未连时
    // 静默失败、而 onResync/onWorkers 又因时序未触发导致的永久空列表。
    hubSession.onState((state) => {
      if (state === 'open') {
        this.refreshAllConnected()
      }
    })
    this.refreshAllConnected()
  }

  /**
   * 向指定 worker 定向校准模型配置(rpcTo config.get,写入 byWorker 并通知该 worker 订阅者)。
   * worker 重启瞬间 config.get 偶发超时(30s);连接仍 open 时退避重试,确保列表最终填满,
   * 避免首屏/重连校准失败后将空列表永久钉在 UI(旧任务下拉因此为空)。
   */
  async refreshFor(workerId: string): Promise<void> {
    if (!workerId) throw new Error('请先选择 worker')
    const inFlight = this.refreshInFlight.get(workerId)
    if (inFlight) return inFlight
    const promise = (async () => {
      // 无论成败都必须清掉 in-flight 标志:否则首次失败后(如 hub 未连时的启动校准)
      // 后续所有 refresh 都会命中去重直接返回旧 promise,onState('open')/onResync/
      // onWorkers 的再校准全部失效,列表被永久钉死为空。
      try {
        const MAX_RETRY = 3
        const BACKOFF_MS = 1500
        let lastError: unknown
        for (let attempt = 0; attempt <= MAX_RETRY; attempt++) {
          try {
            const result = await hubSession.rpcTo(workerId, 'config.get') as { models?: unknown }
            const models = Array.isArray(result?.models) ? result.models : []
            this.apply(workerId, models.filter(
              (m): m is ModelConfigInfo => Boolean(m) && typeof (m as ModelConfigInfo).configId === 'string',
            ))
            return
          } catch (error) {
            lastError = error
            // 仅当该 worker 连接尚在时重试;已断开则放弃,等下次重连/onState('open') 再校准。
            if (!hubSession.clientFor(workerId) || attempt >= MAX_RETRY) break
            await new Promise((r) => setTimeout(r, BACKOFF_MS * (attempt + 1)))
          }
        }
        // 校准最终失败(worker 离线/不支持):保留上次列表,下次校准。
        console.warn('[modelConfigs] config.get 校准失败(worker ' + workerId + '),模型列表暂为空:', lastError)
      } finally {
        this.refreshInFlight.delete(workerId)
      }
    })()
    this.refreshInFlight.set(workerId, promise)
    return promise
  }

  /** 遍历所有已连 worker 各自校准一次(fire-and-forget)。 */
  private refreshAllConnected(): void {
    hubSession.forEachConnectedWorker((workerId) => {
      this.scheduleRefreshFor(workerId)
    })
  }

  // ---- 选中记忆(全局前端偏好,与 worker 无关) ----

  /**
   * 指定 worker 当前应选中的 configId:localStorage 记忆 → 默认配置 → 首个配置。
   * 模型 id 记忆是全局前端偏好,与 worker 无关;仅列表来源按 worker。
   */
  selectedConfigIdFor(workerId: string): string {
    const models = this.currentFor(workerId)
    const remembered = readRememberedConfigId()
    if (remembered && models.some((m) => m.configId === remembered)) {
      return remembered
    }
    const fallback = models.find((m) => m.isDefault) ?? models[0]
    return fallback?.configId ?? ''
  }

  /**
   * @deprecated 兼容旧调用方(需改为 selectedConfigIdFor(workerId)):
   * 基于首个已连 worker 的列表计算。
   */
  selectedConfigId(): string {
    return this.selectedConfigIdFor(this.firstConnectedWorkerId())
  }

  /** 记忆选中配置(订阅方经 subscribe/subscribeFor 感知重渲染)。 */
  selectConfig(configId: string): void {
    if (!configId) return
    try {
      localStorage.setItem(SELECTED_CONFIG_KEY, configId)
    } catch {
      // 隐私模式等写入失败:仅本次会话生效
    }
    this.notifyAll()
  }

  // ---- 内部 ----

  /** 首个已连(open)worker 的 id;无则空串(仅供 deprecated 兼容 API 用)。 */
  private firstConnectedWorkerId(): string {
    for (const [workerId, client] of hubSession.workerClients) {
      if (client.state === 'open') return workerId
    }
    return ''
  }

  private scheduleRefreshFor(workerId: string): void {
    if (!workerId || this.refreshTimers.has(workerId)) return
    const timer = setTimeout(() => {
      this.refreshTimers.delete(workerId)
      void this.refreshFor(workerId)
    }, REFRESH_COALESCE_MS)
    this.refreshTimers.set(workerId, timer)
  }

  private apply(workerId: string, models: ModelConfigInfo[]): void {
    this.byWorker.set(workerId, models)
    const listeners = this.workerListeners.get(workerId)
    if (listeners) {
      for (const fn of listeners) {
        fn(models)
      }
    }
    // deprecated 兼容:旧全局订阅者无法按 worker 区分,任一 worker 校准后都回调一次。
    for (const fn of this.legacyListeners) {
      fn(this.current)
    }
  }

  /** 通知全部订阅者(selectConfig 等本地状态变化触发重渲染)。 */
  private notifyAll(): void {
    for (const [workerId, listeners] of this.workerListeners) {
      const models = this.currentFor(workerId)
      for (const fn of listeners) {
        fn(models)
      }
    }
    for (const fn of this.legacyListeners) {
      fn(this.current)
    }
  }
}

function readRememberedConfigId(): string {
  try {
    return localStorage.getItem(SELECTED_CONFIG_KEY) ?? ''
  } catch {
    return ''
  }
}

export const modelConfigs = new ModelConfigsService()
