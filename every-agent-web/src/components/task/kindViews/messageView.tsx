/**
 * `message` kind 视图(「任务线程统一条目与画板纯化」步骤 10)。
 *
 * thinking(AssistantReasoningBlock:流式且未 reasoningDone 展开、完成后自动折叠)与
 * 正文(RichMessageContent)自 AgentMessageThread 现状迁移(行为等价);工具块为
 * **单调用直渲 / 多调用批壳**双形态:
 * - 单调用(toolCalls.length === 1):跳过批壳,直接渲染 per-tool 美化视图(一次折叠
 *   即见参数与结果,状态图标 + 耗时在视图摘要行上);
 * - 多调用:批壳(左竖线容器)+ 调用行(状态图标 + 耗时 + 等宽工具名 + 参数摘要),
 *   点击行展开详情(per-tool 美化视图接管,defaultOpen 挂载即展开,不再二次折叠)。
 *
 * 运行态读秒:`tool.started` 瞬态映射(toolStartedRuntime,按 callId 直配)点亮 running
 * + 计时 now - startedAt(单一 1s tick,卸载清理);对应 tool.result 到达冻结权威
 * durationMs;历史回放无瞬态事件直接展示 durationMs;批中断(可见终态信号)黄/灰显。
 */
import React from 'react'
import type { LLMToolCall } from '@/types'
import type { ThreadItem, ToolResultPayload } from '@/task/threadItem'
import { getToolStarted, type ToolStartedInfo } from '@/task/toolStartedRuntime'
import { ChevronDownIcon, ChevronRightIcon, WrenchIcon } from '@/components/shared/AppGlyphs'
import RichMessageContent from '@/components/task/RichMessageContent'
import { getToolView } from '@/components/task/toolViews/registry'
import type { AggregatedToolDetail, ToolCallState } from '@/components/task/toolViews/types'
import {
  buildCallInlinePreview,
  formatToolDuration,
  hasActiveTextSelection,
} from '@/components/task/toolViews/helpers'
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

/** per-tool 美化视图接管调用详情(read_file 等专用视图 registry 不动)。 */
function ToolDetailView({
  detail,
  defaultOpen,
}: {
  detail: AggregatedToolDetail
  /** 挂载即展开(批壳调用行点击进入时 true,消除二次折叠)。 */
  defaultOpen?: boolean
}) {
  const View = getToolView(detail.toolName)
  return <View details={[detail]} defaultOpen={defaultOpen} />
}

/**
 * 单个调用行(多调用批壳内):状态图标 + 耗时 + 等宽工具名 + 参数摘要;点击展开详情。
 * 状态优先级:result(权威) > started 瞬态(running/读秒) > 排队;批中断黄/灰显。
 */
function ToolCallRow({
  call,
  result,
  started,
  interrupted,
  now,
}: {
  call: LLMToolCall
  result: ToolResultPayload | null
  started: ToolStartedInfo | undefined
  interrupted: boolean
  now: number
}) {
  const [open, setOpen] = React.useState(false)
  const state = rowStateOf(result, started, interrupted)
  const detail = detailOfCall(call, result)
  const preview = buildCallInlinePreview(detail)
  // 耗时:result 的 durationMs 是批末冻结的权威值;running 用 now - startedAt 读秒;
  // interrupted/queued(无起止信号)与老事件缺 durationMs 时不展示。
  const durationMs = result?.durationMs
    ?? (state === 'running' && started ? Math.max(0, now - started.startedAt) : undefined)
  const durationText = formatToolDuration(durationMs)
  return (
    <div className={`nagent-tool-batch__row nagent-tool-batch__row--${state}`}>
      <button
        type="button"
        className="nagent-tool-batch__summary"
        aria-expanded={open}
        aria-label={`${detail.toolName} 调用详情，点击${open ? '收起' : '展开'}`}
        onClick={() => {
          // 拖选参数文本(选区非空)时不切换折叠,保证参数可选中复制。
          if (hasActiveTextSelection()) return
          setOpen((value) => !value)
        }}
      >
        <WrenchIcon size={12} className={`nagent-tool-batch__icon nagent-tool-state--${state}`} aria-hidden="true" />
        {durationText ? (
          // 耗时置于图标与工具名之间,只显示时间。
          <span className="nagent-tool-batch__duration">{durationText}</span>
        ) : null}
        <span className="nagent-tool-batch__name">{detail.toolName}</span>
        <span className="nagent-tool-batch__preview">
          {preview.kind === 'comment' ? (
            <>
              <span className="nagent-tool-batch__preview-comment">{preview.comment}</span>
              {preview.rest ? ` ${preview.rest}` : null}
            </>
          ) : (
            preview.text
          )}
        </span>
        {open ? (
          <ChevronDownIcon size={12} className="nagent-tool-batch__chevron" />
        ) : (
          <ChevronRightIcon size={12} className="nagent-tool-batch__chevron" />
        )}
      </button>
      {open ? (
        <div className="nagent-tool-batch__detail">
          {/* rowState/durationMs 灌入视图(状态图标色 + 耗时);defaultOpen 挂载即展开。 */}
          <ToolDetailView
            detail={{ ...detail, rowState: state, durationMs }}
            defaultOpen
          />
        </div>
      ) : null}
    </div>
  )
}

/**
 * 工具调用块:一次 message 下发的 toolCalls 为一批(= 同一次 AI 接口请求)。
 * - 单调用:跳过批壳直渲 per-tool 美化视图(一次折叠见详情);
 * - 多调用:批壳(左竖线)+ 调用行,点击行展开 defaultOpen 详情。
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

  // 单调用:直渲 per-tool 美化视图,状态图标 + 耗时灌入视图摘要行(不套批壳)。
  if (toolCalls.length === 1) {
    const state = rowStateOf(results[0], startedByCall[0], interrupted)
    const durationMs = results[0]?.durationMs
      ?? (state === 'running' && startedByCall[0] ? Math.max(0, now - startedByCall[0].startedAt) : undefined)
    return (
      <ToolDetailView
        detail={{ ...detailOfCall(toolCalls[0], results[0]), rowState: state, durationMs }}
      />
    )
  }

  return (
    <div className={`nagent-tool-batch is-open${interrupted ? ' nagent-tool-batch--interrupted' : ''}`}>
      <div className="nagent-tool-batch__rows">
        {toolCalls.map((call, idx) => (
          <ToolCallRow
            key={call.id || `call:${idx}`}
            call={call}
            result={results[idx]}
            started={startedByCall[idx]}
            interrupted={interrupted}
            now={now}
          />
        ))}
      </div>
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
