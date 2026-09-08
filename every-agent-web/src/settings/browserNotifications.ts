/**
 * 浏览器系统通知的本地偏好设置(localStorage)。
 * 与主题一致,纯前端展示偏好,不落 worker。
 *
 * 开关默认开启:未授权时不生效,授权后立即生效。
 */
const BROWSER_NOTIFICATIONS_ENABLED_KEY = 'ea.web.browserNotifications.enabled'

/** 系统通知开关是否开启(默认开启)。 */
export function loadBrowserNotificationsEnabled(): boolean {
  return localStorage.getItem(BROWSER_NOTIFICATIONS_ENABLED_KEY) !== '0'
}

export function saveBrowserNotificationsEnabled(enabled: boolean): void {
  localStorage.setItem(BROWSER_NOTIFICATIONS_ENABLED_KEY, enabled ? '1' : '0')
}