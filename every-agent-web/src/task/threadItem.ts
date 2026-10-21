/**
 * 统一线程条目契约(五字段 ThreadItem,「任务线程统一条目与画板纯化」步骤 8 定稿)。
 *
 * 线程数据层(folder.state.items)的唯一条目形状:折叠器(eventFolder/eventRegistry)
 * 产出,渲染层消费。**seq 即 id**——旧 `m-` 前缀 messageId / `t-` 前缀 traceId 体系废除,
 * 排序/去重/切片/懒加载游标全部直接用 item.seq(wire 原始雪花大数串,§5.4 口径)。
 *
 * payload 形状所有权归 kind:各 kind 的折叠 handler 写什么形状、对应视图读什么形状,
 * 由本文件的 payload 类型逐 kind 声明;`KindPayloadMap` 为**声明合并接口**(kind →
 * payload 类型的编译期配对校验,纯类型层,运行时不存在、折叠器与视图均不做 Map 消费)。
 * 视图侧经 `payloadAs(item, kind)` 类型化取载荷(kind 不匹配返回 undefined)。
 */
import type { LLMToolCall } from '@/types'

/**
 * 统一线程条目(五字段;items 内不再有消息/trace 双形状)。
 * 折叠器保证:items 恒按 seq 升序;同 seq 轮事件(thinking/delta/message)复用同一条
 * message 项(只能更新不可重复插入),非轮 item 事件同 seq 去重,trace 族同 seq 原地 upsert。
 */
export interface ThreadItem {
  /** 排序/去重/切片/懒加载游标唯一键。wire 原始字符串(64 位 Snowflake 大数串,超 2^53,不可走 Number;一轮 AI 回复 = 1 个 seq,同轮 thinking/delta/message 共用)。 */
  seq: string
  /** 事件时间戳(毫秒)。 */
  ts: number
  /** 归属 agent(折叠器写入:事件缺 agentId 归一到 mainAgentId;mainAgentId 未灌入的极早期可为空串,消费方按 `item.agentId || mainAgentId` 归一)。 */
  agentId: string
  /** kind(开放集合:user.message/message/tool.result/error/cancelled/retry/model.failover/… 及 defaultHandler 折入的任意未来 kind;瞬态 round.opened/round.closed/tool.started 与台账类 usage、agent 族、ask 族事件不进 items)。 */
  kind: string
  /** 形状所有权归 kind(见 KindPayloadMap 与各 payload 类型;渲染层经 payloadAs 类型化读取)。 */
  payload: unknown
}

// ─── 各 kind 的 payload 类型(与 eventRegistry 各 handler 写入字段逐一对齐) ──────

/** `user.message` payload:主/子 agent 用户输入(主=任务输入,子=派生 agent 收到的指令,按 agentId 区分)。 */
export interface UserMessagePayload {
  /** AI 可见明文。 */
  content: string
  /** 原始输入(含 opaque token 串,回放还原 @文件胶囊用);缺失回退 content(wire 同值)。 */
  rawContent?: string
}

/** `message` payload:一轮 AI 回复(流式增量聚合 + message 定稿权威替换,同 seq 复用同一条)。 */
export interface MessagePayload {
  role: 'assistant'
  /** 正文(delta 流式增量聚合;message 定稿权威替换)。 */
  content: string
  /** 思考过程(thinking 流式增量聚合;message 定稿权威替换,丢帧以权威为准)。 */
  thinking?: string
  /** 模型下发的工具调用(真实 id,与 tool.result 按 callId 配对;重复帧按 id 去重追加)。 */
  toolCalls?: LLMToolCall[]
  /** 折叠层帧状态:仍在流式聚合(定稿/finalize/回合边界关闭置 false)。 */
  streaming?: boolean
  /** 折叠层帧状态:思考阶段已结束(正文首达且已有思考置真;思考续增回退 false,驱动前端思考块自动折叠)。 */
  reasoningDone?: boolean
  /**
   * 思考开始时刻(毫秒,宿主时钟):首个 thinking 增量帧的 wire ts,tool.started.startedAt 同口径。
   * 思考块实时读秒基准(now - thinkingStartedAt);历史回放由权威 message 的 thinkingStartedAt 还原。
   */
  thinkingStartedAt?: number
  /**
   * 思考耗时(毫秒):思考阶段真实流式耗时(末个 thinking 增量 - 首个)。
   * 优先取权威 message 下发的值;worker 未下发时前端按「思考结束帧 ts - thinkingStartedAt」兜底。
   */
  thinkingDurationMs?: number
}

/** `tool.result` payload:单次工具调用结果(per-call 链上行段发射,callId 直配)。 */
export interface ToolResultPayload {
  /** 工具调用 ID(与 message 下发 toolCalls[].id 配对)。 */
  callId: string
  /** 结果文本(链上行改写后的原文,脱敏掩码照常生效)。 */
  content: string
  /** 成败(权威值,worker 结构化记账判定收口:失败前缀 ∨ shell 退出码;缺省按成功)。 */
  status?: 'success' | 'error'
  /** 调用开始时间(毫秒;per-call 链下行切口记录,老事件缺失)。 */
  startedAt?: number
  /** 执行耗时(毫秒,批末冻结的权威值;老事件缺失)。 */
  durationMs?: number
  /** 工具名(callId → message.toolCalls 注册表回填;迟到/未知 callId 缺失)。 */
  toolName?: string
}

/** trace 族 payload(retry/context.compression/model.failover/model.switch/auth.review/system.notice 与 defaultHandler 兜底,kind=事件名)。 */
export interface TracePayload {
  /** 标题(worker 宽松下发,缺失折叠为空串)。 */
  title: string
  /** 摘要,收起态展示。 */
  summary?: string
  /** 正文,展开态渲染(字符串)。 */
  content?: string
  /** kind 专属结构化数据(旧 metadata 语义,auth.review 等经 canExpand/renderContent 消费)。 */
  data?: Record<string, unknown>
}

/** `error` payload:任务/子任务运行错误(title 按 isMain 主/子判定)。 */
export interface ErrorPayload {
  title: string
  summary?: string
  content?: string
}

/** `cancelled` payload:任务取消。 */
export interface CancelledPayload {
  title: string
  summary?: string
}

// ─── KindPayloadMap(声明合并接口:编译期配对校验,运行时不存在) ─────────────────

/**
 * kind → payload 类型映射(声明合并接口)。
 * 内置 kind 由本文件声明;新 kind 的 handler/视图配对经模块扩充声明合并
 * (`declare module '@/task/threadItem' { interface KindPayloadMap { 'my.kind': MyPayload } }`),
 * 使「handler 写的形状」与「视图读的形状」在编译期锁定一致。**纯类型层**:
 * 折叠器与渲染层不做任何运行时 Map 消费(kind 判定走字符串比较/payloadAs)。
 */
export interface KindPayloadMap {
  'user.message': UserMessagePayload
  'message': MessagePayload
  'tool.result': ToolResultPayload
  'error': ErrorPayload
  'cancelled': CancelledPayload
  'retry': TracePayload
  'context.compression': TracePayload
  'model.failover': TracePayload
  'model.switch': TracePayload
  'auth.review': TracePayload
  'system.notice': TracePayload
}

/** 类型化取载荷:item.kind 与预期 kind 一致时断言返回对应 payload 类型,否则 undefined。 */
export function payloadAs<K extends keyof KindPayloadMap & string>(
  item: ThreadItem,
  kind: K,
): KindPayloadMap[K] | undefined {
  return item.kind === kind ? (item.payload as KindPayloadMap[K]) : undefined
}

/**
 * 消息族 kind(fold 产出「消息位」条目):user.message / message / tool.result。
 * 参与懒加载缓存覆盖判定(roundRealFloor/roundRealCeiling——trace 不证明整轮覆盖,
 * 终点 message 在场才算)与尾轮首事件定位(firstTailEventSeq);其余 kind 一律按
 * trace 族处理(defaultHandler 兜底折入,TaskTraceShell 形状渲染)。
 */
export const MESSAGE_KINDS: ReadonlySet<string> = new Set(['user.message', 'message', 'tool.result'])
