import React from 'react'
import Markdown from "react-markdown"
import remarkGfm from 'remark-gfm'
import { buildMarkdownComponents, MarkdownSourceContext, MarkdownLinkContext } from './markdown/sharedMarkdownRenderer'
import { remarkFourTildeStrikethrough } from './markdown/remarkFourTildeStrikethrough'
import { useMarkdownLinkNav } from './markdown/useMarkdownLinkNav'

/**
 * 聊天面板 Markdown 渲染（AI 输出）。
 *
 * 基于 react-markdown + remark-gfm，所有块级/内联语法（含 GFM 表格、任务列表、自动链接）
 * 由 react-markdown 统一解析；删除线语法经 remarkFourTildeStrikethrough 改写为
 * 仅 `~~~~text~~~~` 生效（`~~`/`~` 按字面文本渲染）。样式与表格列宽算法通过共享组件工厂注入。
 *
 * 性能：组件以 React.memo 包裹，normalized 内容按 content 记忆；react-markdown 内部按整文档解析，
 * 对常见聊天消息（数 KB）开销可忽略。流式更新时整树重渲染，但 React 协调复用同位同类 DOM，成本与文档规模线性相关。
 *
 * 链接导航：提供 workspaceRoot 时，消息内相对路径链接（如 AI 汇报的章节文件）点击不再
 * 走浏览器新标签页，而是经共享导航 hook 解析为工作区路径后打开应用内文件标签页
 * （外链仍走系统浏览器，#锚点被拦截不动作）。回调经 MarkdownLinkContext 注入，
 * components 保持模块级单例不变（流式渲染不重挂树）。
 */
const MarkdownDisplay = React.memo(function MarkdownDisplay({
  content,
  workspaceRoot,
}: {
  content: string
  /** 所属工作区根(机器绝对路径)：提供时接管相对路径链接为文件标签页；缺省保持浏览器默认。 */
  workspaceRoot?: string
}) {
  const normalized = React.useMemo(() => normalizeMarkdownContent(content), [content])
  // 源文本经 context 按消息注入(表格工具栏「复制内容」按 offset 切整表源码,
  // offset 基准即传给 react-markdown 的 normalized 文本)。components 保持模块级
  // 单例:若随内容重建,流式输出每个 token 都会让整棵 markdown 树卸载重挂
  // (元素类型变了),还会在聊天流 MutationObserver 里制造大量 childList 变更。
  const source = React.useMemo(() => ({ sourceText: normalized }), [normalized])
  // 聊天消息没有"文件所在目录"概念,相对路径一律相对工作区根解析(baseDir='')。
  const onLinkClick = useMarkdownLinkNav({ workspaceRoot })
  const linkSource = React.useMemo(() => ({ onLinkClick }), [onLinkClick])
  if (!normalized.trim()) return null
  return (
    <div className="md-root" style={rootStyle}>
      <MarkdownSourceContext.Provider value={source}>
        <MarkdownLinkContext.Provider value={linkSource}>
          <Markdown remarkPlugins={[remarkGfm, remarkFourTildeStrikethrough]} components={displayComponents}>
            {normalized}
          </Markdown>
        </MarkdownLinkContext.Provider>
      </MarkdownSourceContext.Provider>
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
