import React from 'react'
import { CheckIcon, ChevronDownIcon, ChevronRightIcon, CopyIcon } from './AppGlyphs'

type JsonValue = null | boolean | number | string | JsonValue[] | { [key: string]: JsonValue }

type JsonBlockProps = {
  content: string
  /** 内容区高度上限；不传（undefined）时不限制高度，内容完整展开、无竖向滚动条 */
  maxHeight?: number
  showToolbar?: boolean
  collapsed?: boolean
  onCollapsedChange?: (nextValue: boolean) => void
  wrapLines?: boolean
  onWrapLinesChange?: (nextValue: boolean) => void
  collapsedPaths?: Set<string>
  onCollapsedPathsChange?: (nextValue: Set<string>) => void
  copied?: boolean
  onCopy?: () => void | Promise<void>
}

export default function JsonBlock({
  content,
  maxHeight,
  showToolbar = true,
  collapsed: collapsedProp,
  onCollapsedChange,
  wrapLines: wrapLinesProp,
  onWrapLinesChange,
  collapsedPaths: collapsedPathsProp,
  onCollapsedPathsChange,
  copied: copiedProp,
  onCopy,
}: JsonBlockProps) {
  const [internalCollapsed, setInternalCollapsed] = React.useState(false)
  const [internalCopied, setInternalCopied] = React.useState(false)
  const [internalWrapLines, setInternalWrapLines] = React.useState(true)
  const [internalCollapsedPaths, setInternalCollapsedPaths] = React.useState<Set<string>>(new Set())
  const [hoveredPath, setHoveredPath] = React.useState<string | null>(null)
  const contentRef = React.useRef<HTMLDivElement>(null)
  const [bodyHeight, setBodyHeight] = React.useState<number | undefined>(maxHeight)
  const collapsed = collapsedProp ?? internalCollapsed
  const wrapLines = wrapLinesProp ?? internalWrapLines
  const collapsedPaths = collapsedPathsProp ?? internalCollapsedPaths
  const copied = copiedProp ?? internalCopied

  const parsed = React.useMemo(() => parseJsonContent(content), [content])
  const canTreeRender = parsed.kind === 'json'
  const rootPath = '$'
  const effectiveCollapsedPaths = React.useMemo(() => {
    const next = new Set(collapsedPaths)
    if (collapsed) {
      next.add(rootPath)
    }
    return next
  }, [collapsed, collapsedPaths])
  const rootIsCollapsible = canTreeRender && isCollapsibleValue(parsed.value)
  const rootCollapsed = rootIsCollapsible && effectiveCollapsedPaths.has(rootPath)

  React.useEffect(() => {
    if (collapsedPathsProp !== undefined) {
      onCollapsedPathsChange?.(new Set())
      return
    }
    setInternalCollapsedPaths(new Set())
  }, [content])

  React.useLayoutEffect(() => {
    const element = contentRef.current
    if (!element) return

    const measure = () => {
      // 内容横向溢出（长 key / 长数字 / 关闭换行）时预留滚动条高度，
      // 否则横向滚动条会压在内容底部、遮住最后一行。
      const hasHorizontalOverflow = element.scrollWidth > element.clientWidth + 1
      const scrollbarAllowance = hasHorizontalOverflow ? 16 : 0
      setBodyHeight(element.scrollHeight + scrollbarAllowance)
    }

    measure()
    const frameId = window.requestAnimationFrame(measure)

    if (typeof ResizeObserver !== 'undefined') {
      const observer = new ResizeObserver(() => measure())
      observer.observe(element)
      return () => {
        window.cancelAnimationFrame(frameId)
        observer.disconnect()
      }
    }

    return () => {
      window.cancelAnimationFrame(frameId)
    }
  }, [collapsed, effectiveCollapsedPaths, content, maxHeight, wrapLines])

  const handleCopy = async () => {
    if (onCopy) {
      await onCopy()
      return
    }
    try {
      await navigator.clipboard.writeText(content)
      setInternalCopied(true)
      setTimeout(() => setInternalCopied(false), 1500)
    } catch {
      // ignore
    }
  }

  const updateCollapsed = React.useCallback((nextValue: boolean) => {
    onCollapsedChange?.(nextValue)
    if (collapsedProp === undefined) {
      setInternalCollapsed(nextValue)
    }
  }, [collapsedProp, onCollapsedChange])

  const updateWrapLines = React.useCallback((nextValue: boolean) => {
    onWrapLinesChange?.(nextValue)
    if (wrapLinesProp === undefined) {
      setInternalWrapLines(nextValue)
    }
  }, [onWrapLinesChange, wrapLinesProp])

  const updateCollapsedPaths = React.useCallback((nextValue: Set<string>) => {
    onCollapsedPathsChange?.(nextValue)
    if (collapsedPathsProp === undefined) {
      setInternalCollapsedPaths(nextValue)
    }
  }, [collapsedPathsProp, onCollapsedPathsChange])

  const handleToggleNode = React.useCallback((path: string) => {
    const next = new Set(collapsedPaths)
    if (next.has(path)) next.delete(path)
    else next.add(path)
    updateCollapsedPaths(next)
  }, [collapsedPaths, updateCollapsedPaths])

  const handleToggleAll = React.useCallback(() => {
    if (!canTreeRender || !rootIsCollapsible) return
    if (!rootCollapsed) {
      const collapsedSet = new Set<string>()
      collectCollapsiblePaths(parsed.value, rootPath, collapsedSet)
      updateCollapsedPaths(collapsedSet)
      return
    }
    updateCollapsedPaths(new Set())
  }, [canTreeRender, parsed, rootCollapsed, rootIsCollapsible, rootPath, updateCollapsedPaths])

  return (
    <div style={jsonBlockWrapperStyle}>
      {showToolbar ? (
        <div style={jsonBlockHeaderStyle}>
          <span style={jsonBlockTitleStyle}>JSON</span>
          {canTreeRender && rootIsCollapsible && (
            <button
              type="button"
              onClick={(event) => {
                event.stopPropagation()
                handleToggleAll()
              }}
              style={jsonBlockActionStyle}
              title={rootCollapsed ? '全部展开' : '全部折叠'}
            >
              {rootCollapsed ? '全部展开' : '全部折叠'}
            </button>
          )}
          <button
            type="button"
            onClick={(event) => {
              event.stopPropagation()
              updateWrapLines(!wrapLines)
            }}
            style={jsonBlockActionStyle}
            title={wrapLines ? '关闭自动换行' : '开启自动换行'}
          >
            {wrapLines ? '自动换行' : '不换行'}
          </button>
          <button
            type="button"
            onClick={(event) => {
              event.stopPropagation()
              updateCollapsed(!collapsed)
            }}
            style={jsonBlockActionStyle}
            title={collapsed ? '展开' : '折叠'}
          >
            {collapsed ? <ChevronRightIcon size={12} /> : <ChevronDownIcon size={12} />}
          </button>
          <button
            type="button"
            onClick={(event) => {
              event.stopPropagation()
              void handleCopy()
            }}
            style={jsonBlockActionStyle}
            title="复制"
          >
            {copied ? <CheckIcon size={12} /> : <CopyIcon size={12} />}
          </button>
        </div>
      ) : null}
      <div
        style={{
          ...jsonBlockBodyStyle,
          height: bodyHeight,
          maxHeight,
          whiteSpace: wrapLines ? 'pre-wrap' : 'pre',
          overflowX: 'auto',
          overflowY: 'auto',
        }}
      >
        <div ref={contentRef}>
          {canTreeRender ? (
            <div style={jsonTreeStyle}>
              <JsonTreeNode
                label={null}
                value={parsed.value}
                path={rootPath}
                depth={0}
                isLast
                collapsedPaths={effectiveCollapsedPaths}
                hoveredPath={hoveredPath}
                wrapLines={wrapLines}
                onHover={setHoveredPath}
                onToggle={handleToggleNode}
              />
            </div>
          ) : (
            <pre
              style={{
                ...jsonFallbackPreStyle,
                whiteSpace: wrapLines ? 'pre-wrap' : 'pre',
                wordBreak: wrapLines ? 'break-all' : 'normal',
                overflowWrap: wrapLines ? 'anywhere' : 'normal',
              }}
            >
              {parsed.fallbackText}
            </pre>
          )}
        </div>
      </div>
    </div>
  )
}

function JsonTreeNode({
  label,
  value,
  path,
  depth,
  isLast,
  collapsedPaths,
  hoveredPath,
  wrapLines,
  onHover,
  onToggle,
}: {
  label: string | number | null
  value: JsonValue
  path: string
  depth: number
  isLast: boolean
  collapsedPaths: Set<string>
  hoveredPath: string | null
  wrapLines: boolean
  onHover: (path: string | null) => void
  onToggle: (path: string) => void
}) {
  const collapsible = isCollapsibleValue(value)
  const collapsed = collapsible && collapsedPaths.has(path)
  const indent = depth * 16
  const showToggle = collapsible && hoveredPath === path
  const punctuationColor = 'var(--text-muted)'

  if (!collapsible) {
    return (
      <div
        style={buildJsonLineStyle(wrapLines, indent)}
        onMouseEnter={() => onHover(path)}
        onMouseLeave={() => onHover(null)}
      >
        <span style={jsonFoldSlotStyle} />
        {label !== null && (
          <>
            <span style={buildJsonKeyStyle(wrapLines)}>{formatKey(label)}</span>
            <span style={jsonKeyPunctuationStyle}>: </span>
          </>
        )}
        <JsonPrimitive value={value} wrapLines={wrapLines} />
        {!isLast && <span style={{ color: punctuationColor }}>,</span>}
      </div>
    )
  }

  const entries = Array.isArray(value)
    ? value.map((item, index) => ({
      key: index,
      childPath: `${path}[${index}]`,
      childValue: item,
    }))
    : Object.entries(value).map(([key, childValue]) => ({
      key,
      childPath: path === '$' ? `$.${key}` : `${path}.${key}`,
      childValue,
    }))

  const openBracket = Array.isArray(value) ? '[' : '{'
  const closeBracket = Array.isArray(value) ? ']' : '}'
  const preview = buildCollapsedPreview(value)

  return (
    <div
      onMouseEnter={() => onHover(path)}
      onMouseLeave={() => onHover(null)}
    >
      <div style={buildJsonLineStyle(wrapLines, indent)}>
        <button
          type="button"
          onClick={() => onToggle(path)}
          style={{
            ...jsonFoldButtonStyle,
            opacity: showToggle ? 1 : 0,
            pointerEvents: showToggle ? 'auto' : 'none',
          }}
          title={collapsed ? '展开节点' : '折叠节点'}
        >
          {collapsed ? <ChevronRightIcon size={10} /> : <ChevronDownIcon size={10} />}
        </button>
        {label !== null && (
          <>
            <span style={buildJsonKeyStyle(wrapLines)}>{formatKey(label)}</span>
            <span style={jsonKeyPunctuationStyle}>: </span>
          </>
        )}
        <span style={jsonBracketStyle}>{openBracket}</span>
        {collapsed && preview && (
          <span style={jsonCollapsedPreviewStyle}>{preview}</span>
        )}
        {collapsed && (
          <>
            <span style={jsonBracketStyle}>{closeBracket}</span>
            {!isLast && <span style={{ color: punctuationColor }}>,</span>}
          </>
        )}
      </div>
      {!collapsed && (
        <>
          {entries.map((entry, index) => (
            <JsonTreeNode
              key={entry.childPath}
              label={entry.key}
              value={entry.childValue}
              path={entry.childPath}
              depth={depth + 1}
              isLast={index === entries.length - 1}
              collapsedPaths={collapsedPaths}
              hoveredPath={hoveredPath}
              wrapLines={wrapLines}
              onHover={onHover}
              onToggle={onToggle}
            />
          ))}
          <div style={buildJsonLineStyle(wrapLines, indent)}>
            <span style={jsonFoldSlotStyle} />
            <span style={jsonBracketStyle}>{closeBracket}</span>
            {!isLast && <span style={{ color: punctuationColor }}>,</span>}
          </div>
        </>
      )}
    </div>
  )
}

function JsonPrimitive({
  value,
  wrapLines,
}: {
  value: Exclude<JsonValue, JsonValue[] | { [key: string]: JsonValue }>
  wrapLines: boolean
}) {
  // 长字符串（尤其 CJK + 字面 `\n` 转义 + 标点混合，max-content 段超长且无空格）、
  // 长数字 / 长 ID / 长布尔值都需要能断行，否则会撑破行容器、右边被裁掉。
  // `break-word` / `anywhere` 在 `pre-wrap` 下优先级不够（pre-wrap 自带空格断点，
  // 浏览器认为"已有 break opportunity"不再触发字符间断），必须用 `break-all`
  // 强制在任意字符间增加 break opportunity，CJK 段和无空格长串才能换行。
  const wrapStyle = wrapLines
    ? { wordBreak: 'break-all' as const, overflowWrap: 'anywhere' as const }
    : { wordBreak: 'normal' as const, overflowWrap: 'normal' as const }
  if (value === null) return <span style={{ ...jsonNullStyle, ...jsonPrimitiveValueStyle, ...wrapStyle }}>null</span>
  if (typeof value === 'string') {
    return (
      <span
        style={{
          ...jsonStringStyle,
          ...jsonPrimitiveValueStyle,
          flex: wrapLines ? '1 1 auto' : undefined,
          whiteSpace: wrapLines ? 'pre-wrap' : 'pre',
          ...wrapStyle,
        }}
      >
        {JSON.stringify(value)}
      </span>
    )
  }
  if (typeof value === 'number') {
    return (
      <span style={{ ...jsonNumberStyle, ...jsonPrimitiveValueStyle, whiteSpace: 'pre', ...wrapStyle }}>
        {String(value)}
      </span>
    )
  }
  if (typeof value === 'boolean') {
    return (
      <span style={{ ...jsonBooleanStyle, ...jsonPrimitiveValueStyle, whiteSpace: 'pre', ...wrapStyle }}>
        {String(value)}
      </span>
    )
  }
  return <span style={{ ...jsonNullStyle, ...jsonPrimitiveValueStyle, ...wrapStyle }}>null</span>
}

function parseJsonContent(content: string): { kind: 'json'; value: JsonValue } | { kind: 'text'; fallbackText: string } {
  try {
    return { kind: 'json', value: JSON.parse(content) as JsonValue }
  } catch {
    return { kind: 'text', fallbackText: content }
  }
}

function isCollapsibleValue(value: JsonValue): value is JsonValue[] | { [key: string]: JsonValue } {
  if (Array.isArray(value)) return true
  return typeof value === 'object' && value !== null
}

function collectCollapsiblePaths(value: JsonValue, path: string, target: Set<string>) {
  if (!isCollapsibleValue(value)) return
  target.add(path)
  if (Array.isArray(value)) {
    value.forEach((item, index) => collectCollapsiblePaths(item, `${path}[${index}]`, target))
    return
  }
  Object.entries(value).forEach(([key, child]) => {
    collectCollapsiblePaths(child, path === '$' ? `$.${key}` : `${path}.${key}`, target)
  })
}

function buildCollapsedPreview(value: JsonValue[] | { [key: string]: JsonValue }): string {
  if (Array.isArray(value)) {
    return value.length === 0 ? '' : ` ${value.length} 项 `
  }
  const keys = Object.keys(value)
  if (keys.length === 0) return ''
  const preview = keys.slice(0, 3).join(', ')
  return keys.length > 3 ? ` ${preview}, ... ` : ` ${preview} `
}

function formatKey(label: string | number): string {
  return typeof label === 'number' ? String(label) : JSON.stringify(label)
}

const jsonBlockWrapperStyle: React.CSSProperties = {
  border: '1px solid var(--border-light)',
  borderRadius: 'var(--radius-sm)',
  overflow: 'hidden',
  background: 'var(--bg-primary)',
  marginBottom: 6,
}

const jsonBlockHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  padding: '6px 8px',
  background: 'var(--bg-sunken)',
  borderBottom: '1px solid var(--border-light)',
}

const jsonBlockTitleStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  fontWeight: 700,
  marginRight: 'auto',
}

const jsonBlockActionStyle: React.CSSProperties = {
  border: '1px solid transparent',
  background: 'transparent',
  color: 'var(--text-muted)',
  cursor: 'pointer',
  padding: '3px 8px',
  fontSize: 'var(--text-xs)',
  lineHeight: 1.2,
  borderRadius: 4,
}

const jsonBlockBodyStyle: React.CSSProperties = {
  color: 'var(--text-primary)',
}

const jsonTreeStyle: React.CSSProperties = {
  padding: '8px 0',
  fontSize: 'var(--text-xs)',
  lineHeight: 1.6,
  fontFamily: "var(--font-mono, 'Courier New', monospace)",
}

const jsonLineStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  paddingRight: 10,
}

function buildJsonLineStyle(wrapLines: boolean, paddingLeft: number): React.CSSProperties {
  return {
    ...jsonLineStyle,
    paddingLeft,
    minWidth: wrapLines ? 0 : 'max-content',
  }
}

const jsonFoldSlotStyle: React.CSSProperties = {
  width: 16,
  height: 18,
  flexShrink: 0,
}

const jsonFoldButtonStyle: React.CSSProperties = {
  width: 16,
  height: 18,
  flexShrink: 0,
  border: 'none',
  background: 'transparent',
  color: 'var(--text-muted)',
  cursor: 'pointer',
  padding: 0,
  fontSize: 'var(--text-xs)',
  lineHeight: '18px',
  textAlign: 'center',
}

/**
 * key 样式：保持不收缩（视觉对齐），但自动换行模式下给超长 key 一个行宽上限，
 * 允许在内部断行而不是撑破容器被右边裁掉（与字符串值行为一致）。
 */
function buildJsonKeyStyle(wrapLines: boolean): React.CSSProperties {
  return {
    color: 'var(--json-key)',
    flexShrink: 0,
    whiteSpace: 'pre',
    ...(wrapLines
      ? {
        maxWidth: 'calc(100% - 24px)',
        minWidth: 0,
        wordBreak: 'break-all',
        overflowWrap: 'anywhere',
      }
      : {}),
  }
}

const jsonKeyPunctuationStyle: React.CSSProperties = {
  color: 'var(--text-muted)',
  flexShrink: 0,
  whiteSpace: 'pre',
}

const jsonBracketStyle: React.CSSProperties = {
  color: 'var(--text-primary)',
}

const jsonCollapsedPreviewStyle: React.CSSProperties = {
  color: 'var(--text-muted)',
}

const jsonStringStyle: React.CSSProperties = {
  color: 'var(--json-string)',
}

const jsonPrimitiveValueStyle: React.CSSProperties = {
  minWidth: 0,
}

const jsonNumberStyle: React.CSSProperties = {
  color: 'var(--json-number)',
}

const jsonBooleanStyle: React.CSSProperties = {
  color: 'var(--json-boolean)',
}

const jsonNullStyle: React.CSSProperties = {
  color: 'var(--json-null)',
}

const jsonFallbackPreStyle: React.CSSProperties = {
  margin: 0,
  padding: '8px 10px',
  fontSize: 'var(--text-xs)',
  lineHeight: 1.6,
  fontFamily: "var(--font-mono, 'Courier New', monospace)",
  color: 'var(--text-primary)',
}
