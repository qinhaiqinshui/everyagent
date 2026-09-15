import React from 'react'
import { highlightCode, normalizeCodeLanguage } from './codeHighlight'
import './codeBlock.css'

export type CodeBlockProps = {
  /** 代码块原始文本(不含围栏)。 */
  code: string
  /** fence 信息串(```js);未注册语言降级纯文本渲染。 */
  language?: string | null
  /** 场景:preview=文件预览(较宽、字号略大) / display=聊天输出(较窄、字号略小)。 */
  variant?: 'preview' | 'display'
  /** 外层附加样式(如预览的分层缩进 marginLeft)。 */
  style?: React.CSSProperties
}

/**
 * 共享代码块渲染组件(MarkdownPreview / MarkdownDisplay 共用)。
 * 头部条:左侧语言标签 + 右侧复制按钮;正文 Prism 高亮,长行横向滚动不撑破容器(移动端)。
 */
export default function CodeBlock({ code, language, variant = 'preview', style }: CodeBlockProps) {
  const lang = normalizeCodeLanguage(language)
  const html = React.useMemo(() => (lang ? highlightCode(code, lang) : null), [code, lang])
  const [copied, setCopied] = React.useState(false)

  const handleCopy = React.useCallback(() => {
    copyText(code)
      .then(() => {
        setCopied(true)
        window.setTimeout(() => setCopied(false), 1500)
      })
      .catch(() => {
        setCopied(false)
      })
  }, [code])

  return (
    <div className={`code-block code-block--${variant}`} style={style}>
      <div className="code-block__header">
        <span className="code-block__lang">{lang ?? 'text'}</span>
        <button
          type="button"
          className="code-block__copy"
          onClick={handleCopy}
          aria-label="复制代码"
        >
          {copied ? '已复制' : '复制'}
        </button>
      </div>
      <pre className="code-block__pre">
        {html
          ? <code className={`language-${lang}`} dangerouslySetInnerHTML={{ __html: html }} />
          : <code>{code}</code>}
      </pre>
    </div>
  )
}

/** 复制到剪贴板:优先 navigator.clipboard,降级 execCommand(兼容部分移动端/旧环境)。 */
async function copyText(text: string): Promise<void> {
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
    document.execCommand('copy')
  } finally {
    document.body.removeChild(textarea)
  }
}
