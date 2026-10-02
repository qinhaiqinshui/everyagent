/**
 * 浏览器系统通知宿主。
 *
 * 订阅领域事件(domainEventBus),在以下时机
 * 且「页面不在前台」时发送系统通知:
 * - 任务运行完成(completed);
 * - 任务出错(error);
 * - ask_user 工具调用 / 危险操作授权(USER_INTERACTION_REQUESTED)。
 *
 * 通知开关与权限判断由 settings/browserNotifications + notification 门面提供;
 * 本组件只负责「何时触发」,不渲染任何 UI。
 * 具体系统通知实现由宿主注入的适配器决定:浏览器环境走 Web Notification API,
 * 桌面(electron)环境走 Electron 原生通知(desktop 模块实现,前台判断主进程再兜底一次)。
 * 本组件不自行请求权限:权限申请入口在设置页与 BrowserNotificationGuide。
 */
import React from 'react'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import type { UserInteractionRequest } from '@/types'
import { showSystemNotification } from '@/notification'
import { loadBrowserNotificationsEnabled } from '@/settings/browserNotifications'

/** 仅当页面不在前台且开关开启时才发系统通知(聚焦时应用内 UI 已可见)。 */
function shouldNotify(): boolean {
  return loadBrowserNotificationsEnabled() && !document.hasFocus()
}

/** 是否是需要系统通知的终态(仅 completed / error;stopped 不通知)。 */
function isNotifiableTerminal(status: string): boolean {
  return status === 'completed' || status === 'error'
}

function fireInteractionNotification(request: UserInteractionRequest): void {
  if (!shouldNotify()) return
  const body = request.prompt
    || request.details
    || request.questions?.[0]?.prompt
    || ''
  showSystemNotification({
    title: 'AI 等待你的回答',
    body,
    tag: `ask-${request.id}`,
    onClick: () => {
      domainEventBus.emit(DOMAIN_EVENTS.WORKSPACE_OPEN_USER_INTERACTION_REQUESTED, {
        interactionId: request.id,
      })
    },
  })
}

export default function BrowserNotificationHost() {
  React.useEffect(() => {
    const unsubscribeTask = domainEventBus.subscribe(
      DOMAIN_EVENTS.TASK_STATUS_CHANGED,
      ({ taskId, status, error, displayTitle }) => {
        if (!shouldNotify() || !isNotifiableTerminal(status)) return
        const isError = status === 'error'
        const body = isError && error
          ? `${displayTitle}\n${error}`
          : displayTitle
        showSystemNotification({
          title: isError ? '任务出错' : '任务完成',
          body,
          tag: `task-${taskId}`,
          onClick: () => {
            domainEventBus.emit(DOMAIN_EVENTS.WORKSPACE_FOCUS_TASK_REQUESTED, { taskId })
          },
        })
      },
    )
    const unsubscribeInteraction = domainEventBus.subscribe(
      DOMAIN_EVENTS.USER_INTERACTION_REQUESTED,
      ({ request }) => {
        fireInteractionNotification(request)
      },
    )
    return () => {
      unsubscribeTask()
      unsubscribeInteraction()
    }
  }, [])

  return null
}
