/**
 * 扩展详情页 README 区渲染器 —— react-markdown + remark-gfm 薄封装。
 *
 * 两个库经插件 bare import 白名单（宿主注入 window.__EA_REACT_MARKDOWN__ /
 * __EA_REMARK_GFM__，各挂 default export，只可用默认导入形态）复用宿主
 * 同一份实例，与聊天面板 / Markdown 文件预览同版本同行为（GFM 表格、
 * 任务列表、删除线、自动链接均支持）。不再自研正则解析——旧实现曾因
 * 捕获组 off-by-one 导致含列表的 README 必崩（2026-10 移除）。
 *
 * 插件特有能力：相对路径图片经 resolveImage 异步解析（插件目录内资源 →
 * plugin.asset data URL）；http(s)/data URL 原样直出；解析失败不渲染图。
 * 文本节点全部经 React 渲染（react-markdown 默认不启用 raw HTML），无
 * innerHTML 注入面。
 */
import React from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import type { Components } from 'react-markdown'
import './markdown.css'

/** 图片 src 解析器：返回可直接用于 <img src> 的 URL（null = 放弃渲染）。 */
export type MarkdownImageResolver = (src: string, alt: string) => Promise<string | null>

interface MarkdownProps {
  source: string
  /** 可选：相对路径图片解析器（如插件目录内资源 → base64 data URL）。 */
  resolveImage?: MarkdownImageResolver
}

/**
 * 相对路径图片：先异步解析再渲染（解析期间不占位，失败静默不渲染图）。
 * http(s)/data/blob URL 无需解析直接用。
 */
function MarkdownImage({ src, alt, resolveImage }: {
  src?: string
  alt?: string
  resolveImage?: MarkdownImageResolver
}) {
  const [url, setUrl] = React.useState<string | null>(null)

  React.useEffect(() => {
    let cancelled = false
    if (!src) {
      setUrl(null)
      return
    }
    if (/^(https?:|data:|blob:)/i.test(src)) {
      setUrl(src)
      return
    }
    void (async () => {
      const resolved = resolveImage ? await resolveImage(src, alt ?? '') : null
      if (!cancelled) setUrl(resolved)
    })()
    return () => {
      cancelled = true
    }
  }, [src, alt, resolveImage])

  if (!url) return null
  return <img src={url} alt={alt ?? ''} />
}

export default function Markdown({ source, resolveImage }: MarkdownProps) {
  const components = React.useMemo<Components>(() => ({
    // 相对路径图片走插件目录资源解析（plugin.asset）
    img: ({ src, alt }) => <MarkdownImage src={src} alt={alt} resolveImage={resolveImage} />,
    // 外链一律新窗口打开（README 链接不离开宿主页面）
    a: ({ href, children }) => (
      <a href={href} target="_blank" rel="noreferrer">
        {children}
      </a>
    ),
  }), [resolveImage])

  return (
    <div className="pm-md">
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
        {source}
      </ReactMarkdown>
    </div>
  )
}
