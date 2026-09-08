import React from 'react'

const FIND_MARK_ATTR = 'data-find-mark'
/** 单次高亮最多渲染的 <mark> 数，避免巨型文件撑爆 DOM。 */
const MAX_MARKS = 2000

/** 命中高亮基础样式（普通命中）。 */
const MARK_BASE_STYLE = 'background: var(--accent-amber-dim); color: inherit; border-radius: 2px; padding: 0;'
/** 当前命中高亮样式（覆盖基础样式，强调可读性）。 */
const MARK_ACTIVE_STYLE = 'background: var(--accent-amber); color: #1a1206;'

/** 不应被高亮的元素标签（文本不在其子树文本节点中，或高亮无意义）。 */
const SKIP_TAGS = new Set(['SCRIPT', 'STYLE', 'NOSCRIPT', 'TEXTAREA', 'SELECT', 'MARK'])

/**
 * 收集容器内所有可高亮的文本节点。
 * 跳过 <script>/<style>/<textarea>/我们自己的 <mark>，
 * 以及 aria-hidden 子树（LineNumberedSourceView 的隐藏测量节点 aria-hidden="true"，
 * 高亮它无意义且会造成视觉重复）。
 *
 * 行号槽位（gutter）每个数字所在的 div 带 `user-select: none` 内联样式，
 * 据此把它识别为非正文、跳过，避免搜索「1」时把行号一起高亮。
 */
function collectTextNodes(root: HTMLElement): Text[] {
  const result: Text[] = []
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      const parent = node.parentElement
      if (!parent) return NodeFilter.FILTER_REJECT
      if (SKIP_TAGS.has(parent.tagName)) return NodeFilter.FILTER_REJECT
      // 跳过 aria-hidden 子树（测量镜像节点等）。
      if (parent.closest('[aria-hidden="true"]')) return NodeFilter.FILTER_REJECT
      // 跳过可编辑态高亮叠层：它的 <mark> 由 React 直接渲染，外层不应再注入/包裹，避免重复高亮。
      if (parent.closest('[data-find-overlay]')) return NodeFilter.FILTER_REJECT
      // 跳过行号槽位等 user-select:none 的装饰性文本。
      if (parent.style.userSelect === 'none') return NodeFilter.FILTER_REJECT
      if (!node.nodeValue || !node.nodeValue.length) return NodeFilter.FILTER_REJECT
      return NodeFilter.FILTER_ACCEPT
    },
  })
  let current = walker.nextNode()
  while (current) {
    result.push(current as Text)
    current = walker.nextNode()
  }
  return result
}

/**
 * 把命中片段包裹进 <mark>，返回创建的 mark 列表（文档顺序）。
 * regex 必须带 g 标志；超过 MAX_MARKS 后停止以保护 DOM 体积。
 */
function applyMarks(root: HTMLElement, regex: RegExp): HTMLElement[] {
  const textNodes = collectTextNodes(root)
  let produced = 0
  for (const textNode of textNodes) {
    if (produced >= MAX_MARKS) break
    const text = textNode.nodeValue ?? ''
    regex.lastIndex = 0
    const first = regex.exec(text)
    if (!first) continue

    const fragments: Node[] = []
    let cursor = 0
    let match: RegExpExecArray | null = first
    while (match) {
      if (produced >= MAX_MARKS) break
      const start = match.index
      if (start > cursor) {
        fragments.push(document.createTextNode(text.slice(cursor, start)))
      }
      const mark = document.createElement('mark')
      mark.setAttribute(FIND_MARK_ATTR, '')
      mark.setAttribute('style', MARK_BASE_STYLE)
      mark.textContent = match[0]
      fragments.push(mark)
      produced += 1
      cursor = start + match[0].length
      // 防止零宽匹配死循环。
      if (match.index === regex.lastIndex) {
        regex.lastIndex += 1
      }
      match = regex.exec(text)
    }
    if (cursor < text.length) {
      fragments.push(document.createTextNode(text.slice(cursor)))
    }
    textNode.replaceWith(...fragments)
  }
  return Array.from(root.querySelectorAll(`mark[${FIND_MARK_ATTR}]`)) as HTMLElement[]
}

/**
 * 移除本钩子注入的所有 <mark>，还原纯文本。
 * 用 normalize() 合并相邻文本节点，保证下次高亮（TreeWalker）再次拿到完整文本。
 */
function removeMarks(root: HTMLElement) {
  const marks = root.querySelectorAll(`mark[${FIND_MARK_ATTR}]`)
  if (marks.length === 0) return
  marks.forEach((mark) => {
    mark.replaceWith(document.createTextNode(mark.textContent ?? ''))
  })
  root.normalize()
}

export type UseHighlightMatchesOptions = {
  /** 查找正则（带 g 标志）；为 null 时不高亮。 */
  regex: RegExp | null
  /** 当前命中序号（0-based），用于标识「当前」高亮并滚动入视。 */
  activeIndex: number
  /** 是否启用高亮（查找条关闭时为 false）。 */
  enabled: boolean
}

/**
 * 在容器内对渲染出的文本施加 / 清除 <mark> 高亮，并把当前命中滚动入视。
 *
 * 适用任何渲染形态：纯文本 <pre>、Markdown 渲染 HTML 均可；
 * <textarea> 内无文本节点故不会产生高亮（编辑态改由 FileTabPage 做行级滚动）。
 *
 * 通过 MutationObserver 跟随编辑器重渲染自动重绘高亮，
 * 避免内容 / 视图切换后高亮丢失。
 */
export function useHighlightMatches(
  containerRef: React.RefObject<HTMLElement>,
  options: UseHighlightMatchesOptions,
): void {
  const { regex, activeIndex, enabled } = options

  React.useLayoutEffect(() => {
    const root = containerRef.current
    if (!root) return
    if (!enabled || !regex) {
      removeMarks(root)
      return
    }

    let rafId = 0
    let disposed = false

    const observer = new MutationObserver(() => {
      cancelAnimationFrame(rafId)
      rafId = requestAnimationFrame(draw)
    })

    const draw = () => {
      if (disposed) return
      // 改写 DOM 期间暂停 observer，避免我们自己的变更触发回流。
      observer.disconnect()
      removeMarks(root)
      const fresh = new RegExp(regex.source, regex.flags.includes('g') ? regex.flags : `${regex.flags}g`)
      const marks = applyMarks(root, fresh)
      if (marks.length > 0) {
        const idx = ((activeIndex % marks.length) + marks.length) % marks.length
        const active = marks[idx]
        active.setAttribute('style', MARK_ACTIVE_STYLE)
        active.scrollIntoView({ block: 'center' })
      }
      observer.observe(root, { childList: true, subtree: true, characterData: true })
    }

    rafId = requestAnimationFrame(draw)

    return () => {
      disposed = true
      cancelAnimationFrame(rafId)
      observer.disconnect()
      removeMarks(root)
    }
  }, [containerRef, regex, activeIndex, enabled])
}
