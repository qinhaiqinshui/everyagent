/**
 * 设置页(hub 版,双道鉴权 + 多 worker)。
 *
 * - Hub 连接区:hubUrl + hubKey(hub 级凭证,前端与 worker 都要带)。
 * - Worker 列表区:见 WorkerList(状态收敛 + 单主按钮 + 别名)。
 *
 * 模型配置区块见 modelConfig/ModelConfigSection(表格增删直接写 worker 侧
 * application-worker.yaml);skill 列表见 SkillListTable(自动暴露按工作区排除配置在此设置);
 * 工作区注册表在资源管理器侧栏管理。
 *
 * 段落导航:每段容器打 data-settings-section-id 锚点(内置段用 SETTINGS_SECTION_ID 常量、
 * 插件段用 def.id),目录唯一来源是扫描(useSettingsSectionDirectory)——桌面端左侧渲染
 * 目录列表,点击滚动到位、手动滚动高亮跟随;移动端单栏,header 常驻目录下拉(选中项跟随
 * 当前激活段),弹层宽度处理见 SettingsPanel.css。
 *
 * 块级跳转:ctx.ui.openSettingsPage 链路的消费侧(settings/settingsNavigation 发出
 * pending 意图 + SETTINGS_SECTION_JUMP_REQUESTED 事件),面板挂载时消费 pending、
 * 运行中订阅事件,待「标签可见 && 目标段已在 DOM」后 scrollToSection 定位
 * (rAF 重试兜底,超时静默放弃,未注册 id 不抛错)。
 */
import React from 'react'
import { Select } from 'antd'
import WorkspacePageShell from '@/components/shared/WorkspacePageShell'
import { Button, Checkbox, TextInput } from '@/components/shared/ui'
import { useHub } from '@/hub/HubProvider'
import { useAppUi } from '@/components/app/AppUiContext'
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
import { isSecretCryptoAvailable } from '@/settings/credentialsCrypto'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import ConfirmDialog from '@/components/shared/ConfirmDialog'
import WorkerList from '@/components/system/WorkerList'
import SkillListTable from '@/components/system/SkillListTable'
import ModelConfigSection from '@/components/system/modelConfig/ModelConfigSection'
import AppearanceSection from '@/components/system/AppearanceSection'
import EditorFontSection from '@/components/system/EditorFontSection'
import PluginSettingsSections from '@/components/system/PluginSettingsSections'
import { SETTINGS_SECTION_ID, SETTINGS_SECTION_ID_ATTR } from '@/components/system/settingsSectionIds'
import { consumePendingSectionJump } from '@/settings/settingsNavigation'
import useSettingsSectionDirectory from '@/components/system/useSettingsSectionDirectory'
import { useResponsiveViewport } from '@/hooks/useResponsiveViewport'
import {
  settingsSectionStyle as sectionStyle,
  settingsSectionTitleStyle as sectionTitleStyle,
} from '@/components/system/settingsSectionStyles'
import { APP_NAME, APP_VERSION, APP_COPYRIGHT, APP_LICENSE } from '@/appInfo'
import './SettingsPanel.css'

/** 块级跳转重试上限：约 3 秒 / 180 帧（60fps），超时静默放弃（未注册 id 的段落始终不出现）。 */
const JUMP_RETRY_TIMEOUT_MS = 3000
const JUMP_RETRY_MAX_FRAMES = 180

/**
 * 在滚动容器内查找 sectionId 对应段落元素。
 * sectionId 来自插件（任意字符串），不做模板字符串拼选择器（防选择器注入）：
 * 属性名是宿主常量，属性值在 JS 里等值比较（线性查找）。
 */
function findSectionElement(root: HTMLElement, sectionId: string): HTMLElement | null {
  const element = Array.from(root.querySelectorAll<HTMLElement>(`[${SETTINGS_SECTION_ID_ATTR}]`))
    .find((item) => item.getAttribute(SETTINGS_SECTION_ID_ATTR) === sectionId)
  return element ?? null
}

export default function SettingsPanel() {
  const hub = useHub()
  const { showToast } = useAppUi()
  const { isMobile } = useResponsiveViewport()
  const bodyRef = React.useRef<HTMLDivElement | null>(null)
  const { entries: directoryEntries, activeId, scrollToSection } = useSettingsSectionDirectory(bodyRef)
  const [hoveredDirectoryId, setHoveredDirectoryId] = React.useState<string | null>(null)

  // ── 块级跳转（ctx.ui.openSettingsPage 链路消费侧，见 settings/settingsNavigation.ts）──
  // 设置页标签常驻挂载、非激活仅 display:none：隐藏期滚动容器测不到位置，且 lazy
  // 首挂 / 插件段晚注册时目标段落尚未进 DOM。故定位 = 等到「容器可见 && 段落已出现」
  // 才 scrollToSection，此前 rAF 重试（上限约 3s / 180 帧），超时静默放弃
  // （未注册 id 不抛错、不滚顶）。意图来源：挂载时消费 pending 单槽（事件错过兜底）
  // + 订阅 SETTINGS_SECTION_JUMP_REQUESTED 领域事件（已挂载时的即时跳转）。
  const jumpRafRef = React.useRef<number | null>(null)
  /** 当前重试中的跳转目标段（cleanup 判断是否存在未竟尝试用）。 */
  const jumpTargetRef = React.useRef<string | null>(null)
  /** dev StrictMode 双挂载兜底：cleanup 中断的尝试记下目标，重挂后重启，行为对齐 prod。 */
  const rearmJumpRef = React.useRef<string | null>(null)

  const jumpToSection = React.useCallback((sectionId: string): void => {
    if (jumpRafRef.current !== null) cancelAnimationFrame(jumpRafRef.current)
    jumpTargetRef.current = sectionId
    const deadline = Date.now() + JUMP_RETRY_TIMEOUT_MS
    let frames = 0
    const attempt = (): void => {
      jumpRafRef.current = null
      const root = bodyRef.current
      if (root) {
        // 可见判定：display:none 下 offsetParent 为 null 且 rect 零宽高，二者其一满足即视为可见。
        const rect = root.getBoundingClientRect()
        const containerVisible = root.offsetParent !== null || rect.width > 0 || rect.height > 0
        if (containerVisible && findSectionElement(root, sectionId) !== null) {
          jumpTargetRef.current = null
          scrollToSection(sectionId)
          return
        }
      }
      if (Date.now() >= deadline || frames >= JUMP_RETRY_MAX_FRAMES) {
        // 段落始终未出现（未注册 id）或标签迟迟不可见：超时静默放弃。
        jumpTargetRef.current = null
        return
      }
      frames += 1
      jumpRafRef.current = requestAnimationFrame(attempt)
    }
    jumpRafRef.current = requestAnimationFrame(attempt)
  }, [scrollToSection])

  React.useEffect(() => {
    // 挂载消费 pending 意图（lazy 首挂时事件早已错过，pending 兜底）；sectionId 空串 = 仅打开不定位。
    const rearm = rearmJumpRef.current
    rearmJumpRef.current = null
    const pending = rearm !== null ? { sectionId: rearm } : consumePendingSectionJump()
    if (pending && pending.sectionId) jumpToSection(pending.sectionId)

    const unsubscribe = domainEventBus.subscribe(DOMAIN_EVENTS.SETTINGS_SECTION_JUMP_REQUESTED, ({ sectionId }) => {
      // 事件到达说明面板已挂载、pending 槽不会被挂载消费读走——顺手清掉（与事件同源同值），
      // 避免陈旧意图在下次卸载重挂时复跳；空串事件仅打开设置页，不定位。
      consumePendingSectionJump()
      if (sectionId) jumpToSection(sectionId)
    })
    return () => {
      unsubscribe()
      if (jumpRafRef.current !== null) {
        cancelAnimationFrame(jumpRafRef.current)
        jumpRafRef.current = null
        if (jumpTargetRef.current !== null) {
          rearmJumpRef.current = jumpTargetRef.current
          jumpTargetRef.current = null
        }
      }
    }
  }, [jumpToSection])

  const [hubUrl, setHubUrl] = React.useState(hub.config?.hubUrl ?? 'ws://localhost:6101/ws')
  const [hubKey, setHubKey] = React.useState(hub.config?.hubKey ?? '')
  // 凭据解密(hydrate)是异步的:首帧 config.hubKey 可能为空,解密完成后回填输入框
  // (仅当用户尚未输入时,不覆盖正在编辑的草稿)。
  const hydratedHubKey = hub.config?.hubKey ?? ''
  React.useEffect(() => {
    if (hydratedHubKey) {
      setHubKey((prev) => (prev === '' ? hydratedHubKey : prev))
    }
  }, [hydratedHubKey])
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
  /** 非安全上下文(http:// 非 localhost,无 crypto.subtle):凭据回退 b64 混淆落盘
   *  (非加密,仍会保存),页面提示建议使用 https 或桌面版。 */
  const insecureCredentialContext = !isSecretCryptoAvailable()

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
        // hubKeyEnc 由 applyConfig 内部加密生成(入参恒空占位)。
        hubKeyEnc: '',
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
      bodyRef={bodyRef}
      header={(
        <div style={pageHeaderStyle}>
          {isMobile ? (
            <div style={pageHeaderMobileRowStyle}>
              <h2 style={pageHeaderTitleStyle}>设置</h2>
              <Select
                size="small"
                variant="filled"
                value={activeId ?? undefined}
                placeholder="跳转到设置段"
                notFoundContent="暂无设置段"
                popupMatchSelectWidth={false}
                options={directoryEntries.map((entry) => ({ value: entry.id, label: entry.title }))}
                onChange={(value) => scrollToSection(value as string)}
                style={settingsDirectorySelectStyle}
                classNames={{ popup: { root: 'settings-directory-select-dropdown' } }}
                aria-label="跳转到设置段"
              />
            </div>
          ) : (
            <h2 style={pageHeaderTitleStyle}>设置</h2>
          )}
          <p style={pageHeaderHintStyle}>连接 hub 并纳管其下的 worker。任务运行时与模型配置在 worker 侧。</p>
        </div>
      )}
    >
      <div style={settingsDirectoryLayoutStyle}>
        {isMobile ? null : (
          <nav style={settingsDirectoryNavStyle} aria-label="设置目录">
            {directoryEntries.map((entry) => {
              const active = entry.id === activeId
              return (
                <button
                  key={entry.id}
                  type="button"
                  style={settingsDirectoryItemStyle(active, entry.id === hoveredDirectoryId)}
                  onClick={() => scrollToSection(entry.id)}
                  onMouseEnter={() => setHoveredDirectoryId(entry.id)}
                  onMouseLeave={() => setHoveredDirectoryId(null)}
                  aria-current={active ? 'true' : undefined}
                >
                  {entry.title}
                </button>
              )
            })}
          </nav>
        )}
        <div style={settingsDirectoryContentStyle}>
          <section style={sectionStyle} data-settings-section-id={SETTINGS_SECTION_ID.HUB_CONNECTION}>
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
            {insecureCredentialContext ? (
              <div style={credentialContextWarningStyle}>
                当前为非安全上下文(http),凭据将以混淆方式保存在本地(非加密),建议使用 https 或桌面版。
              </div>
            ) : null}
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

          <section style={sectionStyle} data-settings-section-id={SETTINGS_SECTION_ID.WORKERS}>
            <h3 style={sectionTitleStyle}>Worker</h3>
            <p style={hintStyle}>
              连接 hub 后发现其下的全部 worker。为每台 worker 输入它自己的 apiKey 并连接;
              任务列表与工作区会合并所有已连接 worker 的数据。
            </p>
            {insecureCredentialContext ? (
              <div style={credentialContextWarningStyle}>
                当前为非安全上下文(http),凭据将以混淆方式保存在本地(非加密),建议使用 https 或桌面版。
              </div>
            ) : null}
            <WorkerList />
          </section>

          <section style={sectionStyle} data-settings-section-id={SETTINGS_SECTION_ID.MODELS}>
            <h3 style={sectionTitleStyle}>模型配置</h3>
            <p style={hintStyle}>
              模型配置按 worker 各自维护(直接写入 worker 侧 application-worker.yaml 的 worker.models 段)。
              表格支持添加/删除;手动改过配置文件时点「重新读取」热加载即可,无需重启。
              所有变更仅影响后续新建任务,运行中任务使用创建时冻结的快照。
            </p>
            <ModelConfigSection />
          </section>

          <section style={sectionStyle} data-settings-section-id={SETTINGS_SECTION_ID.SKILLS}>
            <h3 style={sectionTitleStyle}>Skill 列表</h3>
            <p style={hintStyle}>
              外部 skill 放置在 worker 系统技能目录下(每个 skill 一个子目录,内含 skill.md)。增删 skill 目录后无需重启——
              下次新建任务或打开 `/` 菜单时自动发现(SkillCatalog 零缓存,每次实扫目录)。
              自动暴露按工作区配置,默认全部工作区自动暴露;
              skill.md 头部 frontmatter 仅 name/description 两个展示键(标题与一句话描述),不含暴露控制字段。
            </p>
            <SkillListTable />
          </section>

          <section style={sectionStyle} data-settings-section-id={SETTINGS_SECTION_ID.RESTART_WORKER}>
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

          <section style={sectionStyle} data-settings-section-id={SETTINGS_SECTION_ID.APPEARANCE}>
            <h3 style={sectionTitleStyle}>外观</h3>
            <AppearanceSection />
          </section>

          <section style={sectionStyle} data-settings-section-id={SETTINGS_SECTION_ID.EDITOR_FONT}>
            <h3 style={sectionTitleStyle}>编辑器字体</h3>
            <EditorFontSection />
          </section>

          <section style={sectionStyle} data-settings-section-id={SETTINGS_SECTION_ID.NOTIFICATIONS}>
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

          <section style={sectionStyle} data-settings-section-id={SETTINGS_SECTION_ID.ABOUT}>
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

          <PluginSettingsSections />
        </div>
      </div>
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

/** 移动端 header 行：标题 + 目录下拉同行紧凑排布，空间不足由下拉收缩省略。 */
const pageHeaderMobileRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 8,
  minWidth: 0,
}

/** 移动端 header 目录下拉：compact 尺寸，宽度自适应选中项、限宽防溢出。 */
const settingsDirectorySelectStyle: React.CSSProperties = {
  flex: '0 1 auto',
  minWidth: 96,
  maxWidth: '60%',
}

/** 桌面端设置页两栏布局:左目录列 + 右内容列(移动端目录列不渲染,自然单栏)。 */
const settingsDirectoryLayoutStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: 24,
}

/** 左侧目录列:sticky 固定于滚动容器顶部,不随内容滚动。 */
const settingsDirectoryNavStyle: React.CSSProperties = {
  position: 'sticky',
  top: 0,
  flexShrink: 0,
  width: 190,
  display: 'flex',
  flexDirection: 'column',
  gap: 2,
  paddingTop: 16,
  maxHeight: 'calc(100vh - 140px)',
  overflowY: 'auto',
}

/** 右侧内容列:承载现有全部 section 流(样式与顺序不变)。 */
const settingsDirectoryContentStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
}

/** 目录项:当前段高亮(左侧蓝条 + 加粗),hover 提示可点。 */
const settingsDirectoryItemStyle = (active: boolean, hovered: boolean): React.CSSProperties => ({
  display: 'block',
  width: '100%',
  textAlign: 'left',
  border: 'none',
  borderRadius: 'var(--radius-md)',
  background: active ? 'var(--bg-active)' : hovered ? 'var(--bg-hover)' : 'transparent',
  color: active ? 'var(--text-primary)' : 'var(--text-secondary)',
  boxShadow: active ? 'inset 2px 0 0 0 var(--accent-blue)' : undefined,
  fontSize: 'var(--text-xs)',
  fontWeight: active ? 700 : 400,
  padding: '5px 10px',
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  cursor: 'pointer',
})

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

/** 非安全上下文(http)凭据混淆落盘提示:amber 描边警示框,复用连接错误框的版式。 */
const credentialContextWarningStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  color: 'var(--accent-amber)',
  border: '1px solid var(--accent-amber)',
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
