// @vitest-environment jsdom
/**
 * 单元格编辑弹窗的交互级测试(需真实 DOM 事件序列):
 * - 打开即聚焦但不全选(回归:旧版 element.select() 全选,随手一敲整段覆盖原内容);
 * - 失焦/点击弹窗外部不自动收口(回归:旧版 window pointerdown 捕获即提交关闭);
 * - 弹窗互斥:双击其他单元格时旧弹窗自动提交收口,不叠层;
 * - Esc / Ctrl+Enter 显式收口仍有效;
 * - 双击表头(th)同样弹窗并可退出(回归:8c548ae8 弹窗化时 Th 漏挂 cell.editor,
 *   表头进入编辑态却无弹窗、无退出路径)。
 */
import { describe, it, expect, afterEach, beforeAll, vi } from 'vitest'
import React from 'react'
import { createRoot, type Root } from 'react-dom/client'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { buildMarkdownComponents } from './sharedMarkdownRenderer'
import type { MarkdownTableCellEditRequest } from './markdownTableEdit'

const md = '| 列一 | 列二 |\n| --- | --- |\n| alpha | beta |'

beforeAll(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  // jsdom 未开可视化模式时缺 rAF(beginEdit 的聚焦/光标逻辑在 rAF 回调里)。
  if (typeof globalThis.requestAnimationFrame !== 'function') {
    globalThis.requestAnimationFrame = (callback: FrameRequestCallback): number =>
      window.setTimeout(() => callback(performance.now()), 0) as unknown as number
  }
})

/** 派发原生鼠标口径事件(React 合成事件按类型分发;pointerdown 无 PointerEvent 构造器时用 MouseEvent)。 */
function fire(el: Element, type: string, init: MouseEventInit = {}): void {
  el.dispatchEvent(new window.MouseEvent(type, { bubbles: true, cancelable: true, button: 0, ...init }))
}

function fireKey(el: Element, key: string, ctrlKey = false): void {
  el.dispatchEvent(new window.KeyboardEvent('keydown', { key, ctrlKey, bubbles: true, cancelable: true }))
}

/** 绕过 React 值追踪设值并派发 input(触发 onChange)。 */
function setNativeValue(el: HTMLTextAreaElement, value: string): void {
  const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!
  setter.call(el, value)
  el.dispatchEvent(new window.Event('input', { bubbles: true }))
}

/** 等 rAF 回调(beginEdit 聚焦/设光标)执行完。 */
const nextFrame = (): Promise<void> => new Promise((resolve) => { setTimeout(resolve, 30) })

/** jsdom 的 getBoundingClientRect 全零 → Td 双击被底缘行高分支(hitRowEdge)拦截,
 *  给出正常矩形让双击落到编辑分支(真实浏览器矩形非零,无此问题)。 */
function mockRect(el: Element, top = 100, height = 40): void {
  el.getBoundingClientRect = () => ({
    top, left: 0, right: 100, bottom: top + height, width: 100, height, x: 0, y: top,
    toJSON: () => ({}),
  }) as DOMRect
}

const roots: { container: HTMLElement; root: Root }[] = []

afterEach(async () => {
  while (roots.length) {
    const { container, root } = roots.pop()!
    await React.act(async () => {
      root.unmount()
    })
    container.remove()
  }
})

async function renderTable(
  onTableCellEdit: (request: MarkdownTableCellEditRequest) => void = () => {},
): Promise<HTMLElement> {
  const container = document.createElement('div')
  document.body.appendChild(container)
  const root = createRoot(container)
  roots.push({ container, root })
  await React.act(async () => {
    root.render(
      <Markdown
        remarkPlugins={[remarkGfm]}
        components={buildMarkdownComponents({
          variant: 'preview',
          sourceText: md,
          onTableCellEdit,
        })}
      >
        {md}
      </Markdown>,
    )
  })
  return container
}

/** 弹窗经 portal 挂在 body 下(不在容器内),全局取 textarea 即弹窗编辑框。 */
const dialogTextareas = (): HTMLTextAreaElement[] =>
  Array.from(document.querySelectorAll<HTMLTextAreaElement>('textarea'))

describe('单元格编辑弹窗交互', () => {
  it('双击正文单元格弹出编辑窗:聚焦但不全选,光标落在内容末尾', async () => {
    const container = await renderTable()
    const td = container.querySelector('td')!
    mockRect(td)
    await React.act(async () => {
      fire(td, 'dblclick')
    })
    await nextFrame()
    const [textarea] = dialogTextareas()
    expect(textarea).toBeDefined()
    expect(textarea.value).toBe('alpha')
    expect(document.activeElement).toBe(textarea)
    // 不全选(旧版 select():start=0、end=5);光标折叠在末尾。
    expect(textarea.selectionStart).toBe('alpha'.length)
    expect(textarea.selectionEnd).toBe('alpha'.length)
  })

  it('点击弹窗外部(失焦)不自动收口:弹窗保持打开', async () => {
    const onEdit = vi.fn()
    const container = await renderTable(onEdit)
    const td = container.querySelector('td')!
    mockRect(td)
    await React.act(async () => {
      fire(td, 'dblclick')
    })
    expect(dialogTextareas().length).toBe(1)

    // 旧版在 window 捕获阶段监听 pointerdown,点外部即提交收口;现在应保持打开。
    await React.act(async () => {
      fire(document.body, 'pointerdown')
      fire(document.body, 'mousedown')
      fire(document.body, 'click')
    })
    await nextFrame()
    expect(dialogTextareas().length).toBe(1)
    expect(onEdit).not.toHaveBeenCalled()
  })

  it('双击其他单元格:旧弹窗自动提交收口(互斥,不叠层)', async () => {
    const onEdit = vi.fn()
    const container = await renderTable(onEdit)
    const [first, second] = Array.from(container.querySelectorAll('td'))
    mockRect(first)
    mockRect(second)
    await React.act(async () => {
      fire(first, 'dblclick')
    })
    await nextFrame()
    const firstArea = dialogTextareas()[0]
    await React.act(async () => {
      setNativeValue(firstArea, 'ALPHA')
    })

    await React.act(async () => {
      fire(second, 'dblclick')
    })
    expect(dialogTextareas().length).toBe(1)
    expect(dialogTextareas()[0].value).toBe('beta')
    expect(onEdit).toHaveBeenCalledTimes(1)
    expect(onEdit).toHaveBeenCalledWith(expect.objectContaining({ original: 'alpha', next: 'ALPHA' }))
  })

  it('Ctrl+Enter 提交草稿并关闭弹窗', async () => {
    const onEdit = vi.fn()
    const container = await renderTable(onEdit)
    const td = container.querySelector('td')!
    mockRect(td)
    await React.act(async () => {
      fire(td, 'dblclick')
    })
    await nextFrame()
    const textarea = dialogTextareas()[0]
    await React.act(async () => {
      setNativeValue(textarea, 'changed')
    })
    await React.act(async () => {
      fireKey(textarea, 'Enter', true)
    })
    expect(onEdit).toHaveBeenCalledWith(expect.objectContaining({ original: 'alpha', next: 'changed' }))
    expect(dialogTextareas().length).toBe(0)
  })

  it('Esc 关闭弹窗且不上报编辑', async () => {
    const onEdit = vi.fn()
    const container = await renderTable(onEdit)
    const td = container.querySelector('td')!
    mockRect(td)
    await React.act(async () => {
      fire(td, 'dblclick')
    })
    await nextFrame()
    const textarea = dialogTextareas()[0]
    await React.act(async () => {
      fireKey(textarea, 'Escape')
    })
    expect(onEdit).not.toHaveBeenCalled()
    expect(dialogTextareas().length).toBe(0)
  })

  it('双击表头(th)同样弹出编辑窗且可退出', async () => {
    const container = await renderTable()
    const th = container.querySelector('th')!
    await React.act(async () => {
      fire(th, 'dblclick')
    })
    await nextFrame()
    const [textarea] = dialogTextareas()
    expect(textarea).toBeDefined()
    expect(textarea.value).toBe('列一')
    expect(document.activeElement).toBe(textarea)

    await React.act(async () => {
      fireKey(textarea, 'Escape')
    })
    expect(dialogTextareas().length).toBe(0)
  })
})
