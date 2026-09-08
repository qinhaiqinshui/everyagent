import React from 'react'
import type { AgentMessageRecord, ChatComposerToken } from '@/types'
import { splitComposerRawContent } from '@/composerToken/composerOpaqueToken'
import { getComposerChipView } from '@/composerToken/composerChipRenderer'
import { openSlashItemDetail } from '@/components/taskComposer/SlashItemDetailPopover'
import {
  SparkIcon,
  WrenchIcon,
  ChatBubbleIcon,
  ChevronDownIcon,
  ChevronRightIcon,
} from '../shared/AppGlyphs'
import RichMessageContent from './RichMessageContent'
// 工具调用美化视图（目录即注册表）：默认视图 + 各工具专用视图 + 分发器。
// buildAggregatedToolDetailFromCall：以 assistant 下发为锚点合并按 callId 匹配到的结果，
// renderAggregatedToolDetails：与 tool 消息路径共用的唯一渲染出口。
import { buildAggregatedToolDetailFromCall } from '@/components/task/toolViews/helpers'
import ToolCallView, { renderAggregatedToolDetails } from '@/components/task/toolViews/ToolCallView'

/**
 * Agent 消息子块属性。
 */
export interface AgentMessageThreadProps {
  /** 当前消息记录。 */
  message: AgentMessageRecord
  /** 当前 Task ID，用于结构化输出块（如 `<plan>`）的执行按钮回灌。 */
  taskId?: string
  /** 是否是连续同角色消息中的后续消息（非首条）。 */
  isContinuation?: boolean
  /** 合并展示的连续 tool 结果消息（多条结果合并为一条）。 */
  mergedToolMessages?: AgentMessageRecord[]
  /** 解析某条 tool 结果消息对应的函数名（优先 message.toolName）。 */
  resolveToolCallName?: (msg: AgentMessageRecord) => string | undefined
  /** 解析某个工具调用 ID 的结构化负载。 */
  resolveToolCallPayload?: (toolCallId: string) => Record<string, unknown> | undefined
  /** 按工具调用 ID 解析匹配到的 tool 结果消息（live/历史共用，供下发块合并结果）。 */
  resolveToolResult?: (toolCallId: string) => AgentMessageRecord | undefined
}

/**
 * 各角色在流式布局里的视觉元数据。
 */
const ROLE_META: Record<AgentMessageRecord['role'], {
  label: string
  avatarClass: string
  roleClass: string
  avatar: React.ReactNode
}> = {
  user: {
    label: '你',
    avatarClass: 'nagent-msg__avatar--user',
    roleClass: 'nagent-msg__role--user',
    avatar: '你',
  },
  assistant: {
    label: 'AI',
    avatarClass: 'nagent-msg__avatar--assistant',
    roleClass: 'nagent-msg__role--assistant',
    avatar: <SparkIcon size={14} />,
  },
  tool: {
    label: '工具',
    avatarClass: 'nagent-msg__avatar--tool',
    roleClass: 'nagent-msg__role--tool',
    avatar: <WrenchIcon size={14} />,
  },
  system: {
    label: '系统',
    avatarClass: 'nagent-msg__avatar--system',
    roleClass: 'nagent-msg__role--system',
    avatar: <ChatBubbleIcon size={13} />,
  },
}

/**
 * Agent 消息子块。
 * user / assistant 使用统一的纯文本 + 下方时间格式；
 * tool / system 继续保留角色信息与折叠块展示。
 */
export default function AgentMessageThread({
  message,
  taskId,
  isContinuation,
  mergedToolMessages,
  resolveToolCallName,
  resolveToolCallPayload,
  resolveToolResult,
}: AgentMessageThreadProps) {
  const meta = ROLE_META[message.role]
  const toolCalls = message.toolCalls ?? []

  const renderRich = message.role === 'assistant' || message.role === 'system'
  const isAgentSystemPrompt = message.role === 'system' && message.metadata?.source === 'agent_system_prompt'
  const hasAssistantReasoning = message.role === 'assistant' && Boolean(message.reasoning?.trim())
  const hasAssistantContent = message.role === 'assistant' && Boolean(message.content?.trim())
 const isStreaming = message.role === 'assistant' && message.metadata?.streaming === true
  // 思考进行中 = 本轮仍在流式、已有思考内容、且尚未观察到「思考结束」信号。
  // 「思考结束」由 eventFolder 在正文(delta)首达且已有思考时打标 metadata.reasoningDone=true;
  // 推理模型若 reasoning/content 交织,后续 thinking 增量会清除该标记(回退为展开),
  // 因此正文先到不会把思考区提前折叠,只在思考真正停止后自动折叠(正文继续流式)。
  const isAssistantThinking = isStreaming && hasAssistantReasoning && message.metadata?.reasoningDone !== true

 const continuationClass = isContinuation ? ' nagent-msg--continuation' : ''

  if (message.role === 'tool') {
    const merged = (mergedToolMessages && mergedToolMessages.length > 0)
      ? mergedToolMessages
      : [message]
    const resolvedNames = merged.map((m) => resolveToolCallName?.(m) ?? m.toolName?.trim() ?? '')
    return (
      <div className={`nagent-msg nagent-msg--tool${continuationClass}`}>
        <div className="nagent-msg__body">
          <ToolCallView
            messages={merged}
            resolvedNames={resolvedNames}
            resolveToolCallPayload={resolveToolCallPayload}
          />
        </div>
      </div>
    )
  }

  if (message.role === 'user') {
    const replaySegments = buildUserMessageReplaySegments(message)
    return (
      <div className={`nagent-msg nagent-msg--user${continuationClass}`}>
        <div className="nagent-msg__body nagent-msg__body--user">
          <div className="nagent-msg__bubble nagent-msg__bubble--user">
            <UserMessageReplay segments={replaySegments} />
          </div>
          </div>
      </div>
    )
  }

  if (message.role === 'assistant') {
    // 隐藏"空"消息：已定稿（流式结束）且无正文、且无思考链、且无工具调用时才隐藏。
    // - 有思考链：必须保留展示（用户要求"有思考内容则显示"）。
    // - 有工具调用（ReAct 下发）：必须保留展示——下发块即其可见内容，
    //   结果消息到达后按 callId 合并进同一下发块（见 buildAggregatedToolDetailFromCall）。
    // - 流式中（metadata.streaming === true）即使无正文也保留展示，
    //   便于用户看到思考过程/等待首段正文到达，不在此处隐藏。
    if (!isStreaming && !hasAssistantContent && !hasAssistantReasoning && toolCalls.length === 0) {
      return null
    }
    const toolDetails = toolCalls.length > 0
      ? toolCalls.map((call) => buildAggregatedToolDetailFromCall(call, resolveToolResult?.(call.id) ?? null))
      : []
    return (
      <div className={`nagent-msg nagent-msg--assistant${continuationClass}`}>
        <div className="nagent-msg__body nagent-msg__body--assistant">
          {hasAssistantReasoning ? (
            <AssistantReasoningBlock reasoning={message.reasoning ?? ''} isThinking={isAssistantThinking} />
          ) : null}
          {hasAssistantContent ? (
            <div className="nagent-msg__content">
              <RichMessageContent content={message.content} taskId={taskId} messageId={message.messageId} />
            </div>
          ) : null}
          {toolDetails.length > 0 ? (
            <div className="nagent-msg__tool-calls">
              {renderAggregatedToolDetails(toolDetails, resolveToolCallPayload)}
            </div>
          ) : null}
          </div>
      </div>
    )
  }

  // 隐藏"真·空"消息：无正文且无工具调用（系统/其它角色），避免空白气泡
  if (!message.content?.trim() && toolCalls.length === 0) {
    return null
  }

  return (
    <div className={`nagent-msg nagent-msg--${message.role}${continuationClass}`}>
      <span className={`nagent-msg__avatar ${meta.avatarClass}`}>
        {meta.avatar}
      </span>
      <div className="nagent-msg__body">
        <div className="nagent-msg__meta">
          <span className={`nagent-msg__role ${meta.roleClass}`}>
            {meta.label}
          </span>
        </div>
        {message.content?.trim() ? (
          <div className="nagent-msg__content">
            {isAgentSystemPrompt ? (
              <SystemPromptBlock
                content={message.content}
                taskId={taskId}
                messageId={message.messageId}
              />
            ) : renderRich ? (
              <RichMessageContent content={message.content} taskId={taskId} messageId={message.messageId} />
            ) : (
              message.content
            )}
          </div>
        ) : toolCalls.length === 0 ? (
          <div className="nagent-msg__placeholder">
            （无文本）
          </div>
        ) : null}
        </div>
    </div>
  )
}

function buildUserMessageReplaySegments(message: AgentMessageRecord): Array<{
  type: 'text' | 'token'
  value: string
  token?: ChatComposerToken
}> {
  const tokens = message.composerTokens ?? []
  const rawContent = message.rawContent?.length
    ? message.rawContent
    : message.content
  // 只需 rawContent 即可回放胶囊：hub 事件流不带 composerTokens 数组，
  // token 段由 opaque 串自包含解析（见下方注释），tokens 仅作 id 反查可选项。
  if (!rawContent) {
    return message.content?.length
      ? [{ type: 'text', value: message.content }]
      : []
  }
  // 自包含切分：token 段由 opaque 串直接解析，不依赖外部 tokens 数组做反查，
  // 也不用 readXToken / 注册组件渲染（见方案 §4.7.1 / §4.7.2）。
  return splitComposerRawContent(rawContent, tokens).map((segment) => ({
    type: segment.type,
    value: segment.type === 'text'
      ? segment.value
      : (segment.token?.label || segment.value),
    token: segment.type === 'token' ? segment.token : undefined,
  }))
}

function UserMessageReplay({
  segments,
}: {
  segments: Array<{
    type: 'text' | 'token'
    value: string
    token?: ChatComposerToken
  }>
}) {
  if (segments.length === 0) {
    return null
  }

  return (
    <div className="nagent-msg__content nagent-msg__content--user-replay">
      {segments.map((segment, index) => {
        if (segment.type === 'text') {
          return (
            <span key={`text:${index}`} className="nagent-msg__inline-text">
              {segment.value}
            </span>
          )
        }
        const view = segment.token ? getComposerChipView(segment.token) : { tokenId: `tok:${index}`, label: segment.value }
        return (
          <span
            key={`token:${index}:${view.tokenId}`}
            className="nagent-msg__inline-chip"
            title={view.label || segment.value}
            role="button"
            tabIndex={0}
            onClick={(event) => {
              event.stopPropagation()
              if (segment.token) {
                const el = event.currentTarget as HTMLElement
                openSlashItemDetail(el, { token: segment.token })
              }
            }}
            onKeyDown={(event) => {
              if (segment.token && (event.key === 'Enter' || event.key === ' ')) {
                event.preventDefault()
                const el = event.currentTarget as HTMLElement
                openSlashItemDetail(el, { token: segment.token })
              }
            }}
          >
            <span className="nagent-msg__inline-chip-label">{view.label || segment.value}</span>
          </span>
        )
      })}
    </div>
  )
}

function AssistantReasoningBlock({
  reasoning,
  isThinking,
}: {
  reasoning: string
  isThinking: boolean
}) {
  // 流式中（思考进行中）展开，思考完成后默认折叠（保留"思考过程 >"折叠头，不隐藏）。
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

function SystemPromptBlock({
  content,
  taskId,
  messageId,
}: {
  content: string
  taskId?: string
  messageId?: string
}) {
  const [open, setOpen] = React.useState(false)

  // 折叠图标全设备默认隐藏（仅 AI 思考内容常显），故头部整行作为可点击折叠区：
  // 点文本或图标任意位置均可展开/收起，移动端（无 hover）也不会失去入口。
  return (
    <div className="nagent-msg__reasoning nagent-msg__reasoning--system">
      <div
        className="nagent-msg__reasoning-header"
        role="button"
        tabIndex={0}
        aria-expanded={open}
        aria-label={open ? '收起系统提示词' : '展开系统提示词'}
        title={open ? '收起系统提示词' : '展开系统提示词'}
        onClick={() => setOpen((value) => !value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault()
            setOpen((value) => !value)
          }
        }}
      >
        <span>系统提示词</span>
        <span className="nagent-msg__reasoning-toggle" aria-hidden="true">
          {open ? <ChevronDownIcon size={12} /> : <ChevronRightIcon size={12} />}
        </span>
      </div>
      {open ? (
        <div className="nagent-msg__reasoning-content">
          <RichMessageContent content={content} taskId={taskId} messageId={messageId} />
        </div>
      ) : null}
    </div>
  )
}


