/**
 * 浏览器系统通知适配器(Web Notification API)。
 *
 * 复用 utils/browserNotifications.ts 的既有实现,仅将其套成 NotificationAdapter 接口,
 * 供浏览器宿主经 setNotificationAdapter 注入。
 */
import {
  getBrowserNotificationCapability,
  getBrowserNotificationPermission,
  requestBrowserNotificationPermission,
  showBrowserNotification,
} from '@/utils/browserNotifications'
import type {
  NotificationAdapter,
  NotificationPayload,
  NotificationPermissionState,
} from './types'

export function createBrowserNotificationAdapter(): NotificationAdapter {
  return {
    source: 'browser',
    isSupported: () => getBrowserNotificationCapability() === 'supported',
    getPermission: () => getBrowserNotificationPermission() as NotificationPermissionState,
    requestPermission: async () =>
      requestBrowserNotificationPermission() as Promise<NotificationPermissionState>,
    notify: (payload: NotificationPayload) =>
      showBrowserNotification(payload.title, {
        body: payload.body,
        tag: payload.tag,
        onClick: payload.onClick,
      }),
  }
}
