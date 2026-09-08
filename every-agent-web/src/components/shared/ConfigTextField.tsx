import React from 'react'
import Button from '@/components/shared/ui/Button'

/** 配置文本字段属性。 */
type ConfigTextFieldProps = {
  /** 字段标签。 */
  label: React.ReactNode
  /** 当前值。 */
  value: string
  /** 值变更回调。 */
  onChange?: (value: string) => void
  /** 默认是否折叠。 */
  defaultCollapsed?: boolean
  /** 最小高度。 */
  minHeight?: number
  /** 是否只读。 */
  readOnly?: boolean
  /** 占位文本。 */
  placeholder?: string
  /** 是否掩码预览。 */
  maskedPreview?: boolean
  /** 输入模式。 */
  inputMode?: React.HTMLAttributes<HTMLTextAreaElement>['inputMode']
  /** 是否启用拼写检查。 */
  spellCheck?: boolean
}

/**
 * 可折叠的配置文本字段。
 * 这里只负责文本编辑与展示，不直接依赖任何具体配置仓储。
 */
export default function ConfigTextField({
  label,
  value,
  onChange,
  defaultCollapsed = true,
  minHeight = 44,
  readOnly = false,
  placeholder,
  maskedPreview = false,
  inputMode,
  spellCheck = false,
}: ConfigTextFieldProps) {
  const [collapsed, setCollapsed] = React.useState(defaultCollapsed)
  const [isEditing, setIsEditing] = React.useState(false)
  const [isHovered, setIsHovered] = React.useState(false)
  const [wrapLines, setWrapLines] = React.useState(true)
  const [copied, setCopied] = React.useState(false)
  const [draftValue, setDraftValue] = React.useState(value)
  const [savedValue, setSavedValue] = React.useState(value)
  const textareaRef = React.useRef<HTMLTextAreaElement | null>(null)
  const canEdit = !readOnly && typeof onChange === 'function'
  const isDirty = draftValue !== savedValue
  const showFieldChrome = isHovered || isEditing
  const measureText = React.useMemo(() => {
    const source = draftValue.length > 0 ? draftValue : (placeholder ?? '')
    return `${source}\n `
  }, [draftValue, placeholder])

  /** 保存编辑内容。 */
  const handleSave = React.useCallback(() => {
    if (!canEdit || !isDirty) return
    onChange?.(draftValue)
    setSavedValue(draftValue)
  }, [canEdit, draftValue, isDirty, onChange])

  /** 插入缩进。 */
  const handleInsertIndent = React.useCallback(() => {
    const textarea = textareaRef.current
    if (!textarea) return

    const indent = '    '
    const selectionStart = textarea.selectionStart ?? 0
    const selectionEnd = textarea.selectionEnd ?? selectionStart
    const nextValue = `${draftValue.slice(0, selectionStart)}${indent}${draftValue.slice(selectionEnd)}`

    setDraftValue(nextValue)

    requestAnimationFrame(() => {
      const nextCursor = selectionStart + indent.length
      textarea.selectionStart = nextCursor
      textarea.selectionEnd = nextCursor
    })
  }, [draftValue])

  React.useEffect(() => {
    setSavedValue(value)
  }, [value])

  React.useEffect(() => {
    if (collapsed || !isEditing) {
      setDraftValue(value)
    }
  }, [collapsed, isEditing, value])

  React.useEffect(() => {
    if (collapsed || !isEditing) return
    textareaRef.current?.focus()
  }, [collapsed, isEditing])

  /** 开始编辑。 */
  const handleStartEditing = React.useCallback(() => {
    if (!canEdit) return
    setIsEditing(true)
  }, [canEdit])

  /** 复制当前文本。 */
  const handleCopy = React.useCallback(async () => {
    const copySourceText = isEditing ? draftValue : value
    if (!copySourceText) return
    await navigator.clipboard.writeText(copySourceText)
    setCopied(true)
    window.setTimeout(() => {
      setCopied(false)
    }, 1500)
  }, [draftValue, isEditing, value])

  return (
    <div
      className="config-text-field"
      style={buildCardStyle(showFieldChrome)}
      onMouseEnter={() => setIsHovered(true)}
      onMouseLeave={() => setIsHovered(false)}
    >
      <div className="config-text-field__header" style={headerBarStyle}>
        <div className="config-text-field__title-group" style={titleGroupStyle}>
          <span style={titleStyle}>{label}</span>
          {collapsed ? (
            <span style={previewStyle} title={maskedPreview ? undefined : value}>
              {maskedPreview ? '•'.repeat(Math.min(value.replace(/\s+/g, ' ').trim().length, 16)) : (value.replace(/\s+/g, ' ').trim() || '未填写')}
            </span>
          ) : null}
        </div>
        <Button
          type="button"
          variant="ghost"
          onClick={() => setCollapsed((current) => !current)}
          style={toggleButtonStyle}
        >
          <span style={toggleStyle}>{collapsed ? '展开' : '收起'}</span>
        </Button>
      </div>

      {!collapsed ? (
        <div style={buildEditorShellStyle(showFieldChrome)}>
          <div className="config-text-field__toolbar" style={toolbarStyle}>
            <div style={toolbarHintStyle}>
              {readOnly ? '只读预览' : isEditing ? '编辑模式，可用 Ctrl+S 保存' : '只读预览'}
            </div>
            <div className="config-text-field__toolbar-actions" style={toolbarActionsStyle}>
              <Button
                type="button"
                variant="secondary"
                onClick={() => { void handleCopy() }}
                disabled={!value && !draftValue}
                style={{
                  ...toolbarButtonStyle,
                  ...(copied ? toolbarButtonActiveStyle : null),
                  ...(!value && !draftValue ? disabledButtonStyle : null),
                }}
              >
                {copied ? '已复制' : '复制'}
              </Button>
              {!readOnly ? (
                isEditing ? (
                  <>
                    <Button
                      type="button"
                      variant="secondary"
                      onClick={() => {
                        setIsEditing(false)
                        setDraftValue(value)
                      }}
                      style={toolbarButtonStyle}
                    >
                      取消
                    </Button>
                    <Button
                      type="button"
                      variant="primary"
                      onClick={handleSave}
                      disabled={!isDirty}
                      style={{
                        ...toolbarButtonStyle,
                        ...saveButtonStyle,
                        ...(!isDirty ? disabledButtonStyle : null),
                      }}
                    >
                      保存
                    </Button>
                  </>
                ) : (
                  <Button
                    type="button"
                    variant="secondary"
                    onClick={handleStartEditing}
                    style={toolbarButtonStyle}
                  >
                    编辑
                  </Button>
                )
              ) : null}
              <Button
                type="button"
                variant="secondary"
                onClick={() => setWrapLines((current) => !current)}
                style={{
                  ...toolbarButtonStyle,
                  ...(!wrapLines ? toolbarButtonActiveStyle : null),
                }}
              >
                {wrapLines ? '切到横向滚动' : '切到自动换行'}
              </Button>
            </div>
          </div>

          {!readOnly ? (
            isEditing ? (
              <div
                style={{
                  ...textareaStageStyle,
                  minHeight,
                }}
              >
                <div
                  aria-hidden="true"
                  style={{
                    ...buildTextareaMeasureStyle(showFieldChrome),
                    minHeight,
                    whiteSpace: wrapLines ? 'pre-wrap' : 'pre',
                    wordBreak: wrapLines ? 'break-word' : 'normal',
                    overflowX: wrapLines ? 'hidden' : 'auto',
                  }}
                >
                  {measureText}
                </div>
                <textarea
                  ref={textareaRef}
                  value={draftValue}
                  readOnly={!isEditing}
                  placeholder={placeholder}
                  inputMode={inputMode}
                  spellCheck={spellCheck}
                  wrap={wrapLines ? 'soft' : 'off'}
                  onChange={(event) => {
                    const nextValue = event.target.value
                    setDraftValue(nextValue)
                  }}
                  onKeyDown={(event) => {
                    if (event.key === 'Tab') {
                      event.preventDefault()
                      handleInsertIndent()
                      return
                    }
                    if (!(event.ctrlKey || event.metaKey) || event.key.toLowerCase() !== 's') return
                    event.preventDefault()
                    handleSave()
                  }}
                  style={{
                    ...buildTextareaStyle(showFieldChrome),
                    minHeight,
                    whiteSpace: wrapLines ? 'pre-wrap' : 'pre',
                    wordBreak: wrapLines ? 'break-word' : 'normal',
                    overflowX: wrapLines ? 'hidden' : 'auto',
                    color: 'var(--text-primary)',
                    cursor: 'text',
                  }}
                  rows={1}
                />
              </div>
            ) : (
              <ConfigPreviewBlock
                value={value}
                wrapLines={wrapLines}
                minHeight={minHeight}
                showChrome={showFieldChrome}
              />
            )
          ) : (
            <ConfigPreviewBlock
              value={value}
              wrapLines={wrapLines}
              minHeight={minHeight}
              showChrome={showFieldChrome}
            />
          )}
        </div>
      ) : null}
    </div>
  )
}

/**
 * 配置文本预览块。
 */
function ConfigPreviewBlock({
  value,
  wrapLines = true,
  minHeight = 44,
  showChrome = false,
}: {
  /** 预览值。 */
  value: string
  /** 是否自动换行。 */
  wrapLines?: boolean
  /** 最小高度。 */
  minHeight?: number
  /** 是否显示高亮边框。 */
  showChrome?: boolean
}) {
  return (
    <div style={previewBlockStyle}>
      <div
        style={{
          ...buildPreviewBlockContentStyle(showChrome),
          minHeight,
          whiteSpace: wrapLines ? 'pre-wrap' : 'pre',
          wordBreak: wrapLines ? 'break-word' : 'normal',
          overflowX: wrapLines ? 'hidden' : 'auto',
        }}
      >
        {value && value.length > 0 ? value : '未填写'}
      </div>
    </div>
  )
}

/**
 * 构建卡片样式。
 */
function buildCardStyle(showChrome: boolean): React.CSSProperties {
  return {
    display: 'flex',
    flexDirection: 'column',
    minWidth: 0,
    border: showChrome ? '1px solid color-mix(in srgb, var(--accent-blue) 52%, var(--border))' : '1px solid transparent',
    borderRadius: 'calc(var(--radius-md) + 2px)',
    background: 'inherit',
    overflow: 'hidden',
    boxShadow: showChrome ? '0 0 0 1px color-mix(in srgb, var(--accent-blue) 18%, transparent)' : 'none',
    transition: 'border-color 120ms ease, box-shadow 120ms ease',
  }
}

const headerBarStyle: React.CSSProperties = {
  padding: '13px 15px 11px',
  display: 'flex',
  alignItems: 'flex-start',
  justifyContent: 'space-between',
  gap: 12,
}

const titleGroupStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
  minWidth: 0,
  flex: 1,
  userSelect: 'text',
}

const titleStyle: React.CSSProperties = {
  color: 'var(--text-primary)',
  fontSize: 'var(--text-base)',
  fontWeight: 700,
  lineHeight: 1.35,
  minWidth: 0,
}

const previewStyle: React.CSSProperties = {
  color: 'var(--text-muted)',
  fontSize: 'var(--text-xs)',
  lineHeight: 1.4,
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
}

const toggleStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--accent-blue)',
  lineHeight: 1.4,
  paddingTop: 1,
  flex: '0 0 auto',
}

const toggleButtonStyle: React.CSSProperties = {
  border: 'none',
  background: 'transparent',
  padding: 0,
  cursor: 'pointer',
  flex: '0 0 auto',
}

/**
 * 构建编辑器外壳样式。
 */
function buildEditorShellStyle(showChrome: boolean): React.CSSProperties {
  return {
    display: 'flex',
    flexDirection: 'column',
    gap: 10,
    padding: '0 14px 14px',
    borderTop: showChrome ? '1px solid color-mix(in srgb, var(--accent-blue) 36%, var(--border))' : '1px solid transparent',
  }
}

const toolbarStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 10,
  flexWrap: 'wrap',
  paddingTop: 10,
}

const toolbarHintStyle: React.CSSProperties = {
  color: 'var(--text-muted)',
  fontSize: 'var(--text-xs)',
  lineHeight: 1.4,
}

const toolbarActionsStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  flexWrap: 'wrap',
}

const toolbarButtonStyle: React.CSSProperties = {
  border: '1px solid var(--border)',
  borderRadius: 'var(--radius-md)',
  background: 'var(--bg-secondary)',
  color: 'var(--text-secondary)',
  padding: '4px 8px',
  fontSize: 'var(--text-xs)',
  lineHeight: 1.4,
  cursor: 'pointer',
}

const toolbarButtonActiveStyle: React.CSSProperties = {
  color: 'var(--accent-blue)',
  borderColor: 'var(--accent-blue)',
  background: 'color-mix(in srgb, var(--accent-blue) 10%, var(--bg-secondary))',
}

const saveButtonStyle: React.CSSProperties = {
  color: '#fff',
  borderColor: 'var(--accent-green)',
  background: 'var(--accent-green)',
}

const disabledButtonStyle: React.CSSProperties = {
  opacity: 0.55,
  cursor: 'not-allowed',
}

const textareaStageStyle: React.CSSProperties = {
  position: 'relative',
  width: '100%',
}

/**
 * 构建 textarea 样式。
 */
function buildTextareaStyle(showChrome: boolean): React.CSSProperties {
  return {
    position: 'absolute',
    inset: 0,
    display: 'block',
    width: '100%',
    height: '100%',
    border: showChrome ? '1px solid color-mix(in srgb, var(--accent-blue) 44%, var(--border))' : '1px solid transparent',
    borderRadius: 'var(--radius-md)',
    background: 'inherit',
    color: 'var(--text-primary)',
    padding: '12px 14px 13px',
    fontSize: 'var(--text-sm)',
    fontFamily: 'inherit',
    fontWeight: 'inherit',
    lineHeight: 1.75,
    resize: 'none',
    overflowY: 'hidden',
    boxSizing: 'border-box',
    outline: 'none',
    transition: 'border-color 120ms ease',
  }
}

/**
 * 构建 textarea 测量层样式。
 */
function buildTextareaMeasureStyle(showChrome: boolean): React.CSSProperties {
  return {
    width: '100%',
    border: showChrome ? '1px solid color-mix(in srgb, var(--accent-blue) 44%, var(--border))' : '1px solid transparent',
    borderRadius: 'var(--radius-md)',
    background: 'inherit',
    color: 'transparent',
    padding: '12px 14px 13px',
    fontSize: 'var(--text-sm)',
    fontFamily: 'inherit',
    fontWeight: 'inherit',
    lineHeight: 1.75,
    boxSizing: 'border-box',
    pointerEvents: 'none',
    userSelect: 'none',
    visibility: 'hidden',
    transition: 'border-color 120ms ease',
  }
}

const previewBlockStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 0,
}

/**
 * 构建预览块内容样式。
 */
function buildPreviewBlockContentStyle(showChrome: boolean): React.CSSProperties {
  return {
    border: showChrome ? '1px solid color-mix(in srgb, var(--accent-blue) 44%, var(--border))' : '1px solid transparent',
    borderRadius: 'var(--radius-md)',
    background: 'inherit',
    color: 'var(--text-primary)',
    padding: '12px 14px 13px',
    fontSize: 'var(--text-sm)',
    fontFamily: 'inherit',
    fontWeight: 'inherit',
    lineHeight: 1.75,
    boxSizing: 'border-box',
    textRendering: 'optimizeLegibility',
    WebkitFontSmoothing: 'antialiased',
    MozOsxFontSmoothing: 'grayscale',
    transition: 'border-color 120ms ease',
  }
}
