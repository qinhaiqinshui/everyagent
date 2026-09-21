import React from 'react'
import Markdown from "react-markdown"
import remarkGfm from 'remark-gfm'
import { buildMarkdownComponents } from './markdown/sharedMarkdownRenderer'
import { remarkFourTildeStrikethrough } from './markdown/remarkFourTildeStrikethrough'

/**
 * 聊天面板 Markdown 渲染（AI 输出）。
 *
 * 基于 react-markdown + remark-gfm，所有块级/内联语法（含 GFM 表格、任务列表、自动链接）
 * 由 react-markdown 统一解析；删除线语法经 remarkFourTildeStrikethrough 改写为
 * 仅 `~~~~text~~~~` 生效（`~~`/`~` 按字面文本渲染）。样式与表格列宽算法通过共享组件工厂注入。
 *
 * 性能：组件以 React.memo 包裹，normalized 内容按 content 记忆；react-markdown 内部按整文档解析，
 * 对常见聊天消息（数 KB）开销可忽略。流式更新时整树重渲染，但 React 协调复用同位同类 DOM，成本与文档规模线性相关。
 */
const MarkdownDisplay = React.memo(function MarkdownDisplay({ content }: { content: string }) {
  const normalized = React.useMemo(() => normalizeMarkdownContent(content), [content])
  if (!normalized.trim()) return null
  return (
    <div className="md-root" style={rootStyle}>
      <Markdown remarkPlugins={[remarkGfm, remarkFourTildeStrikethrough]} components={displayComponents}>
        {normalized}
      </Markdown>
    </div>
  )
})

export default MarkdownDisplay

/** 聊天场景组件映射（无工作区上下文、无大纲），模块级单例避免每次渲染重建。 */
const displayComponents = buildMarkdownComponents({ variant: 'display' })

/** 根容器：flex column gap:0，与旧自研 MarkdownDisplay 布局一致。 */
const rootStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 0,
}

/**
 * 归一化 AI 输出：
 * - CRLF/CR → LF；
 * - 转义换行序列（`\n` 字面量）→ 真实换行（部分模型在 JSON/字符串化上下文里输出字面 \n）。
 */
function normalizeMarkdownContent(content: string): string {
  if (!content) return ''
  return content
    .replace(/\\r\\n/g, '\n')
    .replace(/\\n/g, '\n')
    .replace(/\r\n?/g, '\n')
}
