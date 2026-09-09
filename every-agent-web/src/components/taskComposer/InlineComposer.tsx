import React from 'react'
import type { ChatComposerToken } from '@/types'
import { splitComposerRawContent, parseOpaqueTokenText } from '@/composerToken/composerOpaqueToken'
import { createSnowflakeId } from '@/utils/snowflakeId'
import { openSlashItemDetail } from './SlashItemDetailPopover'

/**
 * 内联富文本输入框。
 *
 * 用 `contentEditable` 的 div 取代原生 `<textarea>`，使命令 / 技能 / 文件胶囊
 * 能渲染在触发位置的光标处（而不是输入框顶部的独立胶囊条）。
 *
 * 设计要点（IME 安全）：
 * - 仅在「外部 rawContent 变化且与上次自上报内容不同」时才用 `innerHTML` 重建 DOM；
 *   用户内部输入只改 DOM 并就地序列化，绝不整段覆盖，避免中文输入法合成被打断、光标丢失。
 * - 胶囊用零宽空格（U+200B）与正文分隔，序列化时统一剔除零宽空格，保证 `rawContent`
 *   与「纯粹文本视图」一致，供 `/` `@` 触发检测与提交替换复用。
 * - 序列化读 `data-opaque`（构造时写入的 opaqueText），不依赖运行时 token 列表，
 *   因此即使刚插入的 token 尚未进入 React 状态也能正确还原。
 */

const ZW = '​'

/** 输入框变化上报（由内联编辑器触发）。 */
export interface InlineComposerChange {
  /** 含内联 opaque token 的原始内容（展示用）。 */
  rawContent: string
  /** 与 rawContent 同源的纯文本视图（用于 `/` `@` 触发检测）。 */
  text: string
  /** 本次变更刚插入的结构化 token（宿主据此追加到草稿 token 列表）。 */
  insertedToken?: ChatComposerToken
  /** 本次变更批量插入的结构化 token（粘贴含多个胶囊时使用；与 insertedToken 二选一）。 */
  insertedTokens?: ChatComposerToken[]
  /** 本次变更刚被移除（点击 ×）的 token id（宿主据此从草稿 token 列表删除）。 */
  removedTokenId?: string
}

/** 内联编辑器属性。 */
export interface InlineComposerProps {
  /** 当前 rawContent（含内联 opaque token）。 */
  rawContent: string
  /** 当前结构化 token 列表（用于点击文件胶囊打开、移除时定位 token 对象）。 */
  tokens: ChatComposerToken[]
  /** 占位文案。 */
  placeholder?: string
  /** 附加 className。 */
  className?: string
  /** 是否禁用编辑。 */
  disabled?: boolean
  /** 内容变化（输入 / 插入 / 移除胶囊）。 */
  onChange: (next: InlineComposerChange) => void
  /** 光标位置变化（输入 / 移动光标）。 */
  onCaretChange?: (text: string, caret: number) => void
  /** 键盘事件（透传给宿主处理 `/` `@` 导航）。 */
  onKeyDown?: (event: React.KeyboardEvent<HTMLDivElement>) => void
  /** 失焦。 */
  onBlur?: () => void
  /** 移除胶囊（点击 ✕）时上报被删的 token 对象（宿主据此触发 cancelTaskToken 等外部动作）。 */
  onRemoveToken?: (token: ChatComposerToken) => void
}

/** 命令式句柄（宿主通过 ref 直接插入胶囊或纯文本）。 */
export interface InlineComposerHandle {
  /** 聚焦输入框。 */
  focus: () => void
  /** 在光标处插入一个胶囊。 */
  insertToken: (token: ChatComposerToken, deleteBefore?: number) => void
  /** 在指定偏移处插入一个胶囊（交互面板回填用）。 */
  insertTokenAtOffset: (token: ChatComposerToken, offset: number, deleteBefore?: number) => void
  /** 在光标处插入一个 opaque token 串（从串自包含解析出 label/kind，无需外部 token 对象）。 */
  insertOpaqueToken: (opaqueText: string, deleteBefore?: number) => void
  /** 在光标处插入一段纯文本（如 `/` 菜单项 select 返回的 text）。 */
  insertText: (text: string, deleteBefore?: number) => void
}

// ── 纯函数工具 ───────────────────────────────────────────────────────────────

/** HTML 转义（文本节点内容用）。 */
function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

/** 属性值转义（额外转义引号）。 */
function escapeAttr(value: string): string {
  return escapeHtml(value).replace(/"/g, '&quot;')
}

/** 判断某 DOM 节点是否为胶囊元素。 */
function isChip(node: Node | null): node is HTMLElement {
  return node instanceof HTMLElement && node.classList.contains('nagent-inline-chip')
}

/** 统计不含零宽空格的字符数。 */
function countNonZw(value: string): number {
  let count = 0
  for (let i = 0; i < value.length; i++) {
    if (value[i] !== ZW) count++
  }
  return count
}

/** 把「不含零宽空格的偏移」映射回真实字符下标。 */
function mapNonZwToActual(text: string, nonZwPos: number): number {
  let count = 0
  for (let i = 0; i < text.length; i++) {
    if (text[i] !== ZW) {
      if (count === nonZwPos) return i
      count++
    }
  }
  return text.length
}

/** 生成单个胶囊的 HTML 字符串（统一样式，无逐类图标/配色）。 */
function chipHtml(token: ChatComposerToken): string {
  const label = escapeHtml(token.label)
  return (
    `<span class="nagent-inline-chip" contenteditable="false"` +
    ` data-token-id="${escapeAttr(token.id)}"` +
    ` data-opaque="${escapeAttr(token.opaqueText)}" data-label="${escapeAttr(token.label)}">` +
    `<span class="nagent-inline-chip__label">${label}</span>` +
    `<span class="nagent-inline-chip__close" contenteditable="false" data-close="1" aria-label="移除">✕</span>` +
    `</span>${ZW}`
  )
}

/** 由 rawContent + tokens 生成编辑器内部 HTML。 */
function buildEditorHtml(rawContent: string, tokens: ChatComposerToken[]): string {
  if (!rawContent) {
    return ''
  }
  const segments = splitComposerRawContent(rawContent, tokens)
  let html = ''
  for (const segment of segments) {
    if (segment.type === 'text') {
      html += escapeHtml(segment.value)
    } else if (segment.token) {
      html += chipHtml(segment.token)
    }
  }
  return html
}

/** 序列化编辑器 DOM 为 rawContent（剔除零宽空格，胶囊还原为 data-opaque）。 */
function serializeEditor(root: HTMLElement): string {
  let result = ''
  const walk = (node: Node) => {
    if (node.nodeType === Node.TEXT_NODE) {
      result += (node.textContent ?? '').split(ZW).join('')
      return
    }
    if (isChip(node)) {
      const opaque = node.dataset.opaque
      if (opaque) {
        result += opaque
      }
      return
    }
    for (const child of Array.from(node.childNodes)) {
      walk(child)
    }
  }
  walk(root)
  return result
}

/** 子节点下标。 */
function childIndex(node: Node): number {
  const parent = node.parentNode
  if (!parent) return 0
  const siblings = parent.childNodes
  for (let i = 0; i < siblings.length; i++) {
    if (siblings[i] === node) return i
  }
  return -1
}

/** 在「序列化偏移」处求 DOM 位置（container + offset）。 */
function domPositionAtOffset(
  root: HTMLElement,
  target: number,
): { container: Node; offset: number } | null {
  let acc = 0
  let result: { container: Node; offset: number } | null = null
  const walk = (node: Node) => {
    if (result) return
    if (node.nodeType === Node.TEXT_NODE) {
      const text = node.textContent ?? ''
      const len = countNonZw(text)
      if (acc + len >= target) {
        const pos = target - acc
        result = { container: node, offset: mapNonZwToActual(text, pos) }
        return
      }
      acc += len
      return
    }
    if (isChip(node)) {
      const len = node.dataset.opaque?.length ?? 0
      if (acc + len >= target) {
        const parent = node.parentNode
        if (parent) {
          const idx = childIndex(node)
          result = (target - acc > len / 2)
            ? { container: parent, offset: idx + 1 }
            : { container: parent, offset: idx }
        }
        return
      }
      acc += len
      return
    }
    for (const child of Array.from(node.childNodes)) {
      walk(child)
    }
  }
  walk(root)
  return result
}

/** 计算当前光标在「序列化文本」中的偏移。 */
function getCaretTextOffset(root: HTMLElement): number {
  const sel = window.getSelection()
  if (!sel || sel.rangeCount === 0) return 0
  const range = sel.getRangeAt(0)
  if (!root.contains(range.endContainer)) return 0
  let total = 0
  let found = false
  const walk = (node: Node) => {
    if (found) return
    if (node === range.endContainer) {
      if (node.nodeType === Node.TEXT_NODE) {
        total += countNonZw((node.textContent ?? '').slice(0, range.endOffset))
      } else {
        const kids = Array.from(node.childNodes)
        for (let i = 0; i < range.endOffset && !found; i++) {
          walk(kids[i])
        }
      }
      found = true
      return
    }
    if (node.nodeType === Node.TEXT_NODE) {
      total += countNonZw(node.textContent ?? '')
      return
    }
    if (isChip(node)) {
      total += node.dataset.opaque?.length ?? 0
      return
    }
    for (const child of Array.from(node.childNodes)) {
      walk(child)
    }
  }
  walk(root)
  return total
}

/** 把光标设置到「序列化偏移」处。 */
function setCaretAtTextOffset(root: HTMLElement, target: number): void {
  const pos = domPositionAtOffset(root, target)
  const sel = window.getSelection()
  if (!sel) return
  const range = document.createRange()
  if (!pos) {
    range.selectNodeContents(root)
    range.collapse(false)
  } else {
    try {
      range.setStart(pos.container, pos.offset)
    } catch {
      range.selectNodeContents(root)
      range.collapse(false)
    }
    range.collapse(true)
  }
  sel.removeAllRanges()
  sel.addRange(range)
}

/** 从光标处向前删除 count 个纯文本字符（不跨过胶囊）。 */
function deleteBackwardChars(root: HTMLElement, count: number): void {
  if (count <= 0) return
  const endOffset = getCaretTextOffset(root)
  const startOffset = Math.max(0, endOffset - count)
  const start = domPositionAtOffset(root, startOffset)
  const end = domPositionAtOffset(root, endOffset)
  if (!start || !end) return
  const range = document.createRange()
  try {
    range.setStart(start.container, start.offset)
    range.setEnd(end.container, end.offset)
  } catch {
    return
  }
  range.deleteContents()
  const caret = document.createRange()
  try {
    caret.setStart(start.container, start.offset)
  } catch {
    caret.selectNodeContents(root)
    caret.collapse(false)
  }
  caret.collapse(true)
  const sel = window.getSelection()
  sel?.removeAllRanges()
  sel?.addRange(caret)
}

/** 在光标处插入一个胶囊元素，并在其后补一个零宽空格便于继续输入。 */
function insertChipAtCaret(root: HTMLElement, token: ChatComposerToken): void {
  const sel = window.getSelection()
  // 用模板字符串构造与 React 同源的胶囊 DOM。
  const wrapper = document.createElement('div')
  wrapper.innerHTML = chipHtml(token)
  const built = wrapper.firstElementChild as HTMLElement | null
  if (!built) return
  const zw = document.createTextNode(ZW)

  if (sel && sel.rangeCount > 0 && root.contains(sel.getRangeAt(0).endContainer)) {
    const sr = sel.getRangeAt(0)
    sr.deleteContents()
    sr.insertNode(zw)
    sr.insertNode(built)
    const after = document.createRange()
    after.setStartAfter(zw)
    after.collapse(true)
    sel.removeAllRanges()
    sel.addRange(after)
  } else {
    root.appendChild(built)
    root.appendChild(zw)
    const after = document.createRange()
    after.setStartAfter(zw)
    after.collapse(true)
    sel?.removeAllRanges()
    sel?.addRange(after)
  }
}

/** 在光标处插入一段纯文本。 */
function insertTextAtCaret(root: HTMLElement, text: string): void {
  const sel = window.getSelection()
  if (sel && sel.rangeCount > 0 && root.contains(sel.getRangeAt(0).endContainer)) {
    const sr = sel.getRangeAt(0)
    sr.deleteContents()
    const node = document.createTextNode(text)
    sr.insertNode(node)
    const after = document.createRange()
    after.setStartAfter(node)
    after.collapse(true)
    sel.removeAllRanges()
    sel.addRange(after)
  } else {
    root.appendChild(document.createTextNode(text))
    const after = document.createRange()
    after.selectNodeContents(root)
    after.collapse(false)
    sel?.removeAllRanges()
    sel?.addRange(after)
  }
}

// ── 组件 ─────────────────────────────────────────────────────────────────────

const InlineComposer = React.forwardRef<InlineComposerHandle, InlineComposerProps>(function InlineComposer(
  props,
  ref,
) {
  const {
    rawContent,
    tokens,
    placeholder,
    className,
    disabled = false,
    onChange,
    onCaretChange,
    onKeyDown,
    onBlur,
    onRemoveToken,
  } = props

  const editorRef = React.useRef<HTMLDivElement | null>(null)
  const tokensRef = React.useRef<ChatComposerToken[]>(tokens)
  const lastEmittedRef = React.useRef<string>(rawContent)

  // 用 ref 持有最新的回调，避免命令式句柄（useImperativeHandle）闭包到旧 props。
  const onChangeRef = React.useRef(onChange)
  const onCaretChangeRef = React.useRef(onCaretChange)
  const onKeyDownRef = React.useRef(onKeyDown)
  const onBlurRef = React.useRef(onBlur)
  const onRemoveTokenRef = React.useRef(onRemoveToken)
  onChangeRef.current = onChange
  onCaretChangeRef.current = onCaretChange
  onKeyDownRef.current = onKeyDown
  onBlurRef.current = onBlur
  onRemoveTokenRef.current = onRemoveToken

  // tokens 引用同步（序列化主要靠 data-opaque，这里仅用于点击文件胶囊定位 token 对象）。
  React.useEffect(() => {
    tokensRef.current = tokens
  }, [tokens])

  // 外部 rawContent 变化且与上次自上报内容不同 → 重建 DOM（保护光标：相同则跳过重渲染）。
  React.useEffect(() => {
    const root = editorRef.current
    if (!root) return
    if (rawContent === lastEmittedRef.current) return
    root.innerHTML = buildEditorHtml(rawContent, tokens)
    lastEmittedRef.current = rawContent
    updateEmptyState(root, rawContent)
  }, [rawContent, tokens])

  const updateEmptyState = React.useCallback((root: HTMLElement, text: string) => {
    if (text.trim().length === 0) {
      root.classList.add('nagent-inline-editor--empty')
    } else {
      root.classList.remove('nagent-inline-editor--empty')
    }
  }, [])

  const emitFromDom = React.useCallback((options: {
    insertedToken?: ChatComposerToken
    insertedTokens?: ChatComposerToken[]
    removedTokenId?: string
  } = {}) => {
    const root = editorRef.current
    if (!root) return
    const serialized = serializeEditor(root)
    lastEmittedRef.current = serialized
    updateEmptyState(root, serialized)
    onChangeRef.current?.({
      rawContent: serialized,
      text: serialized,
      insertedToken: options.insertedToken,
      insertedTokens: options.insertedTokens,
      removedTokenId: options.removedTokenId,
    })
  }, [updateEmptyState])

  const reportCaret = React.useCallback(() => {
    const root = editorRef.current
    if (!root) return
    const text = serializeEditor(root)
    const caret = getCaretTextOffset(root)
    onCaretChangeRef.current?.(text, caret)
  }, [])

  const handleInput = React.useCallback(() => {
    emitFromDom()
    reportCaret()
  }, [emitFromDom, reportCaret])

  /**
   * 粘贴处理：识别剪贴板文本中的 `[[[[...]]]]` opaque 串并还原成胶囊，
   * 而不是让浏览器当成纯文本插入（否则输入框里会显示字面 `[[[[...]]]]`，
   * 且因 rawContent===lastEmittedRef 守卫，DOM 不会被重建成胶囊）。
   * 这样从用户消息复制按钮复制出的 rawContent 粘贴回来能继续识别 token。
   */
  const handlePaste = React.useCallback((event: React.ClipboardEvent<HTMLDivElement>) => {
    const root = editorRef.current
    if (!root) return
    event.preventDefault()
    const text = event.clipboardData.getData('text/plain') ?? ''
    if (!text) return
    root.focus()
    const segments = splitComposerRawContent(text, [])
    const inserted: ChatComposerToken[] = []
    for (const segment of segments) {
      if (segment.type === 'text') {
        if (segment.value) {
          insertTextAtCaret(root, segment.value)
        }
      } else if (segment.token) {
        // 粘贴进来的 token 重新生成 id，避免与已有 token 冲突。
        const token: ChatComposerToken = {
          id: createSnowflakeId('composer_token'),
          kind: segment.token.kind,
          label: segment.token.label,
          summary: segment.token.summary,
          opaqueText: segment.token.opaqueText,
        }
        insertChipAtCaret(root, token)
        inserted.push(token)
      }
    }
    // 单个 token 走 insertedToken（与命令式句柄同路径）；多个走 insertedTokens 批量上报。
    emitFromDom(
      inserted.length === 1
        ? { insertedToken: inserted[0] }
        : { insertedTokens: inserted },
    )
    reportCaret()
  }, [emitFromDom, reportCaret])

  const handleRootClick = React.useCallback((event: React.MouseEvent<HTMLDivElement>) => {
    const targetEl = event.target as HTMLElement
    const closeEl = targetEl.closest('[data-close]')
    if (closeEl) {
      const chip = closeEl.closest('.nagent-inline-chip') as HTMLElement | null
      const tokenId = chip?.dataset.tokenId
      if (chip && tokenId) {
        // 删除 DOM 前先解析被删 token 并上报（宿主据此触发 cancelTaskToken 等外部动作）。
        const opaque = chip.dataset.opaque
        const parsed = opaque ? parseOpaqueTokenText(opaque) : null
        if (parsed) {
          const removedToken: ChatComposerToken = {
            id: chip.dataset.tokenId ?? '',
            kind: parsed.kind,
            label: parsed.label ?? '',
            summary: parsed.summary,
            opaqueText: opaque ?? '',
          }
          onRemoveTokenRef.current?.(removedToken)
        }
        const zw = chip.nextSibling
        if (zw && zw.nodeType === Node.TEXT_NODE) zw.remove()
        chip.remove()
        emitFromDom({ removedTokenId: tokenId })
        reportCaret()
      }
      return
    }
    const chip = targetEl.closest('.nagent-inline-chip') as HTMLElement | null
    if (chip) {
      // 点击胶囊 → 弹详情（纯展示）。从 opaque 串自包含解析，无需外部 token 数组。
      const opaque = chip.dataset.opaque
      const parsed = opaque ? parseOpaqueTokenText(opaque) : null
      if (parsed) {
        const token: ChatComposerToken = {
          id: chip.dataset.tokenId ?? '',
          kind: parsed.kind,
          label: parsed.label ?? '',
          summary: parsed.summary,
          opaqueText: opaque ?? '',
        }
        openSlashItemDetail(chip, { token })
      }
    }
    reportCaret()
  }, [emitFromDom, reportCaret])

  React.useImperativeHandle(ref, () => ({
    focus: () => {
      editorRef.current?.focus()
    },
    insertToken: (token: ChatComposerToken, deleteBefore = 0) => {
      const root = editorRef.current
      if (!root) return
      root.focus()
      if (deleteBefore > 0) {
        deleteBackwardChars(root, deleteBefore)
      }
      insertChipAtCaret(root, token)
      emitFromDom({ insertedToken: token })
      reportCaret()
    },
    insertTokenAtOffset: (token: ChatComposerToken, offset: number, deleteBefore = 0) => {
      const root = editorRef.current
      if (!root) return
      root.focus()
      setCaretAtTextOffset(root, offset)
      if (deleteBefore > 0) {
        deleteBackwardChars(root, deleteBefore)
      }
      insertChipAtCaret(root, token)
      emitFromDom({ insertedToken: token })
      reportCaret()
    },
    insertOpaqueToken: (opaqueText: string, deleteBefore = 0) => {
      const root = editorRef.current
      if (!root) return
      const parsed = parseOpaqueTokenText(opaqueText)
      if (!parsed) return
      const token: ChatComposerToken = {
        id: createSnowflakeId('composer_token'),
        kind: parsed.kind,
        label: parsed.label ?? '',
        summary: parsed.summary,
        opaqueText,
      }
      root.focus()
      if (deleteBefore > 0) {
        deleteBackwardChars(root, deleteBefore)
      }
      insertChipAtCaret(root, token)
      emitFromDom({ insertedToken: token })
      reportCaret()
    },
    insertText: (text: string, deleteBefore = 0) => {
      const root = editorRef.current
      if (!root) return
      root.focus()
      if (deleteBefore > 0) {
        deleteBackwardChars(root, deleteBefore)
      }
      insertTextAtCaret(root, text)
      emitFromDom()
      reportCaret()
    },
  }), [emitFromDom, reportCaret])

  return (
    <div
      ref={editorRef}
      className={`nagent-inline-editor${className ? ` ${className}` : ''}`}
      contentEditable={!disabled}
      suppressContentEditableWarning
      role="textbox"
      aria-multiline="true"
      aria-label={placeholder}
      data-placeholder={placeholder ?? ''}
      enterKeyHint="enter"
      onInput={handleInput}
      onPaste={handlePaste}
      onKeyUp={reportCaret}
      onClick={handleRootClick}
      onKeyDown={(event) => onKeyDownRef.current?.(event)}
      onBlur={() => onBlurRef.current?.()}
    />
  )
})

export default InlineComposer
