/**
 * 桌面版 bootstrap:检测 Electron preload 暴露的 window.everyAgentDesktop,
 * 若存在则把本地 hub/worker 的默认连接配置写入 hubSession(自动建连)。
 *
 * 仅在「尚未配置 / hub 地址不同 / 缺该 worker 凭证」时自动注入,尊重用户已保存的配置。
 */
import { loadConnectionConfig } from '@/hub/connection'
import { hubSession } from '@/hub/session'
import { setNotificationAdapter } from '@/notification'
import { createDesktopNotificationAdapter } from '@/notification/desktopAdapter'

export interface DesktopBootstrap {
  hubUrl: string
  hubKey: string
  workerId: string
  workerApiKey: string
}

export interface DesktopWindowControl {
  minimize: () => Promise<void>
  toggleMaximize: () => Promise<void>
  close: () => Promise<void>
  isMaximized: () => Promise<boolean>
  onMaximizedChanged: (callback: (maximized: boolean) => void) => void
}

declare global {
  interface Window {
    everyAgentDesktop?: {
      getBootstrap: () => Promise<DesktopBootstrap | null>
      windowControl?: DesktopWindowControl
      /** 发送桌面系统通知(主进程判前台/去重,经 Electron 原生 Notification 展示)。 */
      notify?: (payload: { title: string; body?: string; tag?: string }) => void
      /** 订阅桌面系统通知点击(回调携带 tag,与 notify 时传入的 tag 对应)。 */
      onNotifyClick?: (callback: (tag: string) => void) => void
    }
  }
}

export function isDesktop(): boolean {
  return typeof window !== 'undefined' && typeof window.everyAgentDesktop?.getBootstrap === 'function'
}

/** 标题栏窗口控制按钮仅在桌面版(且 preload 已注入 windowControl)时可用。 */
export function isDesktopWindowControlAvailable(): boolean {
  return typeof window !== 'undefined' && typeof window.everyAgentDesktop?.windowControl?.toggleMaximize === 'function'
}

async function getBootstrap(): Promise<DesktopBootstrap | null> {
  try {
    if (!isDesktop()) return null
    return await window.everyAgentDesktop!.getBootstrap()
  } catch (error) {
    console.warn('[desktop] 读取 bootstrap 失败:', error)
    return null
  }
}

export async function applyDesktopBootstrapIfPresent(): Promise<void> {
  const boot = await getBootstrap()
  if (!boot) return

  // 桌面宿主:注入 Electron 原生通知适配器(覆盖 main.tsx 注入的浏览器默认实现)。
  setNotificationAdapter(createDesktopNotificationAdapter())

  try {
    const current = loadConnectionConfig()
    const needHubConfig =
      !current || current.hubUrl !== boot.hubUrl || current.hubKey !== boot.hubKey

    if (needHubConfig) {
      await hubSession.applyConfig({
        hubUrl: boot.hubUrl,
        hubKey: boot.hubKey,
        workers: current?.workers ?? [],
      })
    }

    const hasWorkerKey = hubSession.config?.workers.some(
      (w) => w.workerId === boot.workerId && Boolean(w.apiKeyEnc),
    )
    if (!hasWorkerKey) {
      await hubSession.setWorkerApiKey(boot.workerId, boot.workerApiKey)
    }
  } catch (error) {
    // bootstrap 失败不应阻断前端渲染;会话层会按既有错误提示兜底。
    console.warn('[desktop] 自动连接配置失败(可在设置页手动配置):', error)
  }
}