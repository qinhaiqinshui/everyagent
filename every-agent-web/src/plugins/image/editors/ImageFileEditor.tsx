// 插件化图片文件编辑器:由文件编辑器 registry 的通用扩展扫描发现(与 markdown 插件同机制)。
// content 承载 data URL;readonly 能力位让外壳屏蔽编辑/保存/查找入口。
import React from 'react'
import type {
  FileContentEditorDescriptor,
  FileContentHeaderAction,
  FileContentEditorProps,
} from '@/components/files/editors/types'
import { IMAGE_EXTENSIONS } from '@/utils/imageAsset'

type FitMode = 'contain' | 'actual'

function ImageFileEditor({
  content,
  loading,
  error,
  onHeaderActionsChange,
}: FileContentEditorProps) {
  const [fit, setFit] = React.useState<FitMode>('contain')
  const [naturalSize, setNaturalSize] = React.useState<{ width: number; height: number } | null>(null)
  const [copied, setCopied] = React.useState(false)

  const handleCopyImage = React.useCallback(async () => {
    if (!content) return
    try {
      // 现代浏览器可直接把图片(data URL)作为 ClipboardItem 写入剪贴板。
      const blob = await (await fetch(content)).blob()
      await navigator.clipboard.write([new ClipboardItem({ [blob.type]: blob })])
      setCopied(true)
    } catch {
      // 降级:复制 data URL 文本(至少让用户拿到图片数据)。
      try {
        await navigator.clipboard.writeText(content)
      } catch {
        // ignore
      }
      setCopied(true)
    }
    window.setTimeout(() => setCopied(false), 1500)
  }, [content])

  const headerActions = React.useMemo<FileContentHeaderAction[]>(() => [
    {
      id: 'image-fit-toggle',
      label: fit === 'contain' ? '原始大小' : '适应窗口',
      onClick: () => setFit((current) => (current === 'contain' ? 'actual' : 'contain')),
      active: fit === 'contain',
      title: fit === 'contain' ? '按原始像素大小显示' : '缩放以适应窗口',
    },
    {
      id: 'image-copy',
      label: copied ? '已复制' : '复制图片',
      onClick: () => { void handleCopyImage() },
      disabled: !content,
    },
  ], [content, copied, fit, handleCopyImage])

  React.useEffect(() => {
    onHeaderActionsChange?.(headerActions)
    return () => onHeaderActionsChange?.([])
  }, [headerActions, onHeaderActionsChange])

  if (loading) {
    return null
  }
  if (error) {
    return <div style={errorStyle}>{error}</div>
  }
  if (!content) {
    return null
  }

  return (
    <div style={containerStyle}>
      <div style={imageWrapStyle}>
        <img
          src={content}
          alt=""
          draggable={false}
          onLoad={(event) => {
            const img = event.currentTarget
            setNaturalSize({
              width: img.naturalWidth,
              height: img.naturalHeight,
            })
          }}
          style={fit === 'contain' ? containStyle : actualStyle}
        />
      </div>
      {naturalSize ? (
        <div style={metaStyle}>
          {naturalSize.width} × {naturalSize.height}
          {fit === 'contain' ? ' · 适应窗口' : ' · 原始大小'}
        </div>
      ) : null}
    </div>
  )
}

export const descriptor: FileContentEditorDescriptor = {
  kind: 'image',
  label: '图片',
  extensions: [...IMAGE_EXTENSIONS],
  /** 二进制只读:图片没有可编辑文本态,外壳据此屏蔽编辑/保存/查找入口。 */
  readonly: true,
  Component: ImageFileEditor,
}

export default ImageFileEditor

const containerStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  minWidth: 0,
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
  background: 'var(--bg-secondary)',
}

const imageWrapStyle: React.CSSProperties = {
  flex: 1,
  minHeight: 0,
  minWidth: 0,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  overflow: 'auto',
  padding: 16,
}

const containStyle: React.CSSProperties = {
  maxWidth: '100%',
  maxHeight: '100%',
  objectFit: 'contain',
  userSelect: 'none',
}

const actualStyle: React.CSSProperties = {
  width: 'auto',
  height: 'auto',
  userSelect: 'none',
}

const metaStyle: React.CSSProperties = {
  flexShrink: 0,
  display: 'flex',
  justifyContent: 'center',
  gap: 8,
  padding: '6px 0 12px',
  color: 'var(--text-muted)',
  fontSize: 'var(--text-xs)',
  fontFamily: 'var(--font-mono)',
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