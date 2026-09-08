import React from 'react'
import { outputBlockRegistry } from '@/plugin/outputBlockRegistry'
import MarkdownDisplay from '@/components/shared/MarkdownDisplay'

/**
 * 富文本消息内容属性。
 */
export interface RichMessageContentProps {
  /** 原始消息文本。 */
  content: string
  /** 当前 Task ID（透传给输出块处理器，用于执行类按钮）。 */
  taskId?: string
  /** 当前消息 ID。 */
  messageId?: string
}

interface ContentSegment {
  type: 'text' | 'tag'
  tag?: string
  value: string
}

function renderPlainText(value: string): React.ReactNode {
  return <MarkdownDisplay content={value} />
}

/**
 * 把消息内容按 `<tag>…</tag>` 切割成文本段与标签段。
 * 仅识别成对出现的标签；不成对的标签视作普通文本，不影响其他内容。
 */
function splitByOutputTags(content: string): ContentSegment[] {
  const segments: ContentSegment[] = []
  const tagRegex = /<([a-zA-Z][\w-]*)>/g
  let lastIndex = 0
  let match: RegExpExecArray | null
  while ((match = tagRegex.exec(content)) !== null) {
    const openIndex = match.index
    const tag = match[1].toLowerCase()
    const closeTag = `</${tag}>`
    const closeIndex = content.indexOf(closeTag, openIndex + match[0].length)
    if (closeIndex === -1) {
      break
    }
    if (openIndex > lastIndex) {
      const text = content.slice(lastIndex, openIndex)
      if (text.trim()) {
        segments.push({ type: 'text', value: text })
      }
    }
    const inner = content.slice(openIndex + match[0].length, closeIndex)
    segments.push({ type: 'tag', tag, value: inner })
    lastIndex = closeIndex + closeTag.length
    tagRegex.lastIndex = lastIndex
  }
  if (lastIndex < content.length) {
    const text = content.slice(lastIndex)
    if (text.trim()) {
      segments.push({ type: 'text', value: text })
    }
  }
  return segments
}

/**
 * 富文本消息内容渲染。
 * 解析 assistant / system 消息里的结构化输出标签（如 `<plan>…</plan>`），
 * 命中已注册的输出块处理器则交给插件渲染，未命中则降级为通用代码块。
 * 纯文本消息原样渲染（保留换行）。
 */
export default function RichMessageContent({ content, taskId, messageId }: RichMessageContentProps): React.ReactNode {
  if (!content?.trim()) {
    return null
  }
  const segments = splitByOutputTags(content)
  if (segments.length === 1 && segments[0].type === 'text') {
    return renderPlainText(content)
  }
  return (
    <div className="nagent-rich-content">
      {segments.map((segment, index) => {
        if (segment.type === 'text') {
          return <React.Fragment key={index}>{renderPlainText(segment.value)}</React.Fragment>
        }
        const handler = segment.tag ? outputBlockRegistry.get(segment.tag) : undefined
        const node = handler ? handler(segment.value, { taskId, messageId }) : null
        if (node) {
          return <React.Fragment key={index}>{node}</React.Fragment>
        }
        return <React.Fragment key={index}>{renderPlainText(`<${segment.tag}>${segment.value}</${segment.tag}>`)}</React.Fragment>
      })}
    </div>
  )
}
