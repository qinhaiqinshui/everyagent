import React, { useMemo } from 'react'

/**
 * SVG 输出块：把 <svg-image> 标签内的 SVG 源码消毒后渲染为图形。
 *
 * 安全策略（白名单）：
 *   - 只允许 SVG 图形元素进入输出，script 等一律丢弃；
 *   - 移除所有 on* 事件属性与 href / xlink:href 外部引用属性；
 *   - 仅使用浏览器内置 DOMParser / XMLSerializer，零第三方依赖。
 */

const SVG_NS = 'http://www.w3.org/2000/svg'

/** 允许渲染的 SVG 元素白名单（含渐变 / 裁剪 / 文本等，不含 script / a / style）。 */
const ALLOWED_TAGS = new Set([
  'svg',
  'g',
  'path',
  'circle',
  'rect',
  'line',
  'polyline',
  'polygon',
  'ellipse',
  'text',
  'tspan',
  'defs',
  'use',
  'symbol',
  'marker',
  'pattern',
  'linearGradient',
  'radialGradient',
  'stop',
  'clipPath',
  'mask',
  'title',
  'desc',
  'image',
  'foreignObject',
])

/** 属性名命中即移除（小写后比较）。 */
const BLOCKED_ATTRS = new Set(['href', 'xlink:href'])

/** 去掉模型输出里可能包裹的 ``` 代码围栏（```svg / ```xml / 裸 ```）。 */
function stripCodeFence(source: string): string {
  const trimmed = source.trim()
  const m = /^```[\w-]*\s*\n([\s\S]*?)\n?```\s*$/.exec(trimmed)
  return m ? m[1].trim() : trimmed
}

/** 递归消毒：白名单元素 + 过滤危险属性，文本节点原样保留，其余节点丢弃。 */
function sanitizeNode(node: Node, doc: XMLDocument): Node | null {
  if (node.nodeType === Node.TEXT_NODE) {
    return doc.createTextNode(node.textContent ?? '')
  }
  if (node.nodeType !== Node.ELEMENT_NODE) {
    return null
  }
  const el = node as Element
  if (!ALLOWED_TAGS.has(el.tagName)) {
    return null
  }
  const clean = doc.createElementNS(SVG_NS, el.tagName)
  for (const attr of Array.from(el.attributes)) {
    const name = attr.name.toLowerCase()
    if (name.startsWith('on') || BLOCKED_ATTRS.has(name)) {
      continue
    }
    clean.setAttribute(attr.name, attr.value)
  }
  for (const child of Array.from(el.childNodes)) {
    const safe = sanitizeNode(child, doc)
    if (safe) {
      clean.appendChild(safe)
    }
  }
  return clean
}

interface SanitizeResult {
  /** 消毒后可注入的 SVG 标记；解析失败或无根 svg 时为 null。 */
  html: string | null
  error?: string
}

function sanitizeSvg(raw: string): SanitizeResult {
  const source = stripCodeFence(raw)
  if (!source) {
    return { html: null, error: '空内容' }
  }
  let doc: XMLDocument
  try {
    doc = new DOMParser().parseFromString(source, 'image/svg+xml')
  } catch (e) {
    return { html: null, error: e instanceof Error ? e.message : String(e) }
  }
  if (doc.querySelector('parsererror')) {
    return { html: null, error: 'SVG 解析失败（XML 语法错误）' }
  }
  const root = doc.documentElement
  if (!root || root.tagName !== 'svg') {
    return { html: null, error: '内容不是合法的 <svg> 文档' }
  }
  const cleanRoot = sanitizeNode(root, doc) as Element | null
  if (!cleanRoot) {
    return { html: null, error: '消毒后没有可渲染内容' }
  }
  return { html: new XMLSerializer().serializeToString(cleanRoot) }
}

const containerStyle: React.CSSProperties = {
  margin: '8px 0',
  padding: 12,
  border: '1px solid var(--ea-border-color, #e5e5e5)',
  borderRadius: 8,
  background: 'var(--ea-bg-container, #fff)',
  overflow: 'auto',
  lineHeight: 0,
}

const errorHintStyle: React.CSSProperties = {
  margin: '0 0 6px',
  fontSize: 12,
  color: '#d4380d',
}

const rawTextStyle: React.CSSProperties = {
  margin: 0,
  padding: 8,
  fontSize: 12,
  lineHeight: 1.6,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-all',
  background: 'var(--ea-bg-code, #f6f6f6)',
  borderRadius: 6,
  color: 'var(--ea-text-secondary, #666)',
}

export interface SvgBlockProps {
  svgSource: string
}

export default function SvgBlock({ svgSource }: SvgBlockProps) {
  const result = useMemo(() => sanitizeSvg(svgSource), [svgSource])

  if (result.html !== null) {
    return (
      <div style={containerStyle}>
        {/* 消毒产物：白名单元素 + 已剔除 on* / href 属性，可安全注入 */}
        <div dangerouslySetInnerHTML={{ __html: result.html }} />
      </div>
    )
  }

  return (
    <div style={{ ...containerStyle, lineHeight: 1.6 }}>
      <p style={errorHintStyle}>SVG 渲染失败（{result.error ?? '未知错误'}），显示原始内容：</p>
      <pre style={rawTextStyle}>{svgSource}</pre>
    </div>
  )
}
