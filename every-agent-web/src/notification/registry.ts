/**
 * 抽象系统通知层:适配器注册表。
 *
 * 宿主环境在应用启动早期注入一个 NotificationAdapter(浏览器宿主注入 browserAdapter,
 * 桌面宿主注入 desktopAdapter 覆盖)。未注入时统一按「不支持 / 未授权」处理。
 */
import type { NotificationAdapter } from './types'

let currentAdapter: NotificationAdapter | null = null

/** 注入系统通知适配器(传入 null 表示卸载)。 */
export function setNotificationAdapter(adapter: NotificationAdapter | null): void {
  currentAdapter = adapter
}

/** 获取当前系统通知适配器;未注入返回 null。 */
export function getNotificationAdapter(): NotificationAdapter | null {
  return currentAdapter
}
