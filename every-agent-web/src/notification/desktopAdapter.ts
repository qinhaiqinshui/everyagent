/**
 * 桌面系统通知适配器:把 window.everyAgentDesktop 桥(preload 暴露)套成 NotificationAdapter。
 *
 * 本文件不 import electron,也不 import desktopBootstrap(避免循环依赖),只依赖
 * desktopBootstrap.ts 里对 Window 接口做的 global augmentation(声明 notify / onNotifyClick)。
 * Electron 原生通知的实现与前台判断在 desktop 模块主进程,这里只是薄桥接。
 */
import type {
  NotificationAdapter,
  NotificationPayload,
  NotificationPermissionState,
} from './types'

/** tag → 点击回调。主进程通知被点击后按 tag 回传,这里查找并执行对应回调。 */
const clickHandlers = new Map<string, () => void>()

let clickListenerInstalled = false

function ensureClickListenerInstalled(): void {
  if (clickListenerInstalled) return
  clickListenerInstalled = true
  const api = window.everyAgentDesktop
  if (!api || typeof api.onNotifyClick !== 'function') return
  try {
    api.onNotifyClick((tag: string) => {
      const handler = clickHandlers.get(tag)
      clickHandlers.delete(tag)
      if (handler) handler()
    })
  } catch (error) {
    console.warn('[desktopNotifications] 订阅通知点击事件失败:', error)
  }
}

function isDesktopNotifyAvailable(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.everyAgentDesktop?.notify === 'function'
  )
}

export function createDesktopNotificationAdapter(): NotificationAdapter {
  ensureClickListenerInstalled()
  return {
    source: 'desktop',
    isSupported: () => isDesktopNotifyAvailable(),
    getPermission: (): NotificationPermissionState => (isDesktopNotifyAvailable() ? 'granted' : 'unsupported'),
    requestPermission: async (): Promise<NotificationPermissionState> =>
      (isDesktopNotifyAvailable() ? 'granted' : 'unsupported'),
    notify: (payload: NotificationPayload): boolean => {
      if (!isDesktopNotifyAvailable()) return false
      const api = window.everyAgentDesktop
      if (!api || typeof api.notify !== 'function') return false
      if (payload.onClick && payload.tag) {
        clickHandlers.set(payload.tag, payload.onClick)
      }
      try {
        api.notify({
          title: payload.title,
          body: payload.body,
          tag: payload.tag,
        })
        return true
      } catch (error) {
        console.warn('[desktopNotifications] 通知发送失败:', error)
        return false
      }
    },
  }
}
