/**
 * 编辑器选区快照采集工具（宿主侧，供「编辑器内容区右键菜单」扩展点消费者使用）。
 *
 * 采集时点：编辑器容器上收到 contextmenu 时，同步读取 `window.getSelection()`，
 * 结合 Range 与编辑器 DOM 换算出 `{ text, startLine, endLine, charCount, lineCount }`。
 *
 * 行号解析依赖 CodeMirror DOM / 视图：优先走 `EditorView.findFromDOM` + `posAtDOM`
 * （精确、不受行 DOM 是否在视口内影响），兜底走 `.cm-content .cm-line` 与
 * `.cm-lineNumbers .cm-gutterElement` 的下标对齐。**将来换编辑器则行号降级为
 * null（功能降级），引用仍带路径与选中原文，不受影响。**
 */

import { EditorView } from '@codemirror/view'
import type { EditorActionSelection } from '@everyagent/plugin-api'

/** 空选区快照（无选区 / 选区不在目标容器内时的统一返回值）。 */
export const EMPTY_EDITOR_SELECTION: EditorActionSelection = {
  text: '',
  startLine: null,
  endLine: null,
  charCount: 0,
  lineCount: 0,
}

/**
 * 给定编辑器容器，采集当前选区快照（永不抛错；无选区/选区不在容器内一律返回空快照）。
 *
 * 保护闸：无选区、`selection.isCollapsed`、或选区端点不在 `root` 内
 * （`root.contains(range.startContainer/endContainer)` 任一不成立）→ 返回空快照。
 * 这同时充当「非本编辑器 / 非文本编辑器」的保护：pdf iframe、图片编辑器里拿不到
 * 本容器内的选区，自然不会接管。
 */
export function readEditorSelection(root: HTMLElement | null): EditorActionSelection {
  if (!root) return EMPTY_EDITOR_SELECTION

  const selection = window.getSelection()
  if (!selection || selection.isCollapsed || selection.rangeCount === 0) {
    return EMPTY_EDITOR_SELECTION
  }

  const text = selection.toString()
  const range = selection.getRangeAt(0)

  // 选区端点必须落在本容器内，否则视为「非本编辑器」不接管。
  if (!root.contains(range.startContainer) || !root.contains(range.endContainer)) {
    return EMPTY_EDITOR_SELECTION
  }

  const { startLine, endLine } = readLineRange(root, range)
  const charCount = text.length
  // 两条行号都拿到时按行号差计算；否则退化为按文本换行计行（不依赖 DOM/视图）。
  const lineCount = startLine != null && endLine != null
    ? endLine - startLine + 1
    : text.split(/\r\n|\r|\n/).length

  return { text, startLine, endLine, charCount, lineCount }
}

/** 解析选区的起止行号（1-based）；两条路径都拿不到时返回 null。 */
function readLineRange(root: HTMLElement, range: Range): { startLine: number | null; endLine: number | null } {
  // 优先走 CodeMirror 视图：精确、不受行 DOM 是否在视口内影响。
  try {
    const cmDom = root.querySelector('.cm-editor')
    if (cmDom) {
      const view = EditorView.findFromDOM(cmDom as HTMLElement)
      if (view) {
        const fromPos = view.posAtDOM(range.startContainer, range.startOffset)
        const toPos = view.posAtDOM(range.endContainer, range.endOffset)
        const startLine = view.state.doc.lineAt(fromPos).number
        // 取 max(from, to) 得结束行（防御性：DOM Range 本已规范化，from 通常不大于 to）。
        const endLine = view.state.doc.lineAt(Math.max(fromPos, toPos)).number
        return { startLine, endLine }
      }
    }
  } catch {
    // 落空 → 继续走 DOM 兜底。
  }

  // 兜底：行号槽 DOM 对齐（行元素与 gutter 元素一一对应时按下标取行号文本）。
  try {
    const lines = root.querySelectorAll('.cm-content .cm-line')
    const gutters = root.querySelectorAll('.cm-lineNumbers .cm-gutterElement')
    if (lines.length > 0 && lines.length === gutters.length) {
      let firstIndex = -1
      let lastIndex = -1
      lines.forEach((lineEl, index) => {
        if (range.intersectsNode(lineEl)) {
          if (firstIndex === -1) firstIndex = index
          lastIndex = index
        }
      })
      if (firstIndex !== -1 && lastIndex !== -1) {
        const startLine = parseLineNumber(gutters[firstIndex])
        const endLine = parseLineNumber(gutters[lastIndex])
        if (startLine != null && endLine != null) {
          return { startLine, endLine }
        }
      }
    }
  } catch {
    // 落空 → 行号降级为 null。
  }

  return { startLine: null, endLine: null }
}

/** 从行号槽元素文本解析行号；解析不出返回 null。 */
function parseLineNumber(element: Element | undefined): number | null {
  if (!element) return null
  const parsed = Number.parseInt(element.textContent ?? '', 10)
  return Number.isFinite(parsed) ? parsed : null
}