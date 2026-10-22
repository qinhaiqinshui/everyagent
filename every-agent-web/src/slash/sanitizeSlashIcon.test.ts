/**
 * slash 菜单 icon 净化测试（审计 M-8 整改）。
 *
 * TaskComposerSurface（`/` 菜单）与 SlashItemDetailPopover（详情弹层）两处渲染点
 * 已统一改为 `dangerouslySetInnerHTML={{ __html: sanitizeSlashIcon(icon) ?? '' }}`
 * （本 helper 是单一注入入口，null → 空 span 占位）。本文件按项目 vitest 约定
 * （node 环境、无 jsdom）用与 svg-renderer/sanitize.test.ts 同构的「最小 XML DOM
 * 替身」注入 SanitizeDom，端到端验证 icon 字符串 → 净化 → 可注入产物。
 */
import { describe, expect, it } from 'vitest'
import { sanitizeSlashIcon } from './sanitizeSlashIcon'
import type { SanitizeDom } from '@plugins/svg-renderer/web/sanitize'

// ── 测试替身：最小 XML DOM（与 every-agent-plugins/svg-renderer/sanitize.test.ts 同构；
// 差异：本文件在 src 下参与 tsc --noEmit，故公共成员上提到基类以通过类型检查）──

class TNode {
  childNodes: TNode[] = []
  nodeType = 0
  get textContent(): string {
    return this.childNodes.map((c) => c.textContent).join('')
  }
}

class TElement extends TNode {
  override nodeType = 1
  attributes: { name: string; value: string }[] = []
  constructor(public tagName: string) {
    super()
  }
  setAttribute(name: string, value: string): void {
    const hit = this.attributes.find((a) => a.name === name)
    if (hit) hit.value = value
    else this.attributes.push({ name, value })
  }
  appendChild(n: TNode): TNode {
    this.childNodes.push(n)
    return n
  }
}

class TText extends TNode {
  override nodeType = 3
  constructor(public data: string) {
    super()
  }
  override get textContent(): string {
    return this.data
  }
}

/** 递归下降 XML 解析（栈式）：标签不匹配、未闭合、无根元素即抛错（对应 parsererror）。 */
function parseXmlTest(source: string): TElement {
  const document = new TElement('#document')
  const stack: TElement[] = [document]
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
    if (m[1]) {
      const top = stack.pop()
      if (!top || top.tagName !== m[2]) throw new Error(`闭合标签不匹配: ${m[2]}`)
    } else {
      const el = new TElement(m[2])
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
  if (stack.length !== 1) throw new Error('未闭合元素')
  const root = document.childNodes.find((c) => c.nodeType === 1)
  if (!root) throw new Error('无根元素')
  return root as TElement
}

const escapeText = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

function serializeTest(node: TNode): string {
  if (node.nodeType === 3) return escapeText((node as TText).data)
  const el = node as TElement
  const attrs = el.attributes.map((a) => ` ${a.name}="${escapeText(a.value).replace(/"/g, '&quot;')}"`).join('')
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

describe('sanitizeSlashIcon（slash 菜单 icon 净化，审计 M-8）', () => {
  it('① 恶意 <svg onload=…> 事件属性被剥离，正常图形保留', () => {
    const icon =
      '<svg viewBox="0 0 16 16" onload="alert(1)">' +
      '<path d="M1 1L9 9" onerror="steal()" onbegin="x"/></svg>'
    const html = sanitizeSlashIcon(icon, testDom)
    expect(html).toBeTruthy()
    expect(html).not.toContain('onload')
    expect(html).not.toContain('onerror')
    expect(html).not.toContain('alert')
    expect(html).not.toContain('steal')
    expect(html).toContain('<path')
    expect(html).toContain('viewBox="0 0 16 16"') // 其余属性不受影响
  })

  it('① <svg> 内夹带 script / image 外链元素被整体丢弃', () => {
    const icon =
      '<svg><script>alert(1)</script>' +
      '<image src="https://evil.com/i.png" href="https://evil.com/i.png"/>' +
      '<circle cx="5" cy="5" r="4"/></svg>'
    const html = sanitizeSlashIcon(icon, testDom)
    expect(html).toBeTruthy()
    expect(html).not.toContain('script')
    expect(html).not.toContain('image')
    expect(html).not.toContain('src=')
    expect(html).not.toContain('evil.com')
    expect(html).toContain('<circle') // 白名单内元素保留
  })

  it('① 非 SVG 根形态（<img onerror=…>）按契约拒绝 → null（渲染占位，不按纯文本/原样注入）', () => {
    expect(sanitizeSlashIcon('<img src=x onerror="alert(1)">', testDom)).toBeNull()
    expect(sanitizeSlashIcon('<a href="javascript:alert(1)">链接</a>', testDom)).toBeNull()
  })

  it('② 正常内联 SVG icon（worker SkillSlashProvider 形态）不受影响', () => {
    const icon =
      '<svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor"' +
      ' stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
      '<path d="M3.2 4.1C3.2 3.5 3.7 3 4.3 3H7.6V13H4.5C3.8 13 3.2 12.4 3.2 11.7V4.1Z" /></svg>'
    const html = sanitizeSlashIcon(icon, testDom)
    expect(html).toBeTruthy()
    for (const part of [
      '<svg',
      'viewBox="0 0 16 16"',
      'width="14"',
      'height="14"',
      'fill="none"',
      'stroke="currentColor"',
      'stroke-width="1.6"',
      'aria-hidden="true"',
      '<path',
    ]) {
      expect(html).toContain(part)
    }
  })

  it('③ 非法 / 空输入 → null（调用方渲染占位空 span）', () => {
    expect(sanitizeSlashIcon(undefined, testDom)).toBeNull()
    expect(sanitizeSlashIcon('', testDom)).toBeNull()
    expect(sanitizeSlashIcon('   ', testDom)).toBeNull()
    expect(sanitizeSlashIcon('🚀 纯文本图标', testDom)).toBeNull() // 非 XML、非 svg 根
    expect(sanitizeSlashIcon('<svg><path d="M0 0"', testDom)).toBeNull() // XML 语法错误
  })

  it('④ 浏览器 DOM 缺失（如 node 环境）兜底返回 null 而非抛错/注入（安全方向失败）', () => {
    // 不注入 SanitizeDom 且当前环境无 DOMParser：宁可无图标，不可注入未消毒内容。
    expect(sanitizeSlashIcon('<svg onload="alert(1)"><path d="M0 0"/></svg>')).toBeNull()
  })

  it('⑤ 同一输入重复净化结果稳定（菜单高频重渲染路径）', () => {
    const icon = '<svg><path d="M0 0L9 9"/></svg>'
    expect(sanitizeSlashIcon(icon, testDom)).toEqual(sanitizeSlashIcon(icon, testDom))
  })
})
