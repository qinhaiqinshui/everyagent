/**
 * 「添加模型配置」对话框(独立组件,antd Modal + Form)。
 *
 * - 厂家下拉内置常见厂商(DeepSeek / 智谱 / OpenAI / 阿里百炼 / Kimi),选中后自动带出
 *   baseUrl / fullUrl,用户只需填 id、模型名、apiKey;
 * - 选「其他」:baseUrl / fullUrl / 厂家名由用户自行填写;
 * - 接口协议固定「OpenAI 兼容」(当前仅支持 OpenAI 格式接口);
 * - id 必填且不可重复(可中文);baseUrl 与 fullUrl 至少填一项;
 * - 提交结果由调用方(ModelConfigSection)经 config.model.add 落盘 application-worker.yaml。
 */
import React from 'react'
import { Form, Input, Modal, Select } from 'antd'
import { PROVIDER_PRESETS, PROVIDER_OTHER_VALUE } from './providers'

export interface AddModelConfigValues {
  configId: string
  provider: string
  baseUrl?: string
  fullUrl?: string
  model: string
  apiKey: string
}

interface AddModelConfigDialogProps {
  open: boolean
  /** 已有 configId 列表(查重提示;后端仍做最终校验)。 */
  existingIds: string[]
  submitting: boolean
  onCancel: () => void
  onSubmit: (values: AddModelConfigValues) => Promise<void>
}

const providerOptions = [
  ...PROVIDER_PRESETS.map((p) => ({ value: p.value, label: p.label })),
  { value: PROVIDER_OTHER_VALUE, label: '其他' },
]

export default function AddModelConfigDialog({
  open,
  existingIds,
  submitting,
  onCancel,
  onSubmit,
}: AddModelConfigDialogProps) {
  const [form] = Form.useForm<{
    providerPreset: string
    providerName?: string
    configId: string
    model: string
    apiKey: string
    baseUrl?: string
    fullUrl?: string
  }>()
  const providerPreset = Form.useWatch('providerPreset', form)
  const isOther = providerPreset === PROVIDER_OTHER_VALUE

  // 切换厂家预设:自动带出 baseUrl / fullUrl;切「其他」则清空由用户自填。
  const handleProviderChange = (value: string) => {
    const preset = PROVIDER_PRESETS.find((p) => p.value === value)
    if (preset) {
      form.setFieldsValue({ baseUrl: preset.baseUrl, fullUrl: preset.fullUrl, providerName: undefined })
    } else {
      form.setFieldsValue({ baseUrl: '', fullUrl: '' })
    }
  }

  const currentPreset = PROVIDER_PRESETS.find((p) => p.value === providerPreset)

  const handleOk = async () => {
    const raw = await form.validateFields()
    const preset = PROVIDER_PRESETS.find((p) => p.value === raw.providerPreset)
    const provider = preset ? preset.label : (raw.providerName?.trim() || '其他')
    const baseUrl = raw.baseUrl?.trim() || ''
    const fullUrl = raw.fullUrl?.trim() || ''
    if (!baseUrl && !fullUrl) {
      form.setFields([
        { name: 'baseUrl', errors: ['baseUrl 与 fullUrl 至少填一项'] },
        { name: 'fullUrl', errors: ['baseUrl 与 fullUrl 至少填一项'] },
      ])
      return
    }
    await onSubmit({
      configId: raw.configId.trim(),
      provider,
      baseUrl: baseUrl || undefined,
      fullUrl: fullUrl || undefined,
      model: raw.model.trim(),
      apiKey: raw.apiKey.trim(),
    })
  }

  return (
    <Modal
      title="添加模型配置"
      open={open}
      okText="添加"
      cancelText="取消"
      confirmLoading={submitting}
      onOk={() => void handleOk()}
      onCancel={() => {
        if (!submitting) onCancel()
      }}
      afterClose={() => form.resetFields()}
      destroyOnHidden={false}
      width={520}
    >
      <Form
        form={form}
        layout="vertical"
        size="middle"
        initialValues={{
          providerPreset: PROVIDER_PRESETS[0].value,
          baseUrl: PROVIDER_PRESETS[0].baseUrl,
          fullUrl: PROVIDER_PRESETS[0].fullUrl,
        }}
        onValuesChange={(changed) => {
          if (Object.prototype.hasOwnProperty.call(changed, 'providerPreset')) {
            handleProviderChange(changed.providerPreset as string)
          }
        }}
      >
        <Form.Item
          name="providerPreset"
          label="模型厂家"
          rules={[{ required: true, message: '请选择模型厂家' }]}
          extra={currentPreset ? '选中后自动填写 baseUrl / fullUrl' : '「其他」需自行填写 baseUrl 或 fullUrl'}
        >
          <Select options={providerOptions} />
        </Form.Item>
        {isOther && (
          <Form.Item name="providerName" label="厂家名称(可选)">
            <Input placeholder="自定义厂家名,缺省为「其他」" allowClear />
          </Form.Item>
        )}
        <Form.Item
          name="configId"
          label="id(配置标识,可中文,不可重复)"
          rules={[
            { required: true, whitespace: true, message: '请输入 id' },
            {
              validator: (_, value: string) =>
                value && existingIds.includes(value.trim())
                  ? Promise.reject(new Error('id 已存在:' + value.trim()))
                  : Promise.resolve(),
            },
          ]}
        >
          <Input placeholder="如:DeepSeek-主力、deepseek-chat" allowClear />
        </Form.Item>
        <Form.Item
          name="model"
          label="模型名"
          rules={[{ required: true, whitespace: true, message: '请输入模型名' }]}
        >
          <Input placeholder={currentPreset?.modelPlaceholder ?? '模型名(如 deepseek-chat)'} allowClear />
        </Form.Item>
        <Form.Item
          name="apiKey"
          label="apiKey"
          rules={[{ required: true, whitespace: true, message: '请输入 apiKey' }]}
        >
          <Input.Password placeholder="apiKey(只存 worker,不出本机)" autoComplete="new-password" />
        </Form.Item>
        <Form.Item
          name="baseUrl"
          label="baseUrl"
          tooltip="API 基础地址;fullUrl 非空时优先使用 fullUrl"
        >
          <Input placeholder="https://…" allowClear />
        </Form.Item>
        <Form.Item
          name="fullUrl"
          label="fullUrl(完整端点 URL)"
          tooltip="非空时优先于 baseUrl"
        >
          <Input placeholder="https://…/chat/completions" allowClear />
        </Form.Item>
        <Form.Item label="接口协议">
          <Input value="OpenAI 兼容(当前仅支持 OpenAI 格式接口)" disabled />
        </Form.Item>
      </Form>
    </Modal>
  )
}
