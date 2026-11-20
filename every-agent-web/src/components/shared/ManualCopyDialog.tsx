import React from 'react'
import ConfirmDialog from './ConfirmDialog'
import Button from './ui/Button'
import { copyText, copyTextSync } from '@/utils/clipboard'

type ManualCopyDialogProps = {
  open: boolean
  /** 弹窗标题(如「复制任务路径」)。 */
  title?: string
  /** 需要复制的文本。 */
  text: string
  onClose: () => void
}

/**
 * 剪贴板写入失败时的「降落伞」弹窗:把文本摆进**已全选**的只读文本域,
 * 用户直接 Ctrl+C(⌘C)即可,不再只看到一句「复制失败」。
 *
 * 什么场合会出现:浏览器拒绝写剪贴板。典型是非安全上下文(局域网 http 访问)——
 * 此时 `navigator.clipboard` 不存在,只剩同步 `execCommand('copy')`,而它要求用户手势
 * 仍在;「先等 RPC 取数据再复制」的入口跨过了网络 await,手势已失效(copyText 抛
 * ClipboardWriteError)。本弹窗给两条出路:
 * - 文本域打开即全选,用户手按 Ctrl+C;
 * - 「重试复制」按钮本身就是一次**新的点击手势**,故其同步 copyTextSync 几乎必然成功。
 */
export default function ManualCopyDialog({ open, title = '手动复制', text, onClose }: ManualCopyDialogProps): React.ReactNode {
  const textareaRef = React.useRef<HTMLTextAreaElement | null>(null)
  const [copied, setCopied] = React.useState(false)

  React.useEffect(() => {
    if (!open) {
      setCopied(false)
      return
    }
    // 打开即全选:用户无需拖选,直接按键复制。
    const node = textareaRef.current
    if (!node) return
    node.focus()
    node.select()
  }, [open, text])

  const handleRetry = React.useCallback(() => {
    // 本次点击手势内同步写;同步路径失败(极少数环境禁用 execCommand)再退回异步 API。
    if (copyTextSync(text)) {
      setCopied(true)
      return
    }
    copyText(text)
      .then(() => setCopied(true))
      .catch(() => setCopied(false))
  }, [text])

  const hint = copied
    ? '已复制到剪贴板。'
    : '浏览器拒绝了剪贴板访问：下方文本已全选，可直接按 Ctrl+C（⌘C）复制，或点「重试复制」。'

  return (
    <ConfirmDialog
      open={open}
      title={title}
      subtitle={hint}
      hideDefaultMessageBlock
      confirmLabel="关闭"
      body={(
        <textarea
          ref={textareaRef}
          readOnly
          value={text}
          spellCheck={false}
          onFocus={(event) => event.currentTarget.select()}
          style={textareaStyle}
        />
      )}
      actions={(
        <>
          <Button variant="secondary" onClick={onClose}>关闭</Button>
          <Button variant="primary" onClick={handleRetry}>{copied ? '已复制' : '重试复制'}</Button>
        </>
      )}
      onConfirm={onClose}
      onCancel={onClose}
    />
  )
}

const textareaStyle: React.CSSProperties = {
  width: '100%',
  minHeight: 72,
  resize: 'vertical',
  padding: '10px 12px',
  borderRadius: 'var(--radius-md, 8px)',
  border: '1px solid var(--border)',
  background: 'var(--bg-primary)',
  color: 'var(--text-primary)',
  fontFamily: 'var(--font-mono, monospace)',
  fontSize: 'var(--text-sm, 13px)',
  lineHeight: 1.5,
  outline: 'none',
  wordBreak: 'break-all',
}