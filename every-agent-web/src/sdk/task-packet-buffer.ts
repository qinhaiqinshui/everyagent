/**
 * TaskPacketBuffer(架构 §6.2 数据包模式):按「数据包数组」维护任务流最新状态。
 *
 * 新协议:每轮 AI 回复 = 1 个 seq(round id,后端 Snowflake 64 位 long),同轮多帧
 * 用 operate 字段区分语义——
 * - replace(缺省):无该 seq → 新建数据包;有该 seq → 用新帧整体替换;
 * - append:取出旧包,把 payload.text 追加到对应字段:thinking→payload.thinking,
 *   delta→payload.content,其他 event→payload.content(兜底)。
 *
 * 数组按键 seq 去重(key 统一转 string:Snowflake 超过 Number.MAX_SAFE_INTEGER,
 * string 键保证唯一性判断正确),按 ts 升序排序(ts 取数据包首个帧的时间,线程位置稳定)。
 *
 * 本类只维护「数据包数组」这一数据结构;把聚合变化增量投递给折叠器(think/delta 追加片段、
 * replace 整包)由上层视图(TaskPacketView)负责。
 */
export type PacketOperate = 'replace' | 'append'

/** 数据包数组中一个 seq 数据包的当前状态。 */
export interface TaskPacket {
  /** 数据包键:seq 统一为 string(Snowflake 唯一性)。 */
  seq: string
  /** 数据包创建帧的 ts(升序排序键,创建后不变 → 线程位置稳定)。 */
  ts: number
  /** 当前事件名(replace 后 = 帧事件;append 聚合保持首帧事件)。 */
  event: string
  /** 归属 agent(缺省/空 = 主线程)。 */
  agentId?: string | null
  /** 聚合后的载荷(replace 整体替换 / append 原地追加文本)。 */
  payload: any
  /** 已应用帧数(同 seq 多帧递增,区分更新次序)。 */
  version: number
}

/** 一帧协议输入(MsgFrame 的任务流切片)。 */
export interface PacketFrame {
  seq: number | string
  ts?: number
  event: string
  agentId?: string | null
  /** 缺省按 "replace" 处理。 */
  operate?: string
  /** 历史回放帧标记(worker DataPusher 磁盘全量推送)。 */
  initial?: boolean
  payload?: any
}

/** 一帧应用后的增量信息(供调用方折叠)。 */
export interface TaskPacketUpdate {
  /** 该帧应用后的数据包(可变引用)。 */
  packet: TaskPacket
  /** 是否改变了数据包内容(replace 恒 true;append 仅追加了非空文本时为 true)。 */
  changed: boolean
  /** append 帧追加到的字段与文本。 */
  appended?: { field: 'thinking' | 'content'; text: string }
}

export class TaskPacketBuffer {
  private readonly index = new Map<string, TaskPacket>()
  private list: TaskPacket[] = []

  /** 数据包数组快照(按 ts 升序)。 */
  get packets(): TaskPacket[] {
    return this.list.slice()
  }

  get size(): number {
    return this.list.length
  }

  get(seq: number | string): TaskPacket | undefined {
    return this.index.get(String(seq))
  }

  clear(): void {
    this.index.clear()
    this.list = []
  }

  /** 应用一帧;返回数据包更新信息(供调用方增量折叠)。 */
  apply(frame: PacketFrame): TaskPacketUpdate {
    const seq = String(frame.seq ?? '')
    const ts = frame.ts ?? Date.now()
    const payload = frame.payload ?? {}
    const operate: PacketOperate = frame.operate === 'append' ? 'append' : 'replace'
    const existing = this.index.get(seq)

    if (!existing) {
      // 无该 seq → 创建新数据包并按 ts 插入(数组保持升序)
      const packet: TaskPacket = {
        seq,
        ts,
        event: frame.event,
        agentId: frame.agentId,
        payload: clonePayload(payload),
        version: 1,
      }
      this.index.set(seq, packet)
      insertSorted(this.list, packet)
      if (operate === 'append') {
        // 首帧即 append(订阅中途进入的流式中):按 append 语义落字段
        const field = appendFieldFor(frame.event)
        const text = String(payload.text ?? '')
        packet.payload = { ...(packet.payload ?? {}), [field]: (packet.payload?.[field] ?? '') + text }
        return { packet, changed: text.length > 0, appended: { field, text } }
      }
      return { packet, changed: true }
    }

    if (operate === 'replace') {
      // 有该 seq + replace → 用新帧整体替换(ts 保持创建值,线程位置稳定)
      existing.event = frame.event
      existing.agentId = frame.agentId
      existing.payload = clonePayload(payload)
      existing.version++
      return { packet: existing, changed: true }
    }

    // 有该 seq + append → 按 event 把 payload.text 追加到旧包对应字段
    const field = appendFieldFor(frame.event)
    const text = String(payload.text ?? '')
    const current = existing.payload?.[field] ?? ''
    existing.payload = { ...(existing.payload ?? {}), [field]: current + text }
    existing.version++
    return { packet: existing, changed: text.length > 0, appended: { field, text } }
  }
}

/** append 追加字段映射:thinking→payload.thinking,delta→payload.content,其他→payload.content。 */
function appendFieldFor(event: string): 'thinking' | 'content' {
  return event === 'thinking' ? 'thinking' : 'content'
}

function clonePayload(payload: any): any {
  if (payload && typeof payload === 'object' && !Array.isArray(payload)) {
    return { ...payload }
  }
  return payload
}

/** 按 ts 升序插入(数据包 ts 创建后不变,插入后数组保持有序)。 */
function insertSorted(list: TaskPacket[], packet: TaskPacket): void {
  let i = list.length
  while (i > 0 && list[i - 1].ts > packet.ts) i--
  list.splice(i, 0, packet)
}

/**
 * seq 比较:Snowflake 64 位 long 超过 Number.MAX_SAFE_INTEGER,不能直接做 number 比较。
 * 两个纯数字串按「位数优先 + 字典序」比较(等价于数值序,长度不一也正确);
 * 含非数字字符时退化为纯字典序。输入 number 统一转 string。返回 -1/0/1。
 */
export function compareSeq(a: number | string, b: number | string): number {
  const sa = String(a)
  const sb = String(b)
  const na = /^\d+$/.test(sa)
  const nb = /^\d+$/.test(sb)
  if (na && nb) {
    if (sa.length !== sb.length) return sa.length < sb.length ? -1 : 1
    return sa < sb ? -1 : sa > sb ? 1 : 0
  }
  return sa < sb ? -1 : sa > sb ? 1 : 0
}
