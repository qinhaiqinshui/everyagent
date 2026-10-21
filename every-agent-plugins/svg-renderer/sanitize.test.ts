/**
 * svg-renderer 净化器测试（审计 M-5 整改）。
 *
 * 运行（仓库根）：
 *   every-agent-web\node_modules\.bin\vitest run --config every-agent-plugins\svg-renderer\vitest.config.ts
 *
 * node 环境无 DOMParser / XMLSerializer：净化器（web/sanitize.ts）经 SanitizeDom
 * 参数注入下方「最小 XML DOM 替身」（元素/文本节点 + 递归下降 XML 解析 + 序列化），
 * 端到端验证 字符串输入 → 净化 → 序列化输出，不引入 jsdom 等新依赖。
 *
 * 本文件位于插件根而非 web/：every-agent-web 的 tsconfig include 会把
 * every-agent-plugins 下各插件的 web 目录纳入 tsc --noEmit，而 'vitest' 类型
 * 自插件目录不可解析（见 vitest.config.ts 注释）。
 */

import { describe, expect, it } from 'vitest'
import { sanitizeSvgHtml, type SanitizeDom } from './web/sanitize'

// ── 测试替身：最小 XML DOM ─────────────────────────────────────────────

class TNode {
  childNodes: TNode[] = []
}

class TElement extends TNode {
  nodeType = 1
  attributes: { name: string; value: string }[] = []
  constructor(public tagName: string) {
    super()
  }
  get textContent(): string {
    return this.childNodes.map(c => c.textContent).join('')
  }
  setAttribute(name: string, value: string): void {
    const hit = this.attributes.find(a => a.name === name)
    if (hit) hit.value = value
    else this.attributes.push({ name, value })
  }
  appendChild(n: TNode): TNode {
    this.childNodes.push(n)
    return n
  }
}

class TText extends TNode {
  nodeType = 3
  constructor(public data: string) {
    super()
  }
  get textContent(): string {
    return this.data
  }
}

/**
 * 递归下降 XML 解析（栈式）：元素（单/双引号属性、自闭合）、文本。
 * 标签不匹配、未闭合、无根元素即抛错（对应 parsererror）。
 */
function parseXmlTest(source: string): TElement {
  const document = new TElement('#document')
  const stack: TElement[] = [document]
  // 属性段允许引号内出现 > 与 /（如 url("https://x/")），引号外禁止裸 > 。
  const tagRe = /<(\/?)([A-Za-z_][\w:.-]*)((?:[^<>'"]|'[^']*'|"[^"]*")*?)(\/?)>/g
  const attrRe = /([A-Za-z_][\w:.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')/g
  const unescape = (s: string) =>
    s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&amp;/g, '&')
  let last = 0
  let m: RegExpExecArray | null
  while ((m = tagRe.exec(source)) !== null) {
    if (m.index > last) {
      stack[stack.length - 1].appendChild(new TText(source.slice(last, m.index)))
    }
    const closing = m[1]
    const name = m[2]
    if (closing) {
      const top = stack.pop()
      if (!top || top.tagName !== name) throw new Error(`闭合标签不匹配: ${name}`)
    } else {
      const el = new TElement(name)
      for (const a of m[3].matchAll(attrRe)) {
        el.setAttribute(a[1], unescape(a[2] ?? a[3] ?? ''))
      }
      stack[stack.length - 1].appendChild(el)
      if (!m[4]) stack.push(el)
    }
    last = tagRe.lastIndex
  }
  if (last < source.length) {
    stack[stack.length - 1].appendChild(new TText(source.slice(last)))
  }
  if (stack.length !== 1) throw new Error(`未闭合元素: ${stack.slice(1).map(e => e.tagName).join('/')}`)
  const root = document.childNodes.find(c => c.nodeType === 1)
  if (!root) throw new Error('无根元素')
  return root as TElement
}

const escapeText = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

function serializeTest(node: TNode): string {
  if (node.nodeType === 3) return escapeText((node as TText).data)
  const el = node as TElement
  const attrs = el.attributes.map(a => ` ${a.name}="${escapeText(a.value).replace(/"/g, '&quot;')}"`).join('')
  const inner = el.childNodes.map(serializeTest).join('')
  return inner === '' ? `<${el.tagName}${attrs}/>` : `<${el.tagName}${attrs}>${inner}</${el.tagName}>`
}

const testDom: SanitizeDom = {
  parseXml(source) {
    try {
      return { documentElement: parseXmlTest(source) as unknown as Element, parseError: false }
    } catch {
      return { documentElement: null, parseError: true }
    }
  },
  createElementNS(_ns: string, qualifiedName: string) {
    return new TElement(qualifiedName) as unknown as Element
  },
  createTextNode(data: string) {
    return new TText(data) as unknown as Node
  },
  serializeToString(node: Node) {
    return serializeTest(node as unknown as TNode)
  },
}

// ── 用例 ────────────────────────────────────────────────────────────────

describe('sanitizeSvgHtml（svg-renderer 净化器，审计 M-5）', () => {
  it('① style 属性被剥离（堵 CSS url() 外链信标信道）', () => {
    const r = sanitizeSvgHtml(
      '<svg style="background:url(https://evil.com/beacon)" width="20">' +
        '<rect width="4" height="4" style="fill:url(#g);background:url(https://evil.com/x)"/></svg>',
      testDom,
    )
    expect(r.error).toBeUndefined()
    expect(r.html).toBeTruthy()
    expect(r.html).not.toContain('style')
    expect(r.html).not.toContain('evil.com')
    expect(r.html).toContain('width="20"') // 其余属性不受影响
  })

  it('② <image> 元素整体移除（innerHTML 把 image 别名为 img，src 即任意外链图片）', () => {
    const r = sanitizeSvgHtml(
      '<svg><image src="https://evil.com/i.png" href="https://evil.com/i.png" width="10" height="10"/>' +
        '<circle cx="5" cy="5" r="4"/></svg>',
      testDom,
    )
    expect(r.error).toBeUndefined()
    expect(r.html).toBeTruthy()
    expect(r.html).not.toContain('image')
    expect(r.html).not.toContain('src=')
    expect(r.html).not.toContain('evil.com')
    expect(r.html).toContain('<circle') // 白名单内兄弟元素保留
  })

  it('②b <foreignObject> 元素移除（显式出白名单，内嵌 HTML 子树一并丢弃）', () => {
    const r = sanitizeSvgHtml(
      '<svg><foreignObject width="10" height="10"><div onload="x">hi</div></foreignObject>' +
        '<line x1="0" y1="0" x2="9" y2="9"/></svg>',
      testDom,
    )
    expect(r.html).toBeTruthy()
    expect(r.html).not.toContain('foreignObject')
    expect(r.html).not.toContain('div')
    expect(r.html).toContain('<line')
  })

  it('③ on* / href / xlink:href 仍被剥离（防回归）', () => {
    const r = sanitizeSvgHtml(
      '<svg onload="alert(1)"><rect width="4" height="4" onerror="steal()" ' +
        'href="https://evil.com/l" xlink:href="https://evil.com/r"/></svg>',
      testDom,
    )
    expect(r.html).toBeTruthy()
    expect(r.html).not.toContain('onload')
    expect(r.html).not.toContain('onerror')
    expect(r.html).not.toContain('href')
    expect(r.html).not.toContain('evil.com')
    expect(r.html).toContain('<rect')
  })

  it('④ 正常 SVG（defs/渐变/g/path/circle/rect/line/polyline/polygon/ellipse/text/tspan 等）不受影响', () => {
    const src =
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">' +
      '<defs><linearGradient id="g1"><stop offset="0%" stop-color="#f00"/></linearGradient></defs>' +
      '<g fill="none" stroke="#333">' +
      '<path d="M10 10 L90 90"/><circle cx="50" cy="50" r="40"/><rect x="8" y="8" width="30" height="30"/>' +
      '<line x1="0" y1="0" x2="99" y2="99"/><polyline points="0,0 10,20 20,0"/><polygon points="60,60 70,70 50,70"/>' +
      '<ellipse cx="50" cy="75" rx="20" ry="10"/>' +
      '</g>' +
      '<text x="5" y="20" font-size="12">你好 <tspan fill="red">svg</tspan></text>' +
      '</svg>'
    const r = sanitizeSvgHtml(src, testDom)
    expect(r.error).toBeUndefined()
    expect(r.html).toBeTruthy()
    for (const tag of [
      'svg',
      'defs',
      'linearGradient',
      'stop',
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
    ]) {
      expect(r.html).toContain(`<${tag}`)
    }
    expect(r.html).toContain('viewBox="0 0 100 100"') // 属性名大小写原样保留
    expect(r.html).toContain('d="M10 10 L90 90"')
    expect(r.html).toContain('你好')
  })

  it('⑤ 既有行为保持：代码围栏剥离、空内容、非 svg 根、XML 语法错误', () => {
    expect(sanitizeSvgHtml('```svg\n<svg><path d="M0 0"/></svg>\n```', testDom).html).toContain('<path')
    expect(sanitizeSvgHtml('   ', testDom)).toEqual({ html: null, error: '空内容' })
    expect(sanitizeSvgHtml('<notsvg><path/></notsvg>', testDom)).toEqual({
      html: null,
      error: '内容不是合法的 <svg> 文档',
    })
    expect(sanitizeSvgHtml('<svg><path', testDom)).toEqual({ html: null, error: 'SVG 解析失败（XML 语法错误）' })
  })
})
