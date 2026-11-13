// @vitest-environment jsdom
/**
 * 表格外浮动工具栏与把手栏交互测试(需 DOM 事件序列):
 * - 单击表头切换**工具栏**(复制内容/调整表格),默认隐藏;
 * - 工具栏「调整表格」按钮切换把手栏(= 旧版表头单击行为);
 * - 工具栏不可见时把手栏连带收起;点击表格的非表头区域(单元格)全部收起;
 * - 「复制内容」把整表 markdown 源码(offset 切片)写入剪贴板;
 * - 桌面口径(hover 能力)悬停表格亦显示工具栏,移出即隐(固定态除外);
 * - 行高 ⇕ 把手在表外左栏每行一个(回归:曾渲染在每个单元格底部 = 行×列个);
 * - 列宽 ↔ 把手在表外顶栏每列右缘一个,不在任何 th/td 内;
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

/** 派发原生鼠标口径事件(React 合成事件与全局 capture 监听均按类型分发)。 */
function fire(el: Element, type: string, init: MouseEventInit = {}): void {
  el.dispatchEvent(new window.MouseEvent(type, { bubbles: true, cancelable: true, button: 0, ...init }))
}

/** 单击表头(pointerdown → click 完整序列,兼容全局 capture 收起规则)。 */
async function clickHeader(container: HTMLElement): Promise<void> {
  const th = container.querySelector('th')!
  await React.act(async () => {
    fire(th, 'pointerdown')
    fire(th, 'click')
  })
}

/** 单击表格元素(表头之外,如单元格):验证非表头区域收起规则。 */
async function clickCell(container: HTMLElement): Promise<void> {
  const td = container.querySelector('td')!
  await React.act(async () => {
    fire(td, 'pointerdown')
    fire(td, 'click')
  })
}

/** 单击工具栏按钮(按按钮文本定位)。 */
async function clickToolbarButton(label: string): Promise<void> {
  const button = Array.from(document.querySelectorAll<HTMLButtonElement>('.md-table-toolbar button'))
    .find((el) => el.textContent?.includes(label))
  expect(button, `工具栏按钮「${label}」应存在`).toBeTruthy()
  await React.act(async () => {
    fire(button!, 'pointerdown')
    fire(button!, 'click')
  })
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

const toolbar = (): Element | null => document.querySelector('.md-table-toolbar')
const colHandles = (): NodeListOf<Element> => document.querySelectorAll('[aria-label="调整列宽"]')
const rowHandles = (): NodeListOf<Element> => document.querySelectorAll('[aria-label="调整行高"]')

/** 桩 matchMedia 为 hover 能力设备;返回恢复函数。 */
function stubHoverMedia(): () => void {
  const original = window.matchMedia
  window.matchMedia = (() => ({
    matches: true,
    media: '',
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  })) as unknown as typeof window.matchMedia
  return () => { window.matchMedia = original }
}

/** 模拟指针移入/移出表格(React 以 pointerover/pointerout 派发 enter/leave)。 */
async function hover(el: Element, over: boolean): Promise<void> {
  await React.act(async () => {
    el.dispatchEvent(new window.MouseEvent(over ? 'pointerover' : 'pointerout', {
      bubbles: true,
      cancelable: true,
      relatedTarget: document.body,
    }))
  })
}

describe('表格外浮动工具栏(单击表头切换)', () => {
  it('默认隐藏:工具栏与把手栏均不渲染', async () => {
    await renderTable()
    expect(toolbar()).toBeNull()
    expect(colHandles().length).toBe(0)
    expect(rowHandles().length).toBe(0)
  })

  it('单击表头 → 工具栏显示(复制内容/调整表格),把手栏仍隐藏', async () => {
    const container = await renderTable()
    await clickHeader(container)
    expect(toolbar()).not.toBeNull()
    expect(toolbar()!.textContent).toContain('复制内容')
    expect(toolbar()!.textContent).toContain('调整表格')
    expect(colHandles().length).toBe(0)
    expect(rowHandles().length).toBe(0)
  })

  it('再次单击表头 → 工具栏隐藏(切换)', async () => {
    const container = await renderTable()
    await clickHeader(container)
    expect(toolbar()).not.toBeNull()
    await clickHeader(container)
    expect(toolbar()).toBeNull()
  })

  it('「复制内容」→ 剪贴板收到整表 markdown 源码(offset 切片,保留原文)', async () => {
    let copied = ''
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: async (text: string) => { copied = text } },
      configurable: true,
    })
    try {
      const container = await renderTable()
      await clickHeader(container)
      await clickToolbarButton('复制内容')
      expect(copied).toBe(md)
      expect(toolbar()!.textContent).toContain('已复制')
    } finally {
      delete (navigator as { clipboard?: unknown }).clipboard
    }
  })

  it('未注入 sourceText 时「复制内容」走矩阵重建兜底(纯文本 GFM)', async () => {
    let copied = ''
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: async (text: string) => { copied = text } },
      configurable: true,
    })
    try {
      const container = document.createElement('div')
      document.body.appendChild(container)
      const root = createRoot(container)
      roots.push({ container, root })
      await React.act(async () => {
        root.render(
          <Markdown remarkPlugins={[remarkGfm]} components={buildMarkdownComponents({ variant: 'preview' })}>
            {md}
          </Markdown>,
        )
      })
      await clickHeader(container)
      await clickToolbarButton('复制内容')
      expect(copied).toContain('| 列一 | 列二 |')
      expect(copied).toContain('| --- | --- |')
      expect(copied).toContain('| alpha | beta |')
    } finally {
      delete (navigator as { clipboard?: unknown }).clipboard
    }
  })
})

describe('表格外把手栏(工具栏「调整表格」切换)', () => {
  /** 唤出工具栏并进入调整模式。 */
  async function enterAdjustMode(container: HTMLElement): Promise<void> {
    await clickHeader(container)
    await clickToolbarButton('调整表格')
  }

  it('「调整表格」→ 把手栏显示:行高每行一个(2 行 → 2 个),列宽每列一个(2 列 → 2 个),且不在任何 th/td 内', async () => {
    const container = await renderTable()
    await enterAdjustMode(container)
    expect(rowHandles().length).toBe(2)
    expect(colHandles().length).toBe(2)
    container.querySelectorAll('th, td').forEach((cell) => {
      expect(cell.querySelector('[aria-label="调整行高"], [aria-label="调整列宽"]')).toBeNull()
    })
    rowHandles().forEach((handle) => expect(handle.closest('table')).toBeNull())
    colHandles().forEach((handle) => expect(handle.closest('table')).toBeNull())
  })

  it('再次「调整表格」→ 把手栏收起,工具栏保留', async () => {
    const container = await renderTable()
    await enterAdjustMode(container)
    await clickToolbarButton('调整表格')
    expect(colHandles().length).toBe(0)
    expect(rowHandles().length).toBe(0)
    expect(toolbar()).not.toBeNull()
  })

  it('工具栏隐藏时把手栏保持驻留(解耦:悬停离开/表头切换不收起调整模式)', async () => {
    const container = await renderTable()
    await enterAdjustMode(container)
    expect(colHandles().length).toBe(2)
    // 表头再次单击只是隐藏工具栏,把手栏驻留(= 旧版表头切换行为)。
    await clickHeader(container)
    expect(toolbar()).toBeNull()
    expect(colHandles().length).toBe(2)
    expect(rowHandles().length).toBe(2)
  })

  it('悬停场景(bug 回归):移开鼠标工具栏隐藏,把手栏驻留;移回工具栏重现且按钮高亮', async () => {
    const restore = stubHoverMedia()
    try {
      const container = await renderTable()
      const th = container.querySelector('th')!
      await hover(th, true)
      await clickToolbarButton('调整表格')
      expect(colHandles().length).toBe(2)
      // 移开鼠标:工具栏(悬停态)隐藏,但调整模式驻留——不能跟着消失。
      await hover(th, false)
      expect(toolbar()).toBeNull()
      expect(colHandles().length).toBe(2)
      expect(rowHandles().length).toBe(2)
      // 移回鼠标:工具栏重现,「调整表格」按钮保持激活态,可再点关闭。
      await hover(th, true)
      expect(toolbar()).not.toBeNull()
      const adjustButton = Array.from(document.querySelectorAll<HTMLButtonElement>('.md-table-toolbar button'))
        .find((el) => el.textContent?.includes('调整表格'))
      expect(adjustButton?.getAttribute('aria-pressed')).toBe('true')
      await clickToolbarButton('调整表格')
      expect(colHandles().length).toBe(0)
    } finally {
      restore()
    }
  })

  it('点击单元格(表头之外) → 工具栏与把手栏全部收起', async () => {
    const container = await renderTable()
    await enterAdjustMode(container)
    expect(colHandles().length).toBe(2)
    await clickCell(container)
    expect(toolbar()).toBeNull()
    expect(colHandles().length).toBe(0)
    expect(rowHandles().length).toBe(0)
  })

  it('拖动列把手加宽:colgroup 首列覆盖为拖动后的 px 宽', async () => {
    const container = await renderTable()
    await enterAdjustMode(container)
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
    await enterAdjustMode(container)
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
    await enterAdjustMode(container)
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

describe('浮动工具栏(桌面悬停显示)', () => {
  it('悬停表格 → 工具栏显示;移出 → 隐藏', async () => {
    const restore = stubHoverMedia()
    try {
      const container = await renderTable()
      const th = container.querySelector('th')!
      await hover(th, true)
      expect(toolbar()).not.toBeNull()
      await hover(th, false)
      expect(toolbar()).toBeNull()
    } finally {
      restore()
    }
  })

  it('悬停唤出后点击表头固定:移出仍显示,再点表头才隐藏', async () => {
    const restore = stubHoverMedia()
    try {
      const container = await renderTable()
      const th = container.querySelector('th')!
      await hover(th, true)
      await clickHeader(container)
      await hover(th, false)
      expect(toolbar()).not.toBeNull()
      await hover(th, true)
      await clickHeader(container)
      // 取消固定后指针仍在表格内(悬停中)→ 工具栏保持可见。
      expect(toolbar()).not.toBeNull()
      await hover(th, false)
      expect(toolbar()).toBeNull()
    } finally {
      restore()
    }
  })

  it('无 hover 能力设备(触屏)不挂悬停监听:pointerover 不唤出工具栏', async () => {
    const container = await renderTable()
    const th = container.querySelector('th')!
    await hover(th, true)
    expect(toolbar()).toBeNull()
  })
})
