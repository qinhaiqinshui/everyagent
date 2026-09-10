/**
 * 设置页(hub 版,双道鉴权 + 多 worker)。
 *
 * - Hub 连接区:hubUrl + hubKey(hub 级凭证,前端与 worker 都要带)。
 * - Worker 列表区:目录发现的 worker,各自输入其 apiKey、启用/禁用开关。
 *
 * 模型配置/技能/权限在 worker 侧,不在此编辑;工作区注册表在资源管理器侧栏管理。
 */
import React from 'react'
import WorkspacePageShell from '@/components/shared/WorkspacePageShell'
import { Button, Checkbox, TextInput } from '@/components/shared/ui'
import { useHub } from '@/hub/HubProvider'
import { useThemeMode } from '@/hooks/useThemeMode'
import { useAppUi } from '@/components/app/AppUiContext'
import { loadThemeMode, saveThemeMode } from '@/settings/localSettings'
import {
  getSystemNotificationPermission,
  requestSystemNotificationPermission,
  getNotificationAdapter,
  type NotificationPermissionState,
} from '@/notification'
import {
  loadBrowserNotificationsEnabled,
  saveBrowserNotificationsEnabled,
} from '@/settings/browserNotifications'
import { taskStore } from '@/hub/taskStore'
import { workspaceRegistry } from '@/hub/workspaceRegistry'
import type { WorkerInfo } from '@/hub/session'

function workerStatusLabel(worker: WorkerInfo): string {
  if (worker.error) return '连接失败 · ' + worker.error.code
  if (worker.connecting) return '连接中…'
  if (!worker.online) return '离线'
  if (worker.connected) return '已连接'
  if (!worker.hasApiKey) return '未配置'
  if (!worker.enabled) return '已禁用'
  return '未连接'
}

function workerStatusColor(worker: WorkerInfo): string {
  if (worker.error) return 'var(--accent-red)'
  if (worker.connecting) return 'var(--accent-amber)'
  if (!worker.online) return 'var(--text-muted)'
  if (worker.connected) return 'var(--accent-green)'
  return 'var(--text-muted)'
}

export default function SettingsPanel() {
  const hub = useHub()
  const themeMode = useThemeMode(loadThemeMode())
  const { showToast } = useAppUi()

  const [hubUrl, setHubUrl] = React.useState(hub.config?.hubUrl ?? 'ws://localhost:9100/ws')
  const [hubKey, setHubKey] = React.useState(hub.config?.hubKey ?? '')
  const [workerKeys, setWorkerKeys] = React.useState<Map<string, string>>(new Map())
  const [savingWorker, setSavingWorker] = React.useState('')
  const [saving, setSaving] = React.useState(false)
  const [message, setMessage] = React.useState('')
  const [messageTone, setMessageTone] = React.useState<'ok' | 'error'>('ok')
  const [notificationsEnabled, setNotificationsEnabled] = React.useState<boolean>(loadBrowserNotificationsEnabled)
  const [notificationPermission, setNotificationPermission] = React.useState<NotificationPermissionState>(getSystemNotificationPermission)
  const [requestingNotification, setRequestingNotification] = React.useState(false)
  const isDesktopNotification = getNotificationAdapter()?.source === 'desktop'

  const connected = hub.state === 'open'
  const connecting = hub.state === 'connecting' || hub.state === 'reconnecting'

  const handleSave = async () => {
    if (saving) return
    if (!hubUrl.trim() || !hubKey.trim()) {
      setMessage('需要填写 hub 地址与 hub key')
      setMessageTone('error')
      return
    }
    setSaving(true)
    setMessage('')
    try {
      await hub.applyConfig({
        hubUrl: hubUrl.trim(),
        hubKey: hubKey.trim(),
        workers: hub.config?.workers ?? [],
      })
      await taskStore.refresh()
      setMessage('已连接 hub')
      setMessageTone('ok')
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '连接失败,请检查 hub 地址与 hub key')
      setMessageTone('error')
    } finally {
      setSaving(false)
    }
  }

  const handleDisconnect = () => {
    hub.disconnect()
    setMessage('已断开')
    setMessageTone('ok')
  }

  const handleSaveWorkerKey = async (workerId: string) => {
    if (savingWorker) return
    const apiKey = (workerKeys.get(workerId) ?? '').trim()
    if (!apiKey) {
      setMessage('请输入 worker ' + workerId + ' 的 apiKey')
      setMessageTone('error')
      return
    }
    setSavingWorker(workerId)
    setMessage('')
    try {
      const result = await hub.setWorkerApiKey(workerId, apiKey)
      setWorkerKeys((prev) => {
        const next = new Map(prev)
        next.delete(workerId)
        return next
      })
      if (result.ok) {
        await taskStore.refresh()
        await workspaceRegistry.refresh()
        setMessage(
          result.online
            ? '已连接 worker ' + workerId
            : '已保存 worker ' + workerId + ' 的 apiKey(worker 当前离线)',
        )
        setMessageTone('ok')
      } else {
        const detail = result.error?.detail || result.error?.code || '未知错误'
        setMessage('连接 worker ' + workerId + ' 失败：' + detail)
        setMessageTone('error')
      }
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '保存 worker apiKey 失败')
      setMessageTone('error')
    } finally {
      setSavingWorker('')
    }
  }

  const handleToggleWorker = async (workerId: string, enabled: boolean) => {
    setMessage('')
    try {
      const result = await hub.setWorkerEnabled(workerId, enabled)
      if (!result.ok) {
        const detail = result.error?.detail || result.error?.code || '未知错误'
        setMessage((enabled ? '启用' : '禁用') + ' worker ' + workerId + ' 失败：' + detail)
        setMessageTone('error')
        return
      }
      if (enabled) {
        await taskStore.refresh()
        await workspaceRegistry.refresh()
        setMessage(
          result.online
            ? '已连接 worker ' + workerId
            : '已启用 worker ' + workerId + '(worker 当前离线)',
        )
      } else {
        void taskStore.refresh()
        void workspaceRegistry.refresh()
        setMessage('已禁用 worker ' + workerId)
      }
      setMessageTone('ok')
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '启用/禁用 worker 失败')
      setMessageTone('error')
    }
  }

  const handleRefreshWorkers = () => {
    void taskStore.refresh().then(() => workspaceRegistry.refresh())
  }

  const handleToggleNotifications = (checked: boolean) => {
    setNotificationsEnabled(checked)
    saveBrowserNotificationsEnabled(checked)
  }

  const handleRequestNotification = async () => {
    if (requestingNotification) return
    setRequestingNotification(true)
    try {
      const next = await requestSystemNotificationPermission()
      setNotificationPermission(next)
      if (next === 'granted') {
        showToast('系统通知已开启', 'success')
      } else if (next === 'denied') {
        showToast('系统通知被浏览器拒绝', 'error')
      }
    } finally {
      setRequestingNotification(false)
    }
  }

  const notificationUnsupported = notificationPermission === 'unsupported'
  const notificationGranted = notificationPermission === 'granted'
  const notificationDenied = notificationPermission === 'denied'
  const notificationButtonLabel = notificationUnsupported
    ? '当前环境不支持系统通知'
    : notificationGranted
      ? '系统通知已开启'
      : '开启系统通知'
  const notificationButtonDisabled = notificationUnsupported || notificationGranted || requestingNotification
  const notificationHint = notificationUnsupported
    ? '系统通知需要 https 或 localhost 环境,且浏览器支持 Notification API。'
    : notificationGranted
      ? '任务完成/出错或需要你回答时,系统通知会在页面不在前台时提醒你。'
      : notificationDenied
        ? '已被浏览器拒绝。请在浏览器的站点设置中允许通知后再试。'
        : '点击后浏览器会弹出授权询问。'

  return (
    <WorkspacePageShell
      header={(
        <div style={pageHeaderStyle}>
          <h2 style={pageHeaderTitleStyle}>设置</h2>
          <p style={pageHeaderHintStyle}>连接 hub 并纳管其下的 worker。任务运行时与模型配置在 worker 侧。</p>
        </div>
      )}
    >
      <section style={sectionStyle}>
        <h3 style={sectionTitleStyle}>Hub 连接</h3>
        <div style={fieldStyle}>
          <label style={labelStyle} htmlFor="setting-hub-url">Hub 地址</label>
          <TextInput
            id="setting-hub-url"
            style={inputStyle}
            value={hubUrl}
            onChange={(event) => setHubUrl(event.target.value)}
            placeholder="ws://your-pc:9100/ws"
            spellCheck={false}
          />
        </div>
        <div style={fieldStyle}>
          <label style={labelStyle} htmlFor="setting-hub-key">Hub Key</label>
          <TextInput
            id="setting-hub-key"
            style={inputStyle}
            type="password"
            value={hubKey}
            onChange={(event) => setHubKey(event.target.value)}
            placeholder="连接 hub 的凭证(与 worker 进程相同)"
            spellCheck={false}
          />
        </div>
        <div style={actionsStyle}>
          <Button
            type="button"
            variant="primary"
            style={primaryButtonStyle}
            onClick={() => void handleSave()}
            disabled={saving || connecting}
          >
            {saving || connecting ? '连接中…' : '保存并连接'}
          </Button>
          {connected ? (
            <Button type="button" variant="secondary" style={secondaryButtonStyle} onClick={handleDisconnect}>
              断开
            </Button>
          ) : null}
        </div>
        <div style={statusRowStyle}>
          <span style={statusDotStyle(connected ? 'var(--accent-green)' : connecting ? 'var(--accent-amber)' : 'var(--text-muted)')} />
          <span style={statusTextStyle}>
            {connected ? 'hub 已连接' : connecting ? '连接中…' : hub.configured ? '已断开' : '未配置'}
          </span>
          {message ? (
            <span style={messageTone === 'error' ? errorStyle : okStyle}>{message}</span>
          ) : null}
        </div>
        {hub.fatalError ? (
          <div style={connectionErrorStyle}>
            hub 拒绝了连接:{hub.fatalError.code} — {hub.fatalError.detail || '未知原因'}。
            请修正 hub 地址 / hub key 后重新连接。
          </div>
        ) : null}
        {hub.rateLimited ? (
          <div style={connectionErrorStyle}>
            hub 请求过于频繁,已进入静默退避,将自动重试,请稍候。
          </div>
        ) : null}
      </section>

      <section style={sectionStyle}>
        <h3 style={sectionTitleStyle}>Worker</h3>
        <p style={hintStyle}>
          连接 hub 后发现其下的全部 worker。请为每台 worker 输入它自己的 apiKey 并启用;
          任务列表与工作区会合并所有已启用 worker 的数据。
        </p>
        {hub.directory.length === 0 ? (
          <p style={hintStyle}>暂未发现 worker——请确认 worker 已启动并连到该 hub(使用正确的 hub key)。</p>
        ) : (
          <div style={workerListStyle}>
            {hub.directory.map((worker) => {
              const keyValue = workerKeys.get(worker.workerId) ?? ''
              return (
                <div key={worker.workerId} style={workerRowStyle}>
                  <span style={statusDotStyle(workerStatusColor(worker))} />
                  <span style={workerIdStyle} title={worker.workerId}>{worker.workerId}</span>
                  <span
                    style={{ ...workerStatusStyle, color: workerStatusColor(worker) }}
                    title={worker.error ? (worker.error.detail || worker.error.code) : undefined}
                  >
                    {workerStatusLabel(worker)}
                  </span>
                  <TextInput
                    style={workerKeyInputStyle}
                    type="password"
                    value={keyValue}
                    onChange={(event) => {
                      const next = new Map(workerKeys)
                      next.set(worker.workerId, event.target.value)
                      setWorkerKeys(next)
                    }}
                    placeholder={worker.hasApiKey ? 'apiKey 已保存(输入可覆盖)' : '输入该 worker 的 apiKey'}
                    spellCheck={false}
                  />
                  <Button
                    type="button"
                    variant="secondary"
                    style={secondaryButtonStyle}
                    onClick={() => void handleSaveWorkerKey(worker.workerId)}
                    disabled={savingWorker === worker.workerId}
                  >
                    {savingWorker === worker.workerId ? '保存中…' : '保存'}
                  </Button>
                  <Checkbox
                    checked={worker.enabled}
                    disabled={!worker.hasApiKey}
                    onChange={(event) => void handleToggleWorker(worker.workerId, event.target.checked)}
                    label="启用"
                  />
                  {worker.error ? (
                    <span style={errorStyle} title={worker.error.code}>
                      {worker.error.detail || worker.error.code}
                    </span>
                  ) : null}
                </div>
              )
            })}
          </div>
        )}
        <div style={actionsStyle}>
          <Button type="button" variant="secondary" style={secondaryButtonStyle} onClick={handleRefreshWorkers}>
            刷新
          </Button>
        </div>
      </section>

      <section style={sectionStyle}>
        <h3 style={sectionTitleStyle}>外观</h3>
        <div style={actionsStyle}>
          <Button
            type="button"
            variant="secondary"
            style={secondaryButtonStyle}
            onClick={() => saveThemeMode(themeMode === 'dark' ? 'light' : 'dark')}
          >
            {themeMode === 'dark' ? '切换到浅色' : '切换到深色'}
          </Button>
        </div>
      </section>

      <section style={sectionStyle}>
        <h3 style={sectionTitleStyle}>{isDesktopNotification ? '系统通知' : '系统通知（浏览器）'}</h3>
        <div style={fieldStyle}>
          <Checkbox
            checked={notificationsEnabled}
            onChange={(event) => handleToggleNotifications(event.target.checked)}
            label={isDesktopNotification
              ? '任务完成 / 出错 / 需要回答时发送系统通知'
              : '任务完成 / 出错 / 需要回答时发送浏览器系统通知'}
          />
        </div>
        <div style={actionsStyle}>
          <Button
            type="button"
            variant="secondary"
            style={secondaryButtonStyle}
            onClick={() => void handleRequestNotification()}
            disabled={notificationButtonDisabled}
          >
            {requestingNotification ? '请求中…' : notificationButtonLabel}
          </Button>
          <span style={hintStyle}>{notificationHint}</span>
        </div>
      </section>
    </WorkspacePageShell>
  )
}

const pageHeaderStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
  padding: '10px 16px',
  minWidth: 0,
}

const pageHeaderTitleStyle: React.CSSProperties = {
  margin: 0,
  fontSize: 'var(--text-lg)',
  fontWeight: 700,
}

const pageHeaderHintStyle: React.CSSProperties = {
  margin: 0,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
}

const sectionStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 10,
  padding: '16px 0',
  borderBottom: '1px solid var(--border)',
}

const sectionTitleStyle: React.CSSProperties = {
  margin: 0,
  fontSize: 'var(--text-base)',
  fontWeight: 700,
}

const fieldStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
  maxWidth: 480,
}

const labelStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
}

const inputStyle: React.CSSProperties = {
  border: '1px solid var(--border)',
  borderRadius: 'var(--radius-md)',
  background: 'var(--bg-primary)',
  color: 'var(--text-primary)',
  padding: '8px 10px',
  fontSize: 'var(--text-sm)',
  fontFamily: 'var(--font-mono, monospace)',
}

const actionsStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
}

const primaryButtonStyle: React.CSSProperties = {
  border: 'none',
  borderRadius: 999,
  background: 'var(--accent-blue)',
  color: '#fff',
  padding: '7px 18px',
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  cursor: 'pointer',
}

const secondaryButtonStyle: React.CSSProperties = {
  border: '1px solid var(--border-light)',
  borderRadius: 999,
  background: 'var(--bg-primary)',
  color: 'var(--text-primary)',
  padding: '6px 14px',
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  cursor: 'pointer',
}

const statusRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
}

const statusDotStyle = (color: string): React.CSSProperties => ({
  width: 8,
  height: 8,
  borderRadius: 999,
  background: color,
  flexShrink: 0,
})

const statusTextStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
}

const workerStatusStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  whiteSpace: 'nowrap',
  minWidth: 72,
  flexShrink: 0,
}

const okStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--accent-green)',
}

const errorStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--accent-red)',
}

const connectionErrorStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--accent-red)',
  border: '1px solid var(--accent-red)',
  borderRadius: 'var(--radius-md)',
  padding: '8px 10px',
  maxWidth: 480,
  lineHeight: 1.5,
}

const hintStyle: React.CSSProperties = {
  margin: 0,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
}

const workerListStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  maxWidth: 720,
}

const workerRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  padding: '6px 8px',
  border: '1px solid var(--border)',
  borderRadius: 'var(--radius-md)',
}

const workerIdStyle: React.CSSProperties = {
  fontFamily: 'var(--font-mono, monospace)',
  fontSize: 'var(--text-xs)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  minWidth: 0,
  maxWidth: 180,
}

const workerKeyInputStyle: React.CSSProperties = {
  border: '1px solid var(--border)',
  borderRadius: 'var(--radius-md)',
  background: 'var(--bg-primary)',
  color: 'var(--text-primary)',
  padding: '6px 8px',
  fontSize: 'var(--text-xs)',
  fontFamily: 'var(--font-mono, monospace)',
  flex: 1,
  minWidth: 160,
}