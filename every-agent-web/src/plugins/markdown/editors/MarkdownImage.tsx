/**
 * Markdown 内嵌图片组件(plugins/markdown)。
 *
 * 渲染 `![alt](src)`:
 * - `http(s):`/`data:` 等外部 URL 直接作为 <img src>;
 * - 其余按「相对当前 md 文件所在目录」解析为工作区相对路径,经 workspaceGateway
 *   读取二进制 → data URL 后渲染(与图片文件编辑器同一 MIME/上限体系)。
 * - 无 workspace 上下文时(且非外部 URL)显示无法加载提示,不抛错。
 */
import React from 'react'
import { workspaceGateway } from '@/platform/fs/workspaceGateway'
import { normalizeWorkspaceRelativePath } from '@/platform/fs/pathUtils'
import { bytesToDataUrl, imageMimeOf, isImageFileName } from '@/utils/imageAsset'

type MarkdownImageProps = {
  src: string
  alt: string
  /** 所属工作区根;缺省时仅外部 URL 可渲染。 */
  workspaceRoot?: string
  /** md 文件所在目录(工作区相对路径,空串 = 工作区根)。 */
  baseDir?: string
}

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; url: string }
  | { status: 'error'; message: string }

export default function MarkdownImage({ src, alt, workspaceRoot, baseDir }: MarkdownImageProps) {
  const [state, setState] = React.useState<LoadState>({ status: 'loading' })

  React.useEffect(() => {
    let cancelled = false
    const trimmed = src.trim()

    // 外部 URL / data URL:浏览器直接加载。
    if (/^(https?:|data:)/i.test(trimmed)) {
      setState({ status: 'ready', url: trimmed })
      return () => {
        cancelled = true
      }
    }

    if (!workspaceRoot) {
      setState({ status: 'error', message: '缺少工作区上下文,无法加载图片' })
      return () => {
        cancelled = true
      }
    }

    const rel = resolveWorkspaceRelativePath(baseDir ?? '', trimmed)
    if (!rel || !isImageFileName(rel)) {
      setState({ status: 'error', message: `不支持的图片引用: ${trimmed}` })
      return () => {
        cancelled = true
      }
    }

    setState({ status: 'loading' })
    workspaceGateway.readBinaryFile(workspaceRoot, rel)
      .then((bytes) => {
        if (cancelled) return
        setState({ status: 'ready', url: bytesToDataUrl(bytes, imageMimeOf(rel)) })
      })
      .catch((loadError: unknown) => {
        if (cancelled) return
        setState({ status: 'error', message: loadError instanceof Error ? loadError.message : String(loadError) })
      })

    return () => {
      cancelled = true
    }
  }, [baseDir, src, workspaceRoot])

  if (state.status === 'loading') {
    return (
      <span style={placeholderStyle} title={src}>
        加载图片…
      </span>
    )
  }
  if (state.status === 'error') {
    return (
      <span style={errorStyle} title={src}>
        {alt ? `图片加载失败(${alt}): ${state.message}` : `图片加载失败: ${state.message}`}
      </span>
    )
  }

  return (
    <img
      src={state.url}
      alt={alt || src}
      loading="lazy"
      style={imageStyle}
    />
  )
}

/**
 * 把「baseDir 相对 src」合并为工作区相对路径,并防 `..` 越出工作区根。
 * 路径坐标系统一为工作区相对路径(无前导 /)。
 */
function resolveWorkspaceRelativePath(baseDir: string, target: string): string {
  const segments = normalizeWorkspaceRelativePath(baseDir).split('/').filter(Boolean)
  for (const part of normalizeWorkspaceRelativePath(target).split('/').filter(Boolean)) {
    if (part === '.') continue
    if (part === '..') {
      segments.pop()
      continue
    }
    segments.push(part)
  }
  return segments.join('/')
}

const imageStyle: React.CSSProperties = {
  maxWidth: '100%',
  height: 'auto',
  display: 'block',
  margin: '6px 0',
  borderRadius: 'var(--radius-md)',
}

const placeholderStyle: React.CSSProperties = {
  display: 'inline-block',
  color: 'var(--text-muted)',
  fontSize: 'var(--text-xs)',
  padding: '4px 0',
}

const errorStyle: React.CSSProperties = {
  display: 'inline-block',
  color: 'var(--accent-red)',
  fontSize: 'var(--text-xs)',
  padding: '4px 0',
  wordBreak: 'break-all',
}