/**
 * 「连接到远程 Hub」对话框(antd Modal + Form,与 ModelConfigFormDialog 同款选型)。
 *
 * 入口在设置页 Worker 列表每行的 ⋮ 更多菜单——语义:把前端从「当前配置的 hub」
 * (通常为桌面版本机自动拉起)切换并连接到远程部署的 hub(如服务器上的)。
 *
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
import { useAppUi } from '@/components/app/AppUiContext'

interface RemoteHubConnectDialogProps {
  open: boolean
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

export default function RemoteHubConnectDialog({ open, onClose }: RemoteHubConnectDialogProps) {
  const hub = useHub()
  const { showToast } = useAppUi()
  const [form] = Form.useForm<FormShape>()
  const [submitting, setSubmitting] = React.useState(false)
  /** applyConfig 失败信息(窗内红字);空 = 无。 */
  const [error, setError] = React.useState('')

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
      afterClose={() => setError('')}
      destroyOnHidden={false}
      width={520}
    >
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

const errorStyle: React.CSSProperties = {
  color: 'var(--accent-red)',
  fontSize: 'var(--text-sm)',
  lineHeight: 1.6,
  whiteSpace: 'pre-wrap',
  wordBreak: 'break-all',
}
