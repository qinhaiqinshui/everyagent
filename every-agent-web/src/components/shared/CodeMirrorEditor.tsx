/**
 * CodeMirror 6 React 封装组件。
 *
 * 统一承担：语法高亮、行号、折行、代码折叠、查找替换（@codemirror/search）、
 * 括号匹配、自动补全、行定位（scrollIntoView）等能力。
 * 只读态与可编辑态共用同一组件，通过 `editable` 切换。
 *
 * 被引用方：UniversalFileEditor、MarkdownSplitEditor。
 */
import React from 'react'
import { EditorState, Compartment } from '@codemirror/state'
import { EditorView, lineNumbers, highlightActiveLineGutter, drawSelection, dropCursor, crosshairCursor, rectangularSelection, highlightSpecialChars, highlightActiveLine } from '@codemirror/view'
import { bracketMatching, indentOnInput, foldGutter, indentUnit } from '@codemirror/language'
import { history } from '@codemirror/commands'
import { closeBrackets, closeBracketsKeymap } from '@codemirror/autocomplete'
import { highlightSelectionMatches, searchKeymap, search, openSearchPanel } from '@codemirror/search'
import { defaultKeymap, historyKeymap } from '@codemirror/commands'
import { completionKeymap } from '@codemirror/autocomplete'
import { foldKeymap } from '@codemirror/language'
import { keymap } from '@codemirror/view'
import { cmTheme } from './codemirrorTheme'

export type CodeMirrorEditorProps = {
  /** 文档内容。 */
  value: string
  /** 内容变更回调（仅可编辑态触发）。 */
  onChange?: (value: string) => void
  /** 是否可编辑（false = 只读）。 */
  editable: boolean
  /** 语法高亮语言扩展；null 表示纯文本。 */
  language?: import('@codemirror/language').LanguageSupport | import('@codemirror/state').Extension | null
  /** 自动换行。 */
  wrapLines?: boolean
  /** 要定位到的目标行号（1-based）。 */
  lineNumber?: number
  /** 行定位请求时间戳；变化时触发滚动到 lineNumber。 */
  lineLocateRequestedAt?: number
  /** 行定位完成后回调。 */
  onLineLocateApplied?: () => void
  /** 外层容器样式。 */
  style?: React.CSSProperties
  /** 外层容器 className。 */
  className?: string
  /** 滚动元素 ref 回调；返回 CodeMirror 内部 .cm-scroller 元素，供 ScrollEdgeToggleFab 等外部组件使用。 */
  scrollRef?: React.MutableRefObject<HTMLElement | null>
}

export default function CodeMirrorEditor({
  value,
  onChange,
  editable = true,
  language = null,
  wrapLines = true,
  lineNumber,
  lineLocateRequestedAt,
  onLineLocateApplied,
  style,
  className,
  scrollRef,
}: CodeMirrorEditorProps) {
  const hostRef = React.useRef<HTMLDivElement | null>(null)
  const viewRef = React.useRef<EditorView | null>(null)
  // Compartments 允许动态 reconfigure 而不重建 editor。
  const compartmentsRef = React.useRef({
    language: new Compartment(),
    editable: new Compartment(),
    wrapping: new Compartment(),
  })

  // 防止 CM → onChange → setValue → CM 回环。
  const isInternalChange = React.useRef(false)

  // ── 初始化 EditorView ──
  React.useEffect(() => {
    if (!hostRef.current) return

    const { language: langComp, editable: editComp, wrapping: wrapComp } = compartmentsRef.current

    const updateListener = EditorView.updateListener.of((update) => {
      if (update.docChanged && onChange) {
        isInternalChange.current = true
        onChange(update.state.doc.toString())
      }
    })

    const state = EditorState.create({
      doc: value,
      extensions: [
        lineNumbers(),
        highlightActiveLineGutter(),
        highlightSpecialChars(),
        history(),
        foldGutter(),
        drawSelection(),
        dropCursor(),
        EditorState.allowMultipleSelections.of(true),
        indentOnInput(),
        indentUnit.of('  '),
        bracketMatching(),
        closeBrackets(),
        highlightActiveLine(),
        highlightSelectionMatches(),
        rectangularSelection(),
        crosshairCursor(),
        search({ top: true }),
        keymap.of([
          ...defaultKeymap,
          ...closeBracketsKeymap,
          ...historyKeymap,
          ...completionKeymap,
          ...foldKeymap,
          ...searchKeymap,
        ]),
        // Ctrl+F 打开搜索面板
        keymap.of([{
          key: 'Mod-f',
          run: (view) => { openSearchPanel(view); return true },
        }]),
        updateListener,
        langComp.of(language ?? []),
        editComp.of(EditorState.readOnly.of(!editable)),
        wrapComp.of(wrapLines ? EditorView.lineWrapping : []),
        ...cmTheme,
      ],
    })

    const view = new EditorView({ state, parent: hostRef.current })
    viewRef.current = view
    // 暴露 .cm-scroller 给外部组件（如 ScrollEdgeToggleFab）。
    if (scrollRef) {
      scrollRef.current = view.scrollDOM
    }

    return () => {
      view.destroy()
      viewRef.current = null
      if (scrollRef) {
        scrollRef.current = null
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // ── 外部 value → CM 同步（仅当非内部变更时） ──
  React.useEffect(() => {
    if (isInternalChange.current) {
      isInternalChange.current = false
      return
    }
    const view = viewRef.current
    if (!view) return
    const current = view.state.doc.toString()
    if (current !== value) {
      view.dispatch({
        changes: { from: 0, to: current.length, insert: value || '' },
      })
    }
  }, [value])

  // ── language reconfigure ──
  React.useEffect(() => {
    const view = viewRef.current
    if (!view) return
    view.dispatch({
      effects: compartmentsRef.current.language.reconfigure(language ?? []),
    })
  }, [language])

  // ── editable reconfigure ──
  React.useEffect(() => {
    const view = viewRef.current
    if (!view) return
    view.dispatch({
      effects: compartmentsRef.current.editable.reconfigure(
        EditorState.readOnly.of(!editable),
      ),
    })
  }, [editable])

  // ── wrapLines reconfigure ──
  React.useEffect(() => {
    const view = viewRef.current
    if (!view) return
    view.dispatch({
      effects: compartmentsRef.current.wrapping.reconfigure(
        wrapLines ? EditorView.lineWrapping : [],
      ),
    })
  }, [wrapLines])

  // ── 行定位：lineLocateRequestedAt 变化时滚动到目标行 ──
  // 用双重 requestAnimationFrame 确保浏览器完成 layout 后再滚动：
  // 第一帧让 React 完成 DOM 提交，第二帧让浏览器完成 layout 计算，
  // 之后 CodeMirror 的 scrollIntoView 才能正确计算滚动位置。
  // 文档为空时不执行定位——首次打开文件时可能先以空内容挂载，文件读取完成
  // 后内容才就绪；此时不应消费 lineLocateRequestedAt（否则会清除定位请求，
  // 导致内容就绪后无法再次定位）。
  // ── 行定位：lineLocateRequestedAt 变化时滚动到目标行 ──
  // 用双重 requestAnimationFrame 确保浏览器完成 layout 后再滚动：
  // 第一帧让 React 完成 DOM 提交，第二帧让浏览器完成 layout 计算，
  // 之后 CodeMirror 的 scrollIntoView 才能正确计算滚动位置。
  // 文档为空时不执行定位——首次打开文件时可能先以空内容挂载，文件读取完成
  // 后内容才就绪；此时不应消费 lineLocateRequestedAt（否则会清除定位请求，
  // 导致内容就绪后无法再次定位）。
  // 依赖 value：当内容从空变为有值时重新触发定位。
  React.useEffect(() => {
    if (lineLocateRequestedAt == null || lineNumber == null) return
    const view = viewRef.current
    if (!view) return
    // 文档为空时跳过——内容尚未就绪，scrollIntoView 无意义且会错误消费定位请求。
    if (view.state.doc.length === 0) return

    let raf1 = 0
    let raf2 = 0

    raf1 = requestAnimationFrame(() => {
      raf2 = requestAnimationFrame(() => {
        const v = viewRef.current
        if (!v) return
        const doc = v.state.doc
        const line = Math.min(Math.max(1, lineNumber), doc.lines)
        const lineStart = doc.line(line).from

        v.dispatch({
          effects: EditorView.scrollIntoView(
            lineStart,
            { y: 'center' },
          ),
        })
        onLineLocateApplied?.()
      })
    })

    return () => {
      cancelAnimationFrame(raf1)
      cancelAnimationFrame(raf2)
    }
  }, [lineLocateRequestedAt, lineNumber, onLineLocateApplied, value])

  return (
    <div
      ref={hostRef}
      className={className}
      style={{
        flex: 1,
        minHeight: 0,
        minWidth: 0,
        overflow: 'hidden',
        ...style,
      }}
    />
  )
}
