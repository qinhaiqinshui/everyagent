import React from 'react'

/** 用户消息编辑上下文:跨组件层级传递编辑状态与回调,避免 prop drilling。 */
export interface UserMessageEditContextValue {
  /** 当前正在编辑的消息 seq(字符串);为 null 表示未进入编辑模式。 */
  editingUserSeq: string | null
  /** 点击编辑按钮:回填输入框内容并进入编辑模式。 */
  onEditUserMessage: (seq: number | string, text: string, rawContent?: string) => void
  /** 取消编辑:清除编辑标记,不删除输入框内容。 */
  onCancelEditUserMessage: () => void
}

const defaultValue: UserMessageEditContextValue = {
  editingUserSeq: null,
  onEditUserMessage: () => {},
  onCancelEditUserMessage: () => {},
}

export const UserMessageEditContext = React.createContext<UserMessageEditContextValue>(defaultValue)
