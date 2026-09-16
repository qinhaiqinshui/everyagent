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
  timer: ReturnType<typeof setTimeout>;
}

export class HubClient {
  private ws: WebSocket | null = null;
  private pendingRpc = new Map<string, PendingRpc>();
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
          resolve();
          this.onResync?.();
          return;
        }
        this.handleFrame(frame);
      };
      ws.onclose = () => {
        clearTimeout(failTimer);
        // 旧 socket 的 onclose 延迟触发(suspend 关闭旧 socket 后 resume 已创建新 socket):
        // 如果 this.ws 已不是本次 connect 创建的 ws,说明已有新一轮 connect 接管,
        // 旧 socket 的事件不应干扰当前连接——直接丢弃,不做 failPending / setState / reject。
        if (this.ws !== ws) {
          reject(new Error('连接关闭(旧 socket)'));
          return;
        }
        this.ws = null;
        // 页面挂起(pagehide 触发)期间,旧 socket 的 onclose 可能延迟到达。
        // suspend() 已置 suspended=true 并把 this.ws 设为 null,所以这里的
        // this.ws !== ws 守卫会丢弃它,不会走到 failPending。
        // 但如果 WS 是远端主动断开(服务端关闭、网络切换等),走正常断开流程。
        if (this.suspended) {
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
        // 旧 socket 的 onerror 延迟触发:同 onclose,已由新 connect 接管时丢弃。
        if (this.ws !== ws) {
          return;
        }
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
    if (!this.ws || this.stateValue !== 'open') {
      return Promise.reject(new Error('hub 未连接'));
    }
    // §6.1 订阅次序约束:先 evt 再 cmd
    this.sub(channels.workerEvt(this.k, workerId));
    const reqId = `req-${++this.reqSeq}`;
    const timeoutMs = opts?.timeoutMs ?? this.opts.rpcTimeoutMs;
    return new Promise<any>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pendingRpc.delete(reqId);
        reject(new Error(`rpc ${method} 超时(${timeoutMs}ms)`));
      }, timeoutMs);
      this.pendingRpc.set(reqId, {
        resolve,
        reject,
        onData: opts?.onData,
        onProgress: opts?.onProgress,
        timer,
      });
      this.pub(channels.workerCmd(this.k, workerId), 'rpc', { reqId, method, params });
    });
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
            clearTimeout(pending.timer);
            this.pendingRpc.delete(reqId);
            pending.resolve(p.result);
            return;
          }
          case 'rpc.err': {
            const p = payload as unknown as RpcErr;
            clearTimeout(pending.timer);
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
      clearTimeout(p.timer);
      p.reject(error);
    }
    this.pendingRpc.clear();
  }

  /**
   * 挂起:关闭当前 socket(iOS/Chrome freeze 后它已是僵尸连接),暂停重连退避。
   * 不做 failPending——挂起是用户正常行为,在途 RPC 会在 onclose 的挂起分支
   * 被保留(not rejected),恢复后连接重建、onResync 触发业务层重新拉取数据。
   */
  private suspend(): void {
    if (this.manualClose || this.suspended) return;
    if (this.stateValue === 'idle' || this.stateValue === 'closed') return;
    this.suspended = true;
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
   * 生命周期监听:pagehide(挂起) / pageshow(恢复) / visibilitychange(恢复)。
   *
   * 挂起只由 pagehide 触发——它标志着页面即将被冻结/卸载(移动端切后台、bfcache、
   * 电脑锁屏等),WS 连接即将失效。不由 visibilitychange(hidden) 触发挂起:
   * 桌面浏览器切标签页时 visibilityState 也会变 hidden,但 JS 继续运行、WS 保持活跃,
   * 此时挂起重连是多余且有害的。
   *
   * 恢复由 pageshow 和 visibilitychange(visible) 共同触发:覆盖 bfcache 恢复、
   * 移动端回前台、电脑解锁等各种路径。
   */
  private wireLifecycle(): void {
    if (this.lifecycleWired) return;
    if (typeof document === 'undefined' || typeof window === 'undefined') return;
    this.lifecycleWired = true;
    // pagehide = 挂起(移动端切后台 / bfcache / 电脑锁屏):此时才需要断开 + 重连。
    window.addEventListener('pagehide', () => this.suspend());
    // pageshow = 恢复(bfcache 恢复等):强制重连。
    window.addEventListener('pageshow', () => this.resume());
    // visibilitychange(visible) = 恢复:覆盖从其他标签页切回、电脑解锁等场景。
    // 不在 hidden 时挂起——桌面切标签页只是 hidden 但 WS 仍活跃。
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') {
        this.resume();
      }
    });
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
