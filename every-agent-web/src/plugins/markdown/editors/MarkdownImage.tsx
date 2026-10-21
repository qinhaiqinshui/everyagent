/**
 * Markdown 内嵌图片组件(plugins/markdown)。
 *
 * 渲染 `![alt](src)`:
 * - `http(s):`/`data:` 等外部 URL 直接作为 <img src>;
 * - 其余按「相对当前 md 文件所在目录」解析为工作区相对路径,经 workspaceGateway
 *   读取二进制(fs.readRaw,不经沙箱;用户操作非 AI 工具) → data URL 后渲染
 * - 无 workspace 上下文时(且非外部 URL)显示无法加载提示,不抛错。
 */
import React from 'react'
import { workspaceGateway } from '@/platform/fs/workspaceGateway'
import { resolveWorkspaceRelativePath, decodeMarkdownUri } from '@/platform/fs/pathUtils'
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
  | { status: 'ready'; url: string; /** 外部 URL 徽章等内联小图:true=行内排布(多个徽章水平排列)。 */ inline: boolean }
  | { status: 'error'; message: string }

export default function MarkdownImage({ src, alt, workspaceRoot, baseDir }: MarkdownImageProps) {
  const [state, setState] = React.useState<LoadState>({ status: 'loading' })

  React.useEffect(() => {
    let cancelled = false
    const trimmed = src.trim()

    // 外部 URL / data URL:浏览器直接加载。徽章等小图内联排布(多个水平排列)。
    if (/^(https?:|data:)/i.test(trimmed)) {
      setState({ status: 'ready', url: trimmed, inline: true })
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

    // 目的地被 CommonMark URI 规范化过(中文 → %XX 编码):解码后解析为工作区路径;
    // 解码结果与原文不同时保留原文候选兜底——文件名本身含 %xx 字面量(如 a%20b.svg)
    // 时解码会指向错误路径,按序尝试、首个读取成功者生效。
    const decoded = decodeMarkdownUri(trimmed)
    const candidates: string[] = []
    for (const value of [decoded, trimmed]) {
      const rel = resolveWorkspaceRelativePath(baseDir ?? '', value)
      if (rel && isImageFileName(rel) && !candidates.includes(rel)) candidates.push(rel)
    }
    if (candidates.length === 0) {
      setState({ status: 'error', message: `不支持的图片引用: ${trimmed}` })
      return () => {
        cancelled = true
      }
    }

    setState({ status: 'loading' })
    void (async () => {
      let lastError: unknown = null
      for (const rel of candidates) {
        try {
          const bytes = await workspaceGateway.readBytesRaw(workspaceRoot, rel)
          if (cancelled) return
          setState({ status: 'ready', url: bytesToDataUrl(bytes, imageMimeOf(rel)), inline: false })
          return
        } catch (readError) {
          lastError = readError
        }
      }
      if (!cancelled) {
        setState({
          status: 'error',
          message: lastError instanceof Error ? lastError.message : String(lastError),
        })
      }
    })()

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
      style={state.inline ? inlineImageStyle : imageStyle}
    />
  )
}

// 相对路径合并(resolveWorkspaceRelativePath)已抽出至 @/platform/fs/pathUtils,
// 与 markdown 文件链接共用同一解析。

const imageStyle: React.CSSProperties = {
  maxWidth: '100%',
  height: 'auto',
  display: 'block',
  margin: '6px 0',
  borderRadius: 'var(--radius-md)',
}

/** 外部 URL 小图(徽章等)内联样式:同行水平排布,基线对齐(GitHub 徽章行观感)。 */
const inlineImageStyle: React.CSSProperties = {
  maxWidth: '100%',
  verticalAlign: 'middle',
  margin: '0 2px',
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