/**
 * Worker 列表(设置页「Worker」区块)。
 *
 * 设计要点(产品视角):
 * - 单一状态:把 online/enabled/hasApiKey/connecting/connected/error 六个内部维度
 *   收敛为用户视角的一个状态徽章(离线/待配置/连接中/已连接/已停用/连接失败);
 * - 一个主按钮:每行只有一个主操作,文案随状态变化(连接/停用/启用/重试);
 * - 错误即指引:连接失败给出行内修复建议,不只抛错误码;
 * - 改名不求人:显示名 = 本地别名(可改)> hostname > workerId,别名仅本端存储。
 */
import React from 'react'
import { Button, TextInput } from '@/components/shared/ui'
import MoreActionsButton, { type MoreActionItem } from '@/components/shared/MoreActionsButton'
import ConfirmDialog from '@/components/shared/ConfirmDialog'
import { useHub } from '@/hub/HubProvider'
import { hubSession, type WorkerInfo } from '@/hub/session'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import { workspaceRegistry } from '@/hub/workspaceRegistry'
import { useAppUi } from '@/components/app/AppUiContext'
import {
  getWorkerAlias,
  onWorkerAliasesChanged,
  setWorkerAlias,
  workerDisplayName,
} from '@/settings/workerAliases'

/** 用户视角 worker 状态(判定优先级从上到下)。 */
type WorkerViewState = 'offline' | 'failed' | 'connecting' | 'connected' | 'unconfigured' | 'disabled'

function workerViewState(w: WorkerInfo): WorkerViewState {
  if (!w.online) return 'offline'
  if (w.error) return 'failed'
  if (w.connecting) return 'connecting'
  if (w.connected) return 'connected'
  if (!w.hasApiKey) return 'unconfigured'
  return 'disabled'
}

const STATE_META: Record<WorkerViewState, { label: string; color: string }> = {
  offline: { label: '离线', color: 'var(--text-muted)' },
  failed: { label: '连接失败', color: 'var(--accent-red)' },
  connecting: { label: '连接中…', color: 'var(--accent-blue)' },
  connected: { label: '已连接', color: 'var(--accent-green)' },
  unconfigured: { label: '待配置', color: 'var(--accent-amber)' },
  disabled: { label: '已停用', color: 'var(--text-muted)' },
}

/** 连接错误 → 用户可执行的修复建议(错误即指引)。 */
function errorAdvice(error: { code: string; detail: string }): string {
  switch (error.code) {
    case 'NOT_AUTHENTICATED':
      return 'apiKey 不正确,请核对该 worker 的 apiKey 后重新输入。'
    case 'RATE_LIMITED':
      return '请求过于频繁被 hub 限流,请稍后重试。'
    case 'CREDENTIAL_DECRYPT':
      return '本地保存的凭证读取失败,请重新输入 apiKey。'
    case 'CONNECT_FAILED':
      return '无法建立连接,请检查网络与 hub 地址后重试。'
    default:
      return error.detail || '请重新输入 apiKey 后重试。'
  }
}

export default function WorkerList() {
  const hub = useHub()
  const { showToast } = useAppUi()

  /** workerId → apiKey 输入草稿。 */
  const [keyDrafts, setKeyDrafts] = React.useState<Map<string, string>>(new Map())
  /** 已配置 worker 手动展开「重新输入 apiKey」的行。 */
  const [expandedKeyInput, setExpandedKeyInput] = React.useState<Set<string>>(new Set())
  /** 正在执行连接/启停操作的 workerId。 */
  const [busyId, setBusyId] = React.useState('')
  /** 正在改名的 workerId 与草稿。 */
  const [renamingId, setRenamingId] = React.useState('')
  const [renameDraft, setRenameDraft] = React.useState('')
  /** 待确认移除配置的 workerId。 */
  const [removingId, setRemovingId] = React.useState('')

  // 别名变化时重渲染(显示名依赖别名)。
  const [, setAliasesVersion] = React.useState(0)
  React.useEffect(() => onWorkerAliasesChanged(() => setAliasesVersion((v) => v + 1)), [])

  /** 连接成功/启用后刷新任务与工作区数据(与原设置页行为一致)。 */
  const refreshWorkerData = React.useCallback(async () => {
    await workspaceRegistry.refresh()
    domainEventBus.emit(DOMAIN_EVENTS.WORKER_DATA_CHANGED, {})
  }, [])

  const handleConnect = async (worker: WorkerInfo) => {
    if (busyId) return
    const apiKey = (keyDrafts.get(worker.workerId) ?? '').trim()
    if (!apiKey) {
      showToast('请输入 worker ' + workerDisplayName(worker) + ' 的 apiKey', 'error')
      return
    }
    setBusyId(worker.workerId)
    try {
      const result = await hub.setWorkerApiKey(worker.workerId, apiKey)
      if (result.ok) {
        setKeyDrafts((prev) => {
          const next = new Map(prev)
          next.delete(worker.workerId)
          return next
        })
        setExpandedKeyInput((prev) => {
          const next = new Set(prev)
          next.delete(worker.workerId)
          return next
        })
        await refreshWorkerData()
        showToast(
          result.online
            ? '已连接 ' + workerDisplayName(worker)
            : '已保存 ' + workerDisplayName(worker) + ' 的 apiKey,worker 上线后将自动连接',
          'success',
        )
      } else {
        // 行内状态徽章会随 directory 变为「连接失败」并展示修复建议,这里仅 toast 兜底。
        showToast(
          '连接 ' + workerDisplayName(worker) + ' 失败:' +
            (result.error?.detail || result.error?.code || '未知错误'),
          'error',
        )
      }
    } catch (error) {
      showToast(error instanceof Error ? error.message : '保存 apiKey 失败', 'error')
    } finally {
      setBusyId('')
    }
  }

  const handleSetEnabled = async (worker: WorkerInfo, enabled: boolean) => {
    if (busyId) return
    setBusyId(worker.workerId)
    try {
      const result = await hub.setWorkerEnabled(worker.workerId, enabled)
      if (!result.ok) {
        showToast(
          (enabled ? '启用' : '停用') + '失败:' + (result.error?.detail || result.error?.code || '未知错误'),
          'error',
        )
        return
      }
      if (enabled) {
        await refreshWorkerData()
        showToast(
          result.online
            ? '已启用 ' + workerDisplayName(worker)
            : '已启用 ' + workerDisplayName(worker) + ',worker 上线后将自动连接',
          'success',
        )
      } else {
        void workspaceRegistry.refresh()
        domainEventBus.emit(DOMAIN_EVENTS.WORKER_DATA_CHANGED, {})
        showToast('已停用 ' + workerDisplayName(worker), 'success')
      }
    } catch (error) {
      showToast(error instanceof Error ? error.message : '操作失败', 'error')
    } finally {
      setBusyId('')
    }
  }

  const handleRemove = (worker: WorkerInfo) => {
    hubSession.removeWorker(worker.workerId)
    setRemovingId('')
    void workspaceRegistry.refresh()
    domainEventBus.emit(DOMAIN_EVENTS.WORKER_DATA_CHANGED, {})
    showToast('已移除 ' + workerDisplayName(worker) + ' 的配置', 'success')
  }

  const handleCopyWorkerId = async (worker: WorkerInfo) => {
    try {
      await navigator.clipboard.writeText(worker.workerId)
      showToast('已复制 worker ID', 'success')
    } catch {
      showToast('复制失败,请手动复制:' + worker.workerId, 'error')
    }
  }

  const commitRename = (worker: WorkerInfo) => {
    setWorkerAlias(worker.workerId, renameDraft)
    setRenamingId('')
  }

  const removingWorker = removingId
    ? hub.directory.find((w) => w.workerId === removingId) ?? null
    : null

  // 排序:在线优先,再按显示名。
  const sorted = [...hub.directory].sort((a, b) => {
    if (a.online !== b.online) return a.online ? -1 : 1
    return workerDisplayName(a).localeCompare(workerDisplayName(b))
  })

  if (sorted.length === 0) {
    return (
      <p style={hintStyle}>
        暂未发现 worker——请确认 worker 进程已启动,并使用与上方相同的 hub 地址与 hub key 接入。
      </p>
    )
  }

  return (
    <div style={listStyle}>
      {sorted.map((worker) => {
        const state = workerViewState(worker)
        const meta = STATE_META[state]
        const displayName = workerDisplayName(worker)
        const alias = getWorkerAlias(worker.workerId)
        const busy = busyId === worker.workerId
        const renaming = renamingId === worker.workerId
        // apiKey 输入行:待配置/连接失败恒展开;已配置的可经 ⋮ 菜单「重新输入 apiKey」展开。
        const keyInputVisible =
          state === 'unconfigured' || state === 'failed' || expandedKeyInput.has(worker.workerId)

        const menuItems: MoreActionItem[] = [
          {
            key: 'rename',
            label: '重命名',
            onSelect: () => {
              setRenamingId(worker.workerId)
              setRenameDraft(alias)
            },
          },
          { key: 'copy-id', label: '复制 worker ID', onSelect: () => void handleCopyWorkerId(worker) },
        ]
        if (worker.hasApiKey && (state === 'connected' || state === 'disabled')) {
          menuItems.push({
            key: 'rekey',
            label: '重新输入 apiKey',
            onSelect: () =>
              setExpandedKeyInput((prev) => new Set(prev).add(worker.workerId)),
          })
        }
        if (worker.hasApiKey) {
          menuItems.push({
            key: 'remove',
            label: '移除配置',
            danger: true,
            onSelect: () => setRemovingId(worker.workerId),
          })
        }

        return (
          <div key={worker.workerId} style={rowStyle}>
            <div style={rowMainStyle}>
              <span style={statusDotStyle(meta.color)} />
              <div style={nameColStyle}>
                {renaming ? (
                  <TextInput
                    size="sm"
                    style={renameInputStyle}
                    value={renameDraft}
                    autoFocus
                    placeholder={worker.hostname?.trim() || worker.workerId}
                    onChange={(event) => setRenameDraft(event.target.value)}
                    onBlur={() => commitRename(worker)}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter') commitRename(worker)
                      if (event.key === 'Escape') setRenamingId('')
                    }}
                    spellCheck={false}
                  />
                ) : (
                  <span style={nameStyle} title={worker.workerId}>{displayName}</span>
                )}
                <span style={subStyle} title={worker.workerId}>
                  {worker.hostname?.trim() && worker.hostname.trim() !== displayName
                    ? worker.hostname.trim() + ' · '
                    : ''}
                  {worker.workerId}
                </span>
              </div>
              <span
                style={{ ...stateBadgeStyle, color: meta.color }}
                title={worker.error ? worker.error.detail || worker.error.code : undefined}
              >
                {meta.label}
              </span>
              {state === 'unconfigured' || state === 'failed' ? (
                <Button
                  type="button"
                  variant="primary"
                  style={primaryButtonStyle}
                  onClick={() => void handleConnect(worker)}
                  disabled={busy}
                >
                  {busy ? '连接中…' : state === 'failed' ? '重试' : '连接'}
                </Button>
              ) : null}
              {state === 'connected' ? (
                <Button
                  type="button"
                  variant="secondary"
                  style={secondaryButtonStyle}
                  onClick={() => void handleSetEnabled(worker, false)}
                  disabled={busy}
                >
                  {busy ? '处理中…' : '停用'}
                </Button>
              ) : null}
              {state === 'disabled' ? (
                <Button
                  type="button"
                  variant="primary"
                  style={primaryButtonStyle}
                  onClick={() => void handleSetEnabled(worker, true)}
                  disabled={busy}
                >
                  {busy ? '连接中…' : '启用'}
                </Button>
              ) : null}
              {state === 'connecting' ? (
                <Button type="button" variant="secondary" style={secondaryButtonStyle} disabled>
                  连接中…
                </Button>
              ) : null}
              <MoreActionsButton items={menuItems} title="更多操作" />
            </div>
            {state === 'offline' && worker.hasApiKey ? (
              <p style={rowHintStyle}>
                {worker.enabled
                  ? '已保存凭证,worker 上线后将自动连接。'
                  : '已停用,启用后将在 worker 上线时自动连接。'}
              </p>
            ) : null}
            {state === 'failed' && worker.error ? (
              <p style={rowErrorStyle}>{errorAdvice(worker.error)}</p>
            ) : null}
            {keyInputVisible ? (
              <div style={keyRowStyle}>
                <TextInput
                  style={keyInputStyle}
                  type="password"
                  value={keyDrafts.get(worker.workerId) ?? ''}
                  onChange={(event) => {
                    const next = new Map(keyDrafts)
                    next.set(worker.workerId, event.target.value)
                    setKeyDrafts(next)
                  }}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter') void handleConnect(worker)
                  }}
                  placeholder={
                    worker.hasApiKey ? 'apiKey 已保存(输入新 key 覆盖)' : '输入该 worker 的 apiKey'
                  }
                  spellCheck={false}
                />
              </div>
            ) : null}
          </div>
        )
      })}
      <ConfirmDialog
        open={Boolean(removingWorker)}
        title="移除 worker 配置"
        message={
          removingWorker
            ? '将清除本机保存的 ' + workerDisplayName(removingWorker) + '(' + removingWorker.workerId +
              ')的 apiKey 与启用开关;worker 进程本身不受影响。'
            : ''
        }
        confirmLabel="移除"
        danger
        onConfirm={() => {
          if (removingWorker) handleRemove(removingWorker)
        }}
        onCancel={() => setRemovingId('')}
      />
    </div>
  )
}

const listStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  maxWidth: 720,
}

const rowStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  padding: '8px 10px',
  border: '1px solid var(--border)',
  borderRadius: 'var(--radius-md)',
}

const rowMainStyle: React.CSSProperties = {
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

const nameColStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 1,
  minWidth: 0,
  flex: 1,
}

const nameStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

const subStyle: React.CSSProperties = {
  fontFamily: 'var(--font-mono, monospace)',
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

const renameInputStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  maxWidth: 240,
}

const stateBadgeStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  whiteSpace: 'nowrap',
  flexShrink: 0,
}

const primaryButtonStyle: React.CSSProperties = {
  border: 'none',
  borderRadius: 999,
  background: 'var(--accent-blue)',
  color: '#fff',
  padding: '6px 16px',
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  cursor: 'pointer',
  flexShrink: 0,
}

const secondaryButtonStyle: React.CSSProperties = {
  border: '1px solid var(--border-light)',
  borderRadius: 999,
  background: 'var(--bg-primary)',
  color: 'var(--text-primary)',
  padding: '5px 14px',
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  cursor: 'pointer',
  flexShrink: 0,
}

const rowHintStyle: React.CSSProperties = {
  margin: 0,
  paddingLeft: 16,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
}

const rowErrorStyle: React.CSSProperties = {
  margin: 0,
  paddingLeft: 16,
  fontSize: 'var(--text-xs)',
  color: 'var(--accent-red)',
}

const keyRowStyle: React.CSSProperties = {
  display: 'flex',
  paddingLeft: 16,
}

const keyInputStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 160,
  fontSize: 'var(--text-xs)',
  fontFamily: 'var(--font-mono, monospace)',
}

const hintStyle: React.CSSProperties = {
  margin: 0,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
}
