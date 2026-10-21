/**
 * 用户消息「复制原始内容」按钮（核心内置，经 ui.user_message_actions 扩展点渲染）。
 *
 * 渲染在每条用户消息气泡左侧的动作区容器（.nagent-msg__actions）内，
 * 与 task-edit-resend 的编辑按钮并列；按钮显隐由该容器统一控制
 * （桌面端 hover 消息区显示；移动端消息获得焦点显示、失去焦点隐藏），
 * 本组件只管自身交互：
 * - 点击把该消息的 rawContent（含 `[[[[...]]]]` opaque token 串）写入剪贴板；
 *   粘贴回输入框时 InlineComposer 的 handlePaste 会用 splitComposerRawContent
 *   解析 opaque 串并还原成胶囊（见 InlineComposer §paste 文本分支注释），
 *   从而「复制 → 粘贴」可正确识别胶囊数据；
 * - 缺 rawContent 时降级复制纯文本 content；
 * - 成功后短暂切「已复制」（1.5s 复原）；复制失败不误报「已复制」。
 *
 * 与 RoundCopyPanel（轮末复制）同源：copyText 全站唯一剪贴板工具，失败抛错。
 */
import React from 'react'
import { CopyIcon, CheckIcon } from '../shared/AppGlyphs'
import { copyText } from '@/utils/clipboard'
import { antdMessage } from '@/utils/appAntdBridge'
import type { UserMessageActionProps } from '@/plugin/types'

export default function CopyRawContentButton({ content, rawContent }: UserMessageActionProps): React.ReactNode {
  const [copied, setCopied] = React.useState(false)
  const timerRef = React.useRef<number | undefined>(undefined)
  // 组件卸载时清理待触发的「已复制」回退计时器，避免对已卸载组件 setState。
  React.useEffect(() => () => window.clearTimeout(timerRef.current), [])

  const text = rawContent && rawContent.length > 0 ? rawContent : (content ?? '')

  const handleCopy = (e: React.MouseEvent) => {
    e.stopPropagation()
    if (!text) return
    copyText(text)
      .then(() => {
        setCopied(true)
        window.clearTimeout(timerRef.current)
        timerRef.current = window.setTimeout(() => setCopied(false), 1500)
      })
      .catch(() => {
        antdMessage('error', '复制失败：浏览器拒绝了剪贴板访问')
      })
  }

  return (
    <button
      type="button"
      className={`nagent-msg__copy-btn${copied ? ' nagent-msg__copy-btn--done' : ''}`}
      title={copied ? '已复制' : '复制原始内容（含胶囊，可粘贴还原）'}
      aria-label={copied ? '已复制' : '复制原始内容'}
      onClick={handleCopy}
    >
      {copied ? <CheckIcon size={13} /> : <CopyIcon size={13} />}
    </button>
  )
}
