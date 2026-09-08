/**
 * 主进程系统通知:接收渲染进程 desktop:notify IPC,用 Electron 原生 Notification 通知。
 *
 * 规则:
 * - 仅当桌面窗口不在前台(未聚焦/最小化)时通知;聚焦时应用内 UI 已可见,直接丢弃。
 *   (web 端 BrowserNotificationHost 已有 document.hasFocus() 判断,这里是第二道兜底。)
 * - 同 tag 短时间去重,避免同一任务/交互连续提醒堆积。
 * - 通知被点击:聚焦窗口,并按 tag 回传 desktop:notify-click,前端据此打开对应任务/交互页。
 */
import { BrowserWindow, Notification, ipcMain } from 'electron'

/** 渲染进程经 preload 传来的通知载荷(IPC 边界,纯 JSON 可序列化)。 */
export interface NotifyIpcPayload {
  title?: string
  body?: string
  tag?: string
}

const DEDUP_MS = 2000
const recentByTag = new Map<string, number>()

export function registerNotifyIpc(): void {
  ipcMain.on('desktop:notify', (event, payload: NotifyIpcPayload) => {
    try {
      if (!Notification.isSupported()) return
      const win = BrowserWindow.fromWebContents(event.sender)
      // 前台不通知:桌面程序可见时应用内 UI 已能提醒,避免打扰。
      if (win && !win.isDestroyed() && win.isFocused() && !win.isMinimized()) return

      const title = typeof payload?.title === 'string' && payload.title.trim()
        ? payload.title.trim()
        : 'Every Agent'
      const body = typeof payload?.body === 'string' && payload.body.trim()
        ? payload.body
        : undefined
      const tag = typeof payload?.tag === 'string' && payload.tag.trim()
        ? payload.tag.trim()
        : undefined

      if (tag) {
        const now = Date.now()
        const last = recentByTag.get(tag)
        if (last !== undefined && now - last < DEDUP_MS) return
        recentByTag.set(tag, now)
      }

      const notification = new Notification({
        title,
        body,
        silent: false,
      })
      notification.on('click', () => {
        if (win && !win.isDestroyed()) {
          if (win.isMinimized()) win.restore()
          win.show()
          win.focus()
        }
        if (tag && win && !win.isDestroyed()) {
          try {
            win.webContents.send('desktop:notify-click', tag)
          } catch {
            /* 渲染进程尚未就绪时忽略 */
          }
        }
      })
      notification.show()
    } catch (error) {
      console.error('[desktop] 系统通知发送失败:', error)
    }
  })
}