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
    this.setState(this.reconnectTimer ? 'reconnecting' : 'connecting');
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
        this.ws = null;
        this.failPending(new Error('hub 连接断开'));
        if (!this.manualClose) {
          this.scheduleReconnect();
        } else {
          this.setState('closed');
        }
        reject(new Error('连接关闭'));
      };
      ws.onerror = () => {
        clearTimeout(failTimer);
      };
    });
  }

  close(): void {
    this.manualClose = true;
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
