/**
 * Markdown 预览 ↔ 源码编辑器滚动位置换算工具。
 *
 * 预览侧依据块级元素上的 `data-source-line` / `data-source-line-end` 标注
 * (1-based 源行号,由 sharedMarkdownRenderer 注入,与 CodeMirror 行号同基准),
 * 实现「预览滚动位置 ↔ 源码行号」双向换算,支撑:
 * - 只读预览 → 编辑模式切换时按顶部行号迁移滚动位置;
 * - 双栏(split)模式下两侧滚动跟随同步(VSCode 式)。
 *
 * 块内(如长代码块/长列表中部)按高度比例插值,行号可为小数;调用方自行取整。
 */
import { EditorView } from '@codemirror/view'

const SOURCE_LINE_ATTR = 'data-source-line'
const SOURCE_LINE_END_ATTR = 'data-source-line-end'

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}

function collectSourceLineElements(scrollEl: HTMLElement): HTMLElement[] {
  return Array.from(scrollEl.querySelectorAll<HTMLElement>(`[${SOURCE_LINE_ATTR}]`))
}

function readStartLine(element: HTMLElement): number | null {
  const value = Number(element.dataset.sourceLine)
  return Number.isFinite(value) && value > 0 ? value : null
}

function readEndLine(element: HTMLElement, start: number): number {
  const value = Number(element.dataset.sourceLineEnd)
  return Number.isFinite(value) && value >= start ? value : start
}

/**
 * 预览侧:视口顶部对应的源行号(1-based,块内按高度比例插值,可为小数)。
 * 取首个(部分)越过视口顶的标注块;无标注块(如内容为空/尚未渲染)返回 null。
 */
export function getPreviewTopSourceLine(scrollEl: HTMLElement | null): number | null {
  if (!scrollEl) return null
  const elements = collectSourceLineElements(scrollEl)
  if (elements.length === 0) return null
  const viewportTop = scrollEl.getBoundingClientRect().top
  for (const element of elements) {
    const rect = element.getBoundingClientRect()
    if (rect.bottom <= viewportTop + 1) continue
    const start = readStartLine(element)
    if (start == null) continue
    const end = readEndLine(element, start)
    if (end <= start || rect.height <= 0) return start
    const inside = clamp((viewportTop - rect.top) / rect.height, 0, 0.999)
    return start + inside * (end - start)
  }
  return null
}

/**
 * 预览侧:滚动到指定源行号(1-based;小数行号在块内按比例定位)。
 * 目标块 = 起始行 <= 指定行号的标注块中起始行最大者;块内按行比例推进,
 * 结果 clamp 到合法滚动范围。
 */
export function scrollPreviewToLine(scrollEl: HTMLElement | null, line: number): void {
  if (!scrollEl || !Number.isFinite(line)) return
  const elements = collectSourceLineElements(scrollEl)
  if (elements.length === 0) return

  let target: HTMLElement | null = null
  let targetStart = -1
  for (const element of elements) {
    const start = readStartLine(element)
    if (start == null || start > line) continue
    // 起始行相同的嵌套块(如 ul 与其首个 li)取文档序更后者(更内层,定位更准)。
    if (start >= targetStart) {
      target = element
      targetStart = start
    }
  }
  if (!target) {
    target = elements[0]
    targetStart = readStartLine(target) ?? 1
  }

  const end = readEndLine(target, targetStart)
  // 目标块顶对齐视口顶,再按块内行比例推进(分母 +1 让末行不滚出块底)。
  const containerTop = scrollEl.getBoundingClientRect().top
  let nextScrollTop = target.getBoundingClientRect().top - containerTop + scrollEl.scrollTop
  if (end > targetStart && line > targetStart) {
    const progress = clamp((line - targetStart) / (end - targetStart + 1), 0, 1)
    nextScrollTop += progress * target.offsetHeight
  }
  const maxScrollTop = scrollEl.scrollHeight - scrollEl.clientHeight
  scrollEl.scrollTop = clamp(nextScrollTop, 0, Math.max(0, maxScrollTop))
}

/** 编辑器侧:视口顶部对应的源行号(1-based 整数);无可用视图返回 null。 */
export function getEditorTopLine(view: EditorView | null): number | null {
  if (!view) return null
  const scroller = view.scrollDOM
  if (!scroller || scroller.scrollHeight <= scroller.clientHeight) {
    // 内容不足一屏(无可滚动)时视为首行,避免 scrollTop 恒 0 分支下的边界噪声。
    return 1
  }
  try {
    const block = view.lineBlockAtHeight(scroller.scrollTop + 1)
    return view.state.doc.lineAt(block.from).number
  } catch {
    return null
  }
}

/** 编辑器侧:滚动到指定源行号(1-based,四舍五入;越界 clamp 到文档范围)。 */
export function scrollEditorToLine(view: EditorView | null, line: number): void {
  if (!view || !Number.isFinite(line)) return
  const doc = view.state.doc
  if (doc.lines === 0) return
  const clamped = clamp(Math.round(line), 1, doc.lines)
  view.dispatch({
    effects: EditorView.scrollIntoView(doc.line(clamped).from, { y: 'start' }),
  })
}
