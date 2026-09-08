import React from 'react'
import LineNumberedSourceView from '@/components/shared/LineNumberedSourceView'
import type { FileContentEditorDescriptor, FileContentEditorProps, FileContentHeaderAction } from './types'

function TextFileEditor({
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
  const [copied, setCopied] = React.useState(false)
  const sourceContent = mode === 'readonly' ? content : draftContent

  const handleCopy = React.useCallback(async () => {
    try {
      await navigator.clipboard.writeText(sourceContent)
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } catch {
      // ignore
    }
  }, [sourceContent])

  const headerActions = React.useMemo<FileContentHeaderAction[]>(() => [
    {
      id: 'text-copy-content',
      label: copied ? '已复制' : '复制内容',
      onClick: () => { void handleCopy() },
    },
    {
      id: 'text-wrap-lines',
      label: wrapLines ? '自动换行' : '不换行',
      onClick: () => setWrapLines((current) => !current),
      active: wrapLines,
    },
  ], [copied, handleCopy, wrapLines])

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
      <LineNumberedSourceView
        content={content}
        wrapLines={wrapLines}
        lineNumber={lineNumber}
        lineLocateRequestedAt={lineLocateRequestedAt}
        onLineLocateApplied={onLineLocateApplied}
        readonlyClassName="file-text-editor__source"
        findRegex={findRegex}
        findActiveIndex={findActiveIndex}
        findEnabled={findEnabled}
      />
    )
  }

  return (
    <LineNumberedSourceView
      content={content}
      editable
      value={draftContent}
      onChange={onDraftChange}
      wrapLines={wrapLines}
      lineNumber={lineNumber}
      lineLocateRequestedAt={lineLocateRequestedAt}
      onLineLocateApplied={onLineLocateApplied}
      findRegex={findRegex}
      findActiveIndex={findActiveIndex}
      findEnabled={findEnabled}
    />
  )
}

export const descriptor: FileContentEditorDescriptor = {
  kind: 'text',
  label: '纯文本',
  extensions: ['.txt'],
  isFallback: true,
  Component: TextFileEditor,
}

export default TextFileEditor
