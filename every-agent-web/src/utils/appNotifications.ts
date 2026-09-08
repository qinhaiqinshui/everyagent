import type { AppNotification } from '@/types'
import { antdNotify, getAntdApp } from './appAntdBridge'
import { createSnowflakeId } from './snowflakeId'

/**
 * 应用级通知外壳。
 *
 * 自 v2 起内部统一改走 antd notification(经 utils/appAntdBridge 桥接);
 * 对外 API(notifyApp / removeAppNotification)保持不变,调用方无需感知。
 * 通知以 notification.id 作为 antd notification 的 key,便于更新/销毁。
 */
export function notifyApp(
  input: Omit<AppNotification, 'id' | 'createdAt'> & Partial<Pick<AppNotification, 'id' | 'createdAt'>>,
): string {
  const notification: AppNotification = {
    id: input.id ?? createSnowflakeId('notification'),
    createdAt: input.createdAt ?? Date.now(),
    title: input.title,
    message: input.message,
    tone: input.tone ?? 'info',
    onClick: input.onClick,
  }
  antdNotify({
    key: notification.id,
    message: notification.title,
    description: notification.message,
    tone: notification.tone,
    // 与原 NotificationHost 的 AUTO_CLOSE_MS=8000 对齐(antd 默认 4.5s)。
    duration: 8,
    onClick: () => {
      // 与原 NotificationHost 一致:点击卡片触发 onClick 并自动关闭。
      notification.onClick?.()
      getAntdApp()?.notification.destroy(notification.id)
    },
  })
  return notification.id
}

export function removeAppNotification(notificationId: string): void {
  // antd notification 以 id 为 key;直接经桥接实例销毁该 key。
  getAntdApp()?.notification.destroy(notificationId)
}
