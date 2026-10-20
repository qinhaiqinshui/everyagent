/**
 * `message` kind 视图(「任务线程统一条目与画板纯化」步骤 10)。
 *
 * thinking(AssistantReasoningBlock:流式且未 reasoningDone 展开、完成后自动折叠)与
 * 正文(RichMessageContent)自 AgentMessageThread 现状迁移(行为等价);工具块为
 * **单调用直渲 / 多调用批容器**双形态:
 * - 单调用(toolCalls.length === 1):无包装,直接渲染 per-tool 美化视图(一次折叠
 *   即见参数与结果,状态图标 + 耗时在视图摘要行上);
 * - 多调用:批容器**不吃任何宽度**,「同一次请求下发」画在左侧轨道里(弧线 + ×N 徽标),
 *   内部平铺各调用的 per-tool 美化视图(无批壳行/无二次折叠)。
 *   于是单条与并行批的文字起点逐像素一致(见 chatPanel.css 栅格常量)。
 *
 * 运行态读秒:`tool.started` 瞬态映射(toolStartedRuntime,按 callId 直配)点亮 running
 * + 计时 now - startedAt(单一 1s tick,卸载清理);对应 tool.result 到达冻结权威
 * durationMs;历史回放无瞬态事件直接展示 durationMs;批中断(可见终态信号)黄/灰显。
 */
import React from 'react'
import type { LLMToolCall } from '@/types'
import type { ThreadItem, ToolResultPayload } from '@/task/threadItem'
import { getToolStarted, type ToolStartedInfo } from '@/task/toolStartedRuntime'
import { ChevronDownIcon, ChevronRightIcon } from '@/components/shared/AppGlyphs'
import RichMessageContent from '@/components/task/RichMessageContent'
import { getToolView } from '@/components/task/toolViews/registry'
import type { AggregatedToolDetail, ToolCallState } from '@/components/task/toolViews/types'
import { formatToolDuration } from '@/components/task/toolViews/helpers'
import { useThreadBoard } from './threadBoardContext'
import { detailOfCall, interruptedAfterMessage, resultsFor } from './toolDomain'
import type { KindViewProps } from './registry'

export type { ToolCallState }
export { formatToolDuration }

/** 由「结果/瞬态 started/批中断」三个可见信号推导调用行状态。 */
function rowStateOf(
  result: ToolResultPayload | null,
  started: ToolStartedInfo | undefined,
  interrupted: boolean,
): ToolCallState {
  if (result) return result.status === 'error' ? 'error' : 'done'
  if (interrupted) return started ? 'interrupted' : 'skipped'
  return started ? 'running' : 'queued'
}

/** per-tool 美化视图接管调用渲染(read_file 等专用视图 registry 不动)。 */
function ToolDetailView({ detail }: { detail: AggregatedToolDetail }) {
  const View = getToolView(detail.toolName)
  return <View details={[detail]} />
}

/**
 * 工具调用块:一次 message 下发的 toolCalls 为一批(= 同一次 AI 接口请求)。
 * - 单调用:直接渲染 per-tool 美化视图(无包装,一次折叠见详情);
 * - 多调用:批容器只在左侧轨道画弧线 + ×N 徽标(自身零 border/padding,不吃缩进),
 *   内部直接平铺各调用的 per-tool 视图(无批壳行/无二次折叠,状态图标 + 耗时在各自视图摘要行上)。
 * 读秒走本组件单一 setInterval(1s,仅存在 running 调用时挂,卸载清理)。
 */
function ToolCallsBlock({
  item,
  toolCalls,
  taskId,
  mainAgentId,
}: {
  item: ThreadItem
  toolCalls: LLMToolCall[]
  taskId: string
  mainAgentId: string
}) {
  const { items } = useThreadBoard()
  const results = resultsFor(toolCalls, items)
  const interrupted = interruptedAfterMessage(item, items, mainAgentId)
  // 每渲染直读瞬态映射(tool.started handler 返回 true → notify 重渲染,items 未必变,
  // 不能 memo):按 callId 直配点亮 running。Map 查找开销可忽略。
  const startedByCall = toolCalls.map((call) => getToolStarted(taskId, call.id))
  const hasLiveRunning = !interrupted && toolCalls.some((_, i) => results[i] == null && startedByCall[i] != null)
  const [now, setNow] = React.useState(() => Date.now())
  React.useEffect(() => {
    if (!hasLiveRunning) return
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [hasLiveRunning])

  // 调用 → 美化视图输入:状态图标 class + 耗时(权威 durationMs,running 实时读秒)。
  const buildDetail = (idx: number): AggregatedToolDetail => {
    const state = rowStateOf(results[idx], startedByCall[idx], interrupted)
    const durationMs = results[idx]?.durationMs
      ?? (state === 'running' && startedByCall[idx] ? Math.max(0, now - startedByCall[idx].startedAt) : undefined)
    return { ...detailOfCall(toolCalls[idx], results[idx]), rowState: state, durationMs }
  }

  // 单调用:无包装,直接渲染视图。
  if (toolCalls.length === 1) {
    return <ToolDetailView detail={buildDetail(0)} />
  }

  // 多调用:批容器不吃任何宽度(无 border-left/padding-left),
  // 「同批」由左侧轨道里的弧线与 ×N 徽标(::before/::after,读 data-tool-count)标识,
  // 因此并行批与单条调用的文字起点完全一致。
  return (
    <div
      className={`nagent-tool-batch${interrupted ? ' nagent-tool-batch--interrupted' : ''}`}
      data-tool-count={toolCalls.length}
    >
      {toolCalls.map((call, idx) => (
        <ToolDetailView key={call.id || `call:${idx}`} detail={buildDetail(idx)} />
      ))}
    </div>
  )
}

/**
 * 思考块:流式中(思考进行中)展开,思考完成后默认折叠(保留「思考过程 >」折叠头)。
 * 自 AgentMessageThread 原位迁移(行为等价:reasoningDone 打标驱动自动折叠,见 eventFolder)。
 */
function AssistantReasoningBlock({
  reasoning,
  isThinking,
}: {
  reasoning: string
  isThinking: boolean
}) {
  const [open, setOpen] = React.useState(isThinking)
  const previousThinkingRef = React.useRef(isThinking)

  React.useEffect(() => {
    if (isThinking && !previousThinkingRef.current) {
      setOpen(true)
    } else if (!isThinking && previousThinkingRef.current) {
      setOpen(false)
    }
    previousThinkingRef.current = isThinking
  }, [isThinking])

  if (isThinking) {
    return (
      <div className="nagent-msg__reasoning">
        <div className="nagent-msg__reasoning-content nagent-msg__reasoning-content--streaming">
          {reasoning}
        </div>
      </div>
    )
  }

  return (
    <div className="nagent-msg__reasoning">
      <div className="nagent-msg__reasoning-header">
        <span>思考过程</span>
        <button
          type="button"
          className="nagent-msg__reasoning-toggle"
          onClick={() => setOpen((value) => !value)}
          aria-expanded={open}
          aria-label={open ? '收起思考过程' : '展开思考过程'}
          title={open ? '收起思考过程' : '展开思考过程'}
        >
          {open ? <ChevronDownIcon size={12} /> : <ChevronRightIcon size={12} />}
        </button>
      </div>
      {open ? (
        <div className="nagent-msg__reasoning-content">
          {reasoning}
        </div>
      ) : null}
    </div>
  )
}

/**
 * 合成最终回复(TaskRoundsPanel 闭合轮折叠态消费,rounds.jsonl finalReply 渲染投影):
 * 已定稿、仅正文的 assistant 消息,与 MessageView 正文渲染同一口径。
 */
export function FinalReplyMessage({
  content,
  messageId,
  taskId,
}: {
  content?: string
  messageId?: string
  taskId?: string
}): React.ReactNode {
  if (!content?.trim()) {
    return null
  }
  return (
    <div className="nagent-msg nagent-msg--assistant">
      <div className="nagent-msg__body nagent-msg__body--assistant">
        <div className="nagent-msg__content">
          <RichMessageContent content={content} taskId={taskId} messageId={messageId} />
        </div>
      </div>
    </div>
  )
}

/** `message` kind 视图(payload 经 KindPayloadMap 与 handler 编译期配对)。 */
export function MessageView({
  item,
  payload,
  taskId,
  mainAgentId,
}: KindViewProps<'message'>): React.ReactNode {
  const content = payload?.content ?? ''
  const reasoning = payload?.thinking
  const toolCalls = payload?.toolCalls ?? []
  const isStreaming = payload?.streaming === true
  const hasReasoning = Boolean(reasoning?.trim())
  const hasContent = Boolean(content?.trim())
  // 隐藏"空"消息:已定稿(非流式)且无正文、无思考链、无工具调用时才隐藏——
  // 有思考链必须保留展示;有工具调用(纯下发轮)必须保留展示(批壳即该行全部内容,
  // 不加占位文案);流式中即使无正文也保留(等待首段正文/思考)。
  if (!isStreaming && !hasContent && !hasReasoning && toolCalls.length === 0) {
    return null
  }
  // 思考进行中 = 本轮仍在流式、已有思考内容、且尚未观察到「思考结束」信号
  // (正文首达且已有思考时 eventFolder 打标 reasoningDone=true;思考续增回退)。
  const isThinking = isStreaming && hasReasoning && payload?.reasoningDone !== true
  return (
    <div className="nagent-msg nagent-msg--assistant">
      <div className="nagent-msg__body nagent-msg__body--assistant">
        {hasReasoning ? (
          <AssistantReasoningBlock reasoning={reasoning ?? ''} isThinking={isThinking} />
        ) : null}
        {hasContent ? (
          <div className="nagent-msg__content">
            <RichMessageContent content={content} taskId={taskId} messageId={item.seq} />
          </div>
        ) : null}
        {toolCalls.length > 0 ? (
          <div className="nagent-msg__tool-calls">
            <ToolCallsBlock item={item} toolCalls={toolCalls} taskId={taskId} mainAgentId={mainAgentId} />
          </div>
        ) : null}
      </div>
    </div>
  )
}
