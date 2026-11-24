// @vitest-environment jsdom
/**
 * trackDisposables 代理的单元测试。
 *
 * 核心回归（工作流定义编辑器 AI 节点输入框失焦 bug）：
 * ctx.ui 上的组件型成员（RichComposer / TokenRichText）本身是函数，会被
 * 代理一并包装；包装函数必须**按属性记忆化**——同一属性重复访问返回同一
 * 函数引用。否则插件 JSX 里 `<ui.RichComposer>` 的元素类型每次渲染都变，
 * React 将整棵子树卸载重挂，contentEditable 输入框每敲一键就失焦。
 *
 * jsdom 环境：被测模块 pluginLoader 在模块作用域向 window 挂宿主全局
 * （__EA_REACT__ 等），node 环境无 window。
 */
import { describe, expect, it } from 'vitest'
import { trackDisposables } from './pluginLoader'
import type { Disposable } from '@everyagent/plugin-api'

function makeRegistry() {
  const disposed: string[] = []
  let component = function OriginalComponent(): null {
    return null
  }
  const registry = {
    /** 组件型成员：函数值，绝不能被包装成每次访问都变的新闭包。 */
    get Component(): () => null {
      return component
    },
    replaceComponent(next: () => null): void {
      component = next
    },
    /** 注册方法：返回 Disposable，应被收集。 */
    registerSomething(id: string): Disposable {
      return {
        dispose() {
          disposed.push(id)
        },
      }
    },
    /** 普通方法：返回值非 Disposable，不收集。 */
    plain(): number {
      return 42
    },
    /** 非函数属性：原样透传。 */
    version: '1.0',
  }
  return { registry, disposed }
}

describe('trackDisposables', () => {
  it('同一函数属性重复访问返回同一引用（组件型成员身份稳定，防重挂失焦）', () => {
    const { registry } = makeRegistry()
    const disposables: Disposable[] = []
    const ui = trackDisposables(registry, disposables)

    const first = ui.Component
    const second = ui.Component
    expect(first).toBe(second)
    // 不同属性名之间互不串缓存：普通方法也各自稳定。
    expect(ui.plain).toBe(ui.plain)
    expect(ui.plain).not.toBe(first)
  })

  it('包装函数转发调用并保留返回值；返回 Disposable 的调用被收集', () => {
    const { registry } = makeRegistry()
    const disposables: Disposable[] = []
    const ui = trackDisposables(registry, disposables)

    expect(ui.version).toBe('1.0')
    expect(ui.plain()).toBe(42)
    expect(disposables).toHaveLength(0)

    const ret = ui.registerSomething('a')
    expect(typeof ret.dispose).toBe('function')
    expect(disposables).toHaveLength(1)
    expect(disposables[0]).toBe(ret)
  })

  it('底层函数被替换后按新函数重新包装（不返回过期缓存）', () => {
    const { registry } = makeRegistry()
    const disposables: Disposable[] = []
    const ui = trackDisposables(registry, disposables)

    const before = ui.Component
    registry.replaceComponent(function NextComponent(): null {
      return null
    })
    const after = ui.Component
    expect(after).not.toBe(before)
    expect(ui.Component).toBe(after)
  })
})
