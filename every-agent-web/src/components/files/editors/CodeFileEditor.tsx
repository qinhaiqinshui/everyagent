import React from 'react'
import LineNumberedSourceView from '@/components/shared/LineNumberedSourceView'
import { highlightCode, languageByExtension } from '@/components/shared/markdown/codeHighlight'
import type { FileContentEditorDescriptor, FileContentEditorProps, FileContentHeaderAction } from './types'

/**
 * 从文件路径中提取扩展名(小写,不含点),用于解析 Prism 语言。
 */
function getExtension(filePath: string): string {
  const normalized = filePath.replace(/\\/g, '/')
  const fileName = normalized.slice(normalized.lastIndexOf('/') + 1)
  const dotIndex = fileName.lastIndexOf('.')
  if (dotIndex <= 0 || dotIndex >= fileName.length - 1) return ''
  return fileName.slice(dotIndex + 1).toLowerCase()
}

function CodeFileEditor({
  file,
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

  // 按文件扩展名解析 Prism 语言名;只读态据此高亮。
  const language = React.useMemo(
    () => languageByExtension(getExtension(file.filePath)),
    [file.filePath],
  )
  const highlightHtml = React.useMemo(() => {
    if (mode !== 'readonly' || !content || !language) return null
    return highlightCode(content, language)
  }, [content, language, mode])

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
      id: 'code-copy-content',
      label: copied ? '已复制' : '复制内容',
      onClick: () => { void handleCopy() },
    },
    {
      id: 'code-wrap-lines',
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
        highlightHtml={highlightHtml}
        wrapLines={wrapLines}
        lineNumber={lineNumber}
        lineLocateRequestedAt={lineLocateRequestedAt}
        onLineLocateApplied={onLineLocateApplied}
        readonlyClassName="code-file-editor__source prism-tokens"
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
  kind: 'code',
  label: '代码',
  // 扩展名仅覆盖 Prism 已注册语言;与 JsonFileEditor(.json)、ImageFileEditor(.svg)
  // 重叠的扩展名不在此声明,避免因 glob 排序靠前而抢占专用编辑器。
  extensions: [
    '.js', '.mjs', '.cjs',
    '.jsx',
    '.ts', '.tsx',
    '.java',
    '.go',
    '.py',
    '.sh', '.bash', '.zsh',
    '.sql',
    '.yaml', '.yml',
    '.html', '.htm', '.xml', '.vue',
    '.css',
  ],
  Component: CodeFileEditor,
}

export default CodeFileEditor
