// @vitest-environment jsdom
/**
 * 表格列宽/行高把手的交互级测试(需 DOM 事件序列):
 * 索引注入(injectTableCellIndices)按组件引用匹配 Th/Td——react-markdown 在
 * 进入 table 组件前已完成 components 映射,children 里的 th/td 已是组件元素,
 * 若按宿主标签字符串匹配会静默失效(列宽把手单击不出现、行高把手缺失)。
 * 本文件用真实 DOM 事件(pointerdown/click)覆盖该回归。
 */
import { describe, it, expect, afterEach, beforeAll } from 'vitest'
import React from 'react'
import { createRoot, type Root } from 'react-dom/client'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { buildMarkdownComponents } from './sharedMarkdownRenderer'

const md = '| 列一 | 列二 |\n| --- | --- |\n| 1 | 2 |'

// jsdom 未实现指针捕获 API,拖动路径(beginColumnResize/beginRowResize)会调用,
// 桩掉避免合成事件下的 TypeError(真实浏览器原生支持)。
beforeAll(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
  if (typeof Element.prototype.setPointerCapture !== 'function') {
    Element.prototype.setPointerCapture = () => {}
  }
  if (typeof Element.prototype.releasePointerCapture !== 'function') {
    Element.prototype.releasePointerCapture = () => {}
  }
  if (typeof Element.prototype.hasPointerCapture !== 'function') {
    Element.prototype.hasPointerCapture = () => false
  }
})

/** 派发原生鼠标口径事件(React 合成事件与全局 capture 监听均按类型分发)。 */
function fire(el: Element, type: string): void {
  el.dispatchEvent(new window.MouseEvent(type, { bubbles: true, cancelable: true, button: 0 }))
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

async function renderTable(markdown = md): Promise<HTMLElement> {
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
          sourceText: markdown,
          onTableCellEdit: () => {},
        })}
      >
        {markdown}
      </Markdown>,
    )
  })
  return container
}

describe('表格把手交互(单击表头/索引注入)', () => {
  it('正文单元格注入行索引:td 内渲染行高把手 ⇕(缺失即索引注入失效)', async () => {
    const container = await renderTable()
    // __rowIndex 已注入 → td 内渲染行高把手(⇕,aria-label=调整行高)。
    expect(container.querySelectorAll('[aria-label="调整行高"]').length).toBe(2)
  })

  it('单击表头 → ↔ 列宽把手出现在该列表头内并保持显示', async () => {
    const container = await renderTable()
    const th = container.querySelector('th')!
    expect(container.querySelectorAll('[aria-label="调整列宽"]').length).toBe(0)

    await React.act(async () => {
      fire(th, 'pointerdown')
      fire(th, 'pointerup')
      fire(th, 'click')
    })
    const handles = container.querySelectorAll('[aria-label="调整列宽"]')
    expect(handles.length).toBe(1)
    expect(th.contains(handles[0])).toBe(true)

    // 无后续事件时把手保持(不闪烁消失)。
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(container.querySelectorAll('[aria-label="调整列宽"]').length).toBe(1)
  })

  it('再次单击同一表头 → 把手收起(切换)', async () => {
    const container = await renderTable()
    const th = container.querySelector('th')!
    await React.act(async () => {
      fire(th, 'pointerdown')
      fire(th, 'click')
    })
    expect(container.querySelectorAll('[aria-label="调整列宽"]').length).toBe(1)

    await React.act(async () => {
      fire(th, 'pointerdown')
      fire(th, 'click')
    })
    expect(container.querySelectorAll('[aria-label="调整列宽"]').length).toBe(0)
  })

  it('把手显示后点击表头之外的单元格(pointerdown) → 把手收起', async () => {
    const container = await renderTable()
    const th = container.querySelector('th')!
    await React.act(async () => {
      fire(th, 'pointerdown')
      fire(th, 'click')
    })
    expect(container.querySelectorAll('[aria-label="调整列宽"]').length).toBe(1)

    const td = container.querySelector('td')!
    await React.act(async () => {
      fire(td, 'pointerdown')
      fire(td, 'click')
    })
    expect(container.querySelectorAll('[aria-label="调整列宽"]').length).toBe(0)
  })

  it('单击另一列表头 → 把手转移到新列(单把手机制)', async () => {
    const container = await renderTable()
    const [first, second] = Array.from(container.querySelectorAll('th'))
    await React.act(async () => {
      fire(first, 'pointerdown')
      fire(first, 'click')
    })
    await React.act(async () => {
      fire(second, 'pointerdown')
      fire(second, 'click')
    })
    const handles = container.querySelectorAll('[aria-label="调整列宽"]')
    expect(handles.length).toBe(1)
    expect(second.contains(handles[0])).toBe(true)
  })
})
