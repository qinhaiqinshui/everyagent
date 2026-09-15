/**
 * 真实终端页组件:用 xterm.js 渲染,经 hub 管道对 worker 的 term.* RPC 打开 PTY,
 * 订阅 u.<K>.term.<termId>.stream 频道接收实时输出(term.output / term.exited)。
 *
 * 生命周期:
 * - mount:先 sub 频道(避免丢首帧)→ term.open → onData→term.input → ResizeObserver→resize。
 * - unmount:close → unsub → 取消 onFrame → dispose。
 */
import '@xterm/xterm/css/xterm.css'

import React from 'react'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import { channels } from '@every-agent/client'
import { hubSession } from '@/hub/session'
import { loadThemeMode } from '@/settings/localSettings'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import { terminalGateway } from '@/platform/fs/terminalGateway'
import type { ITheme } from '@xterm/xterm'
import type { ThemeMode, WorkspaceTerminalTab } from '@/types'

export interface TerminalPageProps {
  tab: WorkspaceTerminalTab
  onClose: () => void
}

// ── base64 工具(UTF-8 安全) ──

function base64ToBytes(base64: string): Uint8Array {
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i)
  }
  return bytes
}

function strToBase64(text: string): string {
  const bytes = new TextEncoder().encode(text)
  let binary = ''
  const CHUNK = 0x8000
  for (let i = 0; i < bytes.length; i += CHUNK) {
    binary += String.fromCharCode(...bytes.subarray(i, i + CHUNK))
  }
  return btoa(binary)
}

function themeFor(mode: ThemeMode): ITheme {
  return mode === 'dark'
    ? { background: '#1e1e1e', foreground: '#cccccc', cursor: '#cccccc' }
    : { background: '#ffffff', foreground: '#333333', cursor: '#333333' }
}

export default function TerminalPage({ tab }: TerminalPageProps) {
  const containerRef = React.useRef<HTMLDivElement>(null)
  const termRef = React.useRef<Terminal | null>(null)
  const [errorText, setErrorText] = React.useState<string | null>(null)
  const [themeMode, setThemeMode] = React.useState<ThemeMode>(loadThemeMode)

  // 订阅主题变化(明暗模式切换时同步 xterm 主题)。
  React.useEffect(() => {
    const unsubscribe = domainEventBus.subscribe(
      DOMAIN_EVENTS.SETTINGS_THEME_PATCHED,
      ({ themeMode: next }: { themeMode: ThemeMode }) => setThemeMode(next),
    )
    return unsubscribe
  }, [])

  // 主题切换:实时更新 xterm 主题对象(须用新对象,见 xterm options 文档)。
  React.useEffect(() => {
    const term = termRef.current
    if (term) term.options.theme = themeFor(themeMode)
  }, [themeMode])

  // 终端生命周期(挂载/卸载),依赖 tab 核心字段。
  React.useEffect(() => {
    const container = containerRef.current
    if (!container) return

    const client = hubSession.clientFor(tab.workerId)
    if (!client) {
      setErrorText('worker 未连接,无法打开终端(请检查 worker 在线状态与 apiKey 配置)')
      return
    }

    const termId = crypto.randomUUID()
    const channel = channels.termStream(client.k, termId)

    const term = new Terminal({
      cols: 80,
      rows: 24,
      cursorBlink: true,
      fontFamily: "'Cascadia Code', 'Fira Code', 'Consolas', 'Courier New', monospace",
      fontSize: 13,
      letterSpacing: 0,
      theme: themeFor(loadThemeMode()),
    })
    termRef.current = term
    const fitAddon = new FitAddon()
    term.loadAddon(fitAddon)
    term.open(container)
    // 先同步 fit 得到容器实际尺寸,再用实际 cols/rows 打开 PTY;
    // 否则 worker 用 80×24 输出但 xterm 已被 fit 改成实际尺寸,行宽不匹配
    // 会导致光标与输入内容错位(内容在光标上方好多行)。
    try {
      fitAddon.fit()
    } catch {
      // 忽略:容器尺寸为 0 时 fit 抛异常,用默认 80×24
    }

    // 先 sub 频道再 open,避免 worker 在 term.open 后立即推送的首帧丢失。
    client.sub(channel)

    // 页面刷新/关闭时 best-effort 发送 term.close(WS 拆除前可能来不及,
    // worker 侧有 subscriber.leave + onHubDisconnected 三层兜底回收,见 TerminalService)。
    const handleCloseOnUnload = () => {
      void terminalGateway.close(tab.workspaceRoot, termId)
    }
    window.addEventListener('beforeunload', handleCloseOnUnload)
    window.addEventListener('pagehide', handleCloseOnUnload)

    let exited = false
    const offFrame = hubSession.onFrame((frame) => {
      if (frame.channel !== channel) return
      if (frame.event === 'term.output') {
        const data = (frame.payload as { data?: string } | null)?.data
        if (data) term.write(base64ToBytes(data))
      } else if (frame.event === 'term.exited') {
        if (!exited) {
          exited = true
          term.write('\r\n\x1b[33m[终端已结束]\x1b[0m\r\n')
        }
      }
    })

    // 用户输入 → term.input(base64)
    const dataDisposable = term.onData((data) => {
      terminalGateway.input(tab.workspaceRoot, termId, strToBase64(data)).catch(() => {})
    })

    // 容器尺寸变化 → fit → resize(节流,避免拖拽窗口时 RPC 风暴)。
    // 仅在 PTY 打开成功后注册,避免 open 尚未完成时 resize RPC 报"会话不存在"
    // 且此时 fit 改变 xterm 尺寸会导致与 worker PTY 行宽不一致。
    let resizeTimer: ReturnType<typeof setTimeout> | null = null
    let resizeObserver: ResizeObserver | null = null
    const startResizeObserver = () => {
      resizeObserver = new ResizeObserver(() => {
        if (resizeTimer) clearTimeout(resizeTimer)
        resizeTimer = setTimeout(() => {
          try {
            fitAddon.fit()
            void terminalGateway.resize(tab.workspaceRoot, termId, term.cols, term.rows).catch(() => {})
          } catch {
            // 忽略:容器尺寸为 0 时 fit 抛异常
          }
        }, 100)
      })
      resizeObserver.observe(container)
    }

    // 打开 PTY(用 fit 后的实际尺寸,避免行宽不匹配);
    // open 成功后注册 ResizeObserver,避免竞态。
    void terminalGateway
      .open(tab.workspaceRoot, termId, tab.path, term.cols, term.rows)
      .then(() => {
        startResizeObserver()
      })
      .catch((err) => {
        const msg = err instanceof Error ? err.message : String(err)
        term.write(`\r\n\x1b[31m[终端打开失败: ${msg}]\x1b[0m\r\n`)
        setErrorText(`终端打开失败: ${msg}`)
      })

    return () => {
      if (resizeTimer) clearTimeout(resizeTimer)
      resizeObserver?.disconnect()
      dataDisposable.dispose()
      offFrame()
      window.removeEventListener('beforeunload', handleCloseOnUnload)
      window.removeEventListener('pagehide', handleCloseOnUnload)
      client.unsub(channel)
      void terminalGateway.close(tab.workspaceRoot, termId)
      term.dispose()
      termRef.current = null
    }
  }, [tab.workspaceRoot, tab.path, tab.workerId])

  return (
    <div
      style={{
        height: '100%',
        display: 'flex',
        flexDirection: 'column',
        background: '#1e1e1e',
        minHeight: 0,
        overflow: 'hidden',
      }}
    >
      <div ref={containerRef} style={{ flex: 1, minHeight: 0, padding: '4px 8px' }} />
      {errorText && (
        <div
          style={{
            padding: '4px 8px',
            color: '#ff6b6b',
            fontSize: 12,
            fontFamily: "'Cascadia Code', 'Fira Code', Consolas, monospace",
          }}
        >
          {errorText}
        </div>
      )}
    </div>
  )
}
