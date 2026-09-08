/**
 * 抽象系统通知层:统一门面。
 *
 * 业务组件(BrowserNotificationHost / BrowserNotificationGuide / SettingsPanel)
 * 一律经此门面访问通知能力,不感知具体宿主实现;宿主实现经 registry 注入。
 */
import { getNotificationAdapter } from './registry'
import type {
  NotificationAdapter,
  NotificationPayload,
  NotificationPermissionState,
} from './types'

export type {
  NotificationAdapter,
  NotificationPayload,
  NotificationPermissionState,
} from './types'
export { getNotificationAdapter, setNotificationAdapter } from './registry'

/** 当前环境是否支持系统通知。 */
export function isSystemNotificationSupported(): boolean {
  return getNotificationAdapter()?.isSupported() ?? false
}

/** 当前系统通知权限状态;无适配器时返回 'unsupported'。 */
export function getSystemNotificationPermission(): NotificationPermissionState {
  return getNotificationAdapter()?.getPermission() ?? 'unsupported'
}

/** 请求系统通知权限(桌面恒 granted,浏览器需用户手势)。 */
export async function requestSystemNotificationPermission(): Promise<NotificationPermissionState> {
  const adapter = getNotificationAdapter()
  if (!adapter) return 'unsupported'
  if (adapter.requestPermission) return adapter.requestPermission()
  return adapter.getPermission()
}

/** 发送系统通知;无适配器 / 未授权 / 不支持时返回 false。 */
export function showSystemNotification(payload: NotificationPayload): boolean {
  const adapter = getNotificationAdapter()
  if (!adapter || !adapter.isSupported()) return false
  if (adapter.getPermission() !== 'granted') return false
  try {
    return adapter.notify(payload)
  } catch (error) {
    console.warn('[notification] 通知发送失败:', error)
    return false
  }
}
