import React from 'react'
import LineNumberedSourceView from '@/components/shared/LineNumberedSourceView'
import type { FileContentEditorDescriptor, FileContentEditorProps, FileContentHeaderAction } from './types'

function formatJsonText(value: string): string {
  if (!value.trim()) return ''
  return JSON.stringify(JSON.parse(value), null, 2)
}

function JsonFileEditor({
  mode,
  content,
  draftContent,
  loading,
  error,
  lineNumber,
  lineLocateRequestedAt,
  onLineLocateApplied,
  onDraftChange,
  onHeaderActionsChange,
  findRegex,
  findActiveIndex,
  findEnabled,
}: FileContentEditorProps) {
  const [wrapLines, setWrapLines] = React.useState(true)
  const [collapsed, setCollapsed] = React.useState(false)
  const [collapsedPaths, setCollapsedPaths] = React.useState<Set<string>>(new Set())
  const [copied, setCopied] = React.useState(false)
  const displayContent = React.useMemo(() => {
    const source = mode === 'readonly' ? content : draftContent
    if (!source.trim()) {
      return source
    }
    try {
      return formatJsonText(source)
    } catch {
      return source
    }
  }, [content, draftContent, mode])

  const validationMessage = React.useMemo(() => {
    const source = mode === 'readonly' ? content : draftContent
    if (!source.trim()) return ''
    try {
      formatJsonText(source)
      return ''
    } catch (jsonError) {
      return jsonError instanceof Error ? jsonError.message : String(jsonError)
    }
  }, [content, draftContent, mode])

  const sourceContent = mode === 'readonly' ? content : draftContent
  const isTreeRenderable = React.useMemo(() => {
    if (!displayContent.trim()) {
      return false
    }
    try {
      const parsed = JSON.parse(displayContent) as unknown
      return typeof parsed === 'object' && parsed !== null
    } catch {
      return false
    }
  }, [displayContent])

  React.useEffect(() => {
    setCollapsed(false)
    setWrapLines(true)
    setCollapsedPaths(new Set())
  }, [fileStateKey(mode, content, draftContent)])

  const handleCopy = React.useCallback(async () => {
    try {
      await navigator.clipboard.writeText(sourceContent)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      // ignore
    }
  }, [sourceContent])

  const headerActions = React.useMemo<FileContentHeaderAction[]>(() => {
    const actions: FileContentHeaderAction[] = [
      {
        id: 'json-copy-content',
        label: copied ? '已复制' : '复制内容',
        onClick: () => { void handleCopy() },
      },
      {
        id: 'json-wrap-lines',
        label: wrapLines ? '自动换行' : '不换行',
        onClick: () => setWrapLines((current) => !current),
        active: wrapLines,
      },
      {
        id: 'json-collapse-toggle',
        label: collapsed ? '展开内容' : '折叠内容',
        onClick: () => setCollapsed((current) => !current),
      },
    ]

    if (isTreeRenderable) {
      actions.push({
        id: 'json-collapse-all',
        label: collapsedPaths.size > 0 ? '全部展开' : '全部折叠',
        onClick: () => {
          if (collapsedPaths.size > 0) {
            setCollapsedPaths(new Set())
            return
          }
          try {
            const parsed = JSON.parse(displayContent) as JsonLike
            const next = new Set<string>()
            collectJsonCollapsedPaths(parsed, '$', next)
            setCollapsedPaths(next)
          } catch {
            setCollapsedPaths(new Set())
          }
        },
      })
    }

    return actions
  }, [collapsed, collapsedPaths.size, copied, displayContent, handleCopy, isTreeRenderable, wrapLines])

  React.useEffect(() => {
    onHeaderActionsChange?.(headerActions)
  }, [headerActions, onHeaderActionsChange])

  React.useEffect(() => (
    () => {
      onHeaderActionsChange?.([])
    }
  ), [onHeaderActionsChange])

  if (loading) {
    return null
  }
  if (error) {
    return null
  }

  if (mode === 'readonly') {
    return (
      <div style={wrapStyle}>
        {validationMessage ? (
          <div style={warningStyle}>当前 JSON 内容格式无效：{validationMessage}</div>
        ) : null}
        <LineNumberedSourceView
          content={displayContent}
          wrapLines={wrapLines}
          onWrapLinesChange={setWrapLines}
          lineNumber={lineNumber}
          lineLocateRequestedAt={lineLocateRequestedAt}
          onLineLocateApplied={onLineLocateApplied}
          readonlyClassName="file-json-editor__source"
          findRegex={findRegex}
          findActiveIndex={findActiveIndex}
          findEnabled={findEnabled}
        />
      </div>
    )
  }

  return (
    <div style={wrapStyle}>
      {validationMessage ? (
        <div style={warningStyle}>当前 JSON 内容格式无效：{validationMessage}</div>
      ) : null}
      <LineNumberedSourceView
        content={content}
        editable
        value={draftContent}
        onChange={onDraftChange}
        wrapLines={wrapLines}
        onWrapLinesChange={setWrapLines}
        lineNumber={lineNumber}
        lineLocateRequestedAt={lineLocateRequestedAt}
        onLineLocateApplied={onLineLocateApplied}
        findRegex={findRegex}
        findActiveIndex={findActiveIndex}
        findEnabled={findEnabled}
      />
    </div>
  )
}

export const descriptor: FileContentEditorDescriptor = {
  kind: 'json',
  label: 'JSON',
  extensions: ['.json'],
  Component: JsonFileEditor,
}

type JsonLike = null | boolean | number | string | JsonLike[] | { [key: string]: JsonLike }

function collectJsonCollapsedPaths(value: JsonLike, path: string, target: Set<string>) {
  if (Array.isArray(value)) {
    target.add(path)
    value.forEach((item, index) => {
      collectJsonCollapsedPaths(item, `${path}[${index}]`, target)
    })
    return
  }
  if (typeof value === 'object' && value !== null) {
    target.add(path)
    Object.entries(value).forEach(([key, child]) => {
      collectJsonCollapsedPaths(child, path === '$' ? `$.${key}` : `${path}.${key}`, target)
    })
  }
}

function fileStateKey(mode: string, content: string, draftContent: string): string {
  return `${mode}::${content}::${draftContent}`
}

const wrapStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
}

const warningStyle: React.CSSProperties = {
  flexShrink: 0,
  marginTop: 12,
  marginBottom: 8,
  color: 'var(--accent-red)',
  fontSize: 'var(--text-xs)',
  lineHeight: 1.6,
}

const plainJsonStyle: React.CSSProperties = {
  margin: 0,
  padding: '12px 0 20px',
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-word',
  color: 'var(--text-primary)',
  fontSize: 'var(--text-base)',
  lineHeight: 1.7,
  fontFamily: 'var(--font-mono)',
}

export default JsonFileEditor
