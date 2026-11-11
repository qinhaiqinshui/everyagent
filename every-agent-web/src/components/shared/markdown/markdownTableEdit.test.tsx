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
import { buildMarkdownComponents, computeTableColumnWidths } from './sharedMarkdownRenderer'
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

describe('computeTableColumnWidths(max 单元格口径)', () => {
  it('空表头返回空数组', () => {
    expect(computeTableColumnWidths([], [])).toEqual([])
  })

  it('列宽由该列内容最多的单元格决定,而非字符总量占比', () => {
    // 旧总量占比算法下「短列 x 短列」会被长列挤压;max 口径下短列保住下限。
    const widths = computeTableColumnWidths(
      ['AAAAAAAAAAAAAAAA', 'B'],
      [['数据较长的单元格内容', 'x'], ['又一个长内容单元格', 'y']],
    )
    expect(widths[0]).toBe('23ch') // max = 10 个中文字 ×2 = 20 + 3
    expect(widths[1]).toBe('10ch') // max 1 + 3 → clamp 到下限
  })

  it('CJK/全角字符按 2 倍视觉宽度计', () => {
    const widths = computeTableColumnWidths(['中文'], [['短'], ['三十个中文字'.repeat(1)]])
    expect(widths[0]).toBe(`${Math.min(60, 6 * 2 + 3)}ch`)
  })

  it('超上限的列 clamp 到 60ch', () => {
    const long = 'a'.repeat(100)
    expect(computeTableColumnWidths(['H'], [[long]])).toEqual(['60ch'])
  })

  it('统计前剥离内联 markdown 语法', () => {
    // `**加粗**`(8 语法字符 + 2 内容)按 2 计,而非源码长度。
    expect(computeTableColumnWidths(['**ok**'], [[]])).toEqual(['10ch']) // 2+3=5 → 下限 10
  })

  it('全空内容落到下限', () => {
    expect(computeTableColumnWidths(['', ''], [['', '']])).toEqual(['10ch', '10ch'])
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
    expect(html).toMatch(/<col style="width:\d+ch"\/>/)
  })

  it('包裹层允许横向滚动(overflow-x auto)', () => {
    const html = render(md)
    expect(html).toMatch(/overflow-x:auto/)
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
    expect(html).toContain('title="双击编辑单元格"')
  })

  it('未注入编辑通道时单元格保持只读(无编辑标注)', () => {
    const html = render(md)
    expect(html).not.toContain('双击编辑单元格')
  })

  it('静态渲染(无溢出判定)不携带手型光标与拖动提示——普通表格选择不受打扰', () => {
    const html = render(md)
    expect(html).not.toContain('cursor:grab')
    expect(html).not.toContain('Space 拖动平移')
  })

  it('静态渲染不产生吸顶浮动表头(吸顶仅在真实滚动几何下经 portal 挂载)', () => {
    const html = render(md)
    expect(html).not.toContain('position:fixed')
  })
})
