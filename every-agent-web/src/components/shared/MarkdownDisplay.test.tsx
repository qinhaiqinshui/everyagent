// @vitest-environment jsdom
/**
 * MarkdownDisplay 链接导航交互测试(需 DOM 事件):
 * - 提供 workspaceRoot 时,消息内相对路径链接(含中文 URI 编码,复现 AI 章节汇报
 *   场景)点击经网关存在性校验后打开应用内文件标签页(openGlobalFileTab),
 *   浏览器默认新开标签页被拦截(cancelable click 被取消);
 * - 外部链接(http)仍走统一外链入口(openExternalLink),不打开文件标签页;
 * - 未提供 workspaceRoot 时降级:toast 报「缺少工作区上下文」,不打开文件标签页。
 */
import { describe, it, expect, vi, beforeAll, afterEach } from 'vitest'
import React from 'react'
import { createRoot, type Root } from 'react-dom/client'

const openGlobalFileTab = vi.fn()
const showToast = vi.fn()
const statRaw = vi.fn()
const openExternalLinkMock = vi.fn()

vi.mock('@/components/app/WorkspaceShellContext', () => ({
  useWorkspaceShellOptional: () => ({
    openGlobalFileTab: (...args: unknown[]) => openGlobalFileTab(...args),
  }),
}))
vi.mock('@/components/app/AppUiContext', () => ({
  useAppUiOptional: () => ({
    showToast: (...args: unknown[]) => showToast(...args),
  }),
}))
vi.mock('@/platform/fs/workspaceGateway', () => ({
  workspaceGateway: {
    statRaw: (...args: unknown[]) => statRaw(...args),
  },
}))
// isExternalLinkUrl 保留真实判定(协议白名单是被测分发逻辑的一部分),仅替换打开动作。
vi.mock('@/platform/externalLink', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/platform/externalLink')>()
  return {
    ...actual,
    openExternalLink: (...args: string[]) => openExternalLinkMock(...args),
  }
})

import MarkdownDisplay from './MarkdownDisplay'

const WS = 'C:/ws/novel-root'

beforeAll(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true
})

const roots: { container: HTMLElement; root: Root }[] = []

afterEach(async () => {
  while (roots.length) {
    const { container, root } = roots.pop()!
    await React.act(async () => {
      root.unmount()
    })
    container.remove()
  }
  openGlobalFileTab.mockClear()
  showToast.mockClear()
  statRaw.mockClear()
  openExternalLinkMock.mockClear()
})

async function renderMessage(markdown: string, workspaceRoot?: string): Promise<HTMLElement> {
  const container = document.createElement('div')
  document.body.appendChild(container)
  const root = createRoot(container)
  roots.push({ container, root })
  await React.act(async () => {
    root.render(<MarkdownDisplay content={markdown} workspaceRoot={workspaceRoot} />)
  })
  return container
}

/** 派发无修饰键左键单击;返回值 = 事件是否未被取消(默认行为是否放行)。 */
function plainClick(el: Element): boolean {
  return el.dispatchEvent(new window.MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }))
}

function linkOf(container: HTMLElement): HTMLAnchorElement {
  const a = container.querySelector('a')
  expect(a, '消息内应渲染链接').toBeTruthy()
  return a!
}

describe('MarkdownDisplay 链接导航(消息内相对路径 → 文件标签页)', () => {
  it('相对路径链接(中文编码)点击:校验后打开文件标签页,默认导航被拦截', async () => {
    statRaw.mockResolvedValue({ isDirectory: false })
    const container = await renderMessage(
      '- 正文：[第006章-首功落名.md](novels/穿越者猎人/正文/第006章-首功落名.md)',
      WS,
    )
    const a = linkOf(container)
    expect(a.getAttribute('href')).toContain('%E7%A9%BF') // CommonMark 规范化:中文被编码
    let notCancelled = true
    await React.act(async () => {
      notCancelled = plainClick(a)
    })
    await vi.waitFor(() => expect(openGlobalFileTab).toHaveBeenCalledTimes(1))
    // 网关探测用解码后的工作区相对路径;打开用带前导 / 的业务绝对路径。
    expect(statRaw).toHaveBeenCalledWith(WS, 'novels/穿越者猎人/正文/第006章-首功落名.md')
    expect(openGlobalFileTab).toHaveBeenCalledWith({
      workspaceRoot: WS,
      filePath: '/novels/穿越者猎人/正文/第006章-首功落名.md',
    })
    // 返回 false = preventDefault 生效,浏览器不再把工作区路径当 URL 新开标签页。
    expect(notCancelled).toBe(false)
    expect(openExternalLinkMock).not.toHaveBeenCalled()
  })

  it('外部链接(http)点击:走统一外链入口,不打开文件标签页', async () => {
    const container = await renderMessage('[文档](https://example.com/guide.md)', WS)
    let notCancelled = true
    await React.act(async () => {
      notCancelled = plainClick(linkOf(container))
    })
    expect(openExternalLinkMock).toHaveBeenCalledWith('https://example.com/guide.md')
    expect(openGlobalFileTab).not.toHaveBeenCalled()
    expect(notCancelled).toBe(false)
  })

  it('缺省 workspaceRoot:toast 报缺工作区上下文,不打开文件标签页', async () => {
    const container = await renderMessage('[第006章](novels/穿越者猎人/正文/第006章-首功落名.md)')
    await React.act(async () => {
      plainClick(linkOf(container))
    })
    await vi.waitFor(() => expect(showToast).toHaveBeenCalledTimes(1))
    expect(showToast.mock.calls[0][0]).toContain('缺少工作区上下文')
    expect(openGlobalFileTab).not.toHaveBeenCalled()
  })
})
