/**
 * HubClient(架构 §6.1):connect/hello/sub/pub/自动重连(重连后自动重订阅 + 触发 onReconnect)。
 * rpc():生成 reqId、按 reqId 匹配应答,支持 data/progress 流式回调与超时(默认 30s,纯客户端语义)。
 * 订阅次序约束:对任一 worker 先 sub 其 evt 频道,再发 cmd(§6.1/§7.2)。
 * 连接生死唯一由应用层心跳判定:5s 空闲探测,ping 后 15s 无帧判死 → 自动重连;
 * visibilitychange 仅用于订阅降载(hidden 退订 stream 频道),不参与连接管理(§4.2/§4.2.2)。
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
  /** 重放所需:RPC 目标 worker、方法与参数(重连后重新发送)。 */
  workerId: string;
  method: string;
  params?: Record<string, unknown>;
  /** 原始超时(重放时重置计时)。 */
  timeoutMs: number;
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
  /** 心跳间隔(§4.2):仅本周期无任何帧时才发 ping。 */
  private static readonly HEARTBEAT_INTERVAL_MS = 5_000;
  /** 判死阈值:发出 ping 后 15s 无任何帧到达(非「距上帧超时」,避免误杀合法空闲连接)。 */
  private static readonly HEARTBEAT_DEAD_MS = 15_000;
  /** 最近一次收到任何帧的时刻(收到任何帧即证明连接存活)。 */
  private lastFrameAt = 0;
  /** 已发出且未应答的 ping 的时刻;0 = 无未应答 ping。 */
  private pingSentAt = 0;
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  /** visibility 降载(§4.2.2 防线 3):hidden 时实际退订、desiredSubs 仍保留的频道。 */
  private readonly hiddenShedSubs = new Set<string>();
  private visibilityShedWired = false;

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
  onReconnect: (() => void) | null = null;
  onStateChange: ((s: HubState) => void) | null = null;

  async connect(): Promise<void> {
    this.manualClose = false;
    this.wireVisibilityShedding();
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
        // 心跳:收到任何帧即证明连接存活(§4.2)。
        this.lastFrameAt = Date.now();
        if (frame.type === 'pong') {
          // 心跳应答:清未应答 ping,不进 handleFrame、不路由 messageListeners。
          this.pingSentAt = 0;
          return;
        }
        if (frame.type === 'welcome') {
          clearTimeout(failTimer);
          // 恢复全部期望订阅(重连场景)
          for (const ch of this.desiredSubs) {
            this.send({ type: 'sub', channel: ch });
          }
          this.setState('open');
          // 连接已确立:启动应用层心跳(连接生死唯一判定,§4.2)。
          this.startHeartbeat();
          // 重连成功:重放在途 RPC(瞬态断连期间挂起的请求),业务层全程无感知。
          this.replayPendingRpcs();
          resolve();
          this.onReconnect?.();
          return;
        }
        this.handleFrame(frame);
      };
      ws.onclose = () => {
        clearTimeout(failTimer);
        // 旧 socket 的 onclose 延迟触发(心跳判死主动关闭旧 socket 后,重连已创建新 socket):
        // 如果 this.ws 已不是本次 connect 创建的 ws,说明已有新一轮 connect 接管,
        // 旧 socket 的事件不应干扰当前连接——直接丢弃,不做 holdPendingRpcs / setState / reject。
        if (this.ws !== ws) {
          reject(new Error('连接关闭(旧 socket)'));
          return;
        }
        this.ws = null;
        // 连接断开:停心跳并重置未应答 ping(welcome 后由 startHeartbeat 重新启动)。
        this.stopHeartbeat();
        if (this.manualClose) {
          // 手动关闭:直接拒绝所有在途 RPC(用户意图断连)。
          this.failPending(new Error('客户端关闭'));
          this.setState('closed');
        } else {
          // 瞬态断连:挂起在途 RPC(暂停超时,等重连后重放),不向业务层抛错误。
          // 重连由 scheduleReconnect 驱动;重连成功后 replayPendingRpcs 重放,
          // 业务层全程无感知(不弹错误提示,仅 UI 层弹重连模态框)。
          this.holdPendingRpcs();
          this.scheduleReconnect();
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
    this.clearHoldTimer();
    this.stopHeartbeat();
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
    // 若该频道已因 hidden 降载退订,同步移出集合,避免 visible 时误重订已关闭的频道。
    this.hiddenShedSubs.delete(channel);
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
   * 重连期间:不拒绝,而是将 RPC 入 pendingRpc 队列(sub 加入 desiredSubs),
   * 等 welcome 后 replayPendingRpcs 统一重放——业务层全程无感知。
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
    if (this.manualClose) {
      return Promise.reject(new Error('hub 未连接'));
    }
    // §6.1 订阅次序约束:先 evt 再 cmd。
    // sub 在重连期间只加入 desiredSubs(不发),welcome 后统一重订阅,再重放 RPC。
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
        workerId,
        method,
        params,
        timeoutMs,
      });
      // 已连接:立即发送;重连中:入队等待 welcome 后 replayPendingRpcs 重放。
      if (this.ws && this.stateValue === 'open') {
        this.pub(channels.workerCmd(this.k, workerId), 'rpc', { reqId, method, params });
      }
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
    this.clearHoldTimer();
    for (const [, p] of this.pendingRpc) {
      clearTimeout(p.timer);
      p.reject(error);
    }
    this.pendingRpc.clear();
  }

  /** 持有在途 RPC 的最大时长(毫秒):超时后放弃重放,拒绝全部挂起请求。 */
  private static readonly HOLD_TIMEOUT_MS = 30_000;
  private holdTimer: ReturnType<typeof setTimeout> | null = null;

  private clearHoldTimer(): void {
    if (this.holdTimer) {
      clearTimeout(this.holdTimer);
      this.holdTimer = null;
    }
  }

  /**
   * 挂起在途 RPC:暂停超时计时器,等待重连后重放。
   * 不拒绝任何请求——业务层全程无感知,仅 UI 层弹重连模态框。
   * 设置 holdTimer:若重连耗时过长(HOLD_TIMEOUT_MS),放弃重放并拒绝全部请求。
   */
  private holdPendingRpcs(): void {
    for (const [, p] of this.pendingRpc) {
      clearTimeout(p.timer);
    }
    if (this.pendingRpc.size > 0) {
      this.clearHoldTimer();
      this.holdTimer = setTimeout(() => {
        this.holdTimer = null;
        this.failPending(new Error('重连超时,请检查网络连接'));
      }, HubClient.HOLD_TIMEOUT_MS);
    }
  }

  /**
   * 重放挂起的在途 RPC:在重连成功(welcome + 订阅恢复)后调用。
   * 用相同 reqId 重新发送 rpc 帧——worker 收到后正常处理并应答,
   * 业务层的 Promise 正常 resolve/reject,全程无感知。
   */
  private replayPendingRpcs(): void {
    if (this.pendingRpc.size === 0) return;
    this.clearHoldTimer();
    for (const [reqId, p] of this.pendingRpc) {
      p.timer = setTimeout(() => {
        this.pendingRpc.delete(reqId);
        p.reject(new Error(`rpc ${p.method} 超时(${p.timeoutMs}ms)`));
      }, p.timeoutMs);
      this.pub(channels.workerCmd(this.k, p.workerId), 'rpc', { reqId, method: p.method, params: p.params });
    }
  }

  /**
   * 心跳探测(§4.2):每 HEARTBEAT_INTERVAL_MS 一次,仅当连接 open 且非手动关闭时生效。
   * 本周期有帧到达 → 连接活跃,清未应答 ping,不发;首次空闲 → 发 ping 探测;
   * ping 后 HEARTBEAT_DEAD_MS 无任何帧 → 判死:停心跳,ws.close() 触发 onclose 走
   * 既有瞬态断连流程(holdPendingRpcs + scheduleReconnect 零退避首试)。不在此直接调
   * scheduleReconnect——onclose 已处理;仅对 ws 已 null/非 OPEN 的边缘情况防御性直走断连流程。
   */
  private heartbeatTick(): void {
    if (this.stateValue !== 'open' || this.manualClose) return;
    const now = Date.now();
    if (now - this.lastFrameAt < HubClient.HEARTBEAT_INTERVAL_MS) {
      // 本周期有帧:连接活跃(任务流推送、pong、任何服务端帧都算)。
      this.pingSentAt = 0;
      return;
    }
    if (this.pingSentAt === 0) {
      // 首次空闲:发 ping 探测。判死依据必须是「ping 后无应答」,不能是「距上帧超时」
      // ——降载退订后连接合法空闲、JS 冻结期间无法发 ping,按距上帧判死会误杀健康连接。
      this.send({ type: 'ping', ts: now });
      this.pingSentAt = now;
      return;
    }
    if (now - this.pingSentAt < HubClient.HEARTBEAT_DEAD_MS) {
      // 等待应答中,下个周期再看,不发重复 ping。
      return;
    }
    // 判死:发出 ping 后 HEARTBEAT_DEAD_MS 无任何帧到达。
    this.pingSentAt = 0;
    this.stopHeartbeat();
    const ws = this.ws;
    if (ws && ws.readyState === WebSocket.OPEN) {
      try {
        // 主动关闭 → onclose → 既有瞬态断连流程。
        ws.close();
        return;
      } catch {
        // close 抛错则落入下方防御路径。
      }
    }
    // 防御:ws 已 null/非 OPEN,onclose 可能不再触发,直接走断连流程。
    this.ws = null;
    this.holdPendingRpcs();
    this.scheduleReconnect();
  }

  /** 启动应用层心跳(welcome 后调用;先清旧定时器,防重复启动)。 */
  private startHeartbeat(): void {
    this.stopHeartbeat();
    this.lastFrameAt = Date.now();
    this.heartbeatTimer = setInterval(
      () => this.heartbeatTick(),
      HubClient.HEARTBEAT_INTERVAL_MS,
    );
  }

  /** 停止心跳并重置未应答 ping(close / 每次连接断开时调用)。 */
  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
    this.pingSentAt = 0;
  }

  /**
   * visibilitychange 订阅降载(§4.2.2 防线 3):只管订阅,不参与连接管理——
   * 连接生死唯一由心跳判定,本事件发不出去也无害(防线 1+2 兜底)。
   *
   * hidden:若 ws open,退订 desiredSubs 中以 .stream 结尾的频道(hub 发
   *   subscriber.leave → worker 销毁推送器,「没人看就别推」),记入 hiddenShedSubs;
   *   不动 desiredSubs——重连后 welcome 重发 desiredSubs 语义保持。
   *   连接不在 open(重连中)则无需处理。
   * visible:若 ws open,重订 hiddenShedSubs 中每个频道并清空集合;确有恢复的频道时
   *   调 onReconnect 触发上层重拉校准(worker 因 subscriber.join 重建推送器回扫尾段)。
   *   不在 open 时仅清空集合(welcome 重发 desiredSubs 覆盖)。
   */
  private wireVisibilityShedding(): void {
    if (this.visibilityShedWired) return;
    if (typeof document === 'undefined') return;
    this.visibilityShedWired = true;
    document.addEventListener('visibilitychange', () => {
      const open = this.ws !== null && this.stateValue === 'open';
      if (document.visibilityState === 'hidden') {
        if (!open) return;
        for (const ch of this.desiredSubs) {
          if (ch.endsWith('.stream')) {
            this.send({ type: 'unsub', channel: ch });
            this.hiddenShedSubs.add(ch);
          }
        }
        return;
      }
      if (document.visibilityState !== 'visible') return;
      const hadShed = this.hiddenShedSubs.size > 0;
      if (open) {
        for (const ch of this.hiddenShedSubs) {
          this.send({ type: 'sub', channel: ch });
        }
      }
      this.hiddenShedSubs.clear();
      if (hadShed && open) {
        // 确有恢复的频道:触发上层重拉校准。
        this.onReconnect?.();
      }
    });
  }

  private backoffMs(attempt: number): number {
    // attempt 0 = 首试零退避:心跳判死属于「主动检测发现」,此刻网络往往是好的
    // (关屏/锁屏场景),立即重连可把重连模态框压到 ~1s。失败后才进入指数退避。
    if (attempt <= 0) {
      return 0;
    }
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
