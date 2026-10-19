import React, { useEffect, useRef, useState } from 'react'
import type { ThreadItem } from '@/task/threadItem'
import { MESSAGE_KINDS, payloadAs } from '@/task/threadItem'
import type { AgentMessageRecord } from '@/types'
import { BrandMark } from '@/components/shared/BrandLoadingBlock'
import AgentMessageThread from '../task/AgentMessageThread'
// 步骤 9:TaskTraceShell 原位提取为 kindViews/TraceShell.tsx(提取不改行为),
// FallbackView 兜底复用同一呈现;步骤 10 分发切换时本组件改查表分发。
import { TaskTraceShell } from './kindViews/TraceShell'

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
