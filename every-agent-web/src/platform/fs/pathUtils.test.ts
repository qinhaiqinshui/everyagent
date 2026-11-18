/**
 * pathUtils 纯函数测试:markdown 链接/图片目的地的百分号编码解码。
 *
 * 背景 bug:react-markdown(底层 micromark)按 CommonMark 规范对目的地做 URI 规范化,
 * `![架构](screenshots/架构.svg)` 到达渲染组件时 src 已是 `screenshots/%E6%9E%B6%E6%9E%84.svg`;
 * 未解码直接按工作区路径读取 → worker 侧 NOT_FOUND(文件不存在)。锁定
 * decodeMarkdownUri 的行为口径:正常编码还原、裸 % 不抛错按原文、与相对路径解析联动。
 */
import { describe, it, expect } from 'vitest'
import {
  decodeMarkdownUri,
  resolveWorkspaceRelativePath,
  machinePathToBusiness,
  isAbsoluteBusinessPath,
} from './pathUtils'

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

/**
 * 机器原始路径 → 业务坐标回归:Unix 绝对路径(单前导 /)必须转为 `//` 双斜杠
 * 外部标记,否则会被文件标签页当工作区相对路径拼到工作区根上(worker 报 NOT_FOUND)。
 * 背景 bug:设置页 skill 列表点 name 打开 skill.md 时直接透传 worker 机器路径
 * `/home/admin/.everyagent/skills/.../skill.md`,Linux worker 必现
 * 「文件不存在: <工作区根>/home/admin/.everyagent/skills/.../skill.md」。
 */
describe('machinePathToBusiness(Unix 机器绝对路径)', () => {
  it('转 // 双斜杠外部标记,且被 isAbsoluteBusinessPath 判为工作区外', () => {
    const machine = '/home/admin/.everyagent/skills/agent-dispatch/skill.md'
    const business = machinePathToBusiness(machine)
    expect(business).toBe('//home/admin/.everyagent/skills/agent-dispatch/skill.md')
    expect(isAbsoluteBusinessPath(business)).toBe(true)
  })

  it('幂等:已是 // 业务形态的输入不再叠加标记', () => {
    expect(machinePathToBusiness('//home/admin/x.md')).toBe('//home/admin/x.md')
  })

  it('Windows 盘符机器路径转 /盘符 业务形式,仍判为工作区外', () => {
    const business = machinePathToBusiness('C:/Users/admin/.everyagent/skills/x/skill.md')
    expect(business).toBe('/C:/Users/admin/.everyagent/skills/x/skill.md')
    expect(isAbsoluteBusinessPath(business)).toBe(true)
  })

  it('工作区相对路径不加外部标记(与 Unix 绝对路径可区分)', () => {
    expect(machinePathToBusiness('src/main.ts')).toBe('/src/main.ts')
    expect(isAbsoluteBusinessPath('/src/main.ts')).toBe(false)
  })
})
