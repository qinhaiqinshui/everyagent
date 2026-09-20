import React, { useEffect, useRef, useState } from 'react'
import type { TaskThreadItem } from '@/query/taskQueryService'
import type { AgentMessageRecord, TaskTraceRecord } from '@/types'
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
  /** 已按 createdAt 排序好的线程项。 */
  items: TaskThreadItem[]
  /** 当前是否处于加载中。 */
  loading?: boolean
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
  // 过程性内容折叠:折叠窗口由 eventFolder 在折叠阶段边接收边打标(foldRole),
  // 渲染层只消费标记,不依赖任务是否终态 → 历史回放与实时输出共用同一路径。
  const foldWindows = buildFoldWindows(items)
  return (
    <div className="nagent-thread">
      {topSlot}
      {foldWindows.length > 0
        ? renderThreadWithFoldWindows(items, taskId, foldWindows)
        : buildMessageGroups(items, taskId)}
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

/** 从一段线程项中提取工具调用 join 索引。 */
function buildToolMaps(items: TaskThreadItem[]): TaskThreadToolMaps {
  const toolCallNameById = new Map<string, string>()
  const toolCallPayloadById = new Map<string, Record<string, unknown>>()
  // callId → tool 结果消息：供 assistant 下发块按 callId 合并结果（live/历史同一套 join）。
  const toolResultById = new Map<string, AgentMessageRecord>()
  for (const it of items) {
    if (it.type !== 'agent_message') {
      continue
    }
    for (const call of it.message.toolCalls ?? []) {
      toolCallNameById.set(call.id, call.function.name)
      // 仅存下发参数（callId → arguments）。结果内容在 tool 角色消息的 content 中，
      // 不应覆盖此处，否则 resolveToolCallPayload 会返回结果而非参数（见 AgentMessageThread 聚合块）。
      toolCallPayloadById.set(call.id, parseToolPayload(call.function.arguments))
    }
    const toolCallId = it.message.toolCallId?.trim()
    if (it.message.role === 'tool' && toolCallId) {
      if (!toolResultById.has(toolCallId)) {
        toolResultById.set(toolCallId, it.message)
      }
    }
  }
  return { toolCallNameById, toolCallPayloadById, toolResultById }
}

/**
 * 将线程项按连续同角色消息分组渲染（使用调用方传入的工具 join 索引）。
 */
function renderMessageGroups(
  items: TaskThreadItem[],
  taskId: string,
  maps: TaskThreadToolMaps,
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
    if (item.type !== 'agent_message') {
      result.push(renderTaskTraceItem(item as Extract<TaskThreadItem, { type: 'task_trace' }>, taskId))
      i += 1
      continue
    }

    type AgentMsgItem = Extract<TaskThreadItem, { type: 'agent_message' }>
    const groupMessages: AgentMsgItem[] = []
    const groupRole = (item as AgentMsgItem).message.role

    if (groupRole === 'tool') {
      while (i < items.length) {
        const current = items[i]
        if (current.type === 'agent_message' && current.message.role === 'tool') {
          groupMessages.push(current)
          i += 1
          continue
        }
        // task_trace 不纳入 tool 分组，使其保持独立排序位置（落在触发它的工具调用之后）
        break
      }
    } else {
      while (i < items.length && items[i].type === 'agent_message' && (items[i] as AgentMsgItem).message.role === groupRole) {
        groupMessages.push(items[i] as AgentMsgItem)
        i += 1
      }
    }

    if (groupRole === 'tool') {
      const firstTool = groupMessages[0]?.message
      const hasMatchingDispatch = Boolean(
        firstTool?.toolCallId?.trim() && toolCallNameById.has(firstTool.toolCallId.trim()),
      )
      // 结果已被对应 assistant 下发块吸收（按 callId join），不再单独渲染，避免重复 block。
      if (!hasMatchingDispatch) {
        result.push(
          <AgentMessageThread
            key={`toolgroup:${groupMessages[0].message.messageId}`}
            message={groupMessages[0].message}
            taskId={taskId}
            mergedToolMessages={groupMessages.map((groupItem) => groupItem.message)}
            resolveToolCallName={toolCallNameOf}
            resolveToolCallPayload={(toolCallId) => toolCallPayloadById.get(toolCallId)}
          />,
        )
      }
      continue
    }

    if (groupMessages.length === 1) {
      result.push(
        <AgentMessageThread
          key={`message:${groupMessages[0].message.messageId}`}
          message={groupMessages[0].message}
          taskId={taskId}
          resolveToolCallPayload={(toolCallId) => toolCallPayloadById.get(toolCallId)}
          resolveToolResult={resolveToolResult}
        />,
      )
      continue
    }

    result.push(
      <div className="nagent-msg-group" key={`group:${groupMessages[0].message.messageId}`}>
        {groupMessages.map((groupItem, idx) => (
          <AgentMessageThread
            key={`message:${groupItem.message.messageId}`}
            message={groupItem.message}
            taskId={taskId}
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
  items: TaskThreadItem[],
  taskId: string,
): React.ReactNode[] {
  return renderMessageGroups(items, taskId, buildToolMaps(items))
}

/** 过程性内容折叠窗口：一条用户消息到其后第一条 AI 最终回复之间构成一个窗口。 */
interface FoldWindow {
  /** 用户消息在线程中的下标（含，右侧展示）。 */
  userIndex: number
  /** AI 最终回复在线程中的下标（含，左侧展示）。 */
  finalReplyIndex: number
  /** AI 最终回复线程项。 */
  finalItem: Extract<TaskThreadItem, { type: 'agent_message' }>
  /** 用户消息与最终回复之间的过程性内容（折叠）。 */
  processItems: TaskThreadItem[]
}

/**
 * 从线程中按 foldRole 标记推导折叠窗口：
 * 从每条用户消息开始往下扫，遇到其后第一条 AI 最终回复（主 agent、无工具调用且有正文）
 * 即关闭该窗口；二者之间全是过程性内容。若先扫到下一条用户消息，说明当前轮没有
 * final_reply（任务进行中 / 过滤视图缺终点），跳过该用户并继续扫后续用户消息。
 * 标记由 eventFolder 在折叠阶段边接收边写入，因此历史回放与实时输出得到一致的窗口划分。
 */
function buildFoldWindows(items: TaskThreadItem[]): FoldWindow[] {
  const windows: FoldWindow[] = []
  let i = 0
  while (i < items.length) {
    const item = items[i]
    if (item.type !== 'agent_message' || item.foldRole !== 'user') {
      i += 1
      continue
    }
    const userIndex = i
    let finalReplyIndex = -1
    for (let j = userIndex + 1; j < items.length; j++) {
      const candidate = items[j]
      if (candidate.type !== 'agent_message') {
        continue
      }
      // 折叠窗口不跨用户：扫到下一条用户消息时，当前轮尚未出现 final_reply，
      // 本轮不折叠，交给后续扫描处理后面已闭合的轮次。
      if (candidate.foldRole === 'user') {
        break
      }
      if (candidate.foldRole === 'final_reply') {
        finalReplyIndex = j
        break
      }
    }
    if (finalReplyIndex < 0) {
      // 当前用户消息之后暂无 final_reply（任务进行中，或过滤视图缺少该轮终点标记）：
      // 仅跳过这条用户消息并继续扫后续用户消息，避免漏掉后面已经闭合的折叠窗口。
      // 渲染是幂等的：items 推进后整线程重扫，未闭合轮次会在终点到达后被正确折叠。
      i = userIndex + 1
      continue
    }
    const processItems = items.slice(userIndex + 1, finalReplyIndex)
    if (processItems.length > 0) {
      windows.push({
        userIndex,
        finalReplyIndex,
        finalItem: items[finalReplyIndex] as Extract<TaskThreadItem, { type: 'agent_message' }>,
        processItems,
      })
    }
    // 继续扫下一条用户消息（若 final 紧邻 user 且无过程内容，窗口被跳过，但仍越过该 final）。
    i = finalReplyIndex + 1
  }
  return windows
}

/** 按折叠窗口渲染线程：窗口外的普通内容照常，窗口内仅保留用户消息与 AI 最终回复。 */
function renderThreadWithFoldWindows(
  items: TaskThreadItem[],
  taskId: string,
  windows: FoldWindow[],
): React.ReactNode[] {
  // 全线程共享同一份工具 join 索引：过程段、最终回复、窗口外片段各自渲染时，
  // 按 callId 合并工具结果都能命中同一张表。
  const maps = buildToolMaps(items)
  const result: React.ReactNode[] = []
  let cursor = 0
  for (const window of windows) {
    // 窗口之前的内容（含该窗口的用户消息）照常渲染。
    if (cursor <= window.userIndex) {
      result.push(...renderMessageGroups(items.slice(cursor, window.userIndex + 1), taskId, maps))
    }
    result.push(
      <CollapsibleRound
        key={`round:${window.finalItem.message.messageId}`}
        taskId={taskId}
        finalItem={window.finalItem}
        processItems={window.processItems}
        maps={maps}
      />,
    )
    cursor = window.finalReplyIndex + 1
  }
  // 最后一个窗口之后的内容照常渲染。
  if (cursor < items.length) {
    result.push(...renderMessageGroups(items.slice(cursor), taskId, maps))
  }
  return result
}

/** 折叠块：默认折叠过程内容，仅在 AI 最终回复上方保留一个折叠标记。 */
function CollapsibleRound({
  taskId,
  finalItem,
  processItems,
  maps,
}: {
  taskId: string
  finalItem: Extract<TaskThreadItem, { type: 'agent_message' }>
  processItems: TaskThreadItem[]
  maps: TaskThreadToolMaps
}) {
  const [open, setOpen] = React.useState(false)
  if (processItems.length === 0) {
    return <>{renderMessageGroups([finalItem], taskId, maps)}</>
  }
  return (
    <div className="nagent-round-collapse">
      <button
        type="button"
        className="nagent-round-collapse__marker"
        aria-expanded={open}
        onClick={() => setOpen((value) => !value)}
        title={open ? '收起 AI 过程内容' : '展开 AI 过程内容'}
      >
        <span className="nagent-round-collapse__brand">
          <BrandMark size={12} animated={false} />
          <span className="nagent-round-collapse__brand-name">Every Agent</span>
        </span>
        <span className="nagent-round-collapse__marker-chevron" aria-hidden="true">
          {open ? <ChevronDownIcon size={12} /> : <ChevronRightIcon size={12} />}
        </span>
      </button>
      {open ? (
        <div className="nagent-round-collapse__process">
          {renderMessageGroups(processItems, taskId, maps)}
        </div>
      ) : null}
      <div className="nagent-round-collapse__final">
        {renderMessageGroups([finalItem], taskId, maps)}
      </div>
    </div>
  )
}

/** 渲染单条任务 trace。 */
function renderTaskTraceItem(
  item: Extract<TaskThreadItem, { type: 'task_trace' }>,
  taskId: string,
): React.ReactNode {
  return <TaskTraceShell key={`trace:${item.trace.traceId}`} item={item} taskId={taskId} />
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

/** 单条 task trace 线程项。 */
function TaskTraceShell({
  item,
  taskId,
}: {
  item: Extract<TaskThreadItem, { type: 'task_trace' }>
  taskId: string
}) {
  const { trace } = item
  const [open, setOpen] = React.useState(false)
  const traceType = getTraceType(trace.kind)
  const icon = resolveTraceIcon(trace.icon ?? traceType.getIcon?.(trace))
  const title = trace.title?.trim() || ''
  const showTitle = !traceType.hideTitle && !!title
  const summary = buildTraceSummary(trace, traceType.getSummary)
  const titleText = [showTitle ? title : '', summary].filter(Boolean).join(' ')
  // 缺省仅当存在 content 时才可折叠/展开；kind 自定义 canExpand(如 auth.review 数据在 metadata)时以其结果为准。
  const expandable = trace.content != null || traceType.canExpand?.(trace) === true
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
    <section className={`nagent-trace-shell nagent-msg${open && expandable ? ' is-open' : ''}`} title={titleText}>
      {summaryRow}
      {open && expandable ? <div className="nagent-trace-shell__content">{content}</div> : null}
    </section>
  )
}

/** 生成收起态摘要。 */
function buildTraceSummary(
  trace: TaskTraceRecord,
  getSummary?: (trace: TaskTraceRecord) => string | undefined,
): string {
  // 已注册 getSummary 时以其结果为准（返回空即不展示摘要）；未注册的旧数据才回退派生。
  const raw = getSummary
    ? (getSummary(trace)?.trim() ?? '')
    : (trace.summary?.trim() || stringifyTraceContent(trace.content).trim())
  if (!raw) {
    return ''
  }
  // 不截断摘要：summary 需完整展示（如 model_failover 的最后切换模型），
  // 过长时由样式层换行/省略控制。
  return raw
}
