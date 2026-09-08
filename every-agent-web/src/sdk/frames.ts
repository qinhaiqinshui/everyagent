/** 线上帧类型(架构 §3)。与 every-agent-contract Frames.java 对齐。 */

export const PROTOCOL_VERSION = 2;

/** 前端流消费进度回报事件(worker 级输入频道,payload={taskId, creditIndex})。 */
export const STREAM_ACK = 'stream.ack';

export interface HelloFrame {
  type: 'hello';
  ver: number;
  role: 'frontend' | 'worker' | 'agent';
  apiKey: string;
  clientId: string;
  /** hub 连接凭证(保护 hub);旧协议/旧客户端可能不带,故为可选。 */
  hubKey?: string;
}

export interface WelcomeFrame {
  type: 'welcome';
  ver: number;
  sessionId?: string;
  serverTs?: number;
}

export interface SubFrame {
  type: 'sub';
  channel: string;
}

export interface UnsubFrame {
  type: 'unsub';
  channel: string;
}

export interface PubFrame {
  type: 'pub';
  mid: string;
  channel: string;
  event: string;
  ts?: number;
  payload?: unknown;
}

/** hub 扇出的消息帧。 */
export interface MsgFrame {
  type: 'msg';
  channel: string;
  event: string;
  /**
   * 事件序号(仅 stream 频道携带;其余频道无此字段)。
   * worker 以<b>字符串</b>传输 64 位 Snowflake seq(> Number.MAX_SAFE_INTEGER,
   * JSON number 会丢精度,详见 contract Frames.wirePub);number 仅为兼容旧后端/测试保留。
   */
  seq?: number | string;
  ts?: number;
  from?: { clientId: string; role: string; sessionId?: string };
  /**
   * 数据包协议操作语义(架构 §6.2 新协议,缺省按 "replace" 处理):
   * - "replace":无该 seq → 新建数据包;有该 seq → 用新帧整体替换;
   * - "append":取出旧包,把 payload.text 追加到对应字段(thinking→payload.thinking,
   *   delta→payload.content,其他 event→payload.content)。
   *
   * 后端(DataPusher)把 operate 放在 ext.operate(与 ext.persist 同层,经 hub 原样透传);
   * 本顶层字段保留作兼容——读取方应优先取 ext.operate,缺失时回退本字段。
   */
  operate?: string;
  /** 扩展元数据(hub 原样透传;任务流帧含 target/operate/persist 等)。 */
  ext?: any;
  payload?: any;
}

export interface ErrorFrame {
  type: 'error';
  code: string;
  message?: string;
}

export type ClientFrame = HelloFrame | SubFrame | UnsubFrame | PubFrame;
export type ServerFrame = WelcomeFrame | MsgFrame | ErrorFrame;

/** rpc 载荷(worker cmd 频道上的 event="rpc")。 */
export interface RpcRequest {
  reqId: string;
  method: string;
  params?: Record<string, unknown>;
}

export interface RpcOk {
  reqId: string;
  result: any;
}

export interface RpcErr {
  reqId: string;
  code: string;
  message: string;
}

export interface RpcData {
  reqId: string;
  batch: any[];
  hasMore: boolean;
}

export interface RpcProgress {
  reqId: string;
  message: string;
  pct?: number;
}

export class RpcError extends Error {
  constructor(readonly code: string, message: string) {
    super(`[${code}] ${message}`);
  }
}
