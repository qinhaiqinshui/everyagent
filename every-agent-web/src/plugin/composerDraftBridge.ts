/**
 * 输入框草稿桥接上下文。
 *
 * 核心（TaskChat）在任务页提供草稿写能力，插件（如编辑重发）经此回填输入框，
 * 无需核心感知具体业务。未提供（null）时插件应自行降级（如隐藏入口）。
 */
import React from 'react'

export interface ComposerDraftBridgeValue {
  /** 把纯文本追加到当前草稿末尾（清空胶囊 token，回到普通输入态）。 */
  appendText: (text: string) => void
  /** 用 rawContent（可能含 [[[[...]]]] opaque token 串）替换整个草稿，
   *  InlineComposer 重建 DOM 时会自动解析 opaque token 还原胶囊。 */
  setRawContent: (rawContent: string) => void
  /** 在输入框光标处插入一段草稿内容（opaque token 串或纯文本）并聚焦；
   *  编辑器尚未挂载时退化为末尾追加。供「插入到光标位置」类宿主动作使用。 */
  insertAtCaret: (rawContent: string) => void
}

export const ComposerDraftBridgeContext = React.createContext<ComposerDraftBridgeValue | null>(null)
