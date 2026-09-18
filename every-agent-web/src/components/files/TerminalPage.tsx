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
import { Dropdown } from 'antd'
import type { MenuProps } from 'antd'
import { channels } from '@every-agent/client'
import { hubSession } from '@/hub/session'
import { loadThemeMode } from '@/settings/localSettings'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import { terminalGateway } from '@/platform/fs/terminalGateway'
import { randomUUID } from '@/utils/uuid'
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
  if (mode === 'dark') {
    return {
      background: '#0b0d12',
      foreground: '#eef1f5',
      cursor: '#eef1f5',
      selectionBackground: 'rgba(97, 165, 255, 0.3)',
      selectionForeground: '#ffffff',
      // ANSI 16 色:与全局深色调色板对齐
      black: '#0b0d12',
      red: '#ff6b6b',
      green: '#41d19c',
      yellow: '#f7bd54',
      blue: '#61a5ff',
      magenta: '#b59cff',
      cyan: '#5fcfff',
      white: '#eef1f5',
      brightBlack: '#6f7a8b',
      brightRed: '#ff8e8e',
      brightGreen: '#5fe5b0',
      brightYellow: '#ffd17a',
      brightBlue: '#82baff',
      brightMagenta: '#c9b3ff',
      brightCyan: '#82dfff',
      brightWhite: '#ffffff',
    }
  }
  return {
    background: '#ffffff',
    foreground: '#1a1a1a',
    cursor: '#333333',
    selectionBackground: 'rgba(47, 111, 237, 0.25)',
    selectionForeground: '#000000',
    // ANSI 16 色:浅色背景下用饱和度较高的深色,确保可读
    black: '#000000',
    red: '#d92d42',
    green: '#058761',
    yellow: '#b97912',
    blue: '#2f6fed',
    magenta: '#7857d8',
    cyan: '#0087a7',
    white: '#1a1a1a',
    brightBlack: '#666666',
    brightRed: '#e5484d',
    brightGreen: '#0a9f70',
    brightYellow: '#d4900f',
    brightBlue: '#3b7af0',
    brightMagenta: '#8567e0',
    brightCyan: '#0a9bc0',
    brightWhite: '#000000',
  }
}

export default function TerminalPage({ tab }: TerminalPageProps) {
  const containerRef = React.useRef<HTMLDivElement>(null)
  const termRef = React.useRef<Terminal | null>(null)
  const [errorText, setErrorText] = React.useState<string | null>(null)
  const [themeMode, setThemeMode] = React.useState<ThemeMode>(loadThemeMode)
  const [menuOpen, setMenuOpen] = React.useState(false)

  // ── 终端右键菜单(仿 VSCode:选中文字→复制,无选中→粘贴,子菜单含全选/清屏) ──

  const getSelectedText = React.useCallback((): string => {
    const term = termRef.current
    if (!term) return ''
    const selection = term.getSelection()
    return selection || ''
  }, [])

  const copySelection = React.useCallback(async () => {
    const text = getSelectedText()
    if (text) {
      try { await navigator.clipboard.writeText(text) } catch { /* ignore */ }
    }
  }, [getSelectedText])

  const pasteFromClipboard = React.useCallback(async () => {
    const term = termRef.current
    if (!term) return
    try {
      const text = await navigator.clipboard.readText()
      if (text) {
        term.paste(text)
      }
    } catch { /* ignore */ }
  }, [])

  const selectAll = React.useCallback(() => {
    const term = termRef.current
    if (!term) return
    term.selectAll()
  }, [])

  const clearTerminal = React.useCallback(() => {
    const term = termRef.current
    if (!term) return
    term.clear()
  }, [])

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

    const client = hubSession.workerClient(tab.workerId)
    if (!client) {
      setErrorText('worker 未连接,无法打开终端(请检查 worker 在线状态与 apiKey 配置)')
      return
    }

    const termId = randomUUID()
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

    // 右键菜单(仿 VSCode):有选中文字→复制,无选中→粘贴;菜单含全选/清屏。
    // 不在 attachCustomKeyEventHandler 中拦截 contextmenu——xterm 收到 contextmenu
    // 会立即清除选区,导致 handleContextMenu 取不到选中文本。改为由外层 div 的
    // onContextMenu 事件统一处理(preventDefault 阻止浏览器默认菜单即可)。

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

  // 右键菜单项(仿 VSCode)
  const contextMenuItems: MenuProps['items'] = [
    {
      key: 'copy',
      label: '复制',
      disabled: !getSelectedText(),
      onClick: () => void copySelection(),
    },
    {
      key: 'paste',
      label: '粘贴',
      onClick: () => void pasteFromClipboard(),
    },
    { type: 'divider' },
    {
      key: 'select-all',
      label: '全选',
      onClick: () => selectAll(),
    },
    {
      key: 'clear',
      label: '清屏',
      onClick: () => clearTerminal(),
    },
  ]

  // 右键:有选中文字→直接复制(仿 VSCode,不清除选区让用户可见);
  // 无选中→显示菜单(粘贴/全选/清屏)
  const handleContextMenu = (e: React.MouseEvent) => {
    e.preventDefault()
    const hasSelection = !!getSelectedText()
    if (hasSelection) {
      // 有选中:复制(不清除选区,仿 VSCode 行为)
      void copySelection()
    } else {
      // 无选中:显示菜单
      setMenuOpen(true)
    }
  }

  // 点击左键时关闭菜单
  const handlePointerDown = () => {
    if (menuOpen) setMenuOpen(false)
  }

  return (
    <div
      style={{
        height: '100%',
        display: 'flex',
        flexDirection: 'column',
        background: 'var(--bg-primary)',
        minHeight: 0,
        overflow: 'hidden',
      }}
    >
      <Dropdown
        menu={{ items: contextMenuItems }}
        trigger={['contextMenu']}
        open={menuOpen}
        onOpenChange={setMenuOpen}
      >
        <div
          ref={containerRef}
          style={{ flex: 1, minHeight: 0 }}
          onContextMenu={handleContextMenu}
          onPointerDown={handlePointerDown}
        />
      </Dropdown>
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
