import React from 'react'
import { CheckIcon, CopyIcon } from '../shared/AppGlyphs'
import { copyText } from '@/utils/clipboard'
import type { RoundTailPanelProps } from '@/plugin/types'

/**
 * 核心内置的「复制本轮结果」轮末面板。
 *
 * 与插件轮末面板（file-change 等）同源：经 `ui.round_tail_panels` 扩展点注册、
 * 由 TaskRoundsPanel 统一渲染在轮末工具栏行内，因此与插件面板共享同一套栅格与对齐，
 * host 不再硬编码任何一块轮末内容（注册见 `@/plugin/coreRoundTailPanels`）。
 *
 * 点击把该轮最终回复纯正文（rounds.jsonl 摘要 `finalReply`，不含思考）写入剪贴板，
 * 成功后短暂切「已复制」（1.5s 复原）；正文为空时禁用，复制失败不误报「已复制」。
 */
export default function RoundCopyPanel({ round }: RoundTailPanelProps): React.ReactNode {
  const [copied, setCopied] = React.useState(false)
  const timerRef = React.useRef<number | undefined>(undefined)
  // 组件卸载时清理待触发的「已复制」回退计时器，避免对已卸载组件 setState。
  React.useEffect(() => () => window.clearTimeout(timerRef.current), [])

  const text = round.finalReply
  const disabled = !text.trim()
  const handleCopy = React.useCallback(() => {
    if (disabled) return
    copyText(text)
      .then(() => {
        setCopied(true)
        window.clearTimeout(timerRef.current)
        timerRef.current = window.setTimeout(() => setCopied(false), 1500)
      })
      .catch(() => setCopied(false))
  }, [text, disabled])

  const label = copied ? `已复制第 ${round.index} 轮结果` : `复制第 ${round.index} 轮结果`
  return (
    <button
      type="button"
      className={`nagent-round-collapse__copy${copied ? ' nagent-round-collapse__copy--done' : ''}`}
      onClick={handleCopy}
      disabled={disabled}
      aria-label={label}
      title={copied ? '已复制' : `复制第 ${round.index} 轮结果`}
    >
      {copied ? <CheckIcon size={13} /> : <CopyIcon size={13} />}
      <span>{copied ? '已复制' : '复制'}</span>
    </button>
  )
}