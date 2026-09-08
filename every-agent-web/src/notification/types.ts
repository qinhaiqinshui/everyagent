/**
 * 抽象系统通知层:接口类型定义(平台无关,web 模块不引入 electron)。
 *
 * 宿主环境(browser / electron)各自注入一个 NotificationAdapter 实现:
 * - browser:Web Notification API(见 browserAdapter.ts);
 * - desktop:经 window.everyAgentDesktop 桥接到 Electron 主进程原生通知(见 desktopAdapter.ts)。
 */

/** 通知权限状态;不支持系统通知的环境返回 'unsupported'。 */
export type NotificationPermissionState = 'granted' | 'denied' | 'default' | 'unsupported'

/** 一次系统通知的载荷。 */
export interface NotificationPayload {
  /** 通知标题。 */
  title: string
  /** 通知正文。 */
  body?: string
  /** 同一 tag 的新通知替换旧通知(避免同一任务/交互连续提醒堆积)。 */
  tag?: string
  /** 点击系统通知后的回调(桌面实现由主进程回传 tag 触发)。 */
  onClick?: () => void
}

/** 系统通知适配器接口。 */
export interface NotificationAdapter {
  /** 适配器来源:浏览器 Web Notification / 桌面 Electron 原生通知。 */
  source: 'browser' | 'desktop'
  /** 当前环境是否支持系统通知。 */
  isSupported(): boolean
  /** 当前通知权限状态;不支持时返回 'unsupported'。 */
  getPermission(): NotificationPermissionState
  /** 请求通知权限(浏览器需用户手势;桌面恒 granted)。 */
  requestPermission?(): Promise<NotificationPermissionState>
  /** 发送系统通知,返回是否已投递(桌面实现:窗口在前台时由主进程丢弃,返回 false)。 */
  notify(payload: NotificationPayload): boolean
}
