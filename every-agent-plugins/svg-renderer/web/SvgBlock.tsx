import React, { useEffect, useMemo, useRef, useState } from 'react'
import { CheckOutlined, CloseOutlined, CopyOutlined, DownloadOutlined } from '@ant-design/icons'
import { closeSvgFragment } from './closeFragment'

/**
 * SVG 输出块：把 <svg-image> 标签内的 SVG 源码消毒后渲染为图形。
 *
 * 流式渐进渲染：AI 输出过程中（streaming）闭合标签尚未到达时，经
 * closeSvgFragment 容错闭合已到达片段并渲染部分图形，观感为图形自上而下
 * 逐步加载；消息定稿但闭合标签缺席（truncated，输出中断）时同样尽力渲染，
 * 并标注「内容不完整」。
 *
 * 安全策略（白名单）：
 *   - 只允许 SVG 图形元素进入输出，script 等一律丢弃；
 *   - 移除所有 on* 事件属性与 href / xlink:href 外部引用属性；
 *   - 仅使用浏览器内置 DOMParser / XMLSerializer，零第三方依赖。
 *   - 渐进路径与终态共用同一消毒管线，流式期间不降低安全标准。
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

/**
 * 流式期间节流取值：每个窗口内首帧直通、后续更新合并为窗口末尾一次
 * （leading + trailing），避免高频 chunk 全量重解析。intervalMs=0 直通
 * （终态 / 已定稿内容不再变化，无节流必要）。
 */
function useThrottledValue<T>(value: T, intervalMs: number): T {
  const [throttled, setThrottled] = useState(value)
  const lastEmitRef = useRef(-Infinity)
  const timerRef = useRef<number | null>(null)
  const valueRef = useRef(value)
  valueRef.current = value

  useEffect(() => {
    if (value === throttled) {
      return
    }
    const elapsed = Date.now() - lastEmitRef.current
    if (elapsed >= intervalMs) {
      lastEmitRef.current = Date.now()
      setThrottled(value)
    } else if (timerRef.current == null) {
      // trailing：窗口末尾取最新值（期间新值持续写 valueRef，天然合并）。
      timerRef.current = window.setTimeout(() => {
        timerRef.current = null
        lastEmitRef.current = Date.now()
        setThrottled(valueRef.current)
      }, intervalMs - elapsed)
    }
    // 注意：不在此清理 trailing 定时器——高频更新会反复重跑本 effect，
    // 中途清理会让末次更新丢失；卸载清理见下方独立 effect。
  }, [value, throttled, intervalMs])

  useEffect(() => {
    return () => {
      if (timerRef.current != null) {
        window.clearTimeout(timerRef.current)
        timerRef.current = null
      }
    }
  }, [])

  return throttled
}

const containerStyle: React.CSSProperties = {
  margin: '8px 0',
  padding: 12,
  border: '1px solid var(--ea-border-color, #e5e5e5)',
  borderRadius: 8,
  background: 'var(--ea-bg-container, #fff)',
  overflow: 'auto',
  lineHeight: 0,
  position: 'relative',
}

// ── 操作按钮（复制 / 下载）─────────────────────────────────────────────
//
// 交互形态（按输入设备区分）：
//   - hover 设备（桌面鼠标）：鼠标悬停容器时按钮浮现（CSS :hover，模块级样式表）；
//   - 触摸设备（hover: none）：点击图形区域 toggle 按钮显隐（--open 类）。
// 按钮触发即用反馈（✓/✕ 图标切换），不弹全局 toast，保持插件自包含。
//
// 下载走浏览器原生下载（Blob + objectURL + <a download>）：内容落在**运行
// 浏览器的设备**上——通过 web 远程访问时即下载到访问端，与宿主 downloadBlob
// 同一机制（插件不可 import 宿主内部路径，此处内联同等实现）。

let actionStylesInjected = false

/** 注入操作按钮样式（模块级一次；hover 行为无法用 inline style 表达）。 */
function ensureActionStyles(): void {
  if (actionStylesInjected || typeof document === 'undefined') {
    return
  }
  actionStylesInjected = true
  const style = document.createElement('style')
  style.textContent = `
.ea-svg-wrap { position: relative; }
.ea-svg-actions {
  position: absolute; top: 8px; right: 8px; z-index: 2;
  display: flex; gap: 6px;
  opacity: 0; visibility: hidden; pointer-events: none;
  transition: opacity .15s ease, visibility .15s ease;
}
.ea-svg-actions.ea-svg-actions--open,
.ea-svg-actions:focus-within { opacity: 1; visibility: visible; pointer-events: auto; }
@media (hover: hover) {
  .ea-svg-wrap:hover .ea-svg-actions { opacity: 1; visibility: visible; pointer-events: auto; }
}
.ea-svg-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 28px; height: 28px; padding: 0;
  border: 1px solid var(--ea-border-color, #e5e5e5);
  border-radius: 6px;
  background: var(--ea-bg-container, #fff);
  color: var(--ea-text-secondary, #666);
  cursor: pointer;
  box-shadow: 0 1px 4px rgba(0, 0, 0, .1);
}
.ea-svg-btn:hover { color: #4a6cf7; border-color: #4a6cf7; }
.ea-svg-btn:active { transform: scale(.94); }
.ea-svg-btn--ok { color: #22c55e; border-color: #22c55e; }
.ea-svg-btn--fail { color: #d4380d; border-color: #d4380d; }
`
  document.head.appendChild(style)
}

ensureActionStyles()

/** 是否纯触摸设备（无 hover 能力）：决定按钮显隐走「点击 toggle」还是「悬停浮现」。 */
function useTouchMode(): boolean {
  const [touch, setTouch] = useState(false)
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') {
      return
    }
    const mq = window.matchMedia('(hover: none)')
    const apply = () => setTouch(mq.matches)
    apply()
    mq.addEventListener('change', apply)
    return () => mq.removeEventListener('change', apply)
  }, [])
  return touch
}

/** 复制文本：clipboard API 优先，非安全上下文（http 访问）降级 execCommand。 */
async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    // Clipboard API 仅安全上下文可用；局域网 http 访问时走隐藏 textarea 兜底。
    try {
      const ta = document.createElement('textarea')
      ta.value = text
      ta.style.position = 'fixed'
      ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      const ok = document.execCommand('copy')
      document.body.removeChild(ta)
      return ok
    } catch {
      return false
    }
  }
}

/** 下载文件名：svg-YYYYMMDD-HHmmss.svg（时间戳防重名）。 */
function svgFileName(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `svg-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}.svg`
}

/** 触发浏览器原生下载（内容落在运行浏览器的设备上）。 */
function downloadSvg(svgMarkup: string): void {
  const doc = `<?xml version="1.0" encoding="UTF-8"?>\n${svgMarkup}`
  const blob = new Blob([doc], { type: 'image/svg+xml;charset=utf-8' })
  const objectUrl = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = objectUrl
  anchor.download = svgFileName()
  anchor.rel = 'noopener'
  document.body.appendChild(anchor)
  anchor.click()
  document.body.removeChild(anchor)
  URL.revokeObjectURL(objectUrl)
}

/** 操作按钮栏。复制/下载内容均为消毒后的 SVG（与所见图形一致，可直接使用）。 */
function SvgActions({ svgMarkup, open }: { svgMarkup: string; open: boolean }) {
  /** 复制反馈：ok / fail / null（无反馈）。 */
  const [feedback, setFeedback] = useState<'ok' | 'fail' | null>(null)
  const timerRef = useRef<number | null>(null)

  useEffect(() => {
    return () => {
      if (timerRef.current != null) {
        window.clearTimeout(timerRef.current)
      }
    }
  }, [])

  const showFeedback = (kind: 'ok' | 'fail') => {
    setFeedback(kind)
    if (timerRef.current != null) {
      window.clearTimeout(timerRef.current)
    }
    timerRef.current = window.setTimeout(() => setFeedback(null), 1500)
  }

  const handleCopy = async (e: React.MouseEvent) => {
    e.stopPropagation() // 触摸模式下防止冒泡到容器 toggle 关闭按钮栏
    const ok = await copyText(svgMarkup)
    showFeedback(ok ? 'ok' : 'fail')
  }

  const handleDownload = (e: React.MouseEvent) => {
    e.stopPropagation()
    downloadSvg(svgMarkup)
  }

  const copyClass =
    feedback === 'ok' ? 'ea-svg-btn ea-svg-btn--ok' : feedback === 'fail' ? 'ea-svg-btn ea-svg-btn--fail' : 'ea-svg-btn'

  return (
    <div className={open ? 'ea-svg-actions ea-svg-actions--open' : 'ea-svg-actions'}>
      <button
        type="button"
        className={copyClass}
        title="复制 SVG 源码"
        aria-label="复制 SVG 源码"
        onClick={handleCopy}
      >
        {feedback === 'ok' ? <CheckOutlined /> : feedback === 'fail' ? <CloseOutlined /> : <CopyOutlined />}
      </button>
      <button
        type="button"
        className="ea-svg-btn"
        title="下载 SVG 文件"
        aria-label="下载 SVG 文件"
        onClick={handleDownload}
      >
        <DownloadOutlined />
      </button>
    </div>
  )
}

/** 渐进渲染状态提示（图下方小字）。 */
const statusStyle: React.CSSProperties = {
  margin: '8px 0 0',
  fontSize: 12,
  color: 'var(--ea-text-secondary, #888)',
  lineHeight: 1.6,
}

/** 截断告示（内容不完整，比生成中提示更醒目）。 */
const truncatedStyle: React.CSSProperties = {
  ...statusStyle,
  color: '#d48806',
}

/** 生成中占位行。 */
const placeholderStyle: React.CSSProperties = {
  ...statusStyle,
  margin: 0,
  padding: '4px 0',
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
  /** 流式生成中（闭合标签未到达，消息仍在输出）。 */
  streaming?: boolean
  /** 消息已定稿但闭合标签缺席（输出中断，内容不完整）。 */
  truncated?: boolean
}

export default function SvgBlock({ svgSource, streaming, truncated }: SvgBlockProps) {
  const progressive = Boolean(streaming || truncated)
  // 流式期间节流重解析；定稿 / 终态直通（内容已定型，intervalMs=0）。
  const effectiveSource = useThrottledValue(svgSource, streaming ? 160 : 0)

  // 操作按钮：触摸设备点击图形区域 toggle 显隐；桌面设备由 CSS hover 接管。
  const touchMode = useTouchMode()
  const [actionsOpen, setActionsOpen] = useState(false)

  const result = useMemo<SanitizeResult>(() => {
    if (!progressive) {
      return sanitizeSvg(effectiveSource)
    }
    // 渐进路径：容错闭合后走同一消毒管线（安全标准与终态一致）。
    const fragment = closeSvgFragment(effectiveSource)
    if (fragment.closed != null) {
      const sanitized = sanitizeSvg(fragment.closed)
      if (sanitized.html != null) {
        return sanitized
      }
    }
    // 根标签尚未完整到达（内容太早）或容错闭合仍解析失败。
    return { html: null, error: '生成中' }
  }, [effectiveSource, progressive])

  if (result.html !== null) {
    return (
      <div
        className="ea-svg-wrap"
        style={containerStyle}
        onClick={streaming ? undefined : touchMode ? () => setActionsOpen(v => !v) : undefined}
      >
        {/* 消毒产物：白名单元素 + 已剔除 on* / href 属性，可安全注入 */}
        <div dangerouslySetInnerHTML={{ __html: result.html }} />
        {streaming ? null : <SvgActions svgMarkup={result.html} open={actionsOpen} />}
        {streaming ? <p style={statusStyle}>▍图形生成中…</p> : null}
        {truncated ? <p style={truncatedStyle}>⚠ 图形内容不完整（输出已中断）</p> : null}
      </div>
    )
  }

  // 生成中且暂无可渲染内容：占位等待（不倾倒源码——这正是本次要消除的体验）。
  if (streaming) {
    return (
      <div style={containerStyle}>
        <p style={placeholderStyle}>▍正在生成 SVG 图形…</p>
      </div>
    )
  }

  return (
    <div style={{ ...containerStyle, lineHeight: 1.6 }}>
      <p style={errorHintStyle}>
        SVG 渲染失败（{truncated ? '输出中断，内容不完整' : result.error ?? '未知错误'}），显示原始内容：
      </p>
      <pre style={rawTextStyle}>{svgSource}</pre>
    </div>
  )
}
