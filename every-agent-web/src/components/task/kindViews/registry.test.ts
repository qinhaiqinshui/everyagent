/**
 * kind 渲染注册表基建单测(「任务线程统一条目与画板纯化」步骤 9,纯增量)。
 *
 * 覆盖:registry 查表语义(未注册返回 undefined——兜底归分发点显式 ?? FallbackView,
 * 不埋进注册表)/重复注册覆盖/注销/空 kind 抛错;FallbackView 宽松读取
 * (title+summary+content 对象 / content 字符串 / 非对象 payload 三形态与
 * stringify 零丢失兜底,经纯函数 readFallbackTraceShape 直测)与 **data 透传**(第二级
 * trace 类型的 metadata 供体——auth.review 空卡片回归:归一吃掉了 payload.data,
 * 插件视图拿到空 metadata 却因 stringify 兜底的 content 仍可展开);useThreadBoard
 * 缺 Provider 行为(空 items + console.warn 恰一次);插件 kind 视图接管
 * (ui.kind_views 扩展点:注册可见/插件优先于核心/dispose 回退)。
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
import type { Disposable, KindViewComponent, PluginKindViewProps, TraceTypeDefinition } from '@everyagent/plugin-api'
import type { ThreadItem } from '@/task/threadItem'
import { pluginDispatcher } from '@/plugin/PluginDispatcher'
import { initializeTraceTypes, registerTraceType, unregisterTraceType } from '@/plugin/traceTypeRegistry'
import {
  FallbackView,
  getKindView,
  readFallbackTraceData,
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

describe('插件 kind 视图接管(ui.kind_views 扩展点,插件优先于核心)', () => {
  /** 插件注册项的测试自清理:登记 dispose 桶,afterEach 统一注销。 */
  const pluginDisposables: Disposable[] = []

  afterEach(() => {
    while (pluginDisposables.length > 0) {
      pluginDisposables.pop()!.dispose()
    }
  })

  it('registerKindView 注册后 listRegisteredKindViews 可见;空/纯空白 kind 抛错', () => {
    const PluginView: KindViewComponent = () => null
    pluginDisposables.push(pluginDispatcher.registerKindView('demo.plugin.kind', PluginView))
    const listed = pluginDispatcher.listRegisteredKindViews()
    expect(listed).toContainEqual({ kind: 'demo.plugin.kind', Component: PluginView })
    expect(() => pluginDispatcher.registerKindView('', PluginView)).toThrowError(/不能为空/)
    expect(() => pluginDispatcher.registerKindView('   ', PluginView)).toThrowError(/不能为空/)
  })

  it('getKindView 插件优先于核心:已接管 kind 返回插件组件并接管渲染', () => {
    const CoreView: KindView = () => null
    registerForTest('demo.takeover', CoreView)
    function PluginView(props: PluginKindViewProps): React.ReactNode {
      return React.createElement(
        'div',
        { className: 'plugin-takeover' },
        `kind:${props.item.kind}`,
      )
    }
    pluginDisposables.push(pluginDispatcher.registerKindView('demo.takeover', PluginView))
    expect(getKindView('demo.takeover')).toBe(PluginView as unknown as KindView)
    // props 契约(PluginKindViewProps)与核心 KindViewProps 运行时同形:经分发点渲染可见。
    const item = itemOf('demo.takeover', { title: 'T' })
    const View = getKindView(item.kind) ?? FallbackView
    const html = renderToString(
      React.createElement(View, { item, payload: item.payload, taskId: 'task-1', mainAgentId: 'main' }),
    )
    expect(html).toContain('plugin-takeover')
    expect(html).toContain('kind:demo.takeover')
  })

  it('dispose 后回退核心视图;核心未注册则 undefined(分发点再落 FallbackView)', () => {
    const CoreView: KindView = () => null
    registerForTest('demo.fallback-core', CoreView)
    const PluginView: KindViewComponent = () => null
    const d1 = pluginDispatcher.registerKindView('demo.fallback-core', PluginView)
    expect(getKindView('demo.fallback-core')).toBe(PluginView as unknown as KindView)
    d1.dispose()
    expect(getKindView('demo.fallback-core')).toBe(CoreView)

    const d2 = pluginDispatcher.registerKindView('demo.fallback-absent', PluginView)
    expect(getKindView('demo.fallback-absent')).toBe(PluginView as unknown as KindView)
    d2.dispose()
    expect(getKindView('demo.fallback-absent')).toBeUndefined()
    // 注销项从扩展点注册表消失(不残留)
    expect(pluginDispatcher.listRegisteredKindViews().some((def) => def.kind === 'demo.fallback-absent')).toBe(false)
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
    expect(html).toContain('nagent-entry__summary--static')
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

  it('kind=error 落第二级表:alert 图标标识,无整行错误色类(tone 链已删)', () => {
    // 渲染层两级分发统一化:error/cancelled 不再有第一层专视图(statusViews 已删),
    // FallbackView 只按传入 kind(wire kind)查第二级 traceTypeRegistry 取定义——
    // error 缺省 alert 三角警示图标;整行红显的 tone 链已删。
    initializeTraceTypes()
    const html = renderFallback('error', { title: '任务失败', summary: 'boom' })
    expect(html).toContain('nagent-trace-shell')
    expect(html).toContain('任务失败')
    expect(html).toContain('<svg')
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
    expect(html).not.toContain('nagent-entry__name')
    // content 存在 → 可展开(完整切换链展开态可见)。
    expect(html).toContain('aria-expanded="false"')
  })
})

describe('FallbackView 的 data 透传(第二级 trace 类型的 metadata 供体)', () => {
  it('readFallbackTraceData:对象 payload 的 data 原样读出;缺失/数组/非对象一律 undefined', () => {
    expect(readFallbackTraceData({ title: 'T', data: { decision: 'ALLOW' } })).toEqual({ decision: 'ALLOW' })
    expect(readFallbackTraceData({ title: 'T' })).toBeUndefined()
    expect(readFallbackTraceData({ data: [1, 2] })).toBeUndefined()
    expect(readFallbackTraceData({ data: null })).toBeUndefined()
    expect(readFallbackTraceData('raw')).toBeUndefined()
    expect(readFallbackTraceData(null)).toBeUndefined()
  })

  it('auth.review 式插件 trace 类型:metadata 拿到 data,摘要按结论走(不再退化为「未知」)', () => {
    // 回归:两级分发统一化后 FallbackView 把 payload 归一成 {title,summary,content},
    // 顺手吃掉了 data → 壳内合成的 trace.metadata 为空 → 插件判断卡渲染成空卡片
    // (徽标「未知」、理由/置信度全缺),且 stringify 出的 content 还让该行可展开。
    const seen: Array<Record<string, unknown> | undefined> = []
    const def: TraceTypeDefinition = {
      kind: 'auth.review',
      hideTitle: true,
      canExpand: (trace) => Boolean(trace.metadata && Object.keys(trace.metadata).length > 0),
      getSummary: (trace) => `AI 审议：${String(trace.metadata?.decision ?? '未知')}`,
      renderContent: (trace) => {
        seen.push(trace.metadata)
        return React.createElement('span', null, 'card')
      },
    }
    registerTraceType(def)
    try {
      const html = renderToString(
        React.createElement(FallbackView, {
          item: itemOf('auth.review', {
            title: 'AI 安全审议',
            summary: 'ALLOW — 只读目录列表操作',
            data: { decision: 'ALLOW', reason: '只读目录列表操作' },
          }),
          payload: {
            title: 'AI 安全审议',
            summary: 'ALLOW — 只读目录列表操作',
            data: { decision: 'ALLOW', reason: '只读目录列表操作' },
          },
          taskId: 'task-1',
          mainAgentId: 'main',
        }),
      )
      // 收起态摘要以插件 getSummary 为准(拿到结论就不再原样透出 worker summary)。
      expect(html).toContain('AI 审议：ALLOW')
      expect(html).not.toContain('ALLOW — 只读目录列表操作')
      // renderContent 在收起态也会被壳调用(展开容器按 open 挂载),据此断言 metadata 供体。
      expect(seen).toHaveLength(1)
      expect(seen[0]).toEqual({ decision: 'ALLOW', reason: '只读目录列表操作' })
    } finally {
      expect(unregisterTraceType('auth.review')).toBe(true)
    }
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
