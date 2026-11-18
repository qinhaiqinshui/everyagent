/**
 * 「连接到远程 Hub」对话框(antd Modal + Form,与 ModelConfigFormDialog 同款选型)。
 *
 * 入口在设置页 Worker 列表每行的 ⋮ 更多菜单——语义:把前端从「当前配置的 hub」
 * (通常为桌面版本机自动拉起)切换并连接到远程部署的 hub(如服务器上的)。
 *
 * - 顶部**只读展示该 worker 已连接到的 hub 清单**(sys.hubs:worker.hubs 里配的每一条,
 *   含本地回环 hub),每条标「本地/远程」+「已连接/未连接」,与前端当前所在 hub 相同的
 *   那条标「当前」—— 让用户知道这台 worker 还挂在哪些 hub 上、该填哪个地址;
 *   清单只含地址与状态,worker 侧不回任何凭据(明文不出 worker),hub key 始终由用户手填;
 * - 打开时预填当前配置的 hubUrl / hubKey(useHub().config);
 * - 两项必填,空则 antd Form 行内红字提示;
 * - 确认走 hub.applyConfig(内部加密落盘并自动重连,即「保存后自动连接」),
 *   已保存的 workers 凭证表原样带过不丢失;
 * - 成功 toast 并关窗;失败在窗内红字展示错误,保持打开可改可重试;
 *   提交期间按钮 loading,防重复提交。
 */
import React from 'react'
import { Form, Input, Modal } from 'antd'
import { useHub } from '@/hub/HubProvider'
import { hubSession, type WorkerHubInfo, type WorkerInfo } from '@/hub/session'
import { useAppUi } from '@/components/app/AppUiContext'

interface RemoteHubConnectDialogProps {
  open: boolean
  /** 发起该操作的 worker(hub 清单按它拉取);null = 目录里已找不到它,只剩表单。 */
  worker: WorkerInfo | null
  onClose: () => void
}

interface FormShape {
  hubUrl: string
  hubKey: string
}

/** 提取异常文案:Error.message 优先,带 detail 的对象次之,兜底通用文案。 */
function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof Error && error.message) return error.message
  if (error && typeof error === 'object' && typeof (error as { detail?: unknown }).detail === 'string') {
    return (error as { detail: string }).detail
  }
  return fallback
}

/** 地址比较(忽略首尾空白与末尾斜杠):判断某条 hub 是否即前端当前所处的 hub。 */
function sameHubUrl(a: string, b: string): boolean {
  const norm = (s: string) => s.trim().replace(/\/+$/, '')
  return norm(a) !== '' && norm(a) === norm(b)
}

export default function RemoteHubConnectDialog({ open, worker, onClose }: RemoteHubConnectDialogProps) {
  const hub = useHub()
  const { showToast } = useAppUi()
  const [form] = Form.useForm<FormShape>()
  const [submitting, setSubmitting] = React.useState(false)
  /** applyConfig 失败信息(窗内红字);空 = 无。 */
  const [error, setError] = React.useState('')
  /** 该 worker 的 hub 连接清单(sys.hubs)。 */
  const [workerHubs, setWorkerHubs] = React.useState<WorkerHubInfo[]>([])
  const [hubsLoading, setHubsLoading] = React.useState(false)
  /** 清单读不到的原因(行内提示,不阻塞手填)。 */
  const [hubsHint, setHubsHint] = React.useState('')

  const workerId = worker?.workerId ?? ''
  const workerConnected = Boolean(worker?.connected)
  const currentHubUrl = hub.config?.hubUrl ?? ''

  // 打开瞬间预填当前配置快照(不响应式跟随 config,避免 hydrate 回填等
  // 外部变化在编辑中途覆盖用户输入)。
  React.useEffect(() => {
    if (!open) return
    setError('')
    form.setFieldsValue({
      hubUrl: hub.config?.hubUrl ?? '',
      hubKey: hub.config?.hubKey ?? '',
    })
  }, [open, form])

  // hub 清单:纯展示,弹窗打开时拉一次。worker 数据连接未建立时 RPC 无从发起
  // (离线/未启用是常态,不是错误),直接给「读不到」提示。
  React.useEffect(() => {
    if (!open) return
    setWorkerHubs([])
    setHubsHint('')
    if (!workerId || !workerConnected) {
      setHubsLoading(false)
      setHubsHint('该 worker 当前无数据连接(离线或未启用),读不到它的 hub 清单;可手动填写下方地址。')
      return
    }
    let cancelled = false
    setHubsLoading(true)
    hubSession
      .workerHubs(workerId)
      .then((list) => {
        if (!cancelled) setWorkerHubs(list)
      })
      .catch((e: unknown) => {
        if (cancelled) return
        setHubsHint('读取该 worker 的 hub 清单失败:' + errorMessage(e, 'worker 未响应'))
      })
      .finally(() => {
        if (!cancelled) setHubsLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [open, workerId, workerConnected])

  const handleSubmit = async () => {
    if (submitting) return
    const raw = await form.validateFields().catch(() => null)
    if (!raw) return
    setSubmitting(true)
    setError('')
    try {
      await hub.applyConfig({
        hubUrl: raw.hubUrl.trim(),
        hubKey: raw.hubKey.trim(),
        // hubKeyEnc 由 applyConfig 内部加密生成(入参恒空占位)。
        hubKeyEnc: '',
        workers: hub.config?.workers ?? [],
      })
      showToast('已保存并开始连接远程 hub', 'success')
      onClose()
    } catch (e) {
      setError(errorMessage(e, '保存并连接远程 hub 失败,请检查地址与 hub key'))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Modal
      title="连接到远程 Hub"
      open={open}
      okText="保存并连接"
      cancelText="取消"
      confirmLoading={submitting}
      onOk={() => void handleSubmit()}
      onCancel={() => {
        if (!submitting) onClose()
      }}
      afterClose={() => {
        setError('')
        setWorkerHubs([])
        setHubsHint('')
      }}
      destroyOnHidden={false}
      width={560}
    >
      <section style={hubSectionStyle}>
        <div style={hubSectionTitleStyle}>该 worker 已连接到的 Hub</div>
        {hubsLoading ? <p style={hubHintStyle}>读取中…</p> : null}
        {!hubsLoading && workerHubs.length > 0 ? (
          <div style={hubListStyle}>
            {workerHubs.map((item) => (
              <div key={item.url} style={hubRowStyle}>
                <span style={statusDotStyle(item.connected)} />
                <span style={hubUrlStyle} title={item.url}>{item.url}</span>
                <span style={hubTagStyle}>{item.local ? '本地' : '远程'}</span>
                {sameHubUrl(item.url, currentHubUrl) ? <span style={hubTagStyle}>当前</span> : null}
                <span
                  style={{
                    ...hubStatusStyle,
                    color: item.connected ? 'var(--accent-green)' : 'var(--text-muted)',
                  }}
                >
                  {item.connected ? '已连接' : '未连接'}
                </span>
              </div>
            ))}
          </div>
        ) : null}
        {!hubsLoading && workerHubs.length === 0 && hubsHint === '' ? (
          <p style={hubHintStyle}>该 worker 未配置任何 hub。</p>
        ) : null}
        {hubsHint ? <p style={hubHintStyle}>{hubsHint}</p> : null}
      </section>
      <Form form={form} layout="vertical" size="middle">
        <Form.Item
          name="hubUrl"
          label="远程 Hub 地址"
          rules={[{ required: true, whitespace: true, message: '请输入远程 Hub 地址' }]}
        >
          <Input
            placeholder="ws://your-server:6101/ws"
            allowClear
            spellCheck={false}
            style={{ fontFamily: 'var(--font-mono, monospace)' }}
          />
        </Form.Item>
        <Form.Item
          name="hubKey"
          label="Hub Key"
          rules={[{ required: true, whitespace: true, message: '请输入 Hub Key' }]}
        >
          <Input.Password
            placeholder="连接该 hub 的凭证(与该 hub 的 worker 进程一致)"
            autoComplete="new-password"
          />
        </Form.Item>
        {error ? <div style={errorStyle}>{error}</div> : null}
      </Form>
    </Modal>
  )
}

const hubSectionStyle: React.CSSProperties = {
  marginBottom: 14,
  paddingBottom: 10,
  borderBottom: '1px solid var(--border)',
}

const hubSectionTitleStyle: React.CSSProperties = {
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
  marginBottom: 6,
}

const hubListStyle: React.CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
}

const hubRowStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: 6,
  minWidth: 0,
}

const hubHintStyle: React.CSSProperties = {
  margin: 0,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  lineHeight: 1.6,
}

const hubUrlStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  fontFamily: 'var(--font-mono, monospace)',
  fontSize: 'var(--text-xs)',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
}

const hubTagStyle: React.CSSProperties = {
  flexShrink: 0,
  fontSize: 'var(--text-xs)',
  color: 'var(--text-muted)',
  border: '1px solid var(--border)',
  borderRadius: 999,
  padding: '0 6px',
}

const hubStatusStyle: React.CSSProperties = {
  flexShrink: 0,
  fontSize: 'var(--text-xs)',
  fontWeight: 700,
}

const statusDotStyle = (connected: boolean): React.CSSProperties => ({
  width: 8,
  height: 8,
  borderRadius: 999,
  flexShrink: 0,
  background: connected ? 'var(--accent-green)' : 'var(--text-muted)',
})

const errorStyle: React.CSSProperties = {
  color: 'var(--accent-red)',
  fontSize: 'var(--text-sm)',
  lineHeight: 1.6,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-all',
}