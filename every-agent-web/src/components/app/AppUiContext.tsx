import React from 'react'
import { antdMessage } from '@/utils/appAntdBridge'

export type AppToast = {
  id: string
  type: 'success' | 'error' | 'info'
  message: string
}

type AppUiContextValue = {
  toasts: AppToast[]
  showToast: (message: string, type?: AppToast['type']) => void
  removeToast: (id: string) => void
}

const AppUiContext = React.createContext<AppUiContextValue | null>(null)

/**
 * UI 级上下文。
 * 只承载全局提示等纯界面状态，不承载业务数据。
 *
 * 自 v2 起 `showToast` 外壳保留,内部统一改走 antd message
 * (见 utils/appAntdBridge);toasts/removeToast 属性保留以兼容旧调用方。
 */
export function AppUiProvider({ children }: { children: React.ReactNode }) {
  const showToast = React.useCallback((message: string, type: AppToast['type'] = 'info') => {
    antdMessage(type, message)
  }, [])

  const value = React.useMemo(() => ({
    toasts: [] as AppToast[],
    showToast,
    removeToast: () => {},
  }), [showToast])

  return (
    <AppUiContext.Provider value={value}>
      {children}
    </AppUiContext.Provider>
  )
}

/**
 * 读取 UI 级上下文。
 */
export function useAppUi() {
  const context = React.useContext(AppUiContext)
  if (!context) {
    throw new Error('useAppUi 必须在 AppUiProvider 内使用')
  }
  return context
}
