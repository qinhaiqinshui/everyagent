/**
 * composerOpaqueToken 嵌套 token 回归（2026-11）。
 *
 * 背景：任务标题存 rawContent 原样后，「等待任务」胶囊的 label/payload 会内嵌
 * 目标任务标题里的完整 token 串（嵌套 token）。老的 indexOf 配对 / 非贪婪正则
 * `[[[[[\s\S]*?]]]]` 会在内层首个 `]]]]` 处提前截断外层 → 外层解析失败。
 * 修复后全链路按深度平衡配对，本文件覆盖：解析、切分、拍平三个入口。
 */
import { describe, expect, it } from 'vitest'
import {
  buildOpaqueTokenText,
  parseOpaqueTokenText,
  scanOpaqueTokens,
  splitComposerRawContent,
  stripTokensToLabels,
} from './composerOpaqueToken'

/** 构造一枚文件引用胶囊（内嵌用例的数据源）。 */
function fileToken(label: string, path: string): string {
  return buildOpaqueTokenText('system.workspace_file', {
    label,
    summary: undefined,
    payload: { path, fileName: label },
  })
}

/** 嵌套等待胶囊：label/payload 嵌目标任务标题（=rawContent，含内层 token）。 */
function nestedWaitToken(targetTaskId: string, targetTitle: string): string {
  return buildOpaqueTokenText('task.wait', {
    label: `等待「${targetTitle}」`,
    summary: '等待该任务完成后继续（本次输入有效）',
    payload: { targetTaskId, title: targetTitle },
  })
}

describe('parseOpaqueTokenText 嵌套', () => {
  it('label 内嵌完整 token 时外层完整解析（kind/label/payload 不被内层分隔符切碎）', () => {
    const inner = fileToken('图.png', '图.png')
    const outer = nestedWaitToken('7512345678901234567', `${inner} 帮我分析`)

    const parsed = parseOpaqueTokenText(outer)
    expect(parsed).not.toBeNull()
    expect(parsed!.kind).toBe('task.wait')
    expect(parsed!.label).toBe(`等待「${inner} 帮我分析」`)
    expect(parsed!.payload.targetTaskId).toBe('7512345678901234567')
    expect(parsed!.payload.title).toBe(`${inner} 帮我分析`)
  })

  it('内层 token 在嵌套体内仍可独立解析', () => {
    const inner = fileToken('图.png', '图.png')
    const innerParsed = parseOpaqueTokenText(inner)
    expect(innerParsed).not.toBeNull()
    expect(innerParsed!.label).toBe('图.png')
    expect(innerParsed!.payload.path).toBe('图.png')
  })
})

describe('scanOpaqueTokens 平衡配对', () => {
  it('嵌套外层整体成段、内层不单独成段', () => {
    const inner = fileToken('图.png', '图.png')
    const outer = nestedWaitToken('42', inner)
    const plain = fileToken('a.py', 'a.py')

    const tokens = scanOpaqueTokens(`前置 ${outer} 中间 ${plain} 尾部`)
    expect(tokens).toEqual([outer, plain])
  })

  it('残缺 [[[[(不阻塞其后合法段识别', () => {
    const plain = fileToken('a.py', 'a.py')
    const tokens = scanOpaqueTokens(`[[[[agent-token::::坏掉的无收尾 ${plain}`)
    expect(tokens).toEqual([plain])
  })

  it('空输入/无 token 返回空数组', () => {
    expect(scanOpaqueTokens('')).toEqual([])
    expect(scanOpaqueTokens('普通文本')).toEqual([])
  })
})

describe('splitComposerRawContent 嵌套切分', () => {
  it('嵌套胶囊整体成 token 段（不被内层 ]]]] 截断）', () => {
    const inner = fileToken('图.png', '图.png')
    const outer = nestedWaitToken('42', `${inner} 分析`)
    const segments = splitComposerRawContent(`跑完 ${outer} 再继续`)

    expect(segments).toEqual([
      { type: 'text', value: '跑完 ' },
      { type: 'token', value: outer, token: expect.objectContaining({ kind: 'task.wait', label: `等待「${inner} 分析」` }) },
      { type: 'text', value: ' 再继续' },
    ])
  })

  it('嵌套胶囊与普通胶囊混排各自独立', () => {
    const inner = fileToken('图.png', '图.png')
    const nested = nestedWaitToken('t-1', inner)
    const plain = fileToken('b.py', 'b.py')
    const segments = splitComposerRawContent(`${nested}和${plain}`)

    expect(segments.filter((s) => s.type === 'token')).toHaveLength(2)
    expect(segments.map((s) => s.value)).toEqual([nested, '和', plain])
  })
})

describe('stripTokensToLabels 嵌套拍平', () => {
  it('外层 label 里再嵌 token 时递归拍平为内层 label 明文', () => {
    const inner = fileToken('图.png', '图.png')
    const outer = nestedWaitToken('42', `${inner} 分析`)
    expect(stripTokensToLabels(outer)).toBe('等待「图.png 分析」')
  })

  it('纯文本与单层 token 行为回归不变', () => {
    const token = fileToken('📄 main.py', 'main.py')
    expect(stripTokensToLabels(`${token} 帮我修改这个文件`)).toBe('📄 main.py 帮我修改这个文件')
    expect(stripTokensToLabels('帮我修改这个文件')).toBe('帮我修改这个文件')
  })
})
