import React from 'react'
import Markdown from "react-markdown"
import remarkGfm from 'remark-gfm'
import { buildMarkdownComponents } from '@/components/shared/markdown/sharedMarkdownRenderer'
import { remarkFourTildeStrikethrough } from '@/components/shared/markdown/remarkFourTildeStrikethrough'
import { useWorkspaceShell } from '@/components/app/WorkspaceShellContext'
import { useAppUi } from '@/components/app/AppUiContext'
import { resolveWorkspaceRelativePath, toBusinessAbsolutePath } from '@/platform/fs/pathUtils'
import { workspaceGateway } from '@/platform/fs/workspaceGateway'
import { isExternalLinkUrl, openExternalLink } from '@/platform/externalLink'
import { parseMarkdownHeadings, resolveMarkdownHeadingAnchor, type MarkdownHeading } from './markdownOutline'
import MarkdownImage from './MarkdownImage'

export type MarkdownPreviewHandle = {
  /** 大纲选中标题时调用；返回该 id 是否存在于当前文档（用于决定是否滚动）。不再有折叠展开逻辑。 */
  revealHeading: (headingId: string) => boolean
}

type MarkdownPreviewProps = {
  content: string
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
 * 链接导航(onLinkClick，仅预览场景接管)：
 * - `#锚点` → 本文件内滚动到对应标题(锚点 slug 与标题 slug 按同一规则匹配)；
 * - 相对路径(如 `../web/ui-extensions.md`) → 以 md 所在目录解析为工作区路径，
 *   存在性校验后经 openGlobalFileTab 打开文件标签页；
 * - 外部链接(http(s)/mailto/tel) → web 新开浏览器标签页，desktop 交系统默认浏览器。
 */
const MarkdownPreview = React.forwardRef<MarkdownPreviewHandle, MarkdownPreviewProps>(function MarkdownPreview(
  { content, wrapLines = true, workspaceRoot, baseDir },
  ref,
) {
  const { openGlobalFileTab } = useWorkspaceShell()
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

  /** 工作区文件链接：剥离 #fragment、按 md 所在目录解析相对路径，存在性校验后开文件标签页。 */
  const openWorkspaceFileLink = React.useCallback((rawHref: string) => {
    // fragment(目标文件内锚点)本预览无法承载——文件标签页定位体系只到行号；这里只管打开文件。
    const pathPart = rawHref.split('#')[0] ?? ''
    let target = pathPart
    try {
      target = decodeURIComponent(pathPart)
    } catch {
      /* 非法 percent 编码序列：按原文参与解析 */
    }
    const rel = resolveWorkspaceRelativePath(baseDir ?? '', target)
    if (!rel) return
    const businessPath = toBusinessAbsolutePath(rel)
    if (!workspaceRoot) {
      showToast(`缺少工作区上下文，无法打开文件链接：${businessPath}`, 'error')
      return
    }
    void workspaceGateway.statRaw(workspaceRoot, rel)
      .then((stat) => {
        if (stat.isDirectory) {
          showToast(`链接指向的是目录而非文件：${businessPath}`, 'error')
          return
        }
        openGlobalFileTab({ workspaceRoot, filePath: businessPath })
      })
      .catch(() => {
        showToast(`链接指向的文件不存在：${businessPath}`, 'error')
      })
  }, [baseDir, openGlobalFileTab, showToast, workspaceRoot])

  /**
   * markdown 链接统一分发(全部接管，避免浏览器把工作区路径当 URL 新开标签页)：
   * `#锚点` → 本文件跳转；外部链接 → 统一外链入口；其余按工作区文件链接处理。
   */
  const handleLinkClick = React.useCallback((href: string): boolean => {
    const trimmed = href.trim()
    if (!trimmed) return false
    if (trimmed.startsWith('#')) {
      const anchor = trimmed.slice(1)
      if (anchor) revealAnchor(anchor)
      return true
    }
    if (isExternalLinkUrl(trimmed)) {
      openExternalLink(trimmed)
      return true
    }
    // 带协议前缀但不在放行名单(如 ftp:)的链接:提示后忽略,不当作工作区文件路径解析。
    if (/^[a-z][a-z0-9+.-]*:/i.test(trimmed)) {
      showToast(`暂不支持打开该协议的链接：${trimmed}`, 'error')
      return true
    }
    openWorkspaceFileLink(trimmed)
    return true
  }, [openWorkspaceFileLink, revealAnchor, showToast])

  const components = React.useMemo(
    () => buildMarkdownComponents({
      variant: 'preview',
      wrapLines,
      resolveHeadingId: (line) => headingIds.get(line),
      renderImage: workspaceRoot
        ? (src, alt) => <MarkdownImage src={src} alt={alt} workspaceRoot={workspaceRoot} baseDir={baseDir} />
        : undefined,
      onLinkClick: handleLinkClick,
    }),
    [wrapLines, headingIds, baseDir, workspaceRoot, handleLinkClick],
  )

  React.useImperativeHandle(ref, () => ({
    revealHeading: (headingId: string) => headingIdSet.has(headingId),
  }), [headingIdSet])

  return (
    <div ref={rootRef} className="md-root" style={rootStyle}>
      <Markdown remarkPlugins={[remarkGfm, remarkFourTildeStrikethrough]} components={components}>
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
