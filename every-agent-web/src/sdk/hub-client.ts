/**
 * HubClient(架构 §6.1):connect/hello/sub/pub/自动重连(重连后自动重订阅 + 触发 resync)。
 * rpc():生成 reqId、按 reqId 匹配应答,支持 data/progress 流式回调与超时(默认 30s,纯客户端语义)。
 * 订阅次序约束:对任一 worker 先 sub 其 evt 频道,再发 cmd(§6.1/§7.2)。
 */
import { channels, ownerKey } from './channels';
import {
  ClientFrame,
  ErrorFrame,
  HelloFrame,
  MsgFrame,
  PROTOCOL_VERSION,
  RpcData,
  RpcErr,
  RpcError,
  RpcOk,
  RpcProgress,
  ServerFrame,
} from './frames';

export type HubState = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed';

export interface HubClientOptions {
  url: string;
  apiKey: string;
  /** hub 级连接凭证(保护 hub),由部署者配置;可选,未传时 hello 不带该字段。 */
  hubKey?: string;
  clientId?: string;
  role?: 'frontend' | 'worker' | 'agent';
  /** 重连退避(毫秒)。 */
  backoff?: { initial: number; max: number };
  rpcTimeoutMs?: number;
}

interface PendingRpc {
  resolve: (v: any) => void;
  reject: (e: Error) => void;
  onData?: (batch: any[], hasMore: boolean) => void;
  onProgress?: (message: string, pct?: number) => void;
  timer: ReturnType<typeof setTimeout> | null;
  /** 重放所需的原始调用信息(挂起恢复后换新 reqId 重发)。 */
  workerId: string;
  method: string;
  params?: Record<string, unknown>;
  timeoutMs: number;
  /** 截止时刻(挂起期间不走表;恢复时按剩余时间重新计时)。 */
  deadline: number;
}

export class HubClient {
  private ws: WebSocket | null = null;
  private pendingRpc = new Map<string, PendingRpc>();
  /** 已受理但尚未发往 hub 的 RPC(挂起/重连窗口内入队,welcome 后统一补发)。 */
  private queuedRpc: PendingRpc[] = [];
  private desiredSubs = new Set<string>();
  private reqSeq = 0;
  private midSeq = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private manualClose = false;
  private stateValue: HubState = 'idle';
  /**
   * 页面挂起(切后台/锁屏)标志:iOS Safari 会冻结 JS 定时器并把 WS 变成僵尸连接
   * (TCP 已断但 onclose 不触发)。挂起时冻结在途 RPC 的超时计时并主动关闭 socket;
   * 回前台后强制重建连接并重放挂起请求,调用方全程无感知。
   */
  private suspended = false;
  private lifecycleWired = false;

  /** ownerKey(sha256 hex),connect 后可用。 */
  k = '';

  readonly opts: Required<Pick<HubClientOptions, 'clientId' | 'role' | 'rpcTimeoutMs'>> &
    HubClientOptions;

  constructor(opts: HubClientOptions) {
    this.opts = {
      clientId: 'fe-' + Math.random().toString(36).slice(2, 8),
      role: 'frontend',
      rpcTimeoutMs: 30_000,
      ...opts,
    };
  }

  get state(): HubState {
    return this.stateValue;
  }

  private _onMessage: ((frame: MsgFrame) => void) | null = null;
  private readonly messageListeners = new Set<(frame: MsgFrame) => void>();

  /**
   * 兼容旧用法:单一槽位回调(经 setter 进入 messageListeners 集合,故与
   * addMessageListener 注册的监听器并存互不覆盖)。新代码请用 add/removeMessageListener。
   */
  get onMessage(): ((frame: MsgFrame) => void) | null {
    return this._onMessage;
  }

  set onMessage(fn: ((frame: MsgFrame) => void) | null) {
    if (this._onMessage === fn) return;
    if (this._onMessage) this.messageListeners.delete(this._onMessage);
    this._onMessage = fn;
    if (fn) this.messageListeners.add(fn);
  }

  /** 注册消息监听器(可多个,互不覆盖)。TaskPacketView 用它挂自己的任务流 handler。 */
  addMessageListener(fn: (frame: MsgFrame) => void): void {
    this.messageListeners.add(fn);
  }

  /** 摘除此前 addMessageListener 注册的监听器(close 时必须调用,防止残留导致重复消费)。 */
  removeMessageListener(fn: (frame: MsgFrame) => void): void {
    this.messageListeners.delete(fn);
  }

  onError: ((frame: ErrorFrame) => void) | null = null;
  /** 连接(含重连)建立且订阅恢复后触发 —— 调用方应在此重新订阅/校准(如重开任务流)。 */
  onResync: (() => void) | null = null;
  onStateChange: ((s: HubState) => void) | null = null;

  async connect(): Promise<void> {
    this.manualClose = false;
    this.wireLifecycle();
    this.setState(this.reconnectTimer || this.suspended ? 'reconnecting' : 'connecting');
    if (!this.k) {
      this.k = await ownerKey(this.opts.apiKey);
    }
    await new Promise<void>((resolve, reject) => {
      const ws = new WebSocket(this.opts.url);
      this.ws = ws;
      const failTimer = setTimeout(() => {
        ws.close();
        reject(new Error('连接超时'));
      }, 10_000);
      ws.onopen = () => {
        const hello: HelloFrame = {
          type: 'hello',
          ver: PROTOCOL_VERSION,
          role: this.opts.role,
          apiKey: this.opts.apiKey,
          clientId: this.opts.clientId,
        };
        // 与 Java wireHello 一致:仅当 hubKey 非空时才带上该字段(向后兼容)。
        if (this.opts.hubKey) {
          hello.hubKey = this.opts.hubKey;
        }
        this.send(hello);
      };
      ws.onmessage = (ev) => {
        let frame: ServerFrame;
        try {
          frame = JSON.parse(String(ev.data));
        } catch {
          return;
        }
        if (frame.type === 'welcome') {
          clearTimeout(failTimer);
          // 恢复全部期望订阅(重连场景)
          for (const ch of this.desiredSubs) {
            this.send({ type: 'sub', channel: ch });
          }
          this.setState('open');
          // 挂起恢复:重放被冻结的在途 RPC + 补发排队 RPC,调用方 Promise 从未中断。
          this.replayPending();
          this.flushQueue();
          resolve();
          this.onResync?.();
          return;
        }
        this.handleFrame(frame);
      };
      ws.onclose = () => {
        clearTimeout(failTimer);
        this.ws = null;
        // 页面隐藏(切后台/锁屏)期间的断开:iOS Safari / Chrome freeze 会冻结 JS
        // 定时器,RPC 即使超时也无人处理——按挂起语义冻结在途请求,等 pageshow /
        // visibilitychange 恢复时统一重连 + 重放,而不是 failPending 让错误在解冻
        // 瞬间集中冒出。电脑黑屏时 onclose 可能也是 freeze 期间首批解冻的事件。
        if (this.suspended || this.isPageHidden()) {
          this.freezePending();
          this.suspended = true;
          this.setState('reconnecting');
          reject(new Error('页面挂起,连接已冻结'));
          return;
        }
        this.failPending(new Error('hub 连接断开'));
        if (!this.manualClose) {
          this.scheduleReconnect();
        } else {
          this.setState('closed');
        }
        reject(new Error('连接关闭'));
      };
      ws.onerror = () => {
        // 连接错误必须立即失败:不能只 clearTimeout(failTimer) 等 onclose——若 onclose
        // 不触发(浏览器边缘场景),connect() 的 Promise 会永久 pending,上层连接状态
        // 永远卡在「连接中」。这里主动 close + reject,让调用方总能拿到结果。
        clearTimeout(failTimer);
        try {
          ws.close();
        } catch {
          // 某些状态下 close 可能抛错,忽略即可(reject 已保证调用方不再等待)。
        }
        reject(new Error('WebSocket 连接失败'));
      };
    });
  }

  close(): void {
    this.manualClose = true;
    this.suspended = false;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.failPending(new Error('客户端关闭'));
    this.ws?.close();
    this.setState('closed');
  }

  sub(channel: string): void {
    this.desiredSubs.add(channel);
    if (this.ws && this.stateValue === 'open') {
      this.send({ type: 'sub', channel });
    }
  }

  unsub(channel: string): void {
    this.desiredSubs.delete(channel);
    if (this.ws && this.stateValue === 'open') {
      this.send({ type: 'unsub', channel });
    }
  }

  pub(channel: string, event: string, payload?: unknown): void {
    this.send({
      type: 'pub',
      mid: 'm-' + ++this.midSeq,
      channel,
      event,
      ts: Date.now(),
      payload,
    });
  }

  /**
   * 对指定 worker 发 RPC:先确保已 sub 其 evt 频道,再向 cmd 频道发 rpc。
   * 返回 rpc.ok 的 result;rpc.err 抛 RpcError;rpc.data 经 onData 流式回调。
   *
   * 挂起/重连窗口内的请求不立即失败:入队等待连接恢复后补发,避免用户回前台瞬间
   * 看到「hub 未连接」错误。已发出的请求在挂起时被冻结(超时不走表),恢复后重放。
   */
  rpc(
    workerId: string,
    method: string,
    params?: Record<string, unknown>,
    opts?: {
      timeoutMs?: number;
      onData?: (batch: any[], hasMore: boolean) => void;
      onProgress?: (message: string, pct?: number) => void;
    },
  ): Promise<any> {
    // §6.1 订阅次序约束:先 evt 再 cmd(sub 在非 open 时只记 desiredSubs,welcome 后补发)
    this.sub(channels.workerEvt(this.k, workerId));
    const timeoutMs = opts?.timeoutMs ?? this.opts.rpcTimeoutMs;
    const pending: PendingRpc = {
      resolve: () => {},
      reject: () => {},
      onData: opts?.onData,
      onProgress: opts?.onProgress,
      timer: null,
      workerId,
      method,
      params,
      timeoutMs,
      deadline: Date.now() + timeoutMs,
    };
    return new Promise<any>((resolve, reject) => {
      pending.resolve = resolve;
      pending.reject = reject;
      if (this.ws && this.ws.readyState === WebSocket.OPEN && this.stateValue === 'open') {
        this.dispatchRpc(pending);
      } else if (
        this.suspended ||
        this.stateValue === 'reconnecting' ||
        this.stateValue === 'connecting'
      ) {
        // 连接正在重建或页面挂起:入队,welcome 后 flushQueue 统一补发;
        // 截止时刻已在 deadline 中记录,补发时按剩余时间计时,过长则超时。
        this.queuedRpc.push(pending);
      } else {
        reject(new Error('hub 未连接'));
      }
    });
  }

  /**
   * 生成新 reqId、挂定时器、发往 cmd 频道。timer 计时长度取 pending.deadline 的剩余
   * (重放/补发场景)或完整 timeoutMs(首次发送)。
   */
  private dispatchRpc(pending: PendingRpc): void {
    const reqId = `req-${++this.reqSeq}`;
    const remaining = Math.max(pending.deadline - Date.now(), 0);
    pending.timer = setTimeout(() => {
      this.pendingRpc.delete(reqId);
      pending.reject(new Error(`rpc ${pending.method} 超时(${pending.timeoutMs}ms)`));
    }, remaining);
    this.pendingRpc.set(reqId, pending);
    this.pub(channels.workerCmd(this.k, pending.workerId), 'rpc', { reqId, method: pending.method, params: pending.params });
  }

  // ---- 内部 ----

  private handleFrame(frame: ServerFrame): void {
    if (frame.type === 'error') {
      this.onError?.(frame);
      return;
    }
    if (frame.type !== 'msg') {
      return;
    }
    const payload = frame.payload;
    if (payload && typeof payload === 'object' && 'reqId' in payload) {
      const reqId = String((payload as Record<string, unknown>).reqId);
      const pending = this.pendingRpc.get(reqId);
      if (pending) {
        switch (frame.event) {
          case 'rpc.ok': {
            const p = payload as unknown as RpcOk;
            if (pending.timer) clearTimeout(pending.timer);
            this.pendingRpc.delete(reqId);
            pending.resolve(p.result);
            return;
          }
          case 'rpc.err': {
            const p = payload as unknown as RpcErr;
            if (pending.timer) clearTimeout(pending.timer);
            this.pendingRpc.delete(reqId);
            pending.reject(new RpcError(p.code, p.message));
            return;
          }
          case 'rpc.data': {
            const p = payload as unknown as RpcData;
            pending.onData?.(p.batch, p.hasMore);
            return;
          }
          case 'rpc.progress': {
            const p = payload as unknown as RpcProgress;
            pending.onProgress?.(p.message, p.pct);
            return;
          }
          default:
            break;
        }
      }
    }
    for (const fn of this.messageListeners) {
      fn(frame);
    }
  }

  private failPending(error: Error): void {
    for (const [, p] of this.pendingRpc) {
      if (p.timer) clearTimeout(p.timer);
      p.reject(error);
    }
    this.pendingRpc.clear();
    // 排队的请求一并失败(客户端关闭等场景)。
    const queued = this.queuedRpc;
    this.queuedRpc = [];
    for (const p of queued) p.reject(error);
  }

  /** 冻结在途 RPC:清掉超时定时器但保留请求,挂起期间超时不走表。 */
  private freezePending(): void {
    for (const [, p] of this.pendingRpc) {
      if (p.timer) {
        clearTimeout(p.timer);
        p.timer = null;
      }
    }
  }

  /** 挂起恢复:在途 RPC 全部换新 reqId 重发,剩余超时按 deadline 重新计算。 */
  private replayPending(): void {
    const entries = Array.from(this.pendingRpc.values());
    this.pendingRpc.clear();
    for (const p of entries) {
      if (p.deadline - Date.now() <= 0) {
        // 挂起期间实际已超时(如锁屏数小时):直接失败,不再无谓重发。
        p.reject(new Error(`rpc ${p.method} 超时(${p.timeoutMs}ms)`));
        continue;
      }
      this.dispatchRpc(p);
    }
  }

  /** 补发排队请求(welcome 后);已过 deadline 的直接超时。 */
  private flushQueue(): void {
    const queued = this.queuedRpc;
    this.queuedRpc = [];
    for (const p of queued) {
      if (p.deadline - Date.now() <= 0) {
        p.reject(new Error(`rpc ${p.method} 超时(${p.timeoutMs}ms)`));
        continue;
      }
      this.dispatchRpc(p);
    }
  }

  /** 页面是否处于隐藏态(切后台/锁屏)。非浏览器环境恒 false。 */
  private isPageHidden(): boolean {
    return typeof document !== 'undefined' && document.visibilityState === 'hidden';
  }

  /**
   * 挂起:冻结在途 RPC、暂停重连退避、主动关闭当前 socket(iOS 上它已是僵尸连接,
   * 留着只会让恢复后发出的请求继续黑洞)。不做任何 reject——挂起是用户正常行为,
   * 恢复后一切自动续跑。
   */
  private suspend(): void {
    if (this.manualClose || this.suspended) return;
    // 从未连接/已关闭的连接没有可挂起的资源——尤其不能让它在 resume 时被意外拉起。
    if (this.stateValue === 'idle' || this.stateValue === 'closed') return;
    this.suspended = true;
    this.freezePending();
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    const ws = this.ws;
    this.ws = null;
    if (ws) {
      try {
        ws.close();
      } catch {
        // close 在部分状态下可能抛错,忽略(我们已把引用摘掉)。
      }
    }
    this.setState('reconnecting');
  }

  /**
   * 恢复:强制重建全新连接(不信任任何残留 socket),welcome 后重放挂起请求。
   *
   * 无条件重连——不依赖 suspended 标志:电脑黑屏 freeze 期间 onclose 和 pageshow
   * 的派发顺序不确定(可能 onclose 先 → suspended=true → pageshow → resume 正常;
   * 也可能 pageshow 先 → suspended 仍 false → resume no-op → 连接永远不重建)。
   * 只要页面回到前台(visible)且连接不在 open,就强制重连。
   */
  private resume(): void {
    if (this.manualClose) return;
    this.suspended = false;
    // 已连接/首次连接未建立(idle)时无需强制重连。
    // connecting:页面首次加载时 pageshow 事件(persisted=false)也会触发 resume,
    // 此时初始连接正在进行,不应强制关闭在途 WebSocket 并重建。
    // 真正的挂起恢复——页面曾切到后台时 suspend() 已把状态置为 'reconnecting',
    // 不会停留在 'connecting'。
    if (this.stateValue === 'open' || this.stateValue === 'idle' || this.stateValue === 'connecting') return;
    // 已有重连定时器在跑(正常网络断线重连):让它继续,不要打乱退避节奏。
    // 但若连接已断且无定时器(freeze 后 onclose 没走 scheduleReconnect 的情况),
    // 立即触发一次重连。
    if (this.reconnectTimer) return;
    // 清掉可能残留的僵尸 socket(挂起期间 onclose 可能没触发,ws 引用还在)
    if (this.ws) {
      try { this.ws.close() } catch {}
      this.ws = null;
    }
    void this.connect().catch(() => {
      // 恢复瞬间网络可能尚未就绪(解锁后 Wi-Fi/蜂窝需要数百毫秒重连):
      // 显式兜底重连,退避由 scheduleReconnect 负责(已有重连定时器时为空操作)。
      if (!this.manualClose && this.stateValue !== 'open') {
        this.scheduleReconnect();
      }
    });
  }

  /**
   * 生命周期监听:visibilitychange / pagehide / pageshow。
   * 只对浏览器环境接线;每次 connect 幂等。
   */
  private wireLifecycle(): void {
    if (this.lifecycleWired) return;
    if (typeof document === 'undefined' || typeof window === 'undefined') return;
    this.lifecycleWired = true;
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'hidden') {
        this.suspend();
      } else {
        this.resume();
      }
    });
    window.addEventListener('pagehide', () => this.suspend());
    window.addEventListener('pageshow', () => this.resume());
  }

  private backoffMs(attempt: number): number {
    const { initial, max } = this.opts.backoff ?? { initial: 3000, max: 60_000 };
    const exp = Math.min(max, initial * 2 ** attempt);
    return Math.round(exp * (0.8 + Math.random() * 0.4)); // 抖动
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer || this.manualClose) {
      return;
    }
    this.setState('reconnecting');
    let attempt = 0;
    const tick = () => {
      this.reconnectTimer = setTimeout(async () => {
        this.reconnectTimer = null;
        try {
          await this.connect();
        } catch {
          attempt++;
          tick();
        }
      }, this.backoffMs(attempt));
    };
    tick();
  }

  private setState(s: HubState): void {
    if (this.stateValue !== s) {
      this.stateValue = s;
      this.onStateChange?.(s);
    }
  }

  private send(frame: ClientFrame): void {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(frame));
    }
  }
}
