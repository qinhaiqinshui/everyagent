// @vitest-environment jsdom
/**
 * InlineComposer 初始受控值反显回归测试。
 *
 * 背景（bug）：rebuild 守卫的 lastEmittedRef 曾初始化为挂载时的 rawContent，
 * 首挂时「rawContent === 上次上报值」恒成立 → DOM 重建被跳过，而 contentEditable
 * div 由 React 渲染时无 children——「挂载即非空」的受控值（workflow 定义编辑器
 * AI 节点 input 模板反显、草稿恢复等场景）永远写不进输入框，表现为空白且
 * placeholder 状态（--empty 类）无人维护。
 *
 * 修复：lastEmittedRef 以 null 哨兵起步（尚未与 DOM 同步过），首挂必同步一次。
 */
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { act } from 'react'

vi.mock('@/plugin/PluginDispatcher', () => ({
  pluginDispatcher: { findComposerChipAction: () => null },
}))
vi.mock('@/services/pastedFileService', () => ({
  buildTokenForPastedFile: vi.fn(),
}))
vi.mock('./SlashItemDetailPopover', () => ({
  openSlashItemDetail: vi.fn(),
}))

import InlineComposer from './InlineComposer'
import type { ChatComposerToken } from '@/types'

let container: HTMLDivElement | null = null
let root: Root | null = null

beforeAll(() => {
  ;(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
})

afterEach(() => {
  act(() => root?.unmount())
  container?.remove()
  container = null
  root = null
})

function render(rawContent: string, tokens: ChatComposerToken[] = []): HTMLElement {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  act(() => {
    root?.render(
      <InlineComposer
        rawContent={rawContent}
        tokens={tokens}
        placeholder="占位文案"
        onChange={() => {}}
      />,
    )
  })
  const editor = container.querySelector<HTMLElement>('.nagent-inline-editor')
  if (!editor) throw new Error('未渲染编辑器根节点')
  return editor
}

describe('InlineComposer 初始受控值反显', () => {
  it('挂载即非空的 rawContent 渲染进 DOM（修复前为空白）', () => {
    const editor = render('写一篇关于{{novelName}}的短文')
    expect(editor.textContent).toContain('写一篇关于{{novelName}}的短文')
    expect(editor.classList.contains('nagent-inline-editor--empty')).toBe(false)
  })

  it('挂载时为空 → 进入 placeholder 状态（--empty 类）', () => {
    const editor = render('')
    expect(editor.classList.contains('nagent-inline-editor--empty')).toBe(true)
  })

  it('初始值含 opaque token → 挂载后还原为胶囊（data-opaque 保留可序列化）', () => {
    const rawContent = `参考文件 [[[[agent-token::::system.workspace_file||||label====说明.md||||payload====e30]]]] 后再写`
    const editor = render(rawContent)
    const chip = editor.querySelector<HTMLElement>('.nagent-inline-chip')
    expect(chip).not.toBeNull()
    expect(chip?.dataset.label).toBe('说明.md')
    expect(chip?.dataset.opaque).toBe('[[[[agent-token::::system.workspace_file||||label====说明.md||||payload====e30]]]]')
    // 正文文本与胶囊并存。
    expect(editor.textContent).toContain('参考文件')
    expect(editor.textContent).toContain('后再写')
  })

  it('挂载后外部改写 rawContent 仍走既有重建路径（等值守卫不回归）', () => {
    const editor = render('第一版')
    expect(editor.textContent).toContain('第一版')
    act(() => {
      root?.render(
        <InlineComposer
          rawContent="第二版"
          tokens={[]}
          placeholder="占位文案"
          onChange={() => {}}
        />,
      )
    })
    expect(editor.textContent).toContain('第二版')
    expect(editor.textContent).not.toContain('第一版')
  })
})
