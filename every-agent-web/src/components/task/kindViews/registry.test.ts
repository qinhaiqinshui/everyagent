/**
 * kind 渲染注册表基建单测(「任务线程统一条目与画板纯化」步骤 9,纯增量)。
 *
 * 覆盖:registry 查表语义(未注册返回 undefined——兜底归分发点显式 ?? FallbackView,
 * 不埋进注册表)/重复注册覆盖/注销/空 kind 抛错;FallbackView 宽松读取
 * (title+summary+content 对象 / content 字符串 / 非对象 payload 三形态与
 * stringify 零丢失兜底,经纯函数 readFallbackTraceShape 直测);useThreadBoard
 * 缺 Provider 行为(空 items + console.warn 恰一次)。
 *
 * 渲染断言取舍:react-dom/server 的 renderToString(node 环境可跑,零新依赖——
 * 不引 jsdom/@testing-library,对齐 §8.6 既有 node 测试口径);折叠展开前的
 * 收起态断言走 aria-expanded(可展开按钮)与 --static(静态行)类区分可展开性,
 * content 正文语义由纯函数断言覆盖。本步不接分发,TaskThread 现路径行为由
 * eventFolder/TaskRoundsPanel 两份特征化测试护栏,此处零行为变化。
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { renderToString } from 'react-dom/server'
import type { ThreadItem } from '@/task/threadItem'
import { initializeTraceTypes } from '@/plugin/traceTypeRegistry'
import {
  FallbackView,
  getKindView,
  readFallbackTraceShape,
  registerKindView,
  unregisterKindView,
  type KindView,
} from './registry'
import { ThreadBoardProvider, useThreadBoard } from './threadBoardContext'

function itemOf(kind: string, payload: unknown, seq = '7380000000000000042'): ThreadItem {
  return { seq, ts: 1730000000000, agentId: 'main', kind, payload }
}

/** registry 模块级 Map 的测试自清理:登记本文件注册过的 kind。 */
const registeredKinds: string[] = []

function registerForTest(kind: string, View: KindView): void {
  registerKindView(kind, View)
  registeredKinds.push(kind)
}

afterEach(() => {
  while (registeredKinds.length > 0) {
    unregisterKindView(registeredKinds.pop()!)
  }
})

describe('kindViewRegistry 查表', () => {
  it('未注册 kind 返回 undefined(兜底归分发点显式 ?? FallbackView,不埋进注册表)', () => {
    expect(getKindView('not.registered')).toBeUndefined()
  })

  it('注册后命中返回同一视图', () => {
    const View: KindView = () => null
    registerForTest('demo.kind', View)
    expect(getKindView('demo.kind')).toBe(View)
  })

  it('重复注册同 kind 覆盖(后注册者胜)', () => {
    const first: KindView = () => null
    const second: KindView = () => null
    registerForTest('demo.overwrite', first)
    registerForTest('demo.overwrite', second)
    expect(getKindView('demo.overwrite')).toBe(second)
  })

  it('注销后查表 miss;未注册/重复注销返回 false', () => {
    const View: KindView = () => null
    registerForTest('demo.dispose', View)
    expect(unregisterKindView('demo.dispose')).toBe(true)
    expect(getKindView('demo.dispose')).toBeUndefined()
    expect(unregisterKindView('demo.dispose')).toBe(false)
    expect(unregisterKindView('never.registered')).toBe(false)
  })

  it('空 kind(空串/纯空白)注册抛错;注销返回 false 不抛', () => {
    const View: KindView = () => null
    expect(() => registerKindView('', View)).toThrowError(/不能为空/)
    expect(() => registerKindView('   ', View)).toThrowError(/不能为空/)
    expect(unregisterKindView('')).toBe(false)
  })

  it('分发组合式 getKindView(kind) ?? FallbackView 编译期可配、运行时落到兜底', () => {
    const dispatch: KindView = getKindView('demo.absent') ?? FallbackView
    expect(dispatch).toBe(FallbackView)
  })
})

describe('readFallbackTraceShape 宽松读取', () => {
  it('title+summary+content 对象逐字段直读', () => {
    const shape = readFallbackTraceShape(itemOf('demo.trace', { title: 'T', summary: 'S', content: 'C' }), { title: 'T', summary: 'S', content: 'C' })
    expect(shape).toEqual({ title: 'T', summary: 'S', content: 'C' })
  })

  it('对象 title 缺省/空白/非字符串时回退 item.kind 作标题', () => {
    expect(readFallbackTraceShape(itemOf('future.kind', { summary: 'S' }), { summary: 'S' }).title).toBe('future.kind')
    expect(readFallbackTraceShape(itemOf('future.kind', { title: '   ' }), { title: '   ' }).title).toBe('future.kind')
    expect(readFallbackTraceShape(itemOf('future.kind', { title: 42 }), { title: 42 }).title).toBe('future.kind')
  })

  it('仅 content 字符串的对象:标题回退 kind,content 直读', () => {
    const shape = readFallbackTraceShape(itemOf('future.kind', { content: 'hello' }), { content: 'hello' })
    expect(shape.title).toBe('future.kind')
    expect(shape.content).toBe('hello')
    expect(shape.summary).toBeUndefined()
  })

  it('非对象 payload:字符串整体作为 content(零丢失)', () => {
    expect(readFallbackTraceShape(itemOf('future.kind', 'raw text'), 'raw text')).toEqual({
      title: 'future.kind',
      summary: undefined,
      content: 'raw text',
    })
  })

  it('非对象 payload:数字走 String()', () => {
    const shape = readFallbackTraceShape(itemOf('future.kind', 404), 404)
    expect(shape.title).toBe('future.kind')
    expect(shape.content).toBe('404')
  })

  it('非对象 payload:数组走 JSON.stringify', () => {
    const shape = readFallbackTraceShape(itemOf('future.kind', ['a', 'b']), ['a', 'b'])
    expect(shape.title).toBe('future.kind')
    expect(JSON.parse(shape.content!)).toEqual(['a', 'b'])
  })

  it('null payload:无 content,标题回退 kind', () => {
    expect(readFallbackTraceShape(itemOf('future.kind', null), null)).toEqual({
      title: 'future.kind',
      summary: undefined,
      content: undefined,
    })
  })

  it('content 空但对象尚有未展示字段(如 trace data)时整体 stringify 兜底——内容零丢失', () => {
    const payload = { title: 'T', data: { x: 1 } }
    const shape = readFallbackTraceShape(itemOf('demo.trace', payload), payload)
    expect(shape.title).toBe('T')
    expect(JSON.parse(shape.content!)).toEqual(payload)
  })

  it('仅 title/summary 的对象不重复 stringify(已展示字段不算丢失)', () => {
    const shape = readFallbackTraceShape(itemOf('demo.trace', { title: 'T', summary: 'S' }), { title: 'T', summary: 'S' })
    expect(shape.content).toBeUndefined()
  })
})

describe('FallbackView 渲染(renderToString,node 环境)', () => {
  const renderFallback = (kind: string, payload: unknown): string =>
    renderToString(React.createElement(FallbackView, { item: itemOf(kind, payload), payload, taskId: 'task-1', mainAgentId: 'main' }))

  it('title+summary 对象:收起态渲染标题与摘要,无 content 为静态行(不可展开)', () => {
    const html = renderFallback('demo.trace', { title: '重试通知', summary: '第 2 次' })
    expect(html).toContain('nagent-trace-shell')
    expect(html).toContain('重试通知')
    expect(html).toContain('第 2 次')
    expect(html).toContain('nagent-trace-shell__summary--static')
    expect(html).not.toContain('aria-expanded')
  })

  it('有 content 的对象:可展开按钮(aria-expanded),复用 TaskTraceShell 呈现', () => {
    const html = renderFallback('demo.trace', { title: 'T', summary: 'S', content: 'C' })
    expect(html).toContain('aria-expanded="false"')
    expect(html).not.toContain('--static')
  })

  it('非对象 payload(字符串):标题回退 kind 且可展开', () => {
    const html = renderFallback('future.kind', 'raw text')
    expect(html).toContain('future.kind')
    expect(html).toContain('aria-expanded="false"')
  })

  it('kind=error 的错误色调已收敛为 kind 视图直配(FallbackView 不再改写 kind)', () => {
    // 步骤 10 收敛:error → run_error 的旧 key 映射从 TraceShell 迁入 statusViews
    // 的 ErrorView(kind 视图);FallbackView 只按传入 kind 查表,不改写——未注册路径
    // 的 kind=error(仅测试直构场景)不再带错误色调。真实分发下 error 恒命中 ErrorView
    // (builtins 注册),错误色调断言见 builtinViews.test.ts。
    initializeTraceTypes()
    const html = renderFallback('error', { title: '任务失败', summary: 'boom' })
    expect(html).toContain('nagent-trace-shell')
    expect(html).toContain('任务失败')
    expect(html).not.toContain('nagent-trace-shell--error')
  })

  it('model.failover trace:注册 kind 与线程 kind 一致(点号),hideTitle 隐藏 kind 兜底标题', () => {
    // 回归:曾注册为下划线 model_failover(旧 task.trace DTO 口径),与折叠产出的
    // 线程 kind=model.failover 不一致 → hideTitle 从未命中,FallbackView 把空 title
    // 回退成 item.kind「model.failover」当标题展示。payload 形状对齐
    // eventFolder.upsertTraceBySeq 产出(title 空串 + summary/content)。
    initializeTraceTypes()
    const html = renderFallback('model.failover', {
      title: '',
      summary: '容灾切换模型：cfg-2 m-2',
      content: 'cfg-1 m-1 -> cfg-2 m-2',
    })
    // 收起态只展示 summary(容灾切换模型:…),kind 兜底标题不再出现。
    expect(html).toContain('容灾切换模型：cfg-2 m-2')
    expect(html).not.toContain('nagent-trace-shell__title')
    // content 存在 → 可展开(完整切换链展开态可见)。
    expect(html).toContain('aria-expanded="false"')
  })
})

describe('useThreadBoard context', () => {
  function BoardProbe(): React.ReactNode {
    const { items } = useThreadBoard()
    return React.createElement('span', null, `count:${items.length}`)
  }

  it('缺 Provider:返回空 items 并 console.warn 恰一次(模块级 once,两次渲染不刷屏)', () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
    try {
      expect(renderToString(React.createElement(BoardProbe))).toContain('count:0')
      expect(renderToString(React.createElement(BoardProbe))).toContain('count:0')
      expect(warnSpy).toHaveBeenCalledTimes(1)
      expect(warnSpy.mock.calls[0][0]).toMatch(/ThreadBoardProvider/)
    } finally {
      warnSpy.mockRestore()
    }
  })

  it('Provider 内:items 兄弟行可读', () => {
    const items: readonly ThreadItem[] = [itemOf('user.message', { content: 'q' }, '10'), itemOf('message', { role: 'assistant', content: 'a' }, '20')]
    const html = renderToString(
      React.createElement(ThreadBoardProvider, { items, children: React.createElement(BoardProbe) }),
    )
    expect(html).toContain('count:2')
  })
})
