// @vitest-environment jsdom
/**
 * 手动复制降落伞弹窗(剪贴板写入失败时的兜底)交互测试:
 * - 打开即把文本全选,用户可直接按 Ctrl+C(⌘C);
 * - 「重试复制」落在**新的点击手势**内,走同步 copyTextSync(非安全上下文下唯一可用路径);
 * - 同步失败退回异步 copyText,失败不计成功;
 * - 关闭回调由「关闭」按钮与遮罩点击触发。
 */
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { act } from 'react'

const { copyTextSyncMock, copyTextMock } = vi.hoisted(() => ({
  copyTextSyncMock: vi.fn<(text: string) => boolean>(),
  copyTextMock: vi.fn<(text: string) => Promise<void>>(),
}))

vi.mock('@/utils/clipboard', () => ({
  copyText: copyTextMock,
  copyTextSync: copyTextSyncMock,
}))

import ManualCopyDialog from './ManualCopyDialog'

const PATH = '/home/deploy/.everyagent/workspaces/ws1/tasks/t1'

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
  copyTextSyncMock.mockReset()
  copyTextMock.mockReset()
})

function render(onClose: () => void = () => {}) {
  container = document.createElement('div')
  document.body.appendChild(container)
  root = createRoot(container)
  act(() => {
    root?.render(<ManualCopyDialog open title="复制任务路径" text={PATH} onClose={onClose} />)
  })
  return container
}

function findButton(label: string): HTMLButtonElement {
  // antd Button 会给两个中文字之间插空格(「关 闭」),比对前归一化空白。
  const button = Array.from(document.querySelectorAll('button')).find(
    (node) => node.textContent?.replace(/\s+/g, '') === label,
  )
  if (!button) throw new Error(`未渲染按钮:${label}`)
  return button as HTMLButtonElement
}

/** 弹窗经 portal 渲染到 document.body,断言从 document 取。 */
const dialogText = () => document.body.textContent ?? ''

describe('ManualCopyDialog', () => {
  it('打开即展示全选状态的只读文本域', () => {
    render()
    const textarea = document.querySelector('textarea') as HTMLTextAreaElement | null
    expect(textarea?.value).toBe(PATH)
    expect(textarea?.readOnly).toBe(true)
    expect(textarea?.selectionStart).toBe(0)
    expect(textarea?.selectionEnd).toBe(PATH.length)
  })

  it('「重试复制」走同步剪贴板写入(新点击手势内),成功后提示已复制', async () => {
    copyTextSyncMock.mockReturnValue(true)
    render()

    await act(async () => {
      findButton('重试复制').click()
    })

    expect(copyTextSyncMock).toHaveBeenCalledWith(PATH)
    expect(copyTextMock).not.toHaveBeenCalled()
    expect(dialogText()).toContain('已复制')
  })

  it('同步写入失败时退回异步 API;失败不误报「已复制」', async () => {
    copyTextSyncMock.mockReturnValue(false)
    copyTextMock.mockRejectedValue(new Error('浏览器拒绝了剪贴板访问'))
    render()

    await act(async () => {
      findButton('重试复制').click()
    })

    expect(copyTextMock).toHaveBeenCalledWith(PATH)
    expect(dialogText()).not.toContain('已复制到剪贴板')
  })

  it('「关闭」按钮触发 onClose', async () => {
    copyTextSyncMock.mockReturnValue(true)
    const onClose = vi.fn()
    render(onClose)

    await act(async () => {
      findButton('关闭').click()
    })

    expect(onClose).toHaveBeenCalled()
  })
})