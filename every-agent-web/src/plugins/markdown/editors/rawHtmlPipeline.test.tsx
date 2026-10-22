/**
 * rawHtmlPipeline 特征化测试:内嵌 HTML 渲染 + 安全清洗口径。
 *
 * 锁定行为:GitHub/VS Code 式渲染徽章等 HTML 块(外部 img、align 居中),
 * 同时 script/事件属性/javascript: 协议一律剥离;代码栅栏的 language-* class
 * (语法高亮依赖)不受 sanitize 影响。
 */
import { describe, it, expect } from 'vitest'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { rawHtmlRehypePlugins } from './rawHtmlPipeline'

function render(markdown: string): string {
  return renderToStaticMarkup(
    <Markdown remarkPlugins={[remarkGfm]} rehypePlugins={rawHtmlRehypePlugins}>
      {markdown}
    </Markdown>,
  )
}

describe('rawHtmlPipeline(仅文件预览启用)', () => {
  it('徽章 HTML 块渲染为居中段落与外部 img(保留 src/alt/align)', () => {
    const html = render(
      '<p align="center"> <img alt="License" src="https://img.shields.io/badge/License-Apache%202.0-blue"> </p>',
    )
    expect(html).toContain('<p align="center">')
    expect(html).toContain('src="https://img.shields.io/badge/License-Apache%202.0-blue"')
    expect(html).toContain('alt="License"')
  })

  it('script 标签被剥离', () => {
    const html = render('<script>alert(1)</script>\n\n正文')
    expect(html).not.toContain('script')
    expect(html).not.toContain('alert(1)')
    expect(html).toContain('正文')
  })

  it('事件属性被剥离(onerror 等)', () => {
    const html = render('<img src="https://ok.example/a.png" onerror="alert(1)">')
    expect(html).toContain('src="https://ok.example/a.png"')
    expect(html).not.toContain('onerror')
    expect(html).not.toContain('alert')
  })

  it('javascript: 协议的 img src 被移除', () => {
    const html = render('<img src="javascript:alert(1)" alt="x">')
    expect(html).not.toContain('javascript:')
    expect(html).toContain('<img')
  })

  it('代码栅栏的 language-* class 保留(语法高亮不回归)', () => {
    const html = render('```ts\nconst a = 1\n```')
    expect(html).toContain('language-ts')
  })

  it('纯 markdown 段落不受影响', () => {
    const html = render('你好 **世界**')
    expect(html).toContain('<p>')
    expect(html).toContain('<strong>世界</strong>')
  })
})
