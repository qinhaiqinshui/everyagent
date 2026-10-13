/**
 * 剪贴板复制工具（全站唯一实现）。
 *
 * 优先用异步 Clipboard API；在部分移动端 / 旧环境（非安全上下文、无权限）下不可用，
 * 降级到隐藏 textarea + execCommand('copy')。降级路径返回 false 时抛错，
 * 让调用方不误报「已复制」。
 *
 * 从 CodeBlock 的内联 copyText 提取共享，供代码块、轮结果复制按钮等复用。
 */
export async function copyText(text: string): Promise<void> {
  if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text)
    return
  }
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.focus()
  textarea.select()
  try {
    // execCommand 返回 false 时复制实际失败，必须抛错让 UI 不误报「已复制」。
    if (!document.execCommand('copy')) {
      throw new Error('execCommand copy failed')
    }
  } finally {
    document.body.removeChild(textarea)
  }
}