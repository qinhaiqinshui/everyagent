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
}

export const ComposerDraftBridgeContext = React.createContext<ComposerDraftBridgeValue | null>(null)
