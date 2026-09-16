import React from 'react'
import Markdown from "react-markdown"
import remarkGfm from 'remark-gfm'
import { buildMarkdownComponents } from '@/components/shared/markdown/sharedMarkdownRenderer'
import { parseMarkdownHeadings, type MarkdownHeading } from './markdownOutline'
import MarkdownImage from './MarkdownImage'

export type MarkdownPreviewHandle = {
  /** 大纲选中标题时调用；返回该 id 是否存在于当前文档（用于决定是否滚动）。不再有折叠展开逻辑。 */
  revealHeading: (headingId: string) => boolean
}

type MarkdownPreviewProps = {
  content: string
  wrapLines?: boolean
  /** 所属工作区根(用于解析内嵌相对路径图片);缺省时内嵌图片仅外部 URL 可渲染。 */
  workspaceRoot?: string
  /** md 文件所在目录(工作区相对路径,空串 = 工作区根);内嵌图片相对路径解析基准。 */
  baseDir?: string
}

/**
 * Markdown 文件预览。
 *
 * 基于 react-markdown + remark-gfm 渲染，不再维护标题折叠/分层缩进的 section 树。
 * 大纲跳转通过 react-markdown 传入的 hast 节点 position 行号 → parseMarkdownHeadings 预计算的 id 映射，
 * 与 MarkdownOutlinePanel 列出的标题一一对应。
 */
const MarkdownPreview = React.forwardRef<MarkdownPreviewHandle, MarkdownPreviewProps>(function MarkdownPreview(
  { content, wrapLines = true, workspaceRoot, baseDir },
  ref,
) {
  const normalized = React.useMemo(() => content.replace(/\r\n?/g, '\n'), [content])

  // 预计算标题行号→id 映射，供 react-markdown heading 组件按 position 行号回填 id。
  const headingIds = React.useMemo(() => {
    const map = new Map<number, string>()
    parseMarkdownHeadings(normalized).forEach((heading: MarkdownHeading) => {
      map.set(heading.line, heading.id)
    })
    return map
  }, [normalized])

  const headingIdSet = React.useMemo(() => new Set(headingIds.values()), [headingIds])

  const components = React.useMemo(
    () => buildMarkdownComponents({
      variant: 'preview',
      wrapLines,
      resolveHeadingId: (line) => headingIds.get(line),
      renderImage: workspaceRoot
        ? (src, alt) => <MarkdownImage src={src} alt={alt} workspaceRoot={workspaceRoot} baseDir={baseDir} />
        : undefined,
    }),
    [wrapLines, headingIds, baseDir, workspaceRoot],
  )

  React.useImperativeHandle(ref, () => ({
    revealHeading: (headingId: string) => headingIdSet.has(headingId),
  }), [headingIdSet])

  return (
    <div style={rootStyle}>
      <Markdown remarkPlugins={[remarkGfm]} components={components}>
        {normalized}
      </Markdown>
    </div>
  )
})

export default MarkdownPreview

const rootStyle: React.CSSProperties = {
  width: '100%',
  minWidth: 0,
}
