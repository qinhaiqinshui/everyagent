/**
 * 中性流式消息渲染:思考折叠块 + 正文,自会话面板消息视图提炼的通用组件。
 *
 * - 思考折叠块(ReasoningBlock):折叠头常驻「思考过程 + 耗时/读秒 + 折叠图标」,
 *   流式思考中展开并实时读秒(单一 1s tick,卸载清理),收束后自动折叠并冻结耗时;
 *   耗时口径与色阶见 thinkingTiming(始终显示,10s 起色、120s 封顶)。
 * - 正文:默认 MarkdownDisplay 直渲;调用侧可经 renderBody 替换为富文本渲染
 *   (如结构化输出标签分段),流式标记透传给渲染器。
 *
 * 本组件不感知任何宿主域概念(会话归属、数据来源等),className 追加在正文容器上。
 * 样式沿用会话面板既有的 nagent-msg__* 类名,无新增样式。
 */
import React from 'react'
import { ChevronDownIcon, ChevronRightIcon } from './AppGlyphs'
import MarkdownDisplay from './MarkdownDisplay'
import { thinkingDurationText, thinkingDurationTintStyle } from '@/components/task/kindViews/thinkingTiming'

export interface AiStreamMessageProps {
  /** 思考链文本(非空才渲染思考折叠块)。 */
  thinking?: string
  /** 正文内容(空白正文不渲染正文容器)。 */
  content: string
  /** 所属消息是否仍在流式输出。 */
  streaming?: boolean
  /** 思考是否已收束(true = 自动折叠并冻结耗时)。 */
  reasoningDone?: boolean
  /** 思考起点时间戳(毫秒):流式思考中按 now - 起点实时读秒。 */
  thinkingStartedAt?: number
  /** 思考耗时权威值(毫秒):收束后展示。 */
  thinkingDurationMs?: number
  /** 追加到正文容器上的类名。 */
  className?: string
  /** 正文渲染器,缺省 MarkdownDisplay 直渲;入参为(正文文本, 流式标记)。 */
  renderBody?: (content: string, streaming: boolean | undefined) => React.ReactNode
}

/**
 * 思考块:折叠头常驻(「思考过程」+ 耗时/读秒 + 折叠图标),思考中展开、思考完成后自动折叠。
 *
 * - 流式中(isThinking):折叠头常显 + **展开**态,耗时按 `now - startedAt` 实时读秒
 *   (单一 1s tick,仅思考中挂载,卸载清理);
 * - 完成后:自动折叠,耗时冻结为权威值(durationMs)。
 *
 * 耗时在标题与折叠图标之间,始终显示(不像工具耗时那样 ≥10s 才显示),颜色按耗时长短渐变
 * (见 thinkingTiming:10s 起色、120s 封顶)。
 */
function ReasoningBlock({
  reasoning,
  isThinking,
  startedAt,
  durationMs,
}: {
  reasoning: string
  isThinking: boolean
  startedAt?: number
  durationMs?: number
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

  // 实时读秒:仅思考中挂 1s tick,停止后冻结为终值不再重渲染。
  const [now, setNow] = React.useState(() => Date.now())
  React.useEffect(() => {
    if (!isThinking || startedAt == null) return
    setNow(Date.now())
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [isThinking, startedAt])

  const shownMs = isThinking && startedAt != null
    ? Math.max(0, now - startedAt)
    : durationMs
  const durationText = thinkingDurationText(shownMs)

  return (
    <div className="nagent-msg__reasoning">
      <div className="nagent-msg__reasoning-header">
        <span>思考过程</span>
        {durationText ? (
          <span
            className="nagent-msg__reasoning-duration"
            style={thinkingDurationTintStyle(shownMs)}
          >
            {durationText}
          </span>
        ) : null}
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
        <div className={`nagent-msg__reasoning-content${isThinking ? ' nagent-msg__reasoning-content--streaming' : ''}`}>
          {reasoning}
        </div>
      ) : null}
    </div>
  )
}

/** 缺省正文渲染:MarkdownDisplay 直渲。 */
function defaultRenderBody(content: string): React.ReactNode {
  return <MarkdownDisplay content={content} />
}

/**
 * 流式消息渲染:思考折叠块(有思考内容才渲染)+ 正文容器(有正文才渲染)。
 *
 * 「思考进行中」= 仍在流式 + 已有思考内容 + 未观察到收束信号(reasoningDone !== true);
 * 正文容器类名固定为 nagent-msg__content,className 仅追加,缺省不引入额外类。
 */
export function AiStreamMessage({
  thinking,
  content,
  streaming,
  reasoningDone,
  thinkingStartedAt,
  thinkingDurationMs,
  className,
  renderBody,
}: AiStreamMessageProps): React.ReactNode {
  const hasReasoning = Boolean(thinking?.trim())
  const hasContent = Boolean(content?.trim())
  const isThinking = streaming === true && hasReasoning && reasoningDone !== true
  const renderContent = renderBody ?? defaultRenderBody
  return (
    <>
      {hasReasoning ? (
        <ReasoningBlock
          reasoning={thinking ?? ''}
          isThinking={isThinking}
          startedAt={thinkingStartedAt}
          durationMs={thinkingDurationMs}
        />
      ) : null}
      {hasContent ? (
        <div className={className ? `nagent-msg__content ${className}` : 'nagent-msg__content'}>
          {renderContent(content, streaming)}
        </div>
      ) : null}
    </>
  )
}
