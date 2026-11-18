import React from 'react'
import Markdown from "react-markdown"
import remarkGfm from 'remark-gfm'
import { buildMarkdownComponents } from '@/components/shared/markdown/sharedMarkdownRenderer'
import { remarkFourTildeStrikethrough } from '@/components/shared/markdown/remarkFourTildeStrikethrough'
import { useMarkdownLinkNav } from '@/components/shared/markdown/useMarkdownLinkNav'
import { useAppUi } from '@/components/app/AppUiContext'
import { parseMarkdownHeadings, resolveMarkdownHeadingAnchor, type MarkdownHeading } from './markdownOutline'
import MarkdownImage from './MarkdownImage'
import { rawHtmlRehypePlugins } from './rawHtmlPipeline'
import { replaceMarkdownRange, type MarkdownTableCellEditRequest } from '@/components/shared/markdown/markdownTableEdit'

export type MarkdownPreviewHandle = {
  /** 大纲选中标题时调用；返回该 id 是否存在于当前文档（用于决定是否滚动）。不再有折叠展开逻辑。 */
  revealHeading: (headingId: string) => boolean
}

type MarkdownPreviewProps = {
  content: string
  /** 内容回写通道(表格单元格编辑等预览内编辑);缺省时预览完全只读。 */
  onContentChange?: (next: string) => void
  wrapLines?: boolean
  /** 所属工作区根(用于解析内嵌相对路径图片/文件链接);缺省时内嵌图片仅外部 URL 可渲染。 */
  workspaceRoot?: string
  /** md 文件所在目录(工作区相对路径,空串 = 工作区根);内嵌图片/文件链接相对路径解析基准。 */
  baseDir?: string
}

/**
 * Markdown 文件预览。
 *
 * 基于 react-markdown + remark-gfm 渲染，不再维护标题折叠/分层缩进的 section 树。
 * 大纲跳转通过 react-markdown 传入的 hast 节点 position 行号 → parseMarkdownHeadings 预计算的 id 映射，
 * 与 MarkdownOutlinePanel 列出的标题一一对应。
 *
 * 链接导航(useMarkdownLinkNav，与聊天 MarkdownDisplay 共用)：
 * - `#锚点` → 本文件内滚动到对应标题(锚点 slug 与标题 slug 按同一规则匹配)；
 * - 相对路径(如 `../web/ui-extensions.md`) → 以 md 所在目录解析为工作区路径，
 *   存在性校验后经 openGlobalFileTab 打开文件标签页；
 * - 外部链接(http(s)/mailto/tel) → web 新开浏览器标签页，desktop 交系统默认浏览器。
 */
const MarkdownPreview = React.forwardRef<MarkdownPreviewHandle, MarkdownPreviewProps>(function MarkdownPreview(
  { content, onContentChange, wrapLines = true, workspaceRoot, baseDir },
  ref,
) {
  const { showToast } = useAppUi()
  const rootRef = React.useRef<HTMLDivElement | null>(null)

  const normalized = React.useMemo(() => content.replace(/\r\n?/g, '\n'), [content])

  // 预计算标题(行号→id 映射 + 锚点解析)，供 react-markdown heading 组件按 position 行号回填 id。
  const headings = React.useMemo<MarkdownHeading[]>(() => parseMarkdownHeadings(normalized), [normalized])
  const headingIds = React.useMemo(() => {
    const map = new Map<number, string>()
    headings.forEach((heading: MarkdownHeading) => {
      map.set(heading.line, heading.id)
    })
    return map
  }, [headings])

  const headingIdSet = React.useMemo(() => new Set(headingIds.values()), [headingIds])

  /** 页内锚点跳转：解析锚点 → 滚动到对应标题(与大纲选中同表现)。 */
  const revealAnchor = React.useCallback((anchor: string) => {
    const headingId = resolveMarkdownHeadingAnchor(headings, anchor)
    if (!headingId) {
      showToast(`未找到锚点对应的标题：#${anchor}`, 'error')
      return
    }
    requestAnimationFrame(() => {
      const headingElement = Array.from(
        rootRef.current?.querySelectorAll<HTMLElement>('[data-markdown-heading-id]') ?? [],
      ).find((element) => element.dataset.markdownHeadingId === headingId)
      headingElement?.scrollIntoView({
        behavior: 'smooth',
        block: 'start',
      })
    })
  }, [headings, showToast])

  /**
   * markdown 链接统一分发(全部接管，避免浏览器把工作区路径当 URL 新开标签页)：
   * `#锚点` → 本文件跳转；外部链接 → 统一外链入口；其余按工作区文件链接处理(见 useMarkdownLinkNav)。
   */
  const handleLinkClick = useMarkdownLinkNav({ workspaceRoot, baseDir, onAnchor: revealAnchor })

  /** 表格单元格编辑提交:按 offset 区间替换源文本;源已被并发修改时放弃本次提交。 */
  const handleTableCellEdit = React.useCallback((request: MarkdownTableCellEditRequest) => {
    const next = replaceMarkdownRange(normalized, request)
    if (next != null && next !== normalized) onContentChange?.(next)
  }, [normalized, onContentChange])

  const components = React.useMemo(
    () => buildMarkdownComponents({
      variant: 'preview',
      wrapLines,
      resolveHeadingId: (line) => headingIds.get(line),
      renderImage: workspaceRoot
        ? (src, alt) => <MarkdownImage src={src} alt={alt} workspaceRoot={workspaceRoot} baseDir={baseDir} />
        : undefined,
      onLinkClick: handleLinkClick,
      sourceText: onContentChange ? normalized : undefined,
      onTableCellEdit: onContentChange ? handleTableCellEdit : undefined,
    }),
    [wrapLines, headingIds, baseDir, workspaceRoot, handleLinkClick, onContentChange, handleTableCellEdit],
  )

  React.useImperativeHandle(ref, () => ({
    revealHeading: (headingId: string) => headingIdSet.has(headingId),
  }), [headingIdSet])

  return (
    <div ref={rootRef} className="md-root" style={rootStyle}>
      <Markdown
        remarkPlugins={[remarkGfm, remarkFourTildeStrikethrough]}
        rehypePlugins={rawHtmlRehypePlugins}
        components={components}
      >
        {normalized}
      </Markdown>
    </div>
  )
})

export default MarkdownPreview

const rootStyle: React.CSSProperties = {
  width: '100%',
  minWidth: 0,
  background: 'var(--reading-bg)',
  color: 'var(--reading-fg)',
  fontFamily: 'var(--reading-font-family)',
  fontSize: 'var(--editor-font-size)',
}
