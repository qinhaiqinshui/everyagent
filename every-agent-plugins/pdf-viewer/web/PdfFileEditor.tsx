// PDF 文件编辑器:由插件经 `ui.file_content_editors` 扩展点注册到核心编辑器注册表。
// content 承载 data URL(application/pdf);readonly 能力位让外壳屏蔽编辑/保存/查找入口。
// 浏览器与桌面(Electron)环境一致:均在文件标签页内以原生 <iframe> 渲染
// (Chromium 内置 PDF Viewer,零依赖);data URL 先转 Blob URL 以保证 iframe 兼容。
import React from 'react'
import type {
  FileContentEditorDescriptor,
  FileContentHeaderAction,
  FileContentEditorProps,
} from '@/components/files/editors/types'

function PdfFileEditor({
  file,
  content,
  loading,
  error,
  onHeaderActionsChange,
}: FileContentEditorProps) {
  const [blobUrl, setBlobUrl] = React.useState<string | null>(null)
  const [convertError, setConvertError] = React.useState('')

  // data URL → Blob URL:<iframe> 直接挂 data URL 在部分 Chromium 版本会被拦截,
  // Blob URL 走同源安全上下文,浏览器/桌面内置 PDF Viewer 均可稳定渲染。
  React.useEffect(() => {
    if (!content) {
      setBlobUrl(null)
      setConvertError('')
      return
    }
    let disposed = false
    let objectUrl = ''
    void (async () => {
      try {
        const blob = await (await fetch(content)).blob()
        const pdfBlob = blob.type === 'application/pdf'
          ? blob
          : new Blob([blob], { type: 'application/pdf' })
        objectUrl = URL.createObjectURL(pdfBlob)
        if (disposed) {
          URL.revokeObjectURL(objectUrl)
          return
        }
        setBlobUrl(objectUrl)
        setConvertError('')
      } catch {
        if (!disposed) {
          setConvertError('PDF 内容解析失败')
        }
      }
    })()
    return () => {
      disposed = true
      if (objectUrl) {
        URL.revokeObjectURL(objectUrl)
      }
      setBlobUrl(null)
    }
  }, [content])

  const handleDownload = React.useCallback(() => {
    if (!blobUrl) return
    const anchor = document.createElement('a')
    anchor.href = blobUrl
    anchor.download = file.fileName || 'document.pdf'
    anchor.click()
  }, [blobUrl, file.fileName])

  const headerActions = React.useMemo<FileContentHeaderAction[]>(() => [
    {
      id: 'pdf-download',
      label: '下载',
      onClick: handleDownload,
      disabled: !blobUrl,
      title: '将 PDF 保存到本地',
    },
  ], [blobUrl, handleDownload])

  React.useEffect(() => {
    onHeaderActionsChange?.(headerActions)
    return () => onHeaderActionsChange?.([])
  }, [headerActions, onHeaderActionsChange])

  if (loading) {
    return null
  }
  const displayError = error || convertError
  if (displayError) {
    return <div style={errorStyle}>{displayError}</div>
  }
  if (!blobUrl) {
    return null
  }

  return (
    <div style={containerStyle}>
      <iframe
        src={blobUrl}
        title={file.fileName || 'PDF 预览'}
        style={frameStyle}
      />
    </div>
  )
}

export const descriptor: FileContentEditorDescriptor = {
  kind: 'pdf',
  label: 'PDF',
  extensions: ['.pdf'],
  /** 二进制只读:PDF 没有可编辑文本态,外壳据此屏蔽编辑/保存/查找入口。 */
  readonly: true,
  Component: PdfFileEditor,
}

export default PdfFileEditor

const containerStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  minWidth: 0,
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
  background: 'var(--bg-secondary)',
}

const frameStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  minWidth: 0,
  width: '100%',
  height: '100%',
  border: 'none',
}

const errorStyle: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  color: 'var(--accent-red)',
  fontSize: 'var(--text-base)',
  padding: 24,
}
