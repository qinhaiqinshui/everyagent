/**
 * `message` kind 视图(「任务线程统一条目与画板纯化」步骤 10)。
 *
 * thinking(AssistantReasoningBlock:流式且未 reasoningDone 展开、完成后自动折叠)与
 * 正文(RichMessageContent)自 AgentMessageThread 现状迁移(行为等价);工具块替换为
 * **批壳 + 调用行模型**(本步唯一有意的新行为):一次 message 的 payload.toolCalls 为
 * 一批(= 同一次 AI 接口请求),左侧竖线容器 + 批头「N 个调用」(可折叠,默认展开);
 * 调用行 = 状态点 + 等宽工具名 + per-tool 参数摘要 + 行尾状态与耗时,点击展开参数与
 * 结果(per-tool 美化视图接管)。批内同名多调用不折叠;无正文纯下发轮不加占位文案。
 *
 * 运行态读秒:`tool.started` 瞬态映射(toolStartedRuntime,按 callId 直配)点亮 running
 * + 计时 now - startedAt(批壳单一 1s tick,卸载清理);对应 tool.result 到达冻结权威
 * durationMs;历史回放无瞬态事件直接展示 durationMs;批中断(可见终态信号)灰显
 * 「执行中被中断/未执行」。
 */
import React from 'react'
import type { LLMToolCall } from '@/types'
import type { ThreadItem, ToolResultPayload } from '@/task/threadItem'
import { getToolStarted, type ToolStartedInfo } from '@/task/toolStartedRuntime'
import { ChevronDownIcon, ChevronRightIcon, WrenchIcon } from '@/components/shared/AppGlyphs'
import RichMessageContent from '@/components/task/RichMessageContent'
import { getToolView } from '@/components/task/toolViews/registry'
import type { AggregatedToolDetail } from '@/components/task/toolViews/types'
import { buildCallInlinePreview, hasActiveTextSelection } from '@/components/task/toolViews/helpers'
import { useThreadBoard } from './threadBoardContext'
import { detailOfCall, interruptedAfterMessage, resultsFor } from './toolDomain'
import type { KindViewProps } from './registry'

/** 调用行运行态(状态点 class / 行尾文案的单一来源)。 */
export type ToolCallRowState = 'queued' | 'running' | 'done' | 'error' | 'interrupted' | 'skipped'

/** 由「结果/瞬态 started/批中断」三个可见信号推导调用行状态。 */
function rowStateOf(
  result: ToolResultPayload | null,
  started: ToolStartedInfo | undefined,
  interrupted: boolean,
): ToolCallRowState {
  if (result) return result.status === 'error' ? 'error' : 'done'
  if (interrupted) return started ? 'interrupted' : 'skipped'
  return started ? 'running' : 'queued'
}

const ROW_STATE_TEXT: Record<ToolCallRowState, string> = {
  queued: '排队中',
  running: '运行中',
  done: '完成',
  error: '失败',
  interrupted: '执行中被中断',
  skipped: '未执行',
}

/** 紧凑耗时文案:<10s 一位小数,分钟级 m:ss 补零;非法值返回空串。 */
export function formatToolDuration(ms: number | undefined): string {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return ''
  const seconds = ms / 1000
  if (seconds < 10) return `${seconds.toFixed(1)}s`
  if (seconds < 60) return `${Math.floor(seconds)}s`
  const minutes = Math.floor(seconds / 60)
  const rest = Math.floor(seconds % 60)
  return rest > 0 ? `${minutes}m${String(rest).padStart(2, '0')}s` : `${minutes}m`
}

/** per-tool 美化视图接管调用行展开详情(read_file 等专用视图 registry 不动)。 */
function ToolDetailView({ detail }: { detail: AggregatedToolDetail }) {
  const View = getToolView(detail.toolName)
  return <View details={[detail]} />
}

/**
 * 单个调用行:状态点 + 等宽工具名 + 参数摘要 + 行尾状态与耗时;点击展开参数与结果。
 * 状态优先级:result(权威) > started 瞬态(running/读秒) > 排队;批中断灰显。
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
        <WrenchIcon size={14} className={`nagent-tool-batch__icon nagent-tool-batch__icon--${state}`} aria-hidden="true" />
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
        <span className="nagent-tool-batch__tail">
          {durationText ? `${ROW_STATE_TEXT[state]} · ${durationText}` : ROW_STATE_TEXT[state]}
        </span>
        {open ? (
          <ChevronDownIcon size={12} className="nagent-tool-batch__chevron" />
        ) : (
          <ChevronRightIcon size={12} className="nagent-tool-batch__chevron" />
        )}
      </button>
      {open ? (
        <div className="nagent-tool-batch__detail">
          <ToolDetailView detail={detail} />
        </div>
      ) : null}
    </div>
  )
}

/**
 * 工具批壳:一次 message 下发的 toolCalls 为一批(左竖线容器 + 批头「N 个调用」)。
 * 批内同名多调用不折叠(10 个 read_file 10 行);批头可折叠(默认展开)。
 * 读秒走批壳单一 setInterval(1s,仅存在 running 行时挂,卸载清理)。
 */
function ToolBatch({
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
  // 不能 memo):批内按 callId 直配点亮 running。Map 查找开销可忽略。
  const startedByCall = toolCalls.map((call) => getToolStarted(taskId, call.id))
  const hasLiveRunning = !interrupted && toolCalls.some((_, i) => results[i] == null && startedByCall[i] != null)
  const [now, setNow] = React.useState(() => Date.now())
  React.useEffect(() => {
    if (!hasLiveRunning) return
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [hasLiveRunning])
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
            <ToolBatch item={item} toolCalls={toolCalls} taskId={taskId} mainAgentId={mainAgentId} />
          </div>
        ) : null}
      </div>
    </div>
  )
}
