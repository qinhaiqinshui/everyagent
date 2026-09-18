import React from 'react'
import CodeMirrorEditor from '@/components/shared/CodeMirrorEditor'
import { languageByPath } from '@/components/shared/codemirrorLanguages'
import type { FileContentEditorDescriptor, FileContentEditorProps, FileContentHeaderAction } from './types'

/**
 * 通用文件编辑器：CodeMirror + languageByPath 自动按扩展名加载语法。
 *
 * 覆盖所有代码/文本/JSON 文件类型，未匹配到语言包的扩展名以纯文本渲染（无高亮）。
 * 查找替换由 CodeMirror 内置 @codemirror/search 承担，不使用外层 find props。
 */
function UniversalFileEditor({
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
}: FileContentEditorProps) {
  const [wrapLines, setWrapLines] = React.useState(true)
  const [copied, setCopied] = React.useState(false)
  const sourceContent = mode === 'readonly' ? content : draftContent

  const language = React.useMemo(
    () => languageByPath(file.filePath),
    [file.filePath],
  )

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
      id: 'universal-copy-content',
      label: copied ? '已复制' : '复制内容',
      onClick: () => { void handleCopy() },
    },
    {
      id: 'universal-wrap-lines',
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

  const editable = mode !== 'readonly'
  const value = editable ? draftContent : content

  return (
    <CodeMirrorEditor
      value={value}
      onChange={editable ? onDraftChange : undefined}
      editable={editable}
      language={language}
      wrapLines={wrapLines}
      lineNumber={lineNumber}
      lineLocateRequestedAt={lineLocateRequestedAt}
      onLineLocateApplied={onLineLocateApplied}
    />
  )
}

export const descriptor: FileContentEditorDescriptor = {
  kind: 'universal',
  label: '文本',
  /**
   * 声明所有代码/文本/JSON 扩展名（不含 .md 和图片扩展名——它们有专用编辑器）。
   * registry.ts 的 resolveFileContentEditorByPath 按顺序匹配，未匹配的扩展名
   * 通过 isFallback 兜底到本编辑器（纯文本，无语法高亮）。
   */
  extensions: [
    '.js', '.mjs', '.cjs',
    '.jsx',
    '.ts', '.tsx',
    '.java',
    '.go',
    '.py',
    '.sh', '.bash', '.zsh',
    '.json',
    '.sql',
    '.yaml', '.yml',
    '.html', '.htm', '.xml', '.vue',
    '.css',
    '.txt',
  ],
  isFallback: true,
  Component: UniversalFileEditor,
}

export default UniversalFileEditor
