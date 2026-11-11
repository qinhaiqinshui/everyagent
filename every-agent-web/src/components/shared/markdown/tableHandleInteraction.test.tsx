// @vitest-environment jsdom
/**
 * 表格外把手栏交互测试(需 DOM 事件序列):
 * - 行高 ⇕ 把手在表外左栏,每行一个(回归:曾渲染在每个单元格底部 = 行×列个);
 * - 列宽 ↔ 把手在表外顶栏,每列右缘一个(回归:曾藏在 th 内需单击表头唤出);
 * - 把手不落在任何 th/td 内(不压单元格内容);
 * - 拖动调列宽(colgroup 覆盖为 px),双击重置(回到 ch 口径)。
 */
import { describe, it, expect, afterEach, beforeAll } from 'vitest'
import React from 'react'
import { createRoot, type Root } from 'react-dom/client'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { buildMarkdownComponents } from './sharedMarkdownRenderer'

const md = [
  '| 列一 | 列二 |',
  '| --- | --- |',
  '| alpha | beta |',
  '| gamma | delta |',
].join('\n')

beforeAll(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  // jsdom 无 ResizeObserver(几何测量钩子依赖):桩掉,首次测量走 effect 内同步 measure()。
  if (typeof (globalThis as { ResizeObserver?: unknown }).ResizeObserver === 'undefined') {
    ;(globalThis as { ResizeObserver?: unknown }).ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  }
  // jsdom 无指针捕获 API(拖动路径调用)且无布局(矩形全零):桩成统一矩形,
  // 让把手可渲染(几何非空)且拖动原点可预期(列宽/行高均 100×30)。
  Element.prototype.setPointerCapture = () => {}
  Element.prototype.releasePointerCapture = () => {}
  Element.prototype.hasPointerCapture = () => false
  Element.prototype.getBoundingClientRect = function () {
    return { top: 0, left: 0, right: 100, bottom: 30, width: 100, height: 30, x: 0, y: 0, toJSON: () => ({}) } as DOMRect
  }
})

/** 派发原生鼠标口径事件(React 合成事件按类型分发)。 */
function fire(el: Element, type: string, init: MouseEventInit = {}): void {
  el.dispatchEvent(new window.MouseEvent(type, { bubbles: true, cancelable: true, button: 0, ...init }))
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

async function renderTable(): Promise<HTMLElement> {
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
          onTableCellEdit: () => {},
        })}
      >
        {md}
      </Markdown>,
    )
  })
  return container
}

const colHandles = (): NodeListOf<Element> => document.querySelectorAll('[aria-label="调整列宽"]')
const rowHandles = (): NodeListOf<Element> => document.querySelectorAll('[aria-label="调整行高"]')

describe('表格外把手栏(行高/列宽)', () => {
  it('行高把手:每行一个(2 行 2 列 → 2 个),不在任何 th/td 内', async () => {
    const container = await renderTable()
    expect(rowHandles().length).toBe(2)
    container.querySelectorAll('th, td').forEach((cell) => {
      expect(cell.querySelector('[aria-label="调整行高"], [aria-label="调整列宽"]')).toBeNull()
    })
    rowHandles().forEach((handle) => expect(handle.closest('table')).toBeNull())
  })

  it('列宽把手:每列一个(2 列 → 2 个),不在表格内', async () => {
    const container = await renderTable()
    expect(colHandles().length).toBe(2)
    colHandles().forEach((handle) => expect(handle.closest('table')).toBeNull())
  })

  it('拖动列把手加宽:colgroup 首列覆盖为拖动后的 px 宽', async () => {
    const container = await renderTable()
    const handle = colHandles()[0]
    await React.act(async () => {
      fire(handle, 'pointerdown', { clientX: 100, clientY: 9 })
      fire(handle, 'pointermove', { clientX: 130, clientY: 9 })
      fire(handle, 'pointerup', { clientX: 130, clientY: 9 })
    })
    const firstCol = container.querySelector('col')
    expect(firstCol?.getAttribute('style')).toContain('130px')
  })

  it('双击列把手:重置为自动列宽(回到 ch 口径)', async () => {
    const container = await renderTable()
    const handle = colHandles()[0]
    await React.act(async () => {
      fire(handle, 'pointerdown', { clientX: 100, clientY: 9 })
      fire(handle, 'pointermove', { clientX: 150, clientY: 9 })
      fire(handle, 'pointerup', { clientX: 150, clientY: 9 })
    })
    expect(container.querySelector('col')?.getAttribute('style')).toContain('150px')
    await React.act(async () => {
      fire(handle, 'dblclick')
    })
    expect(container.querySelector('col')?.getAttribute('style')).toContain('ch')
  })

  it('拖动/双击行把手不抛错,行把手保持(行高覆盖走内容层 clamp)', async () => {
    const container = await renderTable()
    const handle = rowHandles()[0]
    await React.act(async () => {
      fire(handle, 'pointerdown', { clientX: 10, clientY: 30 })
      fire(handle, 'pointermove', { clientX: 10, clientY: 120 })
      fire(handle, 'pointerup', { clientX: 10, clientY: 120 })
    })
    expect(rowHandles().length).toBe(2)
    await React.act(async () => {
      fire(handle, 'dblclick')
    })
    expect(rowHandles().length).toBe(2)
  })
})
