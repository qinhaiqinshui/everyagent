/**
 * 表格单元格编辑与列宽算法测试。
 * - markdownTableEdit 纯函数:转义口径、offset 区间替换与并发修改防护、触屏双击判定;
 * - computeTableColumnWidths:max 单元格口径 + CJK 视觉宽度 + min/max clamp;
 * - 渲染级:colgroup ch 列宽、横向滚动 wrap、可编辑单元格标注、移动端 touch-action。
 */
import { describe, it, expect } from 'vitest'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import {
  escapeTableCellText,
  replaceMarkdownRange,
  resolveCellRange,
  isTouchDoubleTap,
} from './markdownTableEdit'
import { buildMarkdownComponents } from './sharedMarkdownRenderer'
import { allocateColumnWidths, applyColumnOverrides, computeFallbackColumnWidths } from './useTableLayout'
import { shouldPinHeader } from './usePinnedTableHeader'
import { unified } from 'unified'
import remarkParse from 'remark-parse'
import remarkRehype from 'remark-rehype'

describe('resolveCellRange(单元格区间规范化)', () => {
  const source = '| a | b |\n| --- | --- |\n| c | d |'

  it('剥除前导分隔符与空白(hast 区间含 `| 内容 `)', () => {
    // mdast/hast 的 td 区间从前导 `|` 开始:source.slice(0,4) === '| a '。
    expect(resolveCellRange(source, 0, 4)).toEqual({ start: 2, end: 3, original: 'a' })
  })

  it('剥除行尾单元格的行尾分隔符', () => {
    // 末格区间含 `| d |`:前导管道 + 内容 + 空格 + 行尾管道。
    const tail = source.lastIndexOf('| d |')
    expect(resolveCellRange(source, tail, tail + 5)).toEqual({
      start: tail + 2,
      end: tail + 3,
      original: 'd',
    })
  })

  it('保留边界内的转义竖线 \\| (它是内容)', () => {
    const md = '| a \\| b |'
    // 区间整体 '| a \\| b |':边界管道剥除,内部 \\| 保留。
    const range = resolveCellRange(md, 0, md.length)
    expect(range?.original).toBe('a \\| b')
  })

  it('空单元格返回零宽区间', () => {
    const md = '| a |  |'
    // 末格区间 '|  |' 全是分隔符与空白。
    const range = resolveCellRange(md, 5, md.length)
    expect(range?.original).toBe('')
    expect(range?.start).toBe(range?.end)
  })

  it('非法区间返回 null', () => {
    expect(resolveCellRange(source, -1, 3)).toBeNull()
    expect(resolveCellRange(source, 0, source.length + 1)).toBeNull()
    expect(resolveCellRange(source, 5, 2)).toBeNull()
    expect(resolveCellRange(source, Number.NaN, 3)).toBeNull()
  })
})

describe('resolveCellRange 对真实 remark-gfm 管线(锁定分隔符口径)', () => {
  const md = [
    '| 名称 | 说明 |',
    '| --- | --- |',
    '| alpha | 带转义竖线 a \\| b |',
    '| beta | 普通 |',
  ].join('\n')

  /** 走与 react-markdown 相同的 parse→gfm→rehype 管线,收集 hast td/th 的 position。 */
  function collectCellOffsets(markdown: string): { tag: string; start: number; end: number }[] {
    const proc = unified().use(remarkParse).use(remarkGfm).use(remarkRehype)
    const hast = proc.runSync(proc.parse(markdown))
    const cells: { tag: string; start: number; end: number }[] = []
    const walk = (node: unknown): void => {
      if (!node || typeof node !== 'object') return
      const element = node as {
        tagName?: string
        position?: { start: { offset: number }; end: { offset: number } }
        children?: unknown[]
      }
      if (element.tagName === 'td' || element.tagName === 'th') {
        cells.push({ tag: element.tagName, start: element.position!.start.offset, end: element.position!.end.offset })
      }
      element.children?.forEach(walk)
    }
    walk(hast)
    return cells
  }

  it('原始区间确实包含边界分隔符(口径前提)', () => {
    const cells = collectCellOffsets(md)
    // 首格区间以 `|` 开头、末格区间以 `|` 结尾——这是 resolveCellRange 存在的原因。
    expect(md.slice(cells[0].start, cells[0].start + 1)).toBe('|')
    expect(md.slice(cells[1].end - 1, cells[1].end)).toBe('|')
  })

  it('规范化后每个单元格 original 精确等于单元格文本', () => {
    const expected = [
      '名称', '说明',
      'alpha', '带转义竖线 a \\| b',
      'beta', '普通',
    ]
    const cells = collectCellOffsets(md)
    const originals = cells.map((cell) => resolveCellRange(md, cell.start, cell.end)?.original)
    expect(originals).toEqual(expected)
  })

  it('用规范化区间做替换:只改本格,不吞相邻分隔符', () => {
    const cells = collectCellOffsets(md)
    const target = cells.find((cell) => md.slice(cell.start, cell.end).includes('beta'))!
    const range = resolveCellRange(md, target.start, target.end)!
    const next = replaceMarkdownRange(md, {
      start: range.start,
      end: range.end,
      original: range.original,
      next: 'BETA',
    })!
    expect(next).toContain('| BETA |')
    expect(next).toContain('| alpha |')
    expect(next.split('\n')[3].split('|').filter(Boolean).length).toBe(2)
  })
})

describe('escapeTableCellText', () => {
  it('未转义的竖线补转义', () => {
    expect(escapeTableCellText('a|b')).toBe('a\\|b')
  })

  it('已转义的竖线不二次转义', () => {
    expect(escapeTableCellText('a\\|b')).toBe('a\\|b')
  })

  it('换行折叠为空格', () => {
    expect(escapeTableCellText('a\nb\r\nc')).toBe('a b c')
  })

  it('普通文本原样保留', () => {
    expect(escapeTableCellText('**粗体** 与 `code`')).toBe('**粗体** 与 `code`')
  })
})

describe('replaceMarkdownRange', () => {
  const source = '| a | b |\n| --- | --- |\n| 1 | 2 |'

  it('按 offset 区间替换单元格内容', () => {
    const next = replaceMarkdownRange(source, { start: 2, end: 3, original: 'a', next: 'A' })
    expect(next).toBe('| A | b |\n| --- | --- |\n| 1 | 2 |')
  })

  it('支持内联语法片段的整体替换', () => {
    // 源中单元格为 `**x**`(offset 2..7)
    const md = '| **x** | b |'
    const next = replaceMarkdownRange(md, { start: 2, end: 7, original: '**x**', next: '`y`' })
    expect(next).toBe('| `y` | b |')
  })

  it('源片段与编辑初值不一致时放弃(返回 null)', () => {
    expect(replaceMarkdownRange(source, { start: 2, end: 3, original: 'z', next: 'A' })).toBeNull()
  })

  it('区间不合法返回 null', () => {
    expect(replaceMarkdownRange(source, { start: -1, end: 3, original: 'a', next: 'A' })).toBeNull()
    expect(replaceMarkdownRange(source, { start: 2, end: source.length + 1, original: 'a', next: 'A' })).toBeNull()
    expect(replaceMarkdownRange(source, { start: 5, end: 2, original: '', next: 'A' })).toBeNull()
  })

  it('无变化时返回原文本', () => {
    expect(replaceMarkdownRange(source, { start: 2, end: 3, original: 'a', next: 'a' })).toBe(source)
  })
})

describe('allocateColumnWidths(初始列宽分配)', () => {
  it('长内容列钳 20% 上限,富余分配后仍填满容器', () => {
    // 24 字中文 → 48ch + 3 = 51ch × 8px = 408px,容器 1000 → 上限 200px;
    // 缺口(208)补平后仍有富余,再按理想宽占比 → 单列吃满 1000。
    const widths = allocateColumnWidths(['二十个中文字'.repeat(4)], [], 1000, 8)
    expect(widths).toEqual([1000])
    // 两列等长内容:缺口补平后富余对半分 → 各 500。
    const two = allocateColumnWidths(
      ['很长很长很长很长很长很长很长', '很长很长很长很长很长很长很长'],
      [], 1000, 8)
    expect(two).toEqual([500, 500])
  })

  it('短内容列按内容收紧(64px 下限),富余流向长内容列', () => {
    // 'ab' → 5ch × 8 = 40px,不足 64px 下限 → 64px(旧算法会被 120px 下限垫肥);
    // 长内容列 31ch → 248px 钳 200(缺口 48):补缺口后富余 688 按理想宽
    // 占比(40/288 vs 248/288)分 → [160, 840],合计 1000。
    const widths = allocateColumnWidths(['ab', '很长很长很长很长很长很长很长'], [], 1000, 8)
    expect(widths).toEqual([160, 840])
  })

  it('缺口未被补平时,富余只分给被钳掉的长内容列', () => {
    // 容器 600:上限 = max(120, 600×20%) = 120px。钳制后 [64, 120, 80, 120] = 384,
    // 富余 216 < 缺口(176+360=536)→ 按缺口比例补给列1/列3:
    // 列1 = 120+176×(216/536) ≈ 191,列3 = 120+360×(216/536) ≈ 265;
    // 短内容列(0/2)分文不动,合计恰为 600。
    const headers = ['name', 'key', 'default', 'desc']
    const rows = [['开关', 'a'.repeat(34), 'true', '说明'.repeat(20)]]
    const widths = allocateColumnWidths(headers, rows, 600, 8)
    expect(widths).toEqual([64, 191, 80, 265])
  })

  it('缺口补平后仍有富余 → 按理想宽占比分配(短内容列保持紧凑)', () => {
    // 容器 1000、chPx 8 → 上限 200px:列0 7ch→56→64;列1 37ch→296→钳 200(缺口 96);
    // 列2 10ch→80;列3 60ch 封顶→480→钳 200(缺口 280)。缺口 376 全补平 →
    // [64, 296, 80, 480],余 80 按占比(56/296/80/480)分 → [69, 322, 87, 522]。
    const headers = ['name', 'key', 'default', 'desc']
    const rows = [['开关', 'a'.repeat(34), 'true', '说明'.repeat(20)]]
    const widths = allocateColumnWidths(headers, rows, 1000, 8)
    expect(widths).toEqual([69, 322, 87, 522])
  })

  it('总宽超出容器 → 维持钳制宽(横向滚动)', () => {
    // 容器 300:长内容列钳 max(120, 60)=120,短内容列按 64px 下限,Σ376>300 滚动。
    const headers = ['很长很长很长很长很长很长很长', 'x', 'y', 'z', 'w']
    const widths = allocateColumnWidths(headers, [], 300, 8)
    expect(widths).toEqual([120, 64, 64, 64, 64])
  })

  it('容器宽/1ch 未测得时返回空数组(走 ch 兜底)', () => {
    expect(allocateColumnWidths(['a'], [], 0, 8)).toEqual([])
    expect(allocateColumnWidths(['a'], [], 1000, 0)).toEqual([])
  })

  it('空表头返回空数组', () => {
    expect(allocateColumnWidths([], [], 1000, 8)).toEqual([])
  })
})

describe('applyColumnOverrides(手调合并,Excel 式)', () => {
  it('手调列用覆盖值,其余列维持初始分配不动', () => {
    expect(applyColumnOverrides([100, 200, 300], new Map([[1, 500]]))).toEqual([100, 500, 300])
  })

  it('无手调时原样返回', () => {
    expect(applyColumnOverrides([100, 200], new Map())).toEqual([100, 200])
  })
})

describe('computeFallbackColumnWidths(ch 兜底)', () => {
  it('输出 min(Nch, 100vw) 声明式宽度(短内容按内容收紧,不设下限)', () => {
    expect(computeFallbackColumnWidths(['ab', '很长'.repeat(30)], []))
      .toEqual(['min(5ch, 100vw)', 'min(60ch, 100vw)'])
  })
})

describe('isTouchDoubleTap(触屏双击判定)', () => {
  const tap = (time: number, x = 0, y = 0) => ({ time, x, y })

  it('间隔与位移都在阈值内判定为双击', () => {
    expect(isTouchDoubleTap(tap(1000), tap(1200, 10, 5))).toBe(true)
  })

  it('超过时间窗(350ms)不算双击', () => {
    expect(isTouchDoubleTap(tap(1000), tap(1400))).toBe(false)
  })

  it('位移过大(两指落点相距 > 40px)不算双击', () => {
    expect(isTouchDoubleTap(tap(1000), tap(1100, 50, 0))).toBe(false)
  })

  it('首次轻触(无前次记录)不算双击', () => {
    expect(isTouchDoubleTap(null, tap(1000))).toBe(false)
  })

  it('支持自定义阈值', () => {
    expect(isTouchDoubleTap(tap(1000), tap(1100, 25), 200, 20)).toBe(false)
  })
})

describe('shouldPinHeader(表头吸顶判定)', () => {
  // 场景坐标:阅读容器顶(吸顶线)在视口 y=0。
  it('表头仍在视野内(表头底缘未过吸顶线)不吸顶', () => {
    expect(shouldPinHeader(100, 800, 0, 40)).toBe(false)
    expect(shouldPinHeader(-20, 800, 0, 40)).toBe(false) // 表头顶微出但表头底仍在视野
  })

  it('表头底缘滚出吸顶线且表格仍跨越吸顶线 → 吸顶', () => {
    expect(shouldPinHeader(-100, 500, 0, 40)).toBe(true)
    expect(shouldPinHeader(-41, 500, 0, 40)).toBe(true) // 表头底缘刚过线
  })

  it('表头底缘恰好贴线(最后一像素仍可见)不吸顶', () => {
    expect(shouldPinHeader(-40, 500, 0, 40)).toBe(false)
  })

  it('表格整体滚出上方(底缘也过线)→ 还原不吸顶', () => {
    expect(shouldPinHeader(-600, -100, 0, 40)).toBe(false)
  })

  it('表格在吸顶线下方(尚未滚到)不吸顶', () => {
    expect(shouldPinHeader(50, 300, 0, 40)).toBe(false)
  })

  it('表头高未知(0)时按表头顶缘贴线即吸顶(保守替身)', () => {
    expect(shouldPinHeader(-1, 500, 0, 0)).toBe(true)
    expect(shouldPinHeader(1, 500, 0, 0)).toBe(false)
  })
})

describe('表格渲染(列宽与可编辑标注)', () => {
  const md = '| 列一 | 列二 |\n| --- | --- |\n| 1 | 2 |'

  function render(markdown: string, options?: Parameters<typeof buildMarkdownComponents>[0]): string {
    return renderToStaticMarkup(
      <Markdown
        remarkPlugins={[remarkGfm]}
        components={buildMarkdownComponents(options ?? { variant: 'preview' })}
      >
        {markdown}
      </Markdown>,
    )
  }

  it('colgroup 以 ch 单位输出列宽', () => {
    const html = render(md)
    expect(html).toContain('<colgroup>')
    expect(html).toMatch(/<col style="width:min\(\d+ch, 100vw\)"\/>/)
  })

  it('单列列宽上限钳到屏幕宽度(min(Nch, 100vw))——窄屏/超大字号下不超屏', () => {
    const html = render('| aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa | b |\n| --- | --- |\n| 1 | 2 |')
    expect(html).toMatch(/width:min\(60ch, 100vw\)/)
  })

  it('包裹层允许横向滚动(overflow-x auto)', () => {
    const html = render(md)
    expect(html).toMatch(/overflow-x:auto/)
  })

  it('表格携带 md-table 类(列竖线由 markdown.css 按 :not(:last-child) 绘制)', () => {
    const html = render(md)
    expect(html).toContain('<table class="md-table"')
  })

  it('移动端口径:wrap 禁用双击缩放(touch-action manipulation)并遏制横向滚动链', () => {
    const html = render(md)
    expect(html).toMatch(/touch-action:manipulation/)
    expect(html).toMatch(/overscroll-behavior-x:contain/)
  })

  it('注入 sourceText + onTableCellEdit 时单元格标注可编辑提示', () => {
    const html = render(md, {
      variant: 'preview',
      sourceText: md,
      onTableCellEdit: () => {},
    })
    expect(html).toContain('双击编辑单元格')
  })

  it('未注入编辑通道时单元格保持只读(无编辑标注)', () => {
    const html = render(md)
    expect(html).not.toContain('双击编辑单元格')
  })

  it('静态渲染(无溢出判定)不携带手型光标与拖动提示——普通表格选择不受打扰', () => {
    const html = render(md)
    expect(html).not.toContain('cursor:grab')
    expect(html).not.toContain('Ctrl 拖动平移')
  })

  it('单元格内容层按行截断并显示省略号(-webkit-line-clamp)', () => {
    const html = render(md)
    expect(html).toMatch(/-webkit-line-clamp:\d+/)
    expect(html).toMatch(/display:-webkit-box/)
  })

  it('静态渲染不产生吸顶浮动表头(吸顶仅在真实滚动几何下经 portal 挂载)', () => {
    const html = render(md)
    expect(html).not.toContain('position:fixed')
  })

  it('静态渲染(几何未测得)不渲染表外把手栏——SSR 表格保持原生形态', () => {
    const html = render(md)
    expect(html).not.toContain('调整列宽')
    expect(html).not.toContain('调整行高')
  })
})
