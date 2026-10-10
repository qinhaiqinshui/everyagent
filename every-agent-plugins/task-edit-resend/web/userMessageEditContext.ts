import React from 'react'

/**
 * 用户消息编辑上下文（从 every-agent-web 核心迁入 task-edit-resend 插件）。
 *
 * 缺省值为 null：宿主（旧挂载方式）显式提供时优先于插件自身的 store 实现；
 * 未提供时插件组件回退到 useEditResend 的模块级 store。
 */
export interface UserMessageEditContextValue {
  /** 当前正在编辑的消息 seq(字符串);为 null 表示未进入编辑模式。 */
  editingUserSeq: string | null
  /** 点击编辑按钮:回填输入框内容并进入编辑模式。 */
  onEditUserMessage: (seq: number | string, text: string, rawContent?: string) => void
  /** 取消编辑:清除编辑标记,不删除输入框内容。 */
  onCancelEditUserMessage: () => void
}

export const UserMessageEditContext = React.createContext<UserMessageEditContextValue | null>(null)
