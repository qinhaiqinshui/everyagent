/**
 * SVG 净化器（单一来源）——把不可信（模型流式输出）的 SVG 源码消毒为可经
 * dangerouslySetInnerHTML 安全注入的白名单子集。
 *
 * 使用方：web/SvgBlock.tsx（svg-image 输出块）；后续 icon 净化等场景一律
 * 复用本模块，禁止各自另写等价净化逻辑。
 *
 * 安全策略（白名单元素 + 属性黑名单）：
 *   - 只允许 SVG 图形元素进入输出。显式不包含：
 *       · script / a / style 元素本就不在白名单；
 *       · image —— innerHTML 的 HTML 解析器把 image 别名为 img，src 即
 *         任意 https 外链图片加载（CSP img-src https: 放行），审计 M-5
 *         将其整体移出白名单（元素丢弃，而非仅滤 src）；
 *       · foreignObject —— 可内嵌任意 HTML 子树，显式移除（此前仅靠
 *         子元素白名单间接排除，现使其显式化）。
 *   - 属性黑名单（属性名小写后比较）命中即移除：
 *       · href / xlink:href —— 外部引用；
 *       · src —— 外链资源（image 已整体移出白名单，此处兜底拦截任何残留）；
 *       · style —— 内联样式：CSS url()/background 等同样构成外链信标信道，
 *         且取值空间无法静态枚举，属性整体丢弃。
 *   - 所有 on* 事件属性一律移除。
 *   - 仅使用浏览器内置 DOMParser / XMLSerializer，零第三方依赖。
 *   - DOM 能力经 SanitizeDom 注入，默认取浏览器全局；import 本模块不触碰
 *     任何 DOM 全局，node 环境单测可注入测试替身。
 *   - 渐进路径与终态共用同一消毒管线，流式期间不降低安全标准。
 */

/** DOM 节点类型常量（用字面量而非全局 Node：node 环境无该全局）。 */
const ELEMENT_NODE = 1
const TEXT_NODE = 3

const SVG_NS = 'http://www.w3.org/2000/svg'

/**
 * 允许渲染的 SVG 元素白名单（含渐变 / 裁剪 / 文本等；
 * 不含 script / a / style / image / foreignObject）。
 */
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
])

/** 属性名命中即移除（小写后比较）：外链引用（href / xlink:href / src）与内联样式（style）。 */
const BLOCKED_ATTRS = new Set(['href', 'xlink:href', 'src', 'style'])

/** 净化产物：可注入的 SVG 标记；解析失败或无根 svg 时为 null。 */
export interface SanitizeResult {
  html: string | null
  error?: string
}

/** 净化器所需的最小 DOM 能力（默认浏览器实现；node 测试注入替身）。 */
export interface SanitizeDom {
  /** 按 XML 解析；parseError 置位表示语法错误（对应 DOMParser 的 parsererror）。 */
  parseXml(source: string): { documentElement: Element | null; parseError: boolean }
  createElementNS(ns: string, qualifiedName: string): Element
  createTextNode(data: string): Node
  serializeToString(node: Node): string
}

/** 去掉模型输出里可能包裹的 ``` 代码围栏（```svg / ```xml / 裸 ```）。 */
function stripCodeFence(source: string): string {
  const trimmed = source.trim()
  const m = /^```[\w-]*\s*\n([\s\S]*?)\n?```\s*$/.exec(trimmed)
  return m ? m[1].trim() : trimmed
}

/** 浏览器默认 DOM 实现（延迟取全局：模块在 node 下被 import 时不触碰 DOM）。 */
function browserSanitizeDom(): SanitizeDom {
  if (typeof globalThis.DOMParser !== 'function' || typeof globalThis.XMLSerializer !== 'function') {
    throw new Error('sanitizeSvgHtml: 当前环境缺少 DOMParser / XMLSerializer（浏览器外调用需注入 SanitizeDom）')
  }
  return {
    parseXml(source) {
      const doc = new globalThis.DOMParser().parseFromString(source, 'image/svg+xml')
      return { documentElement: doc.documentElement, parseError: doc.querySelector('parsererror') !== null }
    },
    createElementNS(ns, qualifiedName) {
      return document.createElementNS(ns, qualifiedName)
    },
    createTextNode(data) {
      return document.createTextNode(data)
    },
    serializeToString(node) {
      return new globalThis.XMLSerializer().serializeToString(node)
    },
  }
}

/** 递归消毒：白名单元素 + 过滤危险属性，文本节点原样保留，其余节点丢弃。 */
function sanitizeNode(node: Node, dom: SanitizeDom): Node | null {
  if (node.nodeType === TEXT_NODE) {
    return dom.createTextNode(node.textContent ?? '')
  }
  if (node.nodeType !== ELEMENT_NODE) {
    return null
  }
  const el = node as Element
  if (!ALLOWED_TAGS.has(el.tagName)) {
    return null
  }
  const clean = dom.createElementNS(SVG_NS, el.tagName)
  for (const attr of Array.from(el.attributes)) {
    const name = attr.name.toLowerCase()
    if (name.startsWith('on') || BLOCKED_ATTRS.has(name)) {
      continue
    }
    clean.setAttribute(attr.name, attr.value)
  }
  for (const child of Array.from(el.childNodes)) {
    const safe = sanitizeNode(child, dom)
    if (safe) {
      clean.appendChild(safe)
    }
  }
  return clean
}

/**
 * 净化不可信 SVG 源码（单一来源）。
 * @param raw 原始内容（可含 ``` 代码围栏；流式渐进路径需先 closeSvgFragment 容错闭合）
 * @param dom DOM 实现，缺省取浏览器全局；node 单测注入 SanitizeDom 替身
 */
export function sanitizeSvgHtml(raw: string, dom?: SanitizeDom): SanitizeResult {
  const source = stripCodeFence(raw)
  if (!source) {
    return { html: null, error: '空内容' }
  }
  const d = dom ?? browserSanitizeDom()
  let parsed: { documentElement: Element | null; parseError: boolean }
  try {
    parsed = d.parseXml(source)
  } catch (e) {
    return { html: null, error: e instanceof Error ? e.message : String(e) }
  }
  if (parsed.parseError) {
    return { html: null, error: 'SVG 解析失败（XML 语法错误）' }
  }
  const root = parsed.documentElement
  if (!root || root.tagName !== 'svg') {
    return { html: null, error: '内容不是合法的 <svg> 文档' }
  }
  const cleanRoot = sanitizeNode(root, d)
  if (!cleanRoot) {
    return { html: null, error: '消毒后没有可渲染内容' }
  }
  return { html: d.serializeToString(cleanRoot) }
}
