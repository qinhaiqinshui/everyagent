/**
 * 浏览器系统通知(Web Notifications API)桥接。
 *
 * 与应用内 antd notification(utils/appNotifications.ts)不同,这里调用的是
 * 操作系统级通知(经浏览器 Notification API),用于页面不在前台时的提醒。
 *
 * 触发策略由 BrowserNotificationHost 负责:仅当页面未聚焦(document.hasFocus()
 * === false)、开关开启(settings/browserNotifications)且用户已授权时,
 * 经 showBrowserNotification 发送系统通知。
 */
export type BrowserNotificationCapability = 'supported' | 'unsupported'
export type BrowserNotificationPermissionState = NotificationPermission | 'unsupported'

/** 浏览器是否支持系统通知(需安全上下文:https 或 localhost)。 */
export function getBrowserNotificationCapability(): BrowserNotificationCapability {
  return typeof window !== 'undefined' && 'Notification' in window ? 'supported' : 'unsupported'
}

/** 当前通知权限状态;不支持时返回 'unsupported'。 */
export function getBrowserNotificationPermission(): BrowserNotificationPermissionState {
  if (getBrowserNotificationCapability() === 'unsupported') return 'unsupported'
  return Notification.permission
}

/** 是否已获浏览器通知授权。 */
export function isBrowserNotificationGranted(): boolean {
  return getBrowserNotificationPermission() === 'granted'
}

/**
 * 请求浏览器通知权限。需由用户手势直接触发(点击按钮/引导卡片),
 * 否则浏览器可能静默拒绝。返回请求后的权限状态。
 */
export async function requestBrowserNotificationPermission(): Promise<BrowserNotificationPermissionState> {
  if (getBrowserNotificationCapability() === 'unsupported') return 'unsupported'
  if (Notification.permission === 'granted' || Notification.permission === 'denied') {
    return Notification.permission
  }
  try {
    return await Notification.requestPermission()
  } catch (error) {
    console.warn('[browserNotifications] 请求权限失败:', error)
    return Notification.permission
  }
}

export interface BrowserNotificationOptions {
  /** 通知正文。 */
  body?: string
  /** 系统通知图标(默认取站点 favicon /icon.svg)。 */
  icon?: string
  /** 同一 tag 的新通知会替换旧通知(避免同一任务/交互连续提醒堆积)。 */
  tag?: string
  /** 点击系统通知的回调(默认聚焦窗口)。 */
  onClick?: () => void
  /** 自动关闭延迟(ms);传 0 表示不自动关闭。默认 10s。 */
  autoCloseMs?: number
}

/**
 * 发送浏览器系统通知。
 * 未授权 / 不支持时静默跳过并返回 false;已发送返回 true。
 */
export function showBrowserNotification(title: string, options?: BrowserNotificationOptions): boolean {
  if (!isBrowserNotificationGranted()) return false
  try {
    const notification = new Notification(title, {
      body: options?.body,
      icon: options?.icon ?? '/icon.svg',
      tag: options?.tag,
    })
    notification.onclick = () => {
      notification.close()
      window.focus()
      options?.onClick?.()
    }
    if (options?.autoCloseMs !== 0) {
      window.setTimeout(() => notification.close(), options?.autoCloseMs ?? 10_000)
    }
    return true
  } catch (error) {
    console.warn('[browserNotifications] 通知发送失败:', error)
    return false
  }
}