/**
 * antd App.useApp() 实例桥接。
 *
 * 现状:项目里所有「操作结果通知」都走自研外壳(showToast / notifyApp +
 * ToastHost / NotificationHost)。为统一到 antd,外壳内部需要拿到
 * antd `App.useApp()` 的 message / notification / modal 实例;
 * 但这些外壳会被「非 React 组件上下文」调用(如 hub/askStore 等模块),
 * 不能直接使用 useApp() hook。本模块提供一个在 <AntApp> 内部注册的
 * holder,让任意模块都能取到当前 antd App 实例。
 */
import { App, message, notification, Modal, type NotificationArgsProps } from 'antd'
import type { ModalFuncProps } from 'antd'

type AntdAppInstance = ReturnType<typeof App.useApp>

let appInstance: AntdAppInstance | null = null

export function registerAntdApp(instance: AntdAppInstance): void {
  appInstance = instance
}

/** 取当前 antd App 实例;未注册时降级返回 null(供调用方自行兜底)。 */
export function getAntdApp(): AntdAppInstance | null {
  return appInstance
}

/**
 * 带类型的轻提示(message),外壳统一入口。
 * 优先用 <AntApp> 上下文实例(带主题/上下文);未注册时降级静态 message。
 */
export function antdMessage(
  type: 'success' | 'error' | 'info' | 'warning',
  content: string,
): void {
  if (appInstance) {
    appInstance.message[type](content)
    return
  }
  message[type](content)
}

/** 通知卡片(notification),外壳统一入口(tone 映射到对应类型方法,带类型图标)。 */
export function antdNotify(args: Omit<NotificationArgsProps, 'type'> & {
  tone?: 'success' | 'error' | 'info' | 'warning'
}): void {
  const { tone = 'info', ...rest } = args
  if (appInstance) {
    appInstance.notification[tone](rest)
    return
  }
  notification[tone](rest)
}

/** Modal 确认框,外壳统一入口(替代 window.confirm)。 */
export function antdConfirm(props: ModalFuncProps): void {
  if (appInstance) {
    appInstance.modal.confirm(props)
    return
  }
  Modal.confirm(props)
}