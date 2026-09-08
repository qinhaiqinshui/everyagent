/**
 * 浏览器系统通知宿主。
 *
 * 订阅全局任务镜像(taskStore)与用户交互事件(domainEventBus),在以下时机
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
import type { TaskListEntry } from '@/hub/taskStore'
import { taskStore } from '@/hub/taskStore'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import type { TaskStatus, UserInteractionRequest } from '@/types'
import { showSystemNotification } from '@/notification'
import { loadBrowserNotificationsEnabled } from '@/settings/browserNotifications'

/** 仅当页面不在前台且开关开启时才发系统通知(聚焦时应用内 UI 已可见)。 */
function shouldNotify(): boolean {
  return loadBrowserNotificationsEnabled() && !document.hasFocus()
}

/** 是否是需要系统通知的终态(仅 completed / error;stopped 不通知)。 */
function isNotifiableTerminal(status: TaskStatus): boolean {
  return status === 'completed' || status === 'error'
}

function fireTaskNotification(entry: TaskListEntry, previous: TaskStatus): void {
  if (!shouldNotify() || !isNotifiableTerminal(entry.status)) return
  if (isNotifiableTerminal(previous)) return
  const isError = entry.status === 'error'
  const body = isError && entry.error
    ? `${entry.title}\n${entry.error}`
    : entry.title
  showSystemNotification({
    title: isError ? '任务出错' : '任务完成',
    body,
    tag: `task-${entry.taskId}`,
    onClick: () => {
      domainEventBus.emit(DOMAIN_EVENTS.WORKSPACE_FOCUS_TASK_REQUESTED, {
        taskId: entry.taskId,
      })
    },
  })
}

function fireInteractionNotification(request: UserInteractionRequest): void {
  if (!shouldNotify()) return
  const isAuthorization = request.responseMode === 'authorization'
  const body = request.prompt
    || request.details
    || request.questions?.[0]?.prompt
    || ''
  showSystemNotification({
    title: isAuthorization ? 'AI 请求授权' : 'AI 等待你的回答',
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
  const prevStatusesRef = React.useRef<Map<string, TaskStatus>>(new Map())

  React.useEffect(() => {
    const unsubscribeTask = taskStore.subscribe((entries) => {
      const prev = prevStatusesRef.current
      for (const entry of entries) {
        const previous = prev.get(entry.taskId)
        prev.set(entry.taskId, entry.status)
        // 首次见到(含全量校准回放)不通知,避免历史终态任务在刷新时重复弹通知。
        if (!previous) continue
        if (!isNotifiableTerminal(previous) && isNotifiableTerminal(entry.status)) {
          fireTaskNotification(entry, previous)
        }
      }
    })
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