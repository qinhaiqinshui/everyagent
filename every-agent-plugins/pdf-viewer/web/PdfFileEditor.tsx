// PDF 文件编辑器:由插件经 `ui.file_content_editors` 扩展点注册到核心编辑器注册表。
// content 承载 data URL(application/pdf);readonly 能力位让外壳屏蔽编辑/保存/查找入口。
// 浏览器与桌面(Electron)环境一致:均在文件标签页内以原生 <iframe> 渲染
// (Chromium 内置 PDF Viewer,零依赖);data URL 先转 Blob URL 以保证 iframe 兼容。
import React from 'react'
import type {
  PluginFileContentEditorDescriptor,
  PluginFileContentHeaderAction,
  PluginFileContentEditorProps,
} from '@everyagent/plugin-api'

function PdfFileEditor({
  file,
  content,
  loading,
  error,
  onHeaderActionsChange,
}: PluginFileContentEditorProps) {
  const [blobUrl, setBlobUrl] = React.useState<string | null>(null)
  const [convertError, setConvertError] = React.useState('')

  // data URL → Blob URL:<iframe> 直接挂 data URL 在部分 Chromium 版本会被拦截,
  // Blob URL 走同源安全上下文,浏览器/桌面内置 PDF Viewer 均可稳定渲染。
  // 注意:解码不走 fetch(dataUrl)——宿主 CSP 的 connect-src 未放行 data:,
  // `fetch(data:...)` 会被浏览器(Edge/桌面 Electron 同一 CSP)直接拦截并报错,
  // 故此处手工解析 data URL(零网络、零 CSP 依赖)。
  React.useEffect(() => {
    if (!content) {
      setBlobUrl(null)
      setConvertError('')
      return
    }
    let objectUrl = ''
    try {
      const blob = dataUrlToBlob(content)
      const pdfBlob = blob.type === 'application/pdf'
        ? blob
        : new Blob([blob], { type: 'application/pdf' })
      objectUrl = URL.createObjectURL(pdfBlob)
      setBlobUrl(objectUrl)
      setConvertError('')
    } catch {
      setBlobUrl(null)
      setConvertError('PDF 内容解析失败')
    }
    return () => {
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

  const headerActions = React.useMemo<PluginFileContentHeaderAction[]>(() => [
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

export const descriptor: PluginFileContentEditorDescriptor = {
  kind: 'pdf',
  label: 'PDF',
  extensions: ['.pdf'],
  /** 二进制只读:PDF 没有可编辑文本态,外壳据此屏蔽编辑/保存/查找入口。 */
  readonly: true,
  Component: PdfFileEditor,
}

export default PdfFileEditor

/** 手工解析 data URL → Blob:不依赖 fetch(CSP connect-src 不放行 data:)。 */
function dataUrlToBlob(dataUrl: string): Blob {
  const commaIndex = dataUrl.indexOf(',')
  if (!dataUrl.startsWith('data:') || commaIndex < 0) {
    throw new Error('非法 data URL')
  }
  const meta = dataUrl.slice('data:'.length, commaIndex)
  const payload = dataUrl.slice(commaIndex + 1)
  const mime = meta.split(';')[0] || 'application/octet-stream'
  const bytes = /;base64/i.test(meta)
    ? base64ToBytes(payload)
    : new TextEncoder().encode(decodeURIComponent(payload))
  return new Blob([toArrayBuffer(bytes)], { type: mime })
}

/** base64 → 字节(逐字符拷贝,UTF-8 安全)。 */
function base64ToBytes(base64: string): Uint8Array {
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i)
  }
  return bytes
}

/** 视图 → 精确长度 ArrayBuffer(Blob 兼容;规避 lib 对 Uint8Array 泛型的推断差异)。 */
function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer
}

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
