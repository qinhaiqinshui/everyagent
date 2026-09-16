import React from 'react'
import CodeMirrorEditor from '@/components/shared/CodeMirrorEditor'
import { markdown } from '@codemirror/lang-markdown'
import MarkdownPreview, { type MarkdownPreviewHandle } from './MarkdownPreview'
import ScrollEdgeToggleFab from '@/components/shared/ScrollEdgeToggleFab'

type PreviewLayout = 'horizontal' | 'vertical'
type ViewMode = 'split' | 'editor' | 'preview'

const MIN_RATIO = 20
const MAX_RATIO = 80

// Markdown 语言扩展（单例缓存）。
const mdLanguage = markdown()

export type MarkdownSplitEditorHandle = {
  scrollToHeading: (headingId: string) => void
}

type MarkdownSplitEditorProps = {
  value: string
  onChange: (value: string) => void
  viewMode: ViewMode
  layout: PreviewLayout
  wrapLines: boolean
  onWrapLinesChange: (nextValue: boolean) => void
  lineNumber?: number
  lineLocateRequestedAt?: number
  onLineLocateApplied?: () => void
  onViewModeChange: (mode: ViewMode) => void
  /** 所属工作区根（透传预览，用于解析内嵌相对路径图片）。 */
  workspaceRoot?: string
  /** md 文件所在目录（工作区相对路径，空串=根）。 */
  baseDir?: string
}

const MarkdownSplitEditor = React.forwardRef<MarkdownSplitEditorHandle, MarkdownSplitEditorProps>(({
  value,
  onChange,
  viewMode,
  layout,
  wrapLines,
  onWrapLinesChange,
  lineNumber,
  lineLocateRequestedAt,
  onLineLocateApplied,
  onViewModeChange,
  workspaceRoot,
  baseDir,
}, ref) => {
  const hostRef = React.useRef<HTMLDivElement | null>(null)
  const [ratio, setRatio] = React.useState(50)
  const editorScrollRef = React.useRef<HTMLElement | null>(null)
  const previewScrollRef = React.useRef<HTMLDivElement | null>(null)
  const previewRef = React.useRef<MarkdownPreviewHandle | null>(null)
  const dragStateRef = React.useRef<{
    active: boolean
    rect: DOMRect | null
    layout: PreviewLayout
  }>({ active: false, rect: null, layout: 'horizontal' })
  const panesRef = React.useRef<HTMLDivElement | null>(null)
  const pendingHeadingIdRef = React.useRef<string | null>(null)

  React.useEffect(() => {
    const handlePointerMove = (event: PointerEvent) => {
      const state = dragStateRef.current
      if (!state.active || !state.rect) return

      const nextRatio = state.layout === 'horizontal'
        ? ((event.clientX - state.rect.left) / state.rect.width) * 100
        : ((event.clientY - state.rect.top) / state.rect.height) * 100

      setRatio(Math.min(MAX_RATIO, Math.max(MIN_RATIO, nextRatio)))
    }

    const handlePointerUp = () => {
      dragStateRef.current.active = false
      dragStateRef.current.rect = null
    }

    window.addEventListener('pointermove', handlePointerMove)
    window.addEventListener('pointerup', handlePointerUp)
    return () => {
      window.removeEventListener('pointermove', handlePointerMove)
      window.removeEventListener('pointerup', handlePointerUp)
    }
  }, [])

  const startDrag = (event: React.PointerEvent<HTMLDivElement>) => {
    if (!panesRef.current || viewMode !== 'split') return
    dragStateRef.current = {
      active: true,
      rect: panesRef.current.getBoundingClientRect(),
      layout,
    }
    event.currentTarget.setPointerCapture(event.pointerId)
    event.preventDefault()
  }

  const firstPaneStyle = layout === 'horizontal'
    ? { width: `${ratio}%` }
    : { height: `${ratio}%` }
  const secondPaneStyle = layout === 'horizontal'
    ? { width: `${100 - ratio}%` }
    : { height: `${100 - ratio}%` }
  const fabScrollTargetRefs = React.useMemo(() => {
    if (viewMode === 'editor') return [editorScrollRef]
    if (viewMode === 'preview') return [previewScrollRef]
    return [editorScrollRef, previewScrollRef]
  }, [viewMode])

  const scrollHeadingIntoView = React.useCallback((headingId: string) => {
    const previewRoot = previewScrollRef.current
    if (!previewRoot) return
    previewRef.current?.revealHeading(headingId)
    requestAnimationFrame(() => {
      const headingElement = Array.from(previewRoot.querySelectorAll<HTMLElement>('[data-markdown-heading-id]'))
        .find((element) => element.dataset.markdownHeadingId === headingId)
      if (!headingElement) return
      headingElement.scrollIntoView({
        behavior: 'smooth',
        block: 'start',
      })
    })
  }, [])

  React.useImperativeHandle(ref, () => ({
    scrollToHeading: (headingId: string) => {
      if (viewMode === 'editor') {
        pendingHeadingIdRef.current = headingId
        onViewModeChange('split')
        return
      }
      scrollHeadingIntoView(headingId)
    },
  }), [onViewModeChange, scrollHeadingIntoView, viewMode])

  React.useEffect(() => {
    if (!pendingHeadingIdRef.current || viewMode === 'editor') return
    const headingId = pendingHeadingIdRef.current
    pendingHeadingIdRef.current = null
    requestAnimationFrame(() => {
      scrollHeadingIntoView(headingId)
    })
  }, [scrollHeadingIntoView, value, viewMode])

  // 统一渲染 CodeMirror 编辑器（editor 模式和 split 模式共用）。
  const editorPane = (
    <CodeMirrorEditor
      value={value}
      onChange={onChange}
      editable
      language={mdLanguage}
      wrapLines={wrapLines}
      lineNumber={lineNumber}
      lineLocateRequestedAt={lineLocateRequestedAt}
      onLineLocateApplied={onLineLocateApplied}
      scrollRef={editorScrollRef}
    />
  )

  // 统一渲染预览面板。
  const previewPane = (
    <div
      ref={previewScrollRef}
      style={{
        ...previewScrollStyle,
        overflowX: 'auto',
      }}
    >
      <div style={previewContentStyle}>
        <MarkdownPreview ref={previewRef} content={value} wrapLines={wrapLines} workspaceRoot={workspaceRoot} baseDir={baseDir} />
      </div>
    </div>
  )

  return (
    <div ref={hostRef} style={containerStyle} className="markdown-split-editor">
      {viewMode === 'editor' && (
        <section style={singlePaneStyle}>
          {editorPane}
        </section>
      )}

      {viewMode === 'preview' && (
        <section style={singlePaneStyle}>
          {previewPane}
        </section>
      )}

      {viewMode === 'split' && (
        <div
          ref={panesRef}
          style={{
            ...panesStyle,
            flexDirection: layout === 'vertical' ? 'column' : 'row',
          }}
          className={`markdown-split-editor__panes markdown-split-editor__panes--${layout}`}
        >
          <section
            style={{ ...paneStyle, ...firstPaneStyle }}
            className="markdown-split-editor__pane markdown-split-editor__pane--editor"
          >
            {editorPane}
          </section>

          <div
            role="separator"
            aria-orientation={layout === 'horizontal' ? 'vertical' : 'horizontal'}
            onPointerDown={startDrag}
            style={{
              ...dividerStyle,
              ...(layout === 'horizontal' ? dividerHorizontalStyle : dividerVerticalStyle),
            }}
          />

          <section
            style={{ ...paneStyle, ...secondPaneStyle }}
            className="markdown-split-editor__pane markdown-split-editor__pane--preview"
          >
            {previewPane}
          </section>
        </div>
      )}
      <ScrollEdgeToggleFab
        scrollTargetRefs={fabScrollTargetRefs}
      />
    </div>
  )
})

MarkdownSplitEditor.displayName = 'MarkdownSplitEditor'

export default MarkdownSplitEditor

const containerStyle: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  flexDirection: 'column',
  minHeight: 0,
  height: '100%',
  position: 'relative',
}

const panesStyle: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  minHeight: 0,
}

const singlePaneStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
  background: 'var(--bg-secondary)',
}

const paneStyle: React.CSSProperties = {
  minWidth: 0,
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
  background: 'var(--bg-secondary)',
}

const dividerStyle: React.CSSProperties = {
  flexShrink: 0,
  background: 'color-mix(in srgb, var(--border) 78%, transparent)',
  position: 'relative',
}

const dividerHorizontalStyle: React.CSSProperties = {
  width: 1,
  cursor: 'col-resize',
}

const dividerVerticalStyle: React.CSSProperties = {
  height: 1,
  cursor: 'row-resize',
}

const previewScrollStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  overflow: 'auto',
  overscrollBehavior: 'contain',
}

const previewContentStyle: React.CSSProperties = {
  width: '100%',
  minWidth: 0,
  padding: '16px 18px',
  boxSizing: 'border-box',
}
