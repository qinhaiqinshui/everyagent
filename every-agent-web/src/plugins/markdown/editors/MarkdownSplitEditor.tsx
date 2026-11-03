import React from 'react'
import type { EditorView } from '@codemirror/view'
import CodeMirrorEditor from '@/components/shared/CodeMirrorEditor'
import { markdown } from '@codemirror/lang-markdown'
import MarkdownPreview, { type MarkdownPreviewHandle } from './MarkdownPreview'
import ScrollEdgeToggleFab from '@/components/shared/ScrollEdgeToggleFab'
import { scrollEditorToLine, getEditorTopLine, scrollPreviewToLine, getPreviewTopSourceLine } from './markdownScrollSync'

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
  lineNumber?: number
  lineLocateRequestedAt?: number
  onLineLocateApplied?: () => void
  onViewModeChange: (mode: ViewMode) => void
  /** 所属工作区根（透传预览，用于解析内嵌相对路径图片）。 */
  workspaceRoot?: string
  /** md 文件所在目录（工作区相对路径，空串=根）。 */
  baseDir?: string
  /**
   * 初始定位到编辑器的源行号(1-based,可为小数)。
   * 用于跨组件重建场景(如只读预览 → 编辑模式切换)迁移阅读位置;仅消费一次。
   */
  initialEditorLine?: number | null
}

/**
 * Markdown 分栏编辑器:编辑器(CodeMirror)与预览面板常挂载于同一 DOM 层级,
 * 三种视图(editor/preview/split)仅切换显隐与尺寸样式——切换视图不重建
 * CodeMirror/预览实例,配合 scrollTop 记录/恢复实现「切换保持滚动位置」。
 */
const MarkdownSplitEditor = React.forwardRef<MarkdownSplitEditorHandle, MarkdownSplitEditorProps>(({
  value,
  onChange,
  viewMode,
  layout,
  wrapLines,
  lineNumber,
  lineLocateRequestedAt,
  onLineLocateApplied,
  onViewModeChange,
  workspaceRoot,
  baseDir,
  initialEditorLine,
}, ref) => {
  const hostRef = React.useRef<HTMLDivElement | null>(null)
  const [ratio, setRatio] = React.useState(50)
  const editorScrollRef = React.useRef<HTMLElement | null>(null)
  const previewScrollRef = React.useRef<HTMLDivElement | null>(null)
  const previewRef = React.useRef<MarkdownPreviewHandle | null>(null)
  const editorViewRef = React.useRef<EditorView | null>(null)
  const dragStateRef = React.useRef<{
    active: boolean
    rect: DOMRect | null
    layout: PreviewLayout
  }>({ active: false, rect: null, layout: 'horizontal' })
  const panesRef = React.useRef<HTMLDivElement | null>(null)
  const pendingHeadingIdRef = React.useRef<string | null>(null)
  // ── 视图切换保持滚动位置:display:none 会丢失元素 scrollTop,故滚动时持续记录,
  //    切换视图后(面板重新显示)在 layout effect 中写回。
  const lastViewModeRef = React.useRef(viewMode)
  const editorScrollTopRef = React.useRef(0)
  const previewScrollTopRef = React.useRef(0)
  const initialEditorLineConsumedRef = React.useRef(false)
  // ── 双栏(split)滚动同步:编辑器/预览任一侧滚动时,另一侧按源行号跟随
  //    (VSCode 式)。syncLock 标记当前主动同步的发起侧,短窗内忽略对侧回声
  //    scroll 事件,避免互相触发的同步循环;视图模式经 ref 供事件回调读取最新值。
  const viewModeRef = React.useRef(viewMode)
  viewModeRef.current = viewMode
  const syncLockRef = React.useRef<'editor' | 'preview' | null>(null)
  const syncUnlockTimerRef = React.useRef<number | undefined>(undefined)
  const editorSyncRafRef = React.useRef(0)
  const previewSyncRafRef = React.useRef(0)

  /** 标记一次由 source 侧发起的主动同步,并在短窗后解锁(对侧回声事件在此期间忽略)。 */
  const lockSync = React.useCallback((source: 'editor' | 'preview') => {
    syncLockRef.current = source
    window.clearTimeout(syncUnlockTimerRef.current)
    syncUnlockTimerRef.current = window.setTimeout(() => {
      syncLockRef.current = null
    }, 150)
  }, [])

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

  // 编辑器滚动:记录位置(视图切换恢复用)+ 双栏时同步预览。
  // CodeMirror scroller 挂载后绑定(子组件 effect 先于本组件运行);rAF 合并高频滚动事件。
  React.useEffect(() => {
    const scroller = editorScrollRef.current
    if (!scroller) return
    const onScroll = () => {
      editorScrollTopRef.current = scroller.scrollTop
      if (viewModeRef.current !== 'split' || syncLockRef.current === 'preview') return
      if (editorSyncRafRef.current) return
      editorSyncRafRef.current = requestAnimationFrame(() => {
        editorSyncRafRef.current = 0
        const view = editorViewRef.current
        const previewEl = previewScrollRef.current
        if (!view || !previewEl) return
        if (viewModeRef.current !== 'split' || syncLockRef.current === 'preview') return
        const line = getEditorTopLine(view)
        if (line == null) return
        lockSync('editor')
        scrollPreviewToLine(previewEl, line)
      })
    }
    scroller.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      scroller.removeEventListener('scroll', onScroll)
      if (editorSyncRafRef.current) {
        cancelAnimationFrame(editorSyncRafRef.current)
        editorSyncRafRef.current = 0
      }
    }
  }, [lockSync])

  // 预览滚动:记录位置(视图切换恢复用)+ 双栏时同步编辑器。
  const handlePreviewScroll = React.useCallback(() => {
    const element = previewScrollRef.current
    if (!element) return
    previewScrollTopRef.current = element.scrollTop
    if (viewModeRef.current !== 'split' || syncLockRef.current === 'editor') return
    if (previewSyncRafRef.current) return
    previewSyncRafRef.current = requestAnimationFrame(() => {
      previewSyncRafRef.current = 0
      const view = editorViewRef.current
      if (!view) return
      if (viewModeRef.current !== 'split' || syncLockRef.current === 'editor') return
      const line = getPreviewTopSourceLine(previewScrollRef.current)
      if (line == null) return
      lockSync('preview')
      scrollEditorToLine(view, line)
    })
  }, [lockSync])

  // 卸载清理同步用的定时器与挂起帧。
  React.useEffect(() => () => {
    window.clearTimeout(syncUnlockTimerRef.current)
    if (previewSyncRafRef.current) {
      cancelAnimationFrame(previewSyncRafRef.current)
      previewSyncRafRef.current = 0
    }
  }, [])

  // 视图切换后的位置处理:
  // - 预览 ↔ 编辑(单栏互切):恢复刚显示一侧的滚动位置(隐藏期间浏览器已将其 scrollTop 重置为 0);
  // - 进入双栏(split):刚显示的一侧按源行号对齐先在视的一侧,保证双栏两侧内容位置一致
  //   (双栏期间由滚动同步保持)。进入 split 的对齐等一帧,让 CodeMirror/预览完成恢复显示后的测量。
  React.useLayoutEffect(() => {
    const previous = lastViewModeRef.current
    lastViewModeRef.current = viewMode
    if (previous === viewMode) return
    if (viewMode === 'editor' && previous === 'preview') {
      const scroller = editorScrollRef.current
      if (scroller) {
        scroller.scrollTop = editorScrollTopRef.current
      }
      return
    }
    if (viewMode === 'preview' && previous === 'editor') {
      const element = previewScrollRef.current
      if (element) {
        element.scrollTop = previewScrollTopRef.current
      }
      return
    }
    if (viewMode !== 'split') return
    // 有挂起的大纲跳转(editor 模式选标题 → 切 split 后滚预览)时让位,
    // 避免「预览对齐编辑器」覆盖掉即将执行的大纲定位。
    if (pendingHeadingIdRef.current != null) return
    let raf1 = 0
    let raf2 = 0
    raf1 = requestAnimationFrame(() => {
      raf2 = requestAnimationFrame(() => {
        const view = editorViewRef.current
        const previewEl = previewScrollRef.current
        if (!view || !previewEl) return
        if (previous === 'editor') {
          // 编辑器在视 → 预览对齐编辑器顶行
          const line = getEditorTopLine(view)
          if (line == null) return
          lockSync('editor')
          scrollPreviewToLine(previewEl, line)
        } else if (previous === 'preview') {
          // 预览在视 → 编辑器对齐预览顶行
          const line = getPreviewTopSourceLine(previewEl)
          if (line == null) return
          lockSync('preview')
          scrollEditorToLine(view, line)
        }
      })
    })
    return () => {
      cancelAnimationFrame(raf1)
      cancelAnimationFrame(raf2)
    }
  }, [viewMode, lockSync])

  // 初始行号定位(跨组件重建时迁移阅读位置,如只读预览 → 编辑模式):
  // 等编辑器可见(viewMode editor/split)且内容就绪后消费一次;内容未就绪(空文档)
  // 时不消费,待 value 就位后重触发——与行定位(lineLocate)同思路。
  React.useEffect(() => {
    if (initialEditorLine == null || initialEditorLineConsumedRef.current) return
    if (viewMode === 'preview') return
    const view = editorViewRef.current
    if (!view || view.state.doc.length === 0) return
    initialEditorLineConsumedRef.current = true
    let raf1 = 0
    let raf2 = 0
    raf1 = requestAnimationFrame(() => {
      raf2 = requestAnimationFrame(() => {
        scrollEditorToLine(editorViewRef.current, initialEditorLine)
      })
    })
    return () => {
      cancelAnimationFrame(raf1)
      cancelAnimationFrame(raf2)
    }
  }, [initialEditorLine, viewMode, value])

  // 统一渲染 CodeMirror 编辑器（editor 模式和 split 模式共用;常挂载不随视图切换重建）。
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
      viewRef={editorViewRef}
    />
  )

  // 统一渲染预览面板:滚动容器常挂载(保留滚动位置),正文仅在非 editor 视图渲染
  // (editor 单栏下不付出预览重渲染成本,切回时重建正文、容器位置照常恢复)。
  const previewPane = (
    <div
      ref={previewScrollRef}
      onScroll={handlePreviewScroll}
      style={{
        ...previewScrollStyle,
        overflowX: 'auto',
      }}
    >
      <div style={previewContentStyle}>
        {viewMode !== 'editor' ? (
          <MarkdownPreview ref={previewRef} content={value} wrapLines={wrapLines} workspaceRoot={workspaceRoot} baseDir={baseDir} />
        ) : null}
      </div>
    </div>
  )

  // 三种视图共用同一 pane 层级:split 按比例分宽/高,单栏占满,另一侧 display:none。
  const isSplit = viewMode === 'split'
  const editorPaneStyle: React.CSSProperties = isSplit
    ? (layout === 'horizontal' ? { width: `${ratio}%` } : { height: `${ratio}%` })
    : (viewMode === 'editor' ? singleFillPaneStyle : hiddenPaneStyle)
  const previewPaneStyle: React.CSSProperties = isSplit
    ? (layout === 'horizontal' ? { width: `${100 - ratio}%` } : { height: `${100 - ratio}%` })
    : (viewMode === 'preview' ? singleFillPaneStyle : hiddenPaneStyle)

  return (
    <div ref={hostRef} style={containerStyle} className="markdown-split-editor">
      <div
        ref={panesRef}
        style={{
          ...panesStyle,
          flexDirection: layout === 'vertical' ? 'column' : 'row',
        }}
        className={`markdown-split-editor__panes markdown-split-editor__panes--${layout}`}
      >
        <section
          style={{ ...paneStyle, ...editorPaneStyle }}
          className="markdown-split-editor__pane markdown-split-editor__pane--editor"
        >
          {editorPane}
        </section>

        {isSplit ? (
          <div
            role="separator"
            aria-orientation={layout === 'horizontal' ? 'vertical' : 'horizontal'}
            onPointerDown={startDrag}
            style={{
              ...dividerStyle,
              ...(layout === 'horizontal' ? dividerHorizontalStyle : dividerVerticalStyle),
            }}
          />
        ) : null}

        <section
          style={{ ...paneStyle, ...previewPaneStyle }}
          className="markdown-split-editor__pane markdown-split-editor__pane--preview"
        >
          {previewPane}
        </section>
      </div>
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

const paneStyle: React.CSSProperties = {
  minWidth: 0,
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
  background: 'var(--reading-bg)',
}

/** 单栏视图:占满可用空间。 */
const singleFillPaneStyle: React.CSSProperties = {
  flex: 1,
}

/** 另一侧面板隐藏(元素保持挂载,scrollTop 由记录/恢复机制保持)。 */
const hiddenPaneStyle: React.CSSProperties = {
  display: 'none',
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
