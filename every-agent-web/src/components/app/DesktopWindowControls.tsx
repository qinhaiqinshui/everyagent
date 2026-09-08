/**
 * 桌面版(无边框窗口)标题栏右侧的窗口控制按钮:最小化 / 最大化·还原 / 关闭。
 * 仅在 Electron preload 注入 windowControl 时渲染;浏览器环境返回 null。
 * 按钮属于 no-drag 区域,避免被标题栏拖拽区拦截点击。
 */
import React from 'react'
import { isDesktopWindowControlAvailable } from '@/platform/desktopBootstrap'

export default function DesktopWindowControls() {
  const available = typeof window !== 'undefined' && isDesktopWindowControlAvailable()
  const [maximized, setMaximized] = React.useState(false)

  React.useEffect(() => {
    if (!available) return
    const control = window.everyAgentDesktop!.windowControl!
    let disposed = false
    void control.isMaximized().then((value) => {
      if (!disposed) setMaximized(value)
    })
    control.onMaximizedChanged((value) => {
      if (!disposed) setMaximized(value)
    })
    return () => {
      disposed = true
    }
  }, [available])

  if (!available) return null
  const control = window.everyAgentDesktop!.windowControl!

  return (
    <div className="desktop-window-controls titlebar-no-drag" role="group" aria-label="窗口控制">
      <button
        type="button"
        className="dw-control"
        title="最小化"
        aria-label="最小化"
        onClick={() => void control.minimize()}
      >
        <MinimizeGlyph />
      </button>
      <button
        type="button"
        className="dw-control"
        title={maximized ? '还原' : '最大化'}
        aria-label={maximized ? '还原' : '最大化'}
        onClick={() => void control.toggleMaximize()}
      >
        {maximized ? <RestoreGlyph /> : <MaximizeGlyph />}
      </button>
      <button
        type="button"
        className="dw-control dw-control--close"
        title="关闭"
        aria-label="关闭"
        onClick={() => void control.close()}
      >
        <CloseGlyph />
      </button>
    </div>
  )
}

/** 最小化:底部横线。 */
function MinimizeGlyph() {
  return (
    <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true" focusable="false">
      <path d="M0 5h10v1H0z" fill="currentColor" />
    </svg>
  )
}

/** 最大化:空心方框。 */
function MaximizeGlyph() {
  return (
    <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true" focusable="false">
      <path d="M0 0v10h10V0H0zm1 1h8v8H1V1z" fill="currentColor" />
    </svg>
  )
}

/** 还原:前后两个错位方框。 */
function RestoreGlyph() {
  return (
    <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true" focusable="false">
      <path d="M2 0v2H0v8h8V8h2V0H2zm5 3H3v6H1V3h6v6zm2 2H4v4h5V5z" fill="currentColor" />
    </svg>
  )
}

/** 关闭:×。 */
function CloseGlyph() {
  return (
    <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true" focusable="false">
      <path
        d="M1.207 0L5 3.793 8.793 0 10 1.207 6.207 5 10 8.793 8.793 10 5 6.207 1.207 10 0 8.793 3.793 5 0 1.207z"
        fill="currentColor"
      />
    </svg>
  )
}
