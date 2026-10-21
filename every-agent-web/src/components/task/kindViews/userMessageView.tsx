/**
 * `user.message` kind 视图(「任务线程统一条目与画板纯化」步骤 10)。
 *
 * CollapsibleUserBubble 全套自 AgentMessageThread 原位迁入(行为等价迁移):
 * - 回放胶囊段:rawContent 的 opaque 串自包含切分回放(@文件胶囊等),点击经
 *   `ui.composer_chip_actions` 扩展点或只读详情层;
 * - 子 agent 输入判定 `agentId !== mainAgentId`(空串安全):低调样式 +
 *   折叠阈值 66px(≈3行;普通 220px),不带用户消息动作扩展点(不可编辑重发);
 * - 用户消息动作扩展点 `ui.user_message_actions`(seq = item.seq 直取)。
 *
 * TaskRoundsPanel 的合成 user 气泡(rounds 快照,渲染投影不进 items)复用同一
 * 共享组件 `UserBubbleMessage`(seq 传 round.startSeq)。
 */
import React from 'react'
import type { ChatComposerToken } from '@/types'
import type { UserMessagePayload } from '@/task/threadItem'
import { splitComposerRawContent, parseOpaqueTokenText } from '@/composerToken/composerOpaqueToken'
import { getComposerChipView } from '@/composerToken/composerChipRenderer'
import { openSlashItemDetail } from '@/components/taskComposer/SlashItemDetailPopover'
import { pluginDispatcher } from '@/plugin/PluginDispatcher'
import { taskStore } from '@/task/taskStore'
import { workspaceRegistry } from '@/hub/workspaceRegistry'
import { ChevronDownIcon, ChevronRightIcon } from '@/components/shared/AppGlyphs'
import type { KindViewProps } from './registry'

/** 回放胶囊段(纯文本段 / 胶囊段)。 */
export interface UserReplaySegment {
  type: 'text' | 'token'
  value: string
  token?: ChatComposerToken
}

/**
 * 构建用户消息回放胶囊段(纯函数,导出供单测):
 * rawContent 优先(含 opaque token 串),缺失回退 content 纯文本。
 * token 段由 opaque 串自包含解析,不依赖外部 tokens 数组反查。
 */
export function buildUserMessageReplaySegments(message: {
  content?: string
  rawContent?: string
  composerTokens?: ChatComposerToken[]
}): UserReplaySegment[] {
  const tokens = message.composerTokens ?? []
  const rawContent = message.rawContent?.length
    ? message.rawContent
    : message.content
  // 只需 rawContent 即可回放胶囊:hub 事件流不带 composerTokens 数组,
  // token 段由 opaque 串自包含解析,tokens 仅作 id 反查可选项。
  if (!rawContent) {
    return message.content?.length
      ? [{ type: 'text', value: message.content }]
      : []
  }
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
  taskId,
}: {
  segments: UserReplaySegment[]
  /** 当前 Task ID(点击 workspace_file 胶囊打开文件标签页时反查任务工作区根)。 */
  taskId?: string
}) {
  if (segments.length === 0) {
    return null
  }

  /**
   * 胶囊激活(点击/Enter/空格):按 kind 查胶囊激活扩展点(ui.composer_chip_actions)。
   * 命中即回调注册方行为(核心 `system.workspace_file` 亦经此扩展点注册 → 打开文件标签页);
   * 未命中保持现状(弹只读详情层)。
   */
  const handleChipActivate = (el: HTMLElement, token: ChatComposerToken) => {
    const action = pluginDispatcher.findComposerChipAction(token.kind)
    if (action) {
      action.onActivate({
        token: {
          kind: token.kind,
          label: token.label,
          summary: token.summary,
          opaqueText: token.opaqueText,
          payload: parseOpaqueTokenText(token.opaqueText)?.payload ?? {},
        },
        anchor: el,
        source: 'replay',
        // 消费侧解析工作区根:任务工作区优先、注册表首选兜底。
        workspaceRoot: (taskId ? taskStore.get(taskId)?.workspace : undefined) || workspaceRegistry.primaryRoot() || '',
        taskId,
      })
      return
    }
    // 未命中 → 保持现状:弹只读详情层。
    openSlashItemDetail(el, { token })
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
            className="nagent-inline-chip nagent-inline-chip--replay"
            title={view.label || segment.value}
            role="button"
            tabIndex={0}
            onClick={(event) => {
              event.stopPropagation()
              if (segment.token) {
                const el = event.currentTarget as HTMLElement
                handleChipActivate(el, segment.token)
              }
            }}
            onKeyDown={(event) => {
              if (segment.token && (event.key === 'Enter' || event.key === ' ')) {
                event.preventDefault()
                const el = event.currentTarget as HTMLElement
                handleChipActivate(el, segment.token)
              }
            }}
          >
            <span className="nagent-inline-chip__label">{view.label || segment.value}</span>
          </span>
        )
      })}
    </div>
  )
}

/** 用户消息气泡折叠阈值(px):内容高度超过该值时折叠并显示展开按钮。 */
const USER_BUBBLE_COLLAPSE_HEIGHT = 220

/** 子 agent 输入气泡折叠阈值(px):超过 3 行折叠(更低调,避免抢占视觉焦点)。 */
const SUB_AGENT_BUBBLE_COLLAPSE_HEIGHT = 66

/**
 * 用户消息气泡:内容超过最大高度时折叠(隐藏溢出部分 + 底部渐变遮罩),
 * 并显示居中的展开/收起按钮;气泡内不出现滚动条。
 */
function CollapsibleUserBubble({
  className,
  segments,
  taskId,
  collapseHeight = USER_BUBBLE_COLLAPSE_HEIGHT,
}: {
  className: string
  segments: UserReplaySegment[]
  /** 当前 Task ID(透传给 UserMessageReplay,点击 workspace_file 胶囊时反查工作区根)。 */
  taskId?: string
  /** 折叠阈值(px):内容高度超过该值时折叠;缺省 = 普通用户消息阈值(220px)。 */
  collapseHeight?: number
}) {
  const contentRef = React.useRef<HTMLDivElement>(null)
  const [overflowing, setOverflowing] = React.useState(false)
  const [expanded, setExpanded] = React.useState(false)

  React.useLayoutEffect(() => {
    const el = contentRef.current
    if (!el) return
    const check = () => {
      setOverflowing(el.scrollHeight > collapseHeight + 1)
    }
    check()
    const observer = new ResizeObserver(check)
    observer.observe(el)
    return () => observer.disconnect()
  }, [collapseHeight])

  const collapsed = overflowing && !expanded

  return (
    <div
      className={`${className}${collapsed ? ' nagent-msg__bubble--collapsed' : ''}`}
    >
      <div
        ref={contentRef}
        className="nagent-msg__bubble-content"
        style={collapsed ? { maxHeight: `${collapseHeight}px` } : undefined}
      >
        <UserMessageReplay segments={segments} taskId={taskId} />
      </div>
      {overflowing ? (
        <button
          type="button"
          className="nagent-msg__bubble-toggle"
          aria-expanded={expanded}
          aria-label={expanded ? '收起消息' : '展开消息'}
          title={expanded ? '收起' : '展开全部'}
          onClick={(event) => {
            event.stopPropagation()
            setExpanded((value) => !value)
          }}
        >
          <ChevronDownIcon
            size={12}
            className={`nagent-msg__bubble-toggle-icon${expanded ? ' nagent-msg__bubble-toggle-icon--up' : ''}`}
          />
          <span>{expanded ? '收起' : '展开全部'}</span>
        </button>
      ) : null}
    </div>
  )
}

/** 共享用户气泡视图的最小消息形状(kind 视图与 TaskRoundsPanel 合成记录共用)。 */
export interface UserBubbleMessageProps {
  /** 消息载荷(content/rawContent/composerTokens + 归属 agentId)。 */
  message: {
    agentId?: string
    content?: string
    rawContent?: string
    composerTokens?: ChatComposerToken[]
  }
  /** 动作键(item.seq 直取;合成记录传 round.startSeq;null = 不带动作扩展点)。 */
  seq?: string | null
  /** 当前 Task ID。 */
  taskId?: string
  /** 主 agent 稳定 Id(子 agent 输入判定)。 */
  mainAgentId?: string
}

/**
 * 用户消息视图(user.message kind 视图与合成 user 气泡共用的共享组件):
 * 动作扩展点(ui.user_message_actions)+ 回放胶囊段气泡(子 agent 输入低调样式 + 折叠阈值)。
 */
export function UserBubbleMessage({
  message,
  seq,
  taskId,
  mainAgentId = '',
}: UserBubbleMessageProps): React.ReactNode {
  const replaySegments = buildUserMessageReplaySegments(message)
  // 子 agent 输入判定:agentId 非空且 ≠ mainAgentId(空串安全:合成骨架/mainAgentId 未灌入时落回 false)。
  const isSubAgentInput = Boolean(message.agentId) && message.agentId !== mainAgentId
  // 用户消息动作(扩展点 ui.user_message_actions,如复制原始内容/编辑重发按钮):
  // seq = item.seq(seq 即 id);合成记录传 round.startSeq。子 agent 输入不带扩展点(不可编辑重发)。
  const userMsgSeq = seq ?? null
  const userMessageActions = !isSubAgentInput && userMsgSeq != null
    ? pluginDispatcher.listRegisteredUserMessageActions() : []
  const hasUserMessageActions = userMessageActions.length > 0
  return (
    // tabIndex 仅在存在动作时挂载:移动端动作区显隐由「消息获得/失去焦点」驱动
    // (:focus-within),触摸聚焦需要消息容器本身可聚焦(iOS Safari 要求 tabindex)。
    <div
      className="nagent-msg nagent-msg--user"
      tabIndex={hasUserMessageActions ? 0 : undefined}
    >
      <div className="nagent-msg__body nagent-msg__body--user">
        {hasUserMessageActions ? (
          // 动作区容器统一控制显隐(见 chatPanel.css .nagent-msg__actions):
          // 桌面端 hover 消息区显示;移动端消息获得焦点显示、失去焦点隐藏。
          <div className="nagent-msg__actions">
            {userMessageActions.map((action) => (
              <action.Component
                key={action.id}
                taskId={taskId}
                seq={userMsgSeq!}
                content={message.content ?? ''}
                rawContent={message.rawContent}
              />
            ))}
          </div>
        ) : null}
        <CollapsibleUserBubble
          className={`nagent-msg__bubble nagent-msg__bubble--user${isSubAgentInput ? ' nagent-msg__bubble--sub-agent' : ''}`}
          segments={replaySegments}
          taskId={taskId}
          collapseHeight={isSubAgentInput ? SUB_AGENT_BUBBLE_COLLAPSE_HEIGHT : undefined}
        />
      </div>
    </div>
  )
}

/** `user.message` kind 视图(payload 经 KindPayloadMap 与 handler 编译期配对)。 */
export function UserMessageView({
  item,
  payload,
  taskId,
  mainAgentId,
}: KindViewProps<'user.message'>): React.ReactNode {
  const content = payload?.content ?? ''
  return (
    <UserBubbleMessage
      message={{
        agentId: item.agentId,
        content,
        rawContent: payload?.rawContent,
        composerTokens: [],
      }}
      seq={item.seq}
      taskId={taskId}
      mainAgentId={mainAgentId}
    />
  )
}
