/**
 * Hub 会话(框架无关单例):前端与 hub 的连接出口(双道鉴权 + 多 worker 模型)。
 *
 * 连接模型:
 * - 目录连接(this.client):apiKey=hubKey 的前端连接,ownerKey=sha256(hubKey),
 *   订阅 u.<hubK>.workers 获取 hub 下全部 worker 在线目录(online/offline);
 * - worker 连接(this.workerClients):每台「启用且已填 apiKey」的 worker 一条前端连接,
 *   apiKey=workerApiKey(ownerKey=sha256(workerApiKey)),订阅该 worker 的
 *   u.<K>.tasks 与 u.<K>.worker.<id>.evt,RPC/任务流都走这条连接。
 *
 * 双道鉴权语义:
 * - hubKey 保护 hub(连接 hub 必须带,错误则目录连接 NOT_AUTHENTICATED);
 * - worker apiKey 保护 worker(不知道 apiKey 就建不了该 worker 连接、定位不到其频道)。
 *
 * React 组件经 HubProvider 消费;非 React 模块(网关/git/任务/工作区)直接引用本单例。
 */
import {
  channels,
  HubClient,
  ownerKey,
  type HubState,
  type MsgFrame,
} from '@every-agent/client'
import {
  loadConnectionConfig,
  saveConnectionConfig,
  type HubConnectionConfig,
} from './connection'
import { decryptSecret, encryptSecret } from '@/settings/credentialsCrypto'

export type { HubConnectionConfig }

type FrameListener = (frame: MsgFrame) => void
type StateListener = (state: HubState) => void
type WorkersListener = (workers: Map<string, boolean>) => void
type ResyncListener = () => void
type FatalErrorListener = (error: { code: string; detail: string } | null) => void
type RateLimitedListener = (limited: boolean) => void
type DirectoryListener = (infos: WorkerInfo[]) => void

/** 前端可见的 worker 纳管信息(目录 + 本地开关 + 连接错误)。 */
export interface WorkerInfo {
  workerId: string
  /** presence 在线。 */
  online: boolean
  /** 本地启用开关。 */
  enabled: boolean
  /** 是否已填 apiKey。 */
  hasApiKey: boolean
  /** 该 worker 连接是否正在建立。 */
  connecting: boolean
  /** 前端与该 worker 的连接是否已建立(open)。 */
  connected: boolean
  /** 连接级错误(鉴权失败等),null 表示无。 */
  error: { code: string; detail: string } | null
}

/** 前端 worker 连接操作结果(设置页据此反馈成功/失败)。 */
export interface WorkerConnectResult {
  ok: boolean
  error: { code: string; detail: string } | null
}

const WEB_DIRECTORY_CLIENT_ID = 'web-fe-directory'

/** 被 hub 限流(单 IP hello 超 60/min)后的前端退避:首次 15s,指数增长,上限 120s。 */
const RATE_LIMIT_BACKOFF_MS = 15_000
const RATE_LIMIT_BACKOFF_MAX_MS = 120_000

/** 致命连接错误:重连无意义,须停止 SDK 自动重连风暴。 */
const FATAL_HUB_ERRORS = new Set([
  'VERSION_MISMATCH',
  'NOT_AUTHENTICATED',
  'ACL_DENIED',
  'FRAME_TOO_LARGE',
  'RATE_LIMITED',
])

/** 由 workerId 生成前端 worker 连接 clientId(hub 校验 [a-zA-Z0-9._-]{1,64})。 */
function workerClientId(workerId: string): string {
  const safe = workerId.replace(/[^a-zA-Z0-9._-]/g, '_').slice(0, 48)
  return 'web-fe-worker-' + (safe || 'unnamed')
}

class HubSession {
  /** 目录连接(apiKey=hubKey);null 表示未建立。 */
  client: HubClient | null = null
  /** workerId → 已建立的前端 worker 连接。 */
  workerClients = new Map<string, HubClient>()

  config: HubConnectionConfig | null = loadConnectionConfig()

  /** 目录连接状态。 */
  state: HubState = 'idle'
  rateLimited = false
  fatalError: { code: string; detail: string } | null = null

  /** presence 目录:workerId → online。 */
  workersOnline = new Map<string, boolean>()
  /** worker 连接错误(workerId → 错误);与目录连接 fatalError 分离。 */
  private workerErrors = new Map<string, { code: string; detail: string }>()
  /** workerId → 是否正在建连。 */
  private connectingWorkers = new Map<string, boolean>()

  private rateLimitAttempt = 0
  private rateLimitTimer: ReturnType<typeof setTimeout> | null = null

  private frameListeners = new Set<FrameListener>()
  private stateListeners = new Set<StateListener>()
  private workersListeners = new Set<WorkersListener>()
  private resyncListeners = new Set<ResyncListener>()
  private fatalErrorListeners = new Set<FatalErrorListener>()
  private rateLimitedListeners = new Set<RateLimitedListener>()
  private directoryListeners = new Set<DirectoryListener>()

  // ---- 订阅 ----

  /** 订阅所有 msg 帧(目录连接 + 全部 worker 连接统一扇出)。 */
  onFrame(fn: FrameListener): () => void {
    this.frameListeners.add(fn)
    return () => this.frameListeners.delete(fn)
  }

  onState(fn: StateListener): () => void {
    this.stateListeners.add(fn)
    return () => this.stateListeners.delete(fn)
  }

  /** presence 目录变化(workerId → online)。 */
  onWorkers(fn: WorkersListener): () => void {
    this.workersListeners.add(fn)
    return () => this.workersListeners.delete(fn)
  }

  /** 任一连接(目录/worker)建立且订阅恢复后触发——调用方做全量校准。 */
  onResync(fn: ResyncListener): () => void {
    this.resyncListeners.add(fn)
    return () => this.resyncListeners.delete(fn)
  }

  onFatalError(fn: FatalErrorListener): () => void {
    this.fatalErrorListeners.add(fn)
    return () => this.fatalErrorListeners.delete(fn)
  }

  onRateLimited(fn: RateLimitedListener): () => void {
    this.rateLimitedListeners.add(fn)
    return () => this.rateLimitedListeners.delete(fn)
  }

  /** worker 目录/纳管信息变化(设置页 Worker 列表据此渲染)。 */
  onDirectory(fn: DirectoryListener): () => void {
    this.directoryListeners.add(fn)
    return () => this.directoryListeners.delete(fn)
  }

  // ---- 派生 ----

  get connected(): boolean {
    return this.state === 'open'
  }

  /** 是否已配置 hub 连接(双道鉴权模型下 hubUrl 与 hubKey 都必填)。 */
  get configured(): boolean {
    return Boolean(this.config?.hubUrl && this.config?.hubKey)
  }

  /** 全部已知 worker(在线的 + 本地已配置过的),供设置页列表展示。 */
  get directory(): WorkerInfo[] {
    const credentialByWorker = new Map(
      (this.config?.workers ?? []).map((w) => [w.workerId, w]),
    )
    const ids = new Set<string>()
    for (const workerId of this.workersOnline.keys()) ids.add(workerId)
    for (const workerId of credentialByWorker.keys()) ids.add(workerId)
    const out: WorkerInfo[] = []
    for (const workerId of ids) {
      const cred = credentialByWorker.get(workerId)
      out.push({
        workerId,
        online: this.workersOnline.get(workerId) ?? false,
        enabled: cred?.enabled ?? false,
        hasApiKey: Boolean(cred?.apiKeyEnc),
        connecting: this.connectingWorkers.get(workerId) ?? false,
        connected: this.workerClients.get(workerId)?.state === 'open',
        error: this.workerErrors.get(workerId) ?? null,
      })
    }
    return out.sort((a, b) => a.workerId.localeCompare(b.workerId))
  }

  /** 指定 worker 的连接(已建立且 open)。 */
  clientFor(workerId: string): HubClient | null {
    const c = this.workerClients.get(workerId)
    return c && c.state === 'open' ? c : null
  }

  // ---- 配置 ----

  async applyConfig(config: HubConnectionConfig): Promise<void> {
    saveConnectionConfig(config)
    this.config = config
    await this.reconnect()
  }

  /** 用当前配置连接;未配置时静默保持 idle。 */
  async ensureConnected(): Promise<void> {
    if (!this.configured || this.client || this.state === 'connecting' || this.state === 'reconnecting') {
      return
    }
    await this.reconnect()
  }

  /** 为指定 worker 保存 apiKey(加密)并(重)建其连接。 */
  async setWorkerApiKey(workerId: string, apiKey: string): Promise<WorkerConnectResult> {
    if (!this.config) {
      return { ok: false, error: { code: 'NOT_CONFIGURED', detail: 'hub 尚未配置' } }
    }
    const k = await ownerKey(apiKey.trim())
    const enc = await encryptSecret(apiKey.trim())
    const workers = this.config.workers.filter((w) => w.workerId !== workerId)
    workers.push({ workerId, apiKeyEnc: enc, enabled: true, ownerKey: k })
    this.config = { ...this.config, workers }
    saveConnectionConfig(this.config)
    this.notifyDirectory()
    await this.connectWorker(workerId)
    this.notifyWorkers()
    return this.workerConnectResult(workerId)
  }

  /** 启用/禁用指定 worker:启用则建连,禁用则关闭连接并移除其数据。 */
  async setWorkerEnabled(workerId: string, enabled: boolean): Promise<WorkerConnectResult> {
    if (!this.config) {
      return { ok: false, error: { code: 'NOT_CONFIGURED', detail: 'hub 尚未配置' } }
    }
    const cred = this.config.workers.find((w) => w.workerId === workerId)
    if (!cred || !cred.apiKeyEnc) {
      return { ok: false, error: { code: 'NO_CREDENTIAL', detail: '未填写该 worker 的 apiKey' } }
    }
    const workers = this.config.workers.map((w) =>
      w.workerId === workerId ? { ...w, enabled } : w,
    )
    this.config = { ...this.config, workers }
    saveConnectionConfig(this.config)
    if (enabled) {
      await this.connectWorker(workerId)
    } else {
      this.closeWorker(workerId)
    }
    this.notifyDirectory()
    this.notifyWorkers()
    return enabled ? this.workerConnectResult(workerId) : { ok: true, error: null }
  }

  /** 汇总某 worker 的连接结果:error 优先,其次以「连接是否已 open」判定成功。 */
  private workerConnectResult(workerId: string): WorkerConnectResult {
    const error = this.workerErrors.get(workerId) ?? null
    return { ok: this.clientFor(workerId) !== null, error }
  }

  disconnect(): void {
    this.clearRateLimited()
    this.teardown()
    this.state = 'idle'
    for (const fn of this.stateListeners) fn('idle')
  }

  // ---- RPC ----

  /** 对指定 worker 发 RPC(RPC 一律显式指定目标 worker,任务流按任务归属 worker 定向)。 */
  rpcTo(
    workerId: string,
    method: string,
    params?: Record<string, unknown>,
    opts?: { timeoutMs?: number; onData?: (batch: any[], hasMore: boolean) => void },
  ): Promise<any> {
    const client = this.clientFor(workerId)
    if (!client) {
      return Promise.reject(new Error('worker ' + workerId + ' 未连接'))
    }
    return client.rpc(workerId, method, params, opts)
  }

  /** 遍历所有「在线且已建立连接」的 worker。 */
  forEachConnectedWorker(fn: (workerId: string, client: HubClient) => void): void {
    for (const [workerId, client] of this.workerClients) {
      if (client.state === 'open') fn(workerId, client)
    }
  }

  /**
   * 判断帧来自哪台 worker(按 channel 匹配各 worker 连接的命名空间)。
   * 带显式 workerId 的频道(u.&lt;K&gt;.worker.&lt;workerId&gt;.*):按 workerId 精确归属,
   * 并校验 K 前缀——避免同一 apiKey(同 K)下多台 worker 时误判成首个连接,
   * 导致后续「频道是否等于该 worker 的 evt 频道」比对失败而静默丢帧;
   * 无显式 workerId 的 worker 级频道(u.&lt;K&gt;.tasks 等)回退按 K 前缀匹配首连接。
   */
  workerIdOfFrame(frame: MsgFrame): string | null {
    const channel = frame.channel
    if (!channel || !channel.startsWith('u.')) return null
    // 显式带 workerId 的频道(u.<K>.worker.<workerId>.<suffix>):按 workerId 精确归属并校验
    // K 前缀——避免同一 apiKey(同 K)下多台 worker 时误判成首个连接,导致后续
    // 「频道是否等于该 worker 的 evt 频道」比对失败而静默丢帧(workerId 本身可含点,
    // 用完整前缀匹配而非按点切分)。
    for (const [workerId, client] of this.workerClients) {
      if (!client.k) continue
      const prefix = 'u.' + client.k + '.worker.' + workerId + '.'
      if (channel.startsWith(prefix)) {
        return workerId
      }
    }
    // 无 worker 段(如 u.<K>.tasks / u.<K>.workers):按 K 前缀匹配首连接。
    for (const [workerId, client] of this.workerClients) {
      if (client.k && channel.startsWith('u.' + client.k + '.')) {
        return workerId
      }
    }
    return null
  }

  /** 帧是否来自目录连接(其 k = sha256(hubKey))。 */
  isDirectoryFrame(frame: MsgFrame): boolean {
    const k = this.client?.k
    return Boolean(k && frame.channel === channels.workers(k))
  }

  // ---- 连接生命周期 ----

  private async reconnect(): Promise<void> {
    this.teardown()
    this.setFatalError(null)
    const config = this.config
    if (!config || !this.configured) return
    const directory = new HubClient({
      url: config.hubUrl,
      apiKey: config.hubKey,
      hubKey: config.hubKey,
      clientId: WEB_DIRECTORY_CLIENT_ID,
      role: 'frontend',
    })
    this.client = directory
    directory.onStateChange = (s) => {
      this.state = s
      for (const fn of this.stateListeners) fn(s)
    }
    directory.onMessage = (frame) => {
      this.handleDirectoryFrame(frame)
      for (const fn of this.frameListeners) fn(frame)
    }
    directory.onResync = () => {
      for (const fn of this.resyncListeners) fn()
      // 目录重连后重建全部启用 worker 连接。
      void this.connectConfiguredWorkers()
    }
    directory.onError = (frame) => {
      if (!FATAL_HUB_ERRORS.has(frame.code)) return
      const detail = (frame as unknown as { detail?: string }).detail ?? frame.message ?? ''
      if (frame.code === 'RATE_LIMITED') {
        this.handleRateLimited(directory)
        return
      }
      this.handleDirectoryFatalError(directory, frame.code, detail)
    }
    try {
      await directory.connect()
      this.clearRateLimited()
      this.workersOnline.clear()
      directory.sub(channels.workers(directory.k))
      await this.connectConfiguredWorkers()
    } catch (error) {
      this.teardown()
      this.state = 'closed'
      for (const fn of this.stateListeners) fn('closed')
      const reason = error instanceof Error ? error.message : String(error)
      console.error('[hub] 目录连接失败(已停止自动重连,可在设置页修正后重试): ' + reason)
      throw new Error('无法连接 hub ' + config.hubUrl + ': ' + reason)
    }
  }

  /** 为所有「启用且已填 apiKey」的 worker 建立连接。 */
  private async connectConfiguredWorkers(): Promise<void> {
    if (!this.config) return
    for (const cred of this.config.workers) {
      if (cred.enabled && cred.apiKeyEnc) {
        await this.connectWorker(cred.workerId)
      }
    }
    this.notifyDirectory()
  }

  /** 为单台 worker 建连(幂等;已连则不重复)。
   *  ownerFingerprint = presence 帧携带的 sha256(apiKey) 前 16 位 hex;
   *  若按 workerId 找不到凭证,可借此指纹复用已保存的 apiKey 给新 workerId 建连。 */
  private async connectWorker(workerId: string, ownerFingerprint?: string): Promise<void> {
    const config = this.config
    if (!config || !this.connected) return
    if (this.workerClients.has(workerId) || this.connectingWorkers.get(workerId)) return
    let cred = config.workers.find((w) => w.workerId === workerId)
    // 指纹匹配:worker 改了 workerId(ln)后,凭 ownerKey 前缀找到同一身份的老凭证并迁移。
    if (!cred && ownerFingerprint) {
      cred = config.workers.find(
        (w) => w.ownerKey && w.ownerKey.startsWith(ownerFingerprint),
      )
      if (cred && cred.workerId !== workerId) {
        cred.workerId = workerId
        saveConnectionConfig(config)
        console.info('[hub] worker 身份变更,已复用凭证:旧 workerId → ' + workerId)
      }
    }
    if (!cred || !cred.enabled || !cred.apiKeyEnc) return
    this.connectingWorkers.set(workerId, true)
    this.workerErrors.delete(workerId)
    this.notifyDirectory()
    let apiKey = ''
    try {
      apiKey = await decryptSecret(cred.apiKeyEnc)
    } catch (error) {
      this.workerErrors.set(workerId, {
        code: 'CREDENTIAL_DECRYPT',
        detail: error instanceof Error ? error.message : 'apiKey 解密失败',
      })
      this.connectingWorkers.delete(workerId)
      this.notifyDirectory()
      return
    }
    if (!apiKey) {
      this.connectingWorkers.delete(workerId)
      return
    }
    const client = new HubClient({
      url: config.hubUrl,
      apiKey,
      hubKey: config.hubKey,
      clientId: workerClientId(workerId),
      role: 'frontend',
    })
    this.workerClients.set(workerId, client)
    client.onStateChange = () => {
      this.notifyDirectory()
    }
    client.onMessage = (frame) => {
      for (const fn of this.frameListeners) fn(frame)
    }
    client.onResync = () => {
      for (const fn of this.resyncListeners) fn()
    }
    client.onError = (frame) => {
      if (!FATAL_HUB_ERRORS.has(frame.code)) return
      const detail = (frame as unknown as { detail?: string }).detail ?? frame.message ?? ''
      if (frame.code === 'RATE_LIMITED') {
        // worker 连接被限流:关闭并标记,避免 SDK 重连风暴;下次目录事件可重试。
        client.close()
        this.workerClients.delete(workerId)
        this.workerErrors.set(workerId, { code: frame.code, detail })
        this.connectingWorkers.delete(workerId)
        this.notifyDirectory()
        return
      }
      this.workerErrors.set(workerId, { code: frame.code, detail })
      client.close()
      this.workerClients.delete(workerId)
      this.connectingWorkers.delete(workerId)
      this.notifyDirectory()
      console.error('[hub] worker ' + workerId + ' 连接错误 ' + frame.code + ': ' + detail)
    }
    try {
      await client.connect()
      client.sub(channels.tasks(client.k))
      client.sub(channels.workerEvt(client.k, workerId))
      this.workerErrors.delete(workerId)
    } catch (error) {
      // onError 可能已记录更具体的致命错误(NOT_AUTHENTICATED/RATE_LIMITED 等),
      // 这里只在尚未记录时用笼统的 CONNECT_FAILED 兜底,避免覆盖真实原因。
      if (!this.workerErrors.has(workerId)) {
        this.workerErrors.set(workerId, {
          code: 'CONNECT_FAILED',
          detail: error instanceof Error ? error.message : String(error),
        })
      }
      client.close()
      this.workerClients.delete(workerId)
    } finally {
      this.connectingWorkers.delete(workerId)
      this.notifyDirectory()
      this.notifyWorkers()
    }
  }

  private closeWorker(workerId: string): void {
    const client = this.workerClients.get(workerId)
    if (client) {
      client.close()
      this.workerClients.delete(workerId)
    }
    this.workerErrors.delete(workerId)
    this.notifyDirectory()
  }

  /** 目录帧:处理 presence(u.<hubK>.workers)并联动 worker 连接。 */
  private handleDirectoryFrame(frame: MsgFrame): void {
    const k = this.client?.k
    if (!k || frame.channel !== channels.workers(k)) return
    const workerId = String(frame.payload?.workerId ?? '')
    if (!workerId) return
    const ownerFingerprint = String(frame.payload?.ownerFingerprint ?? '')
    if (frame.event === 'worker.online') {
      this.workersOnline.set(workerId, true)
      this.notifyWorkers()
      // 目录列表(directory getter 含 online 状态)依赖 presence,须一并通知,
      // 否则设置页 Worker 列表(读 directory 而非 workers)不会刷新。
      this.notifyDirectory()
      void this.connectWorker(workerId, ownerFingerprint)
    } else if (frame.event === 'worker.offline') {
      this.workersOnline.set(workerId, false)
      this.closeWorker(workerId)
      this.notifyWorkers()
    }
  }

  private handleRateLimited(client: HubClient): void {
    this.setRateLimited(true)
    this.rateLimitAttempt += 1
    const delay = Math.min(
      RATE_LIMIT_BACKOFF_MAX_MS,
      RATE_LIMIT_BACKOFF_MS * 2 ** (this.rateLimitAttempt - 1),
    )
    client.close()
    this.client = null
    this.state = 'reconnecting'
    for (const fn of this.stateListeners) fn('reconnecting')
    if (this.rateLimitTimer) clearTimeout(this.rateLimitTimer)
    this.rateLimitTimer = setTimeout(() => {
      this.rateLimitTimer = null
      void this.reconnect()
    }, delay)
    console.warn('[hub] 被限流,' + Math.round(delay / 1000) + 's 后重试(第 ' + this.rateLimitAttempt + ' 次)')
  }

  private clearRateLimited(): void {
    this.setRateLimited(false)
    this.rateLimitAttempt = 0
    this.setFatalError(null)
    if (this.rateLimitTimer) {
      clearTimeout(this.rateLimitTimer)
      this.rateLimitTimer = null
    }
  }

  private handleDirectoryFatalError(client: HubClient, code: string, detail: string): void {
    this.setFatalError({ code, detail })
    client.close()
    this.client = null
    this.state = 'closed'
    for (const fn of this.stateListeners) fn('closed')
    console.error('[hub] 目录连接致命错误 ' + code + ': ' + detail + '(已停止重连,需修正后手动重连)')
  }

  private teardown(): void {
    if (this.client) {
      this.client.close()
      this.client = null
    }
    for (const [, client] of this.workerClients) {
      client.close()
    }
    this.workerClients.clear()
    this.workersOnline.clear()
    this.workerErrors.clear()
    this.notifyWorkers()
    this.notifyDirectory()
  }

  // ---- 通知 ----

  private setFatalError(error: { code: string; detail: string } | null): void {
    this.fatalError = error
    for (const fn of this.fatalErrorListeners) fn(error)
  }

  private setRateLimited(limited: boolean): void {
    this.rateLimited = limited
    for (const fn of this.rateLimitedListeners) fn(limited)
  }

  private notifyWorkers(): void {
    const snapshot = new Map(this.workersOnline)
    for (const fn of this.workersListeners) fn(snapshot)
  }

  private notifyDirectory(): void {
    const snapshot = this.directory
    for (const fn of this.directoryListeners) fn(snapshot)
  }
}

export const hubSession = new HubSession()