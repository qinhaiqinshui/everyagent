/**
 * 设置页(hub 版,双道鉴权 + 多 worker)。
 *
 * - Hub 连接区:hubUrl + hubKey(hub 级凭证,前端与 worker 都要带)。
 * - Worker 列表区:见 WorkerList(状态收敛 + 单主按钮 + 别名)。
 *
 * 模型配置区块见 modelConfig/ModelConfigSection(表格增删直接写 worker 侧
 * application-worker.yaml);skill 列表见 SkillListTable(自动暴露按工作区排除配置在此设置);
 * 工作区注册表在资源管理器侧栏管理。
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
import { hubSession } from '@/hub/session'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import ConfirmDialog from '@/components/shared/ConfirmDialog'
import WorkerList from '@/components/system/WorkerList'
import SkillListTable from '@/components/system/SkillListTable'
import ModelConfigSection from '@/components/system/modelConfig/ModelConfigSection'
import { APP_NAME, APP_VERSION, APP_COPYRIGHT, APP_LICENSE } from '@/appInfo'

export default function SettingsPanel() {
  const hub = useHub()
  const themeMode = useThemeMode(loadThemeMode())
  const { showToast } = useAppUi()

  const [hubUrl, setHubUrl] = React.useState(hub.config?.hubUrl ?? 'ws://localhost:6101/ws')
  const [hubKey, setHubKey] = React.useState(hub.config?.hubKey ?? '')
  const [saving, setSaving] = React.useState(false)
  const [message, setMessage] = React.useState('')
  const [messageTone, setMessageTone] = React.useState<'ok' | 'error'>('ok')
  const [notificationsEnabled, setNotificationsEnabled] = React.useState<boolean>(loadBrowserNotificationsEnabled)
  const [notificationPermission, setNotificationPermission] = React.useState<NotificationPermissionState>(getSystemNotificationPermission)
  const [requestingNotification, setRequestingNotification] = React.useState(false)
  const [restartingWorkers, setRestartingWorkers] = React.useState(false)
  const [restartConfirmOpen, setRestartConfirmOpen] = React.useState(false)
  const [restartHint, setRestartHint] = React.useState('')
  const [restartTone, setRestartTone] = React.useState<'ok' | 'error'>('ok')
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
      domainEventBus.emit(DOMAIN_EVENTS.WORKER_DATA_CHANGED, {})
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

  const handleToggleNotifications = (checked: boolean) => {
    setNotificationsEnabled(checked)
    saveBrowserNotificationsEnabled(checked)
  }

  const handleRestartWorkers = async () => {
    setRestartConfirmOpen(false)
    if (restartingWorkers) return
    const connectedWorkers: string[] = []
    hub.directory.forEach((w) => {
      if (w.connected) connectedWorkers.push(w.workerId)
    })
    if (connectedWorkers.length === 0) {
      setRestartHint('无已连接的 worker')
      setRestartTone('error')
      return
    }
    setRestartingWorkers(true)
    setRestartHint('')
    try {
      const results = await Promise.allSettled(
        connectedWorkers.map((id) => hubSession.rpcTo(id, 'worker.restart')),
      )
      const succeeded: string[] = []
      const failed: string[] = []
      results.forEach((r, i) => {
        if (r.status === 'fulfilled') {
          succeeded.push(connectedWorkers[i])
        } else {
          failed.push(connectedWorkers[i])
        }
      })
      if (succeeded.length > 0) {
        setRestartHint('已向 ' + succeeded.length + ' 台 worker 发送重启指令,worker 将短暂离线后自动恢复')
        setRestartTone('ok')
      }
      if (failed.length > 0) {
        const failedText = failed.length + ' 台重启失败(' + failed.join(', ') + ',老 worker 不含此方法或无法重启)'
        setRestartHint((prev) => (prev ? prev + ';' : '') + failedText)
        setRestartTone('error')
      }
    } finally {
      setRestartingWorkers(false)
    }
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
            placeholder="ws://your-pc:6101/ws"
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
          连接 hub 后发现其下的全部 worker。为每台 worker 输入它自己的 apiKey 并连接;
          任务列表与工作区会合并所有已连接 worker 的数据。
        </p>
        <WorkerList />
      </section>

      <section style={sectionStyle}>
        <h3 style={sectionTitleStyle}>模型配置</h3>
        <p style={hintStyle}>
          模型配置按 worker 各自维护(直接写入 worker 侧 application-worker.yaml 的 worker.models 段)。
          表格支持添加/删除;手动改过配置文件时点「重新读取」热加载即可,无需重启。
          所有变更仅影响后续新建任务,运行中任务使用创建时冻结的快照。
        </p>
        <ModelConfigSection />
      </section>

      <section style={sectionStyle}>
        <h3 style={sectionTitleStyle}>Skill 列表</h3>
        <p style={hintStyle}>
          外部 skill 放置在 worker 系统技能目录下(每个 skill 一个子目录,内含 skill.md)。增删 skill 目录后无需重启——
          下次新建任务或打开 `/` 菜单时自动发现(SkillCatalog 零缓存,每次实扫目录)。
          自动暴露按工作区配置,默认全部工作区自动暴露;
          skill.md 头部 frontmatter 仅 name/description 两个展示键(标题与一句话描述),不含暴露控制字段。
        </p>
        <SkillListTable />
      </section>

      <section style={sectionStyle}>
        <h3 style={sectionTitleStyle}>重启 Worker</h3>
        <p style={hintStyle}>
          重启 worker 进程(自重启:优雅关闭后自动重新拉起,无需外部看护)。运行中的任务会被中断,
          重启后由 worker 标记为「worker 重启中断」,任务数据不丢;worker 短暂离线后会自动恢复连接。
          模型配置与 skill 列表优先用上方「重新读取」热加载,仅在热加载无效时才需要重启。
        </p>
        <div style={actionsStyle}>
          <Button
            type="button"
            variant="secondary"
            style={secondaryButtonStyle}
            onClick={() => setRestartConfirmOpen(true)}
            disabled={restartingWorkers}
          >
            {restartingWorkers ? '发送中…' : '重启 Worker'}
          </Button>
          {restartHint ? (
            <span style={restartTone === 'error' ? errorStyle : okStyle}>{restartHint}</span>
          ) : null}
        </div>
        <ConfirmDialog
          open={restartConfirmOpen}
          title="重启 worker"
          message={
            '将向全部已连接的 worker 发送重启指令。运行中的任务会被中断(重启后标记为「worker 重启中断」),' +
            '任务数据不丢;worker 短暂离线后自动恢复。确定重启?'
          }
          confirmLabel="重启"
          danger
          onConfirm={() => void handleRestartWorkers()}
          onCancel={() => setRestartConfirmOpen(false)}
        />
      </section>

      <section style={sectionStyle}>
        <h3 style={sectionTitleStyle}>外观</h3>
        <div style={actionsStyle}>
          <Button
            type="button"
            variant="secondary"
            style={secondaryButtonStyle}
            onClick={() => void saveThemeMode(themeMode === 'dark' ? 'light' : 'dark')}
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

      <section style={sectionStyle}>
        <h3 style={sectionTitleStyle}>关于</h3>
        <div style={aboutGridStyle}>
          <div style={aboutRowStyle}>
            <span style={aboutLabelStyle}>软件名称</span>
            <span style={aboutValueStyle}>{APP_NAME}</span>
          </div>
          <div style={aboutRowStyle}>
            <span style={aboutLabelStyle}>版本</span>
            <span style={aboutValueMonoStyle}>{APP_VERSION}</span>
          </div>
          <div style={aboutRowStyle}>
            <span style={aboutLabelStyle}>所有权</span>
            <span style={aboutValueStyle}>{APP_COPYRIGHT}</span>
          </div>
          <div style={aboutRowStyle}>
            <span style={aboutLabelStyle}>许可证</span>
            <span style={aboutValueStyle}>{APP_LICENSE}</span>
          </div>
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

const aboutGridStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 8,
  maxWidth: 480,
}

const aboutRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'baseline',
  gap: 12,
}

const aboutLabelStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  flexShrink: 0,
  minWidth: 56,
}

const aboutValueStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-primary)',
  wordBreak: 'break-all',
}

const aboutValueMonoStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--text-primary)',
  fontFamily: 'var(--font-mono, monospace)',
}
