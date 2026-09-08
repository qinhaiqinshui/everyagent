/**
 * 前端入口(hub 版)。
 *
 * n 的启动链(浏览器工作区/插件装载/运行时初始化)全部下沉 worker;
 * 这里只做:全局错误兜底 → 主题 → HubProvider(连接 hub)→ Layout。
 * 桌面版(electron)下先应用 preload 注入的 bootstrap 连接配置,再挂 React 树。
 */
import React from 'react'
import ReactDOM from 'react-dom/client'
import Layout from './components/app/Layout'
import { HubProvider } from './hub/HubProvider'
import './assets/styles/ui-tokens.css'
import './assets/styles/ui-primitives.css'
import './assets/styles/ui-utilities.css'
import './assets/styles/ui-controls.css'
import './assets/styles/ui-overlays.css'
import './assets/styles/ui-patterns.css'
import './index.css'
import type { ThemeMode } from './types'
import { loadThemeMode } from './settings/localSettings'
import { installGlobalErrorHandlers } from './utils/globalErrorHandler'
import { GlobalErrorBoundary } from './components/shared/GlobalErrorBoundary'
import { initializeTraceTypes } from './plugin/traceTypeRegistry'
import { registerAuthReviewTraceType } from './plugins/auth-review'
import { wireFsChanged } from './platform/fs/workspaceGateway'
import { workspaceRegistry } from './hub/workspaceRegistry'
import { modelConfigs } from './hub/modelConfigs'
import { registerRemoteSlashProvider } from './slash/remoteSlashProvider'
import { applyDesktopBootstrapIfPresent, isDesktop } from '@/platform/desktopBootstrap'
import { setNotificationAdapter } from '@/notification'
import { createBrowserNotificationAdapter } from '@/notification/browserAdapter'

// 全局异常兜底：最早注册，捕获后续所有未捕获的同步错误与 Promise rejection。
installGlobalErrorHandlers()

// 桌面版(Electron)打平台标记:CSS 据此调整标题栏(如留出可拖拽空白带),
// 且必须在 React 挂载前同步设置,避免首帧标签高度闪烁。
if (isDesktop()) {
  document.documentElement.setAttribute('data-platform', 'desktop')
}

// 核心 trace 类型注册(子任务/错误/系统提示;未注册 kind 走降级渲染)。
initializeTraceTypes()
// AI 安全审议 trace 渲染注册(worker 端每次审议结论发 kind='auth.review' 的 task.trace)。
registerAuthReviewTraceType()

// fs.changed(worker evt 频道)→ 前端文件刷新事件。进程内只接一次。
wireFsChanged()

// 工作区注册表跟踪(workspaces.list 校准 + workspaces.changed 感知)。
workspaceRegistry.wire()

// 模型配置列表跟踪(config.get 校准 + config.changed{models} 感知)。
modelConfigs.wire()

// `/` 斜杠命令数据源下沉 worker(slash.list RPC,动态注册)。
registerRemoteSlashProvider()

const initialThemeMode: ThemeMode = loadThemeMode()
document.documentElement.setAttribute('data-theme', initialThemeMode)
document.body.setAttribute('data-theme', initialThemeMode)

async function boot(): Promise<void> {
  // 浏览器宿主:注入默认的浏览器系统通知适配器(Web Notification API)。
  // 桌面版(electron)随后在 applyDesktopBootstrapIfPresent 里覆盖为桌面适配器。
  setNotificationAdapter(createBrowserNotificationAdapter())

  // 桌面版:在 React 挂载前把本地 hub/worker 的 bootstrap 配置写入 hubSession,
  // 使 HubProvider 首次挂载即能自动连接(非桌面环境直接跳过)。
  await applyDesktopBootstrapIfPresent()

  const root = ReactDOM.createRoot(document.getElementById('root')!)
  root.render(
    <React.StrictMode>
      <GlobalErrorBoundary>
        <HubProvider>
          <Layout initialThemeMode={initialThemeMode} />
        </HubProvider>
      </GlobalErrorBoundary>
    </React.StrictMode>,
  )
}

void boot()
