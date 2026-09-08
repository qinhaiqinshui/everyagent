import React from 'react'
import MarkdownOutlinePanel from './MarkdownOutlinePanel'
import MarkdownPreview from './MarkdownPreview'
import MarkdownSplitEditor from './MarkdownSplitEditor'
import { parseMarkdownHeadings, type MarkdownHeading } from './markdownOutline'
import Dialog from '@/components/shared/ui/Dialog'
import { useResponsiveViewport } from '@/hooks/useResponsiveViewport'
import type { FileContentEditorDescriptor, FileContentEditorProps, FileContentHeaderAction } from '@/components/files/editors/types'

type MarkdownViewMode = 'split' | 'editor' | 'preview'
type MarkdownLayout = 'horizontal' | 'vertical'

function MarkdownFileEditor({
  file,
  mode,
  content,
  draftContent,
  loading,
  error,
  lineNumber,
  lineLocateRequestedAt,
  onLineLocateApplied,
  onDraftChange,
  onHeaderActionsChange,
  onRequestEditMode,
  findRegex,
  findActiveIndex,
  findEnabled,
}: FileContentEditorProps) {
  const [showOutline, setShowOutline] = React.useState(false)
  const { isMobile } = useResponsiveViewport()
  // markdown 文件打开（含 readwrite 编辑模式）默认进入「仅预览」视图；
  // 需要编辑时由标题栏「编辑」按钮或 readonly→readwrite 切换进入源码视图。
  const [viewMode, setViewMode] = React.useState<MarkdownViewMode>('preview')
  const [layout, setLayout] = React.useState<MarkdownLayout>('horizontal')
  const [wrapLines, setWrapLines] = React.useState(true)
  const [copied, setCopied] = React.useState(false)
  const previewRootRef = React.useRef<HTMLDivElement | null>(null)
  const previewComponentRef = React.useRef<React.ElementRef<typeof MarkdownPreview> | null>(null)
  const splitEditorRef = React.useRef<React.ElementRef<typeof MarkdownSplitEditor> | null>(null)
  const previousModeRef = React.useRef(mode)
  const headings = React.useMemo<MarkdownHeading[]>(() => (
    parseMarkdownHeadings(mode === 'readonly' ? content : draftContent)
  ), [content, draftContent, mode])
  const sourceContent = mode === 'readonly' ? content : draftContent

  React.useEffect(() => {
    const previousMode = previousModeRef.current
    if (mode === 'readonly') {
      setViewMode('preview')
    } else if (previousMode === 'readonly' && viewMode === 'preview') {
      setViewMode('editor')
    }
    previousModeRef.current = mode
  }, [mode, viewMode])

  React.useEffect(() => {
    setWrapLines(true)
  }, [file.id])

  const handleCopy = React.useCallback(async () => {
    try {
      await navigator.clipboard.writeText(sourceContent)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      // ignore
    }
  }, [sourceContent])

  const headerActions = React.useMemo<FileContentHeaderAction[]>(() => {
    const actions: FileContentHeaderAction[] = [
      {
        id: 'markdown-copy-content',
        label: copied ? '已复制' : '复制内容',
        onClick: () => { void handleCopy() },
      },
      {
        id: 'markdown-wrap-lines',
        label: wrapLines ? '自动换行' : '不换行',
        onClick: () => setWrapLines((current) => !current),
        active: wrapLines,
      },
      {
        id: 'markdown-outline-toggle',
        label: showOutline ? '隐藏结构' : '显示结构',
        onClick: () => setShowOutline((current) => !current),
        active: showOutline,
      },
    ]

    // 单一「预览/编辑」切换按钮：当前为预览态时显示「编辑」（点击进入编辑），
    // 当前为编辑/双栏态时显示「预览」（点击进入预览）。移动端标题栏也会渲染（save-adjacent）。
    const viewToggleAction: FileContentHeaderAction = {
      id: 'markdown-view-toggle',
      label: viewMode === 'preview' ? '编辑' : '预览',
      onClick: () => {
        if (mode === 'readonly') {
          onRequestEditMode?.()
        } else if (viewMode === 'preview') {
          setViewMode('editor')
        } else {
          setViewMode('preview')
        }
      },
      placement: 'save-adjacent',
    }

    if (mode === 'readonly') {
      actions.push(viewToggleAction)
      return actions
    }

    actions.push(
      {
        id: 'markdown-view-split',
        label: '双栏',
        onClick: () => setViewMode('split'),
        active: viewMode === 'split',
        groupId: 'markdown-view-mode',
      },
      viewToggleAction,
    )

    if (viewMode === 'split') {
      actions.push(
        {
          id: 'markdown-layout-horizontal',
          label: '左右分',
          onClick: () => setLayout('horizontal'),
          active: layout === 'horizontal',
          groupId: 'markdown-split-layout',
        },
        {
          id: 'markdown-layout-vertical',
          label: '上下分',
          onClick: () => setLayout('vertical'),
          active: layout === 'vertical',
          groupId: 'markdown-split-layout',
        },
      )
    }

    return actions
  }, [copied, handleCopy, layout, mode, onRequestEditMode, showOutline, viewMode, wrapLines])

  React.useEffect(() => {
    onHeaderActionsChange?.(headerActions)
  }, [headerActions, onHeaderActionsChange])

  React.useEffect(() => (
    () => {
      onHeaderActionsChange?.([])
    }
  ), [onHeaderActionsChange])

  if (loading) {
    return null
  }
  if (error) {
    return null
  }

  const handleSelectHeading = (heading: MarkdownHeading) => {
    if (mode === 'readonly') {
      previewComponentRef.current?.revealHeading(heading.id)
      requestAnimationFrame(() => {
        const previewRoot = previewRootRef.current
        if (!previewRoot) return
        const headingElement = Array.from(previewRoot.querySelectorAll<HTMLElement>('[data-markdown-heading-id]'))
          .find((element) => element.dataset.markdownHeadingId === heading.id)
        if (!headingElement) return
        headingElement.scrollIntoView({
          behavior: 'smooth',
          block: 'start',
        })
      })
      return
    }
    splitEditorRef.current?.scrollToHeading(heading.id)
  }

  // 移动端：大纲以弹窗形式展示（侧边栏在窄屏会挤占正文），选中标题后关闭弹窗并跳转。
  const handleSelectHeadingAndClose = (heading: MarkdownHeading) => {
    handleSelectHeading(heading)
    setShowOutline(false)
  }
  const mobileOutlineDialog = isMobile && showOutline ? (
    <Dialog open onClose={() => setShowOutline(false)} title="Markdown 结构">
      <div style={mobileOutlineDialogBodyStyle}>
        <MarkdownOutlinePanel
          headings={headings}
          onSelect={handleSelectHeadingAndClose}
          showHeader={false}
        />
      </div>
    </Dialog>
  ) : null

  if (mode === 'readonly') {
    return (
      <div style={containerStyle}>
        <div style={bodyStyle}>
          <div
            ref={previewRootRef}
            style={{
              ...readonlyPreviewScrollStyle,
              overflowX: 'auto',
            }}
          >
            <div style={contentWrapStyle}>
              <MarkdownPreview ref={previewComponentRef} content={content} wrapLines={wrapLines} />
            </div>
          </div>
          {!isMobile && showOutline ? (
            <aside style={outlineWrapStyle}>
              <MarkdownOutlinePanel headings={headings} onSelect={handleSelectHeading} />
            </aside>
          ) : null}
        </div>
        {mobileOutlineDialog}
      </div>
    )
  }

  return (
    <div style={containerStyle}>
      <div style={bodyStyle}>
        <div style={contentWrapStyle}>
          <MarkdownSplitEditor
            ref={splitEditorRef}
            value={draftContent}
            onChange={onDraftChange}
            viewMode={viewMode}
            layout={layout}
            wrapLines={wrapLines}
            onWrapLinesChange={setWrapLines}
            lineNumber={lineNumber}
            lineLocateRequestedAt={lineLocateRequestedAt}
            onLineLocateApplied={onLineLocateApplied}
            onViewModeChange={setViewMode}
            findRegex={findRegex}
            findActiveIndex={findActiveIndex}
            findEnabled={findEnabled}
          />
        </div>
        {!isMobile && showOutline ? (
          <aside style={outlineWrapStyle}>
            <MarkdownOutlinePanel headings={headings} onSelect={handleSelectHeading} />
          </aside>
        ) : null}
      </div>
      {mobileOutlineDialog}
    </div>
  )
}

export const descriptor: FileContentEditorDescriptor = {
  kind: 'markdown',
  label: 'Markdown',
  extensions: ['.md'],
  Component: MarkdownFileEditor,
}

const containerStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
}

const bodyStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  minWidth: 0,
  display: 'flex',
  overflow: 'hidden',
}

const contentWrapStyle: React.CSSProperties = {
  width: '100%',
  minWidth: 0,
}

const readonlyPreviewScrollStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  minHeight: 0,
  overflowY: 'auto',
  overflowX: 'auto',
  // 阻止 overscroll 向祖先链式回弹，避免移动端顶部下拉持续闪烁。
  overscrollBehavior: 'contain',
}

const outlineWrapStyle: React.CSSProperties = {
  width: 300,
  minWidth: 300,
  display: 'flex',
  flexDirection: 'column',
  flex: '0 0 300px',
  minHeight: 0,
  height: '100%',
  position: 'relative',
  overflow: 'hidden',
  borderLeft: '1px solid var(--border)',
  background: 'var(--bg-secondary)',
}

const mobileOutlineDialogBodyStyle: React.CSSProperties = {
  // 给面板提供确定高度的 flex 容器，让内部标题列表自带滚动而非撑高弹窗。
  height: '60vh',
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
}

export default MarkdownFileEditor
