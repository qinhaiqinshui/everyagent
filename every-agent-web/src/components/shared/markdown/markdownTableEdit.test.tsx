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
  isTouchDoubleTap,
} from './markdownTableEdit'
import { buildMarkdownComponents, computeTableColumnWidths } from './sharedMarkdownRenderer'

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
    expect(html).not.toContain('拖动平移表格')
  })
})
