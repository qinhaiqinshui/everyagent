/**
 * CodeMirror 6 主题扩展：映射到项目 CSS 变量，明暗主题自动跟随。
 *
 * 颜色与 codeBlock.css 中 Prism token 配色保持一致（.prism-tokens scope），
 * 确保 Markdown 代码块与 CodeMirror 编辑器视觉统一。
 */
import { EditorView } from '@codemirror/view'
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language'
import { tags as t } from '@lezer/highlight'

/** 编辑器外观主题（背景/前景/字体/行号/选区/折叠槽等），全部映射到 CSS 变量。 */
const editorTheme = EditorView.theme({
  '&': {
    backgroundColor: 'var(--bg-secondary)',
    color: 'var(--text-primary)',
    height: '100%',
    fontSize: '14px',
  },
  '.cm-content': {
    fontFamily: 'var(--font-mono)',
    caretColor: 'var(--accent-blue)',
    padding: '20px 0',
  },
  '.cm-gutters': {
    backgroundColor: 'var(--bg-secondary)',
    color: 'var(--text-muted)',
    border: 'none',
    borderRight: '1px solid var(--border-light)',
  },
  '.cm-gutter.cm-lineNumbers': {
    minWidth: '3.5ch',
  },
  '.cm-lineNumbers .cm-gutterElement': {
    padding: '0 12px 0 0',
    fontFamily: 'var(--font-mono)',
    fontSize: '14px',
    opacity: 0.5,
    userSelect: 'none',
  },
  '.cm-activeLineGutter': {
    backgroundColor: 'transparent',
    color: 'var(--accent-blue)',
    fontWeight: 600,
  },
  '.cm-activeLine': {
    backgroundColor: 'color-mix(in srgb, var(--bg-tertiary) 40%, transparent)',
  },
  '.cm-selectionBackground, ::selection': {
    backgroundColor: 'var(--accent-blue-dim)',
  },
  '&.cm-focused .cm-selectionBackground, &.cm-focused .cm-selectionLayer .cm-selectionBackground': {
    backgroundColor: 'var(--accent-blue-dim)',
  },
  '.cm-cursor, .cm-dropCursor': {
    borderLeftColor: 'var(--accent-blue)',
    borderLeftWidth: '2px',
  },
  '.cm-panels': {
    backgroundColor: 'var(--bg-tertiary)',
    color: 'var(--text-primary)',
    borderTop: '1px solid var(--border-light)',
  },
  '.cm-panels input': {
    backgroundColor: 'var(--bg-primary)',
    color: 'var(--text-primary)',
    border: '1px solid var(--border)',
    borderRadius: 'var(--radius-sm, 4px)',
    padding: '2px 6px',
    fontSize: '13px',
  },
  '.cm-panels button': {
    backgroundColor: 'var(--bg-secondary)',
    color: 'var(--text-secondary)',
    border: '1px solid var(--border)',
    borderRadius: 'var(--radius-sm, 4px)',
    padding: '2px 8px',
    cursor: 'pointer',
    fontSize: '13px',
  },
  '.cm-panels button[name="close"]': {
    border: 'none',
    background: 'transparent',
  },
  '.cm-searchMatch': {
    backgroundColor: 'var(--accent-amber-dim)',
    borderRadius: '2px',
  },
  '.cm-searchMatch.cm-searchMatch-selected': {
    backgroundColor: 'var(--accent-amber)',
  },
  '.cm-foldPlaceholder': {
    backgroundColor: 'var(--bg-tertiary)',
    border: '1px solid var(--border-light)',
    color: 'var(--text-muted)',
    borderRadius: '3px',
    padding: '0 4px',
    fontSize: '12px',
  },
  '.cm-foldGutter .cm-gutterElement': {
    cursor: 'pointer',
    color: 'var(--text-muted)',
    padding: '0 4px',
  },
  '.cm-foldGutter .cm-gutterElement:hover': {
    color: 'var(--accent-blue)',
  },
  '.cm-matchingBracket': {
    backgroundColor: 'var(--accent-blue-dim)',
    color: 'var(--accent-blue)',
  },
  '.cm-tooltip': {
    backgroundColor: 'var(--bg-tertiary)',
    border: '1px solid var(--border)',
    borderRadius: 'var(--radius-sm, 4px)',
  },
  '.cm-tooltip-autocomplete ul li[aria-selected]': {
    backgroundColor: 'var(--accent-blue-dim)',
    color: 'var(--accent-blue)',
  },
  '&.cm-editor': {
    height: '100%',
  },
  '&.cm-editor.cm-focused': {
    outline: 'none',
  },
  '.cm-scroller': {
    overflow: 'auto',
  },
}, { dark: true })

/**
 * 语法高亮配色——与 codeBlock.css 中 .prism-tokens .token.* 一一对应。
 * 通过 HighlightStyle 的 tag 精确匹配 Lezer 语法树节点。
 */
const highlightStyle = HighlightStyle.define([
  // comment / processingInstruction (prolog/cdata/doctype 的 Lezer 等价物)
  { tag: [t.comment, t.processingInstruction], color: 'var(--text-muted)' },
  // keyword
  { tag: [t.keyword, t.controlKeyword, t.definitionKeyword, t.moduleKeyword, t.operatorKeyword], color: 'var(--accent-purple)' },
  // string / char / attr-value / regex / url
  { tag: [t.string, t.character, t.attributeValue, t.regexp, t.url, t.escape], color: 'var(--accent-green)' },
  // number / boolean / literal / unit
  { tag: [t.number, t.bool, t.literal, t.unit], color: 'var(--accent-amber)' },
  // function / attr-name / property
  { tag: [t.function(t.variableName), t.attributeName, t.propertyName], color: 'var(--accent-blue)' },
  // typeName / className / namespace / standard
  { tag: [t.typeName, t.className, t.namespace, t.standard(t.name)], color: 'var(--accent-cyan)' },
  // operator / punctuation / separator
  { tag: [t.operator, t.derefOperator, t.punctuation, t.separator], color: 'var(--text-secondary)' },
  // variable → 默认 code-text
  { tag: [t.variableName], color: 'var(--code-text)' },
  // inserted
  { tag: t.inserted, color: 'var(--accent-green)' },
  // deleted
  { tag: t.deleted, color: 'var(--accent-red)' },
  // meta
  { tag: [t.meta, t.documentMeta, t.annotation], color: 'var(--text-muted)' },
  // heading
  { tag: t.heading, color: 'var(--accent-blue)', fontWeight: 'bold' },
  // link
  { tag: t.link, color: 'var(--accent-blue)' },
  // emphasized
  { tag: t.emphasis, fontStyle: 'italic' },
  // strong
  { tag: t.strong, fontWeight: 'bold' },
])

/** 导出完整主题扩展数组，CodeMirrorEditor 和 MarkdownSplitEditor 共用。 */
export const cmTheme = [
  editorTheme,
  syntaxHighlighting(highlightStyle),
]
