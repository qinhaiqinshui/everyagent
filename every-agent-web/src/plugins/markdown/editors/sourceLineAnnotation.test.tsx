/**
 * markdown 预览源行号标注(data-source-line / data-source-line-end)特征化测试。
 *
 * 这是滚动同步(markdownScrollSync)按行映射的基础:块级元素必须携带
 * 1-based 源行号(与 CodeMirror 行号同基准),长块(代码栅栏/表格/列表)同时
 * 携带结束行供块内比例插值。锁定各块型标注的行号口径不回归。
 */
import { describe, it, expect } from 'vitest'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { buildMarkdownComponents } from '@/components/shared/markdown/sharedMarkdownRenderer'

const SAMPLE = [
  '# 标题一',
  '',
  '第一段正文。',
  '',
  '- 项目 A',
  '- 项目 B',
  '',
  '```ts',
  'const a = 1',
  'const b = 2',
  '```',
  '',
  '| 列一 | 列二 |',
  '| --- | --- |',
  '| 1 | 2 |',
  '',
  '> 引用块',
  '',
  '结尾段落。',
].join('\n')

function render(markdown: string): string {
  return renderToStaticMarkup(
    <Markdown
      remarkPlugins={[remarkGfm]}
      components={buildMarkdownComponents({ variant: 'preview' })}
    >
      {markdown}
    </Markdown>,
  )
}

describe('预览块级元素源行号标注', () => {
  const html = render(SAMPLE)

  it('标题标注起始/结束行(单行块 start === end)', () => {
    expect(html).toContain('<h1 data-source-line="1" data-source-line-end="1"')
  })

  it('段落标注起始/结束行', () => {
    expect(html).toContain('<p data-source-line="3" data-source-line-end="3"')
  })

  it('列表项逐项标注行号', () => {
    expect(html).toContain('<li data-source-line="5" data-source-line-end="5"')
    expect(html).toContain('<li data-source-line="6" data-source-line-end="6"')
  })

  it('代码块标注围栏起止行(含闭合围栏)', () => {
    expect(html).toContain('data-source-line="8" data-source-line-end="11"')
  })

  it('表格包装层标注表头到末行', () => {
    expect(html).toContain('data-source-line="13" data-source-line-end="15"')
  })

  it('引用块标注行号', () => {
    expect(html).toContain('<blockquote data-source-line="17" data-source-line-end="17"')
  })
})
