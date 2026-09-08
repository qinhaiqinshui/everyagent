/**
 * 浏览器系统通知引导。
 *
 * 启动时若满足「系统通知可用 + 尚未授权(permission === default)+ 开关开启」,
 * 在页面右下角浮现一次轻量引导卡片,点击「开启系统通知」申请权限。
 * 授权 / 拒绝 / 用户关闭后不再弹出。
 *
 * 桌面(electron)环境 permission 恒为 granted,引导卡片不会出现;
 * 该环境不申请浏览器权限,直接使用 Electron 原生通知。
 *
 * 权限申请必须由用户手势触发,因此这里用按钮而不是启动时自动调用。
 */
import React from 'react'
import { Button } from '@/components/shared/ui'
import { useAppUi } from '@/components/app/AppUiContext'
import {
  isSystemNotificationSupported,
  getSystemNotificationPermission,
  requestSystemNotificationPermission,
  type NotificationPermissionState,
} from '@/notification'
import { loadBrowserNotificationsEnabled } from '@/settings/browserNotifications'

function shouldShowGuide(): boolean {
  if (!isSystemNotificationSupported()) return false
  if (getSystemNotificationPermission() !== 'default') return false
  return loadBrowserNotificationsEnabled()
}

export default function BrowserNotificationGuide() {
  const { showToast } = useAppUi()
  const [visible, setVisible] = React.useState<boolean>(() => shouldShowGuide())
  const [requesting, setRequesting] = React.useState(false)

  const handleRequest = async () => {
    if (requesting) return
    setRequesting(true)
    try {
      const next: NotificationPermissionState = await requestSystemNotificationPermission()
      setVisible(false)
      if (next === 'granted') {
        showToast('系统通知已开启', 'success')
      } else if (next === 'denied') {
        showToast('系统通知被浏览器拒绝,可在设置页查看', 'info')
      }
    } finally {
      setRequesting(false)
    }
  }

  if (!visible) return null

  return (
    <div style={guideStyle} role="dialog" aria-label="开启系统通知">
      <div style={guideHeaderStyle}>
        <span style={guideTitleStyle}>开启系统通知</span>
        <button
          type="button"
          style={guideCloseStyle}
          onClick={() => setVisible(false)}
          aria-label="关闭"
        >
          ×
        </button>
      </div>
      <div style={guideBodyStyle}>
        任务完成、出错或需要你回答时,浏览器会通过系统通知提醒你(即使页面不在前台)。
      </div>
      <div style={guideActionsStyle}>
        <Button
          variant="primary"
          style={guideButtonStyle}
          onClick={() => void handleRequest()}
          disabled={requesting}
        >
          {requesting ? '请求中…' : '开启系统通知'}
        </Button>
        <Button variant="secondary" style={guideButtonStyle} onClick={() => setVisible(false)}>
          暂不开启
        </Button>
      </div>
    </div>
  )
}

const guideStyle: React.CSSProperties = {
  position: 'fixed',
  right: 20,
  bottom: 20,
  zIndex: 2000,
  width: 300,
  maxWidth: 'calc(100vw - 40px)',
  padding: '14px 16px',
  borderRadius: 14,
  border: '1px solid var(--border)',
  background: 'var(--bg-primary)',
  boxShadow: '0 12px 32px rgba(0,0,0,0.18)',
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
  colorScheme: 'light',
}

const guideHeaderStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 8,
}

const guideTitleStyle: React.CSSProperties = {
  fontSize: 'var(--text-sm)',
  fontWeight: 700,
  color: 'var(--text-primary)',
}

const guideCloseStyle: React.CSSProperties = {
  border: 'none',
  background: 'transparent',
  color: 'var(--text-muted)',
  fontSize: 'var(--text-lg)',
  lineHeight: 1,
  cursor: 'pointer',
  padding: 0,
}

const guideBodyStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  lineHeight: 1.6,
  color: 'var(--text-secondary)',
}

const guideActionsStyle: React.CSSProperties = {
  display: 'flex',
  justifyContent: 'flex-end',
  gap: 8,
  marginTop: 2,
}

const guideButtonStyle: React.CSSProperties = {
  border: 'none',
  borderRadius: 999,
  padding: '6px 14px',
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  cursor: 'pointer',
}