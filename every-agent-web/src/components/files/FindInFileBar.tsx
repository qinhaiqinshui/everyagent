import React from 'react'
import { Button, IconButton } from '@/components/shared/ui'
import { Input } from 'antd'
import type { InputRef } from 'antd'
import { ChevronDownIcon, CloseIcon } from '../shared/AppGlyphs'

type FindInFileBarProps = {
  query: string
  onQueryChange: (next: string) => void
  caseSensitive: boolean
  onToggleCaseSensitive: () => void
  count: number
  activeIndex: number
  onNext: () => void
  onPrev: () => void
  onClose: () => void
  /** 替换词（替换功能可编辑时生效）。 */
  replaceQuery: string
  onReplaceQueryChange: (next: string) => void
  /** 替换当前命中。 */
  onReplace: () => void
  /** 替换全部命中。 */
  onReplaceAll: () => void
  /** 是否允许替换：仅在文件可编辑（readwrite）时为真。 */
  canReplace?: boolean
  isMobile?: boolean
}

/**
 * 文件内查找条（Ctrl+F）。
 * 输入框 + 大小写切换 + 上一处 / 下一处 + 命中计数 + 关闭；
 * 展开「替换」后出现替换词输入框与「替换 / 替换全部」按钮。
 * 计数始终基于源文本；显示为「当前 / 总数」。
 */
export default function FindInFileBar({
  query,
  onQueryChange,
  caseSensitive,
  onToggleCaseSensitive,
  count,
  activeIndex,
  onNext,
  onPrev,
  onClose,
  replaceQuery,
  onReplaceQueryChange,
  onReplace,
  onReplaceAll,
  canReplace = false,
  isMobile = false,
}: FindInFileBarProps) {
  const inputRef = React.useRef<InputRef | null>(null)
  const replaceInputRef = React.useRef<InputRef | null>(null)
  const [showReplace, setShowReplace] = React.useState(false)

  React.useEffect(() => {
    inputRef.current?.focus()
    inputRef.current?.select()
  }, [])

  const handleFindKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      onClose()
      return
    }
    if (event.key === 'Enter') {
      event.preventDefault()
      if (event.shiftKey) {
        onPrev()
      } else {
        onNext()
      }
    }
  }

  const handleReplaceKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Enter') {
      event.preventDefault()
      if (event.altKey) {
        onReplaceAll()
      } else {
        onReplace()
      }
    }
  }

  const counterText = count > 0 ? `${activeIndex + 1}/${count}` : (query ? '0/0' : '')

  return (
    <div
      className="find-in-file-bar titlebar-no-drag"
      style={{ ...barStyle, ...(isMobile ? barMobileStyle : null) }}
      onKeyDown={(event) => {
        // 阻止组合键（如 Ctrl+F）落到输入框时再触发文档级开关。
        if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'f') {
          event.preventDefault()
        }
      }}
    >
      <div style={rowStyle}>
        <Button
          type="button"
          onClick={onToggleCaseSensitive}
          title="区分大小写"
          aria-pressed={caseSensitive}
          style={{ ...caseToggleStyle, ...(caseSensitive ? caseToggleActiveStyle : null) }}
        >
          Aa
        </Button>
        <Input
          ref={inputRef}
          type="text"
          value={query}
          placeholder="查找"
          onChange={(event) => onQueryChange(event.target.value)}
          onKeyDown={handleFindKeyDown}
          spellCheck={false}
          style={inputStyle}
        />
        <span style={counterStyle} aria-live="polite">{counterText}</span>
        <IconButton
          type="button"
          onClick={onPrev}
          icon={<ChevronDownIcon size={14} style={{ transform: 'rotate(180deg)' }} />}
          aria-label="上一处 (Shift+F3)"
          title="上一处 (Shift+F3)"
          disabled={count === 0}
          style={{ ...navButtonStyle, ...(count === 0 ? navButtonDisabledStyle : null) }}
        />
        <IconButton
          type="button"
          onClick={onNext}
          icon={<ChevronDownIcon size={14} />}
          aria-label="下一处 (F3)"
          title="下一处 (F3)"
          disabled={count === 0}
          style={{ ...navButtonStyle, ...(count === 0 ? navButtonDisabledStyle : null) }}
        />
        <Button
          type="button"
          onClick={() => {
            setShowReplace((current) => !current)
            if (!showReplace) {
              requestAnimationFrame(() => replaceInputRef.current?.focus())
            }
          }}
          title="替换"
          aria-pressed={showReplace}
          style={{ ...replaceToggleStyle, ...(showReplace ? caseToggleActiveStyle : null) }}
        >
          替换
        </Button>
        <IconButton
          type="button"
          onClick={onClose}
          icon={<CloseIcon size={14} />}
          aria-label="关闭 (Esc)"
          title="关闭 (Esc)"
          style={closeButtonStyle}
        />
      </div>
      {showReplace && (
        <div style={rowStyle}>
          <span style={replacePrefixStyle} aria-hidden="true" />
          <Input
            ref={replaceInputRef}
            type="text"
            value={replaceQuery}
            placeholder="替换为"
            onChange={(event) => onReplaceQueryChange(event.target.value)}
            onKeyDown={handleReplaceKeyDown}
            spellCheck={false}
            style={inputStyle}
          />
          <Button
            type="button"
            onClick={onReplace}
            disabled={!canReplace || count === 0}
            title={canReplace ? '替换当前 (Enter)' : '文件不可编辑，无法替换'}
            style={{ ...textButtonStyle, ...((!canReplace || count === 0) ? navButtonDisabledStyle : null) }}
          >
            替换
          </Button>
          <Button
            type="button"
            onClick={onReplaceAll}
            disabled={!canReplace}
            title={canReplace ? '替换全部 (Alt+Enter)' : '文件不可编辑，无法替换'}
            style={{ ...textButtonStyle, ...(!canReplace ? navButtonDisabledStyle : null) }}
          >
            替换全部
          </Button>
        </div>
      )}
    </div>
  )
}

const barStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
  padding: '6px',
  borderRadius: 'var(--radius-md)',
  border: '1px solid var(--border)',
  background: 'var(--bg-primary)',
  boxShadow: '0 6px 20px rgba(0, 0, 0, 0.16)',
  width: 360,
  maxWidth: 'calc(100vw - 32px)',
  zIndex: 20,
}

const rowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 4,
}

const barMobileStyle: React.CSSProperties = {
  width: 'auto',
  flex: '0 1 420px',
}

const caseToggleStyle: React.CSSProperties = {
  border: '1px solid var(--border)',
  background: 'var(--bg-secondary)',
  color: 'var(--text-secondary)',
  borderRadius: 'var(--radius-sm)',
  cursor: 'pointer',
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  width: 26,
  height: 26,
  flexShrink: 0,
  padding: 0,
}

const caseToggleActiveStyle: React.CSSProperties = {
  background: 'var(--accent-blue-dim)',
  borderColor: 'color-mix(in srgb, var(--accent-blue) 40%, var(--border))',
  color: 'var(--accent-blue)',
}

const inputStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  border: '1px solid var(--border)',
  borderRadius: 'var(--radius-sm)',
  background: 'var(--bg-secondary)',
  color: 'var(--text-primary)',
  fontSize: 'var(--text-sm)',
  padding: '4px 8px',
  outline: 'none',
}

const counterStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  whiteSpace: 'nowrap',
  minWidth: 44,
  textAlign: 'right',
  flexShrink: 0,
}

const navButtonStyle: React.CSSProperties = {
  border: '1px solid var(--border)',
  background: 'var(--bg-secondary)',
  color: 'var(--text-primary)',
  borderRadius: 'var(--radius-sm)',
  cursor: 'pointer',
  width: 26,
  height: 26,
  flexShrink: 0,
  padding: 0,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
}

const navButtonDisabledStyle: React.CSSProperties = {
  opacity: 0.45,
  cursor: 'not-allowed',
}

const closeButtonStyle: React.CSSProperties = {
  border: '1px solid var(--border)',
  background: 'var(--bg-secondary)',
  color: 'var(--text-secondary)',
  borderRadius: 'var(--radius-sm)',
  cursor: 'pointer',
  width: 26,
  height: 26,
  flexShrink: 0,
  padding: 0,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
}

const replaceToggleStyle: React.CSSProperties = {
  border: '1px solid var(--border)',
  background: 'var(--bg-secondary)',
  color: 'var(--text-secondary)',
  borderRadius: 'var(--radius-sm)',
  cursor: 'pointer',
  fontSize: 'var(--text-xs)',
  fontWeight: 600,
  height: 26,
  flexShrink: 0,
  padding: '0 8px',
}

const replacePrefixStyle: React.CSSProperties = {
  width: 26,
  height: 26,
  flexShrink: 0,
}

const textButtonStyle: React.CSSProperties = {
  border: '1px solid var(--border)',
  background: 'var(--bg-secondary)',
  color: 'var(--text-primary)',
  borderRadius: 'var(--radius-sm)',
  cursor: 'pointer',
  fontSize: 'var(--text-xs)',
  fontWeight: 600,
  height: 26,
  flexShrink: 0,
  padding: '0 8px',
}
