// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ClipboardWriteError, copyText, copyTextSync } from './clipboard'

/**
 * 剪贴板工具的两条路对「何时可用」要求不同(异步 API 仅安全上下文;同步 execCommand 需
 * 用户手势),这里的用例把两条路的组合与降级顺序钉死:任何一条可用就必须成功,
 * 全失败才抛 ClipboardWriteError(UI 绝不误报「已复制」)。
 */

describe('clipboard', () => {
  afterEach(() => {
    // 复位两处环境替身,避免用例间互相污染。
    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true })
    Object.defineProperty(document, 'execCommand', { value: undefined, configurable: true })
  })

  const stubClipboard = (value: unknown) => {
    Object.defineProperty(navigator, 'clipboard', { value, configurable: true })
  }

  const stubExecCommand = (result: boolean) => {
    const exec = vi.fn(() => result)
    Object.defineProperty(document, 'execCommand', { value: exec, configurable: true, writable: true })
    return exec
  }

  const textareaCount = () => document.body.querySelectorAll('textarea').length

  it('安全上下文:走异步 Clipboard API,不碰 execCommand', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    stubClipboard({ writeText })
    const exec = stubExecCommand(true)

    await expect(copyText('p')).resolves.toBeUndefined()
    expect(writeText).toHaveBeenCalledWith('p')
    expect(exec).not.toHaveBeenCalled()
  })

  it('非安全上下文(无 navigator.clipboard):同步 execCommand 兜底成功', async () => {
    stubClipboard(undefined)
    const exec = stubExecCommand(true)

    await expect(copyText('p')).resolves.toBeUndefined()
    expect(exec).toHaveBeenCalledTimes(1)
  })

  it('异步 API 被拒(权限/失焦):落同步 execCommand 兜底', async () => {
    stubClipboard({ writeText: vi.fn().mockRejectedValue(new Error('Write permission denied')) })
    const exec = stubExecCommand(true)

    await expect(copyText('p')).resolves.toBeUndefined()
    expect(exec).toHaveBeenCalledTimes(1)
  })

  it('两条路都失败:抛 ClipboardWriteError,不静默成功', async () => {
    stubClipboard({ writeText: vi.fn().mockRejectedValue(new Error('Write permission denied')) })
    stubExecCommand(false)

    await expect(copyText('p')).rejects.toBeInstanceOf(ClipboardWriteError)
  })

  it('copyTextSync 成功返回 true 且不残留临时 textarea', () => {
    stubExecCommand(true)
    const before = textareaCount()

    expect(copyTextSync('p')).toBe(true)
    expect(textareaCount()).toBe(before)
  })

  it('copyTextSync 失败返回 false 且不残留临时 textarea', () => {
    stubExecCommand(false)
    const before = textareaCount()

    expect(copyTextSync('p')).toBe(false)
    expect(textareaCount()).toBe(before)
  })

  it('execCommand 抛异常时按失败处理(不乱抛、不残留节点)', () => {
    Object.defineProperty(document, 'execCommand', {
      value: () => {
        throw new Error('execCommand is not a function')
      },
      configurable: true,
      writable: true,
    })
    const before = textareaCount()

    expect(copyTextSync('p')).toBe(false)
    expect(textareaCount()).toBe(before)
  })
})