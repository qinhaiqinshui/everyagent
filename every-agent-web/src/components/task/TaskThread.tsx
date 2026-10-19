import React, { useEffect, useRef, useState } from 'react'
import type { ThreadItem } from '@/task/threadItem'
import type { TracePayload } from '@/task/threadItem'
import { MESSAGE_KINDS, payloadAs } from '@/task/threadItem'
import type { PluginTraceRecord } from '@everyagent/plugin-api'
import type { AgentMessageRecord } from '@/types'
import { BrandMark } from '@/components/shared/BrandLoadingBlock'
import { getTraceType, resolveTraceIcon, stringifyTraceContent } from '@/plugin/traceTypeRegistry'
import AgentMessageThread from '../task/AgentMessageThread'
import { ChevronDownIcon, ChevronRightIcon } from '../shared/AppGlyphs'

/**
 * Task 线程组件属性。
 */
export interface TaskThreadProps {
  /** 当前 Task ID。 */
  taskId: string
  /** 已按 seq 升序排好的线程项(五字段 ThreadItem)。 */
  items: ThreadItem[]
  /** 当前是否处于加载中。 */
  loading?: boolean
  /** 主 agent 稳定 Id（渲染层消费 agentId 区分子 agent 输入；透传给 AgentMessageThread）。 */
  mainAgentId?: string
  /**
   * 本轮生成是否在飞（含点击发送 → 首 token 前空窗 → 流式输出 → 工具执行中 → 收口）。
   * 驱动线程底部的任务状态横幅（TaskThread.ThreadStatusBanner）：任务运行中显示、随消息被向下顶、
   * 文案从一组短语随机轮播；同时用于生成窗口内尚无任何线程项时不落入空态。
   */
  isGenerating?: boolean
  /** 线程项之前渲染（懒加载占位：运行中尾轮的 backward sentinel 等）。 */
  topSlot?: React.ReactNode
  /** 线程项之后渲染（懒加载占位：闭合轮/终态尾轮的 forward sentinel 等）。 */
  bottomSlot?: React.ReactNode
}

/**
 * Task 聊天线程。
 * 统一渲染 agent messages 与 task traces，采用单列流式布局。
 */
export default function TaskThread({
  taskId,
  items,
  loading = false,
  mainAgentId = '',
  isGenerating = false,
  topSlot,
  bottomSlot,
}: TaskThreadProps) {
  if (loading) {
    return <div className="nagent-empty">正在线程加载中...</div>
  }
  if (items.length === 0 && !isGenerating) {
    return <div className="nagent-empty">当前 Task 还没有消息或追踪记录</div>
  }
  return (
    <div className="nagent-thread">
      {topSlot}
      {buildMessageGroups(items, taskId, mainAgentId)}
      {bottomSlot}
      <ThreadStatusBanner running={isGenerating} />
    </div>
  )
}

/** 任务运行中的状态横幅。 */
const TASK_STATUS_PHRASES = [
  '推演天机…',
  '运转周天，灵气翻涌…',
  '祭出法宝！',
  '参悟大道，剑意纵横…',
  '撩拨圣女，面红耳赤…',
  '释放大招，山河变色！',
  '镇压诸天',
  '逆天改命',
  '斩妖除魔中…',
  '炼制九转金丹…',
  '布下诛仙大阵！',
  '沟通天地，雷劫将至…',
  '稳固境界，破境在即…',
  '一剑西来，天外飞仙斩尽宵小…',
  '脚踏星河，手摘日月，威压八荒！',
  '凝练神识，元神出窍…',
]

function ThreadStatusBanner({ running }: { running: boolean }) {
  const [phraseIdx, setPhraseIdx] = useState(0)
  const lastIdxRef = useRef(-1)

  useEffect(() => {
    if (!running) return
    let timer: number
    const pickNext = () => {
      let next = lastIdxRef.current
      while (next === lastIdxRef.current && TASK_STATUS_PHRASES.length > 1) {
        next = Math.floor(Math.random() * TASK_STATUS_PHRASES.length)
      }
      lastIdxRef.current = next
      setPhraseIdx(next)
      const delay = 5000 + Math.floor(Math.random() * 5000)
      timer = window.setTimeout(pickNext, delay)
    }
    pickNext()
    return () => window.clearTimeout(timer)
  }, [running])

  if (!running) return null

  return (
    <div className="nagent-thread__status" role="status" aria-live="polite">
      <span className="nagent-thread__status-logo" aria-hidden="true">
        <BrandMark size={12} animated={false} />
      </span>
      <span className="nagent-thread__status-text" data-text={TASK_STATUS_PHRASES[phraseIdx]}>
        {TASK_STATUS_PHRASES[phraseIdx]}
      </span>
    </div>
  )
}

/**
 * 工具调用 join 所需的索引（callId → 名称 / 参数 / tool 结果消息）。
 * 终态折叠时，过程段与最终回复需要共享同一份索引，否则按 callId 合并结果会跨段丢失。
 */
interface TaskThreadToolMaps {
  toolCallNameById: Map<string, string>
  toolCallPayloadById: Map<string, Record<string, unknown>>
  toolResultById: Map<string, AgentMessageRecord>
}

/**
 * ThreadItem → AgentMessageThread 消息视图模型(渲染内部形状:AgentMessageRecord,
 * 从 message 族 kind 的 payload 适配;步骤 10 分发切换时随视图迁移)。
 * 消息族 kind(user.message/message/tool.result)适配;trace 族返回 null(TaskTraceShell 渲染)。
 */
function messageViewOf(item: ThreadItem): AgentMessageRecord | null {
  switch (item.kind) {
    case 'user.message': {
      const p = payloadAs(item, 'user.message')
      return {
        messageId: item.seq,
        agentId: item.agentId,
        role: 'user',
        content: p?.content ?? '',
        rawContent: p?.rawContent,
        historyMode: 'thread_only',
        createdAt: item.ts,
        updatedAt: item.ts,
        sequence: Number(item.seq),
      }
    }
    case 'message': {
      const p = payloadAs(item, 'message')
      return {
        messageId: item.seq,
        agentId: item.agentId,
        role: 'assistant',
        content: p?.content ?? '',
        reasoning: p?.thinking,
        toolCalls: p?.toolCalls,
        historyMode: 'thread_only',
        createdAt: item.ts,
        updatedAt: item.ts,
        sequence: Number(item.seq),
        // 折叠层帧状态(streaming/reasoningDone)→ 视图模型 metadata(消息视图消费口径)。
        metadata: {
          ...(p?.streaming !== undefined ? { streaming: p.streaming } : {}),
          ...(p?.reasoningDone !== undefined ? { reasoningDone: p.reasoningDone } : {}),
        },
      }
    }
    case 'tool.result': {
      const p = payloadAs(item, 'tool.result')
      return {
        messageId: item.seq,
        agentId: item.agentId,
        role: 'tool',
        content: p?.content ?? '',
        toolCallId: p?.callId,
        toolName: p?.toolName,
        status: p?.status,
        historyMode: 'thread_only',
        createdAt: item.ts,
        updatedAt: item.ts,
        sequence: Number(item.seq),
      }
    }
    default:
      return null
  }
}

/** 从一段线程项中提取工具调用 join 索引。 */
function buildToolMaps(items: ThreadItem[]): TaskThreadToolMaps {
  const toolCallNameById = new Map<string, string>()
  const toolCallPayloadById = new Map<string, Record<string, unknown>>()
  // callId → tool 结果消息：供 assistant 下发块按 callId 合并结果（live/历史同一套 join）。
  const toolResultById = new Map<string, AgentMessageRecord>()
  for (const item of items) {
    if (item.kind === 'message') {
      for (const call of payloadAs(item, 'message')?.toolCalls ?? []) {
        toolCallNameById.set(call.id, call.function.name)
        // 仅存下发参数（callId → arguments）。结果内容在 tool.result 项的 payload.content 中，
        // 不应覆盖此处，否则 resolveToolCallPayload 会返回结果而非参数（见 AgentMessageThread 聚合块）。
        toolCallPayloadById.set(call.id, parseToolPayload(call.function.arguments))
      }
      continue
    }
    if (item.kind === 'tool.result') {
      const callId = payloadAs(item, 'tool.result')?.callId?.trim()
      const view = messageViewOf(item)
      if (callId && view && !toolResultById.has(callId)) {
        toolResultById.set(callId, view)
      }
    }
  }
  return { toolCallNameById, toolCallPayloadById, toolResultById }
}

/**
 * 将线程项按连续同角色消息分组渲染（使用调用方传入的工具 join 索引）。
 * 消息族 kind 与 role 一一对应(user.message→user / message→assistant / tool.result→tool),
 * 其余 kind 按 trace 族走 TaskTraceShell。
 */
function renderMessageGroups(
  items: ThreadItem[],
  taskId: string,
  maps: TaskThreadToolMaps,
  mainAgentId: string,
): React.ReactNode[] {
  const { toolCallNameById, toolCallPayloadById, toolResultById } = maps
  const result: React.ReactNode[] = []
  const toolCallNameOf = (msg: { toolCallId?: string }): string | undefined =>
    (msg.toolCallId?.trim() && toolCallNameById.get(msg.toolCallId.trim())) || undefined
  const resolveToolResult = (callId: string): AgentMessageRecord | undefined =>
    (callId?.trim() && toolResultById.get(callId.trim())) || undefined

  let i = 0
  while (i < items.length) {
    const item = items[i]
    if (!MESSAGE_KINDS.has(item.kind)) {
      result.push(renderTraceItem(item, taskId))
      i += 1
      continue
    }

    const groupItems: ThreadItem[] = []

    if (item.kind === 'tool.result') {
      while (i < items.length) {
        const current = items[i]
        if (current.kind === 'tool.result') {
          groupItems.push(current)
          i += 1
          continue
        }
        // trace 族不纳入 tool 分组，使其保持独立排序位置（落在触发它的工具调用之后）
        break
      }
    } else {
      while (i < items.length && items[i].kind === item.kind) {
        groupItems.push(items[i])
        i += 1
      }
    }

    if (item.kind === 'tool.result') {
      const first = groupItems[0]
      const firstView = messageViewOf(first)
      const firstCallId = payloadAs(first, 'tool.result')?.callId?.trim()
      const hasMatchingDispatch = Boolean(firstCallId && toolCallNameById.has(firstCallId))
      // 结果已被对应 assistant 下发块吸收（按 callId join），不再单独渲染，避免重复 block。
      if (!hasMatchingDispatch && firstView) {
        result.push(
          <AgentMessageThread
            key={`toolgroup:${first.seq}`}
            seq={first.seq}
            message={firstView}
            taskId={taskId}
            mainAgentId={mainAgentId}
            mergedToolMessages={groupItems.map((groupItem) => messageViewOf(groupItem)).filter(Boolean) as AgentMessageRecord[]}
            resolveToolCallName={toolCallNameOf}
            resolveToolCallPayload={(toolCallId) => toolCallPayloadById.get(toolCallId)}
          />,
        )
      }
      continue
    }

    if (groupItems.length === 1) {
      const first = groupItems[0]
      result.push(
        <AgentMessageThread
          key={`message:${first.seq}`}
          seq={first.seq}
          message={messageViewOf(first)!}
          taskId={taskId}
          mainAgentId={mainAgentId}
          resolveToolCallPayload={(toolCallId) => toolCallPayloadById.get(toolCallId)}
          resolveToolResult={resolveToolResult}
        />,
      )
      continue
    }

    result.push(
      <div className="nagent-msg-group" key={`group:${groupItems[0].seq}`}>
        {groupItems.map((groupItem, idx) => (
          <AgentMessageThread
            key={`message:${groupItem.seq}`}
            seq={groupItem.seq}
            message={messageViewOf(groupItem)!}
            taskId={taskId}
            mainAgentId={mainAgentId}
            isContinuation={idx > 0}
            resolveToolCallPayload={(toolCallId) => toolCallPayloadById.get(toolCallId)}
            resolveToolResult={resolveToolResult}
          />
        ))}
      </div>,
    )
  }

  return result
}

/** 将线程项按连续同角色消息分组渲染（自行构建工具 join 索引）。 */
function buildMessageGroups(
  items: ThreadItem[],
  taskId: string,
  mainAgentId: string,
): React.ReactNode[] {
  return renderMessageGroups(items, taskId, buildToolMaps(items), mainAgentId)
}

/** 渲染单条 trace 族线程项。 */
function renderTraceItem(
  item: ThreadItem,
  taskId: string,
): React.ReactNode {
  return <TaskTraceShell key={`trace:${item.seq}`} item={item} taskId={taskId} />
}

/** 解析工具调用 / 工具结果的 JSON 负载。 */
function parseToolPayload(rawText: string | undefined): Record<string, unknown> {
  const text = rawText?.trim()
  if (!text) {
    return {}
  }
  try {
    const parsed = JSON.parse(text)
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>
    }
  } catch {
    return {}
  }
  return {}
}

/** 单条 trace 族线程项(payload = trace 形状:title/summary/content/data)。 */
function TaskTraceShell({
  item,
  taskId,
}: {
  item: ThreadItem
  taskId: string
}) {
  const payload: TracePayload = (item.payload as TracePayload | undefined) ?? { title: '' }
  const [open, setOpen] = React.useState(false)
  // trace 类型解析 key:error/cancelled 的渲染消费口径沿用旧 trace.kind 注册
  // (run_error 错误色调/alert 图标、system.notice 图标),步骤 10 kind 渲染注册表
  // 分发切换时统一收敛。
  const traceTypeKey = item.kind === 'error' ? 'run_error' : item.kind === 'cancelled' ? 'system_notice' : item.kind
  const traceType = getTraceType(traceTypeKey)
  // traceTypeRegistry(插件 trace 类型扩展点)消费 PluginTraceRecord 形状:
  // 由 item 五字段 + payload 合成兼容视图对象(getSummary/renderContent/canExpand/getIcon)。
  const trace: PluginTraceRecord = {
    traceId: item.seq,
    taskId,
    agentId: item.agentId || undefined,
    kind: traceTypeKey,
    title: payload.title,
    summary: payload.summary,
    content: payload.content,
    metadata: payload.data,
    createdAt: item.ts,
  }
  const icon = resolveTraceIcon(trace.icon ?? traceType.getIcon?.(trace))
  const title = payload.title?.trim() || ''
  const showTitle = !traceType.hideTitle && !!title
  const summary = buildTraceSummary(trace, traceType.getSummary)
  const titleText = [showTitle ? title : '', summary].filter(Boolean).join(' ')
  // 缺省仅当存在 content 时才可折叠/展开；kind 自定义 canExpand(如 auth.review 数据在 data)时以其结果为准。
  const expandable = payload.content != null || traceType.canExpand?.(trace) === true
  const content = expandable ? (traceType.renderContent?.(trace, { taskId }) ?? null) : null

  const summaryRow = expandable ? (
    <button
      type="button"
      className="nagent-trace-shell__summary"
      aria-expanded={open}
      onClick={() => setOpen((current) => !current)}
    >
      {icon ? <span className="nagent-trace-shell__icon">{icon}</span> : null}
      {showTitle ? <span className="nagent-trace-shell__title">{title}</span> : null}
      {summary ? <span className="nagent-trace-shell__text">{summary}</span> : null}
      <span className="nagent-trace-shell__chevron">
        {open ? <ChevronDownIcon size={13} /> : <ChevronRightIcon size={13} />}
      </span>
    </button>
  ) : (
    <div className="nagent-trace-shell__summary nagent-trace-shell__summary--static">
      {icon ? <span className="nagent-trace-shell__icon">{icon}</span> : null}
      {showTitle ? <span className="nagent-trace-shell__title">{title}</span> : null}
      {summary ? <span className="nagent-trace-shell__text">{summary}</span> : null}
    </div>
  )

  return (
    <section
      className={`nagent-trace-shell nagent-msg${traceType.tone === 'error' ? ' nagent-trace-shell--error' : ''}${open && expandable ? ' is-open' : ''}`}
      title={titleText}
    >
      {summaryRow}
      {open && expandable ? <div className="nagent-trace-shell__content">{content}</div> : null}
    </section>
  )
}

/** 生成收起态摘要。 */
function buildTraceSummary(
  trace: Pick<PluginTraceRecord, 'summary' | 'content'>,
  getSummary?: (trace: PluginTraceRecord) => string | undefined,
): string {
  // 已注册 getSummary 时以其结果为准（返回空即不展示摘要）；未注册的旧数据才回退派生。
  const raw = getSummary
    ? (getSummary(trace as PluginTraceRecord)?.trim() ?? '')
    : (trace.summary?.trim() || stringifyTraceContent(trace.content).trim())
  if (!raw) {
    return ''
  }
  // 不截断摘要：summary 需完整展示（如 model_failover 的最后切换模型），
  // 过长时由样式层换行/省略控制。
  return raw
}
