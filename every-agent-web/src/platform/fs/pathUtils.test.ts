/**
 * pathUtils 纯函数测试:markdown 链接/图片目的地的百分号编码解码。
 *
 * 背景 bug:react-markdown(底层 micromark)按 CommonMark 规范对目的地做 URI 规范化,
 * `![架构](screenshots/架构.svg)` 到达渲染组件时 src 已是 `screenshots/%E6%9E%B6%E6%9E%84.svg`;
 * 未解码直接按工作区路径读取 → worker 侧 NOT_FOUND(文件不存在)。锁定
 * decodeMarkdownUri 的行为口径:正常编码还原、裸 % 不抛错按原文、与相对路径解析联动。
 */
import { describe, it, expect } from 'vitest'
import { decodeMarkdownUri, resolveWorkspaceRelativePath } from './pathUtils'

describe('decodeMarkdownUri', () => {
  it('还原 CommonMark URI 规范化产生的中文编码', () => {
    expect(decodeMarkdownUri('screenshots/%E6%9E%B6%E6%9E%84.svg')).toBe('screenshots/架构.svg')
  })

  it('还原空格与常见编码字符', () => {
    expect(decodeMarkdownUri('docs/my%20doc%201.png')).toBe('docs/my doc 1.png')
  })

  it('无编码内容原样返回', () => {
    expect(decodeMarkdownUri('screenshots/1.png')).toBe('screenshots/1.png')
  })

  it('文件名含裸 %(非法编码序列)不抛错,按原文返回', () => {
    expect(decodeMarkdownUri('screenshots/100%.svg')).toBe('screenshots/100%.svg')
  })

  it('与 resolveWorkspaceRelativePath 联动:解码后正确解析为工作区相对路径', () => {
    const rel = resolveWorkspaceRelativePath('', decodeMarkdownUri('screenshots/%E6%9E%B6%E6%9E%84.svg'))
    expect(rel).toBe('screenshots/架构.svg')
  })

  it('与 resolveWorkspaceRelativePath 联动:子目录 md 中的相对图片引用', () => {
    const rel = resolveWorkspaceRelativePath('docs/guide', decodeMarkdownUri('../img/%E6%9E%B6%E6%9E%84.svg'))
    expect(rel).toBe('docs/img/架构.svg')
  })
})
