/**
 * 用户消息编辑按钮（从核心 AgentMessageThread.tsx 迁入 task-edit-resend 插件）。
 *
 * 经 `ui.user_message_actions` 扩展点渲染在每条用户消息气泡旁：
 * - 桌面端：hover 消息区域时显示（CSS 控制）；
 * - 移动端：点击用户消息时显示（JS 切换 revealed 态）；
 * - 点击编辑按钮 → 经核心草稿桥（ComposerDraftBridgeContext）把原消息内容追加到输入框末尾，
 *   并记录编辑目标 seq（useEditResend store）；再点 → 取消编辑（不清输入框）；
 * - "重新发送会删除此消息之后的所有 AI 回复和过程内容"提示仅在进入编辑模式后才显示；
 * - 缺草稿桥（非任务页宿主）或缺 taskId 时不渲染。
 */
import React from 'react'
import type { UserMessageActionProps } from '@everyagent/plugin-api'
import { getPluginContext } from './pluginRuntime'
import { UserMessageEditContext } from './userMessageEditContext'
import { useEditResend } from './useEditResend'

export default function EditMessageButton({ taskId, seq, content, rawContent }: UserMessageActionProps): React.ReactNode {
  const ctx = getPluginContext()
  // 宿主显式提供编辑上下文（旧挂载方式）时优先；缺省(null)走插件自身 store。
  const override = React.useContext(UserMessageEditContext)
  const { editTarget, startEdit, cancelEdit } = useEditResend(taskId)
  // 移动端：点击用户消息时切换 revealed 态来显示/隐藏编辑按钮。
  const [revealed, setRevealed] = React.useState(false)
  const buttonRef = React.useRef<HTMLButtonElement | null>(null)
  React.useEffect(() => {
    const button = buttonRef.current
    const container = button?.closest('.nagent-msg--user')
    if (!button || !container) return
    const onContainerClick = () => setRevealed((prev) => !prev)
    const onContextMenu = (e: Event) => e.preventDefault()
    container.addEventListener('click', onContainerClick)
    container.addEventListener('contextmenu', onContextMenu)
    return () => {
      container.removeEventListener('click', onContainerClick)
      container.removeEventListener('contextmenu', onContextMenu)
    }
  }, [])

  if (!taskId) return null

  const isEditing = override ? override.editingUserSeq === seq : editTarget?.seq === seq

  const handleClick = (e: React.MouseEvent) => {
    e.stopPropagation()
    if (override) {
      if (isEditing) {
        override.onCancelEditUserMessage()
      } else {
        override.onEditUserMessage(seq, content ?? '', rawContent)
      }
      return
    }
    if (isEditing) {
      cancelEdit()
    } else {
      // 优先用 rawContent（含 [[[[...]]]] opaque token 串）回填，InlineComposer 重建
      // DOM 时会自动解析还原胶囊；缺 rawContent 时降级为纯文本 content。
      if (rawContent && rawContent.length > 0) {
        ctx.ui.setComposerRawContent(rawContent)
      } else {
        ctx.ui.appendComposerText(content ?? '')
      }
      startEdit(seq)
    }
  }

  return (
    <button
      ref={buttonRef}
      type="button"
      className={`nagent-msg__edit-btn${isEditing ? ' nagent-msg__edit-btn--active' : ''}${revealed ? ' nagent-msg__edit-btn--revealed' : ''}`}
      title={isEditing ? '取消编辑' : '编辑并重新发送'}
      aria-label={isEditing ? '取消编辑' : '编辑并重新发送'}
      onClick={handleClick}
    >
      {isEditing ? <CancelEditIcon size={13} /> : <EditIcon size={13} />}
      {isEditing && (
        <span className="nagent-msg__edit-tooltip">重新发送会删除此消息之后的所有 AI 回复和过程内容</span>
      )}
    </button>
  )
}

/** 编辑图标(铅笔形 SVG)。 */
function EditIcon({ size = 14 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <path
        d="M11.5 2.5l2 2L5.5 12.5l-2.5.5.5-2.5L11.5 2.5z"
        stroke="currentColor"
        strokeWidth="1.2"
        strokeLinejoin="round"
      />
    </svg>
  )
}

/** 取消编辑图标(叉形 SVG)。 */
function CancelEditIcon({ size = 14 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" aria-hidden="true">
      <path
        d="M4 4l8 8M12 4l-8 8"
        stroke="currentColor"
        strokeWidth="1.4"
        strokeLinecap="round"
      />
    </svg>
  )
}
