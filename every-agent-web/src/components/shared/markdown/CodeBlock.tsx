import React from 'react'
import { highlightCode, normalizeCodeLanguage } from './codeHighlight'
import { copyText } from '@/utils/clipboard'
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
  const lang = React.useMemo(() => normalizeCodeLanguage(language), [language])
  const html = React.useMemo(() => (lang ? highlightCode(code, lang) : null), [code, lang])
  const [copied, setCopied] = React.useState(false)
  const timerRef = React.useRef<number | undefined>(undefined)

  // 组件卸载时清理待触发的「已复制」回退计时器,避免对已卸载组件 setState。
  React.useEffect(() => () => window.clearTimeout(timerRef.current), [])

  const handleCopy = React.useCallback(() => {
    copyText(code)
      .then(() => {
        setCopied(true)
        window.clearTimeout(timerRef.current)
        timerRef.current = window.setTimeout(() => setCopied(false), 1500)
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
      <pre className="code-block__pre prism-tokens" tabIndex={0}>
        {html
          ? <code className={`language-${lang}`} dangerouslySetInnerHTML={{ __html: html }} />
          : <code>{code}</code>}
      </pre>
    </div>
  )
}
