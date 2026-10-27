/**
 * 「添加 / 编辑模型配置」对话框(独立组件,antd Modal + Form;双模式)。
 *
 * - 厂家下拉内置常见厂商(DeepSeek / 智谱 / OpenAI / 阿里百炼 / Kimi),选中后自动带出
 *   baseUrl,用户只需填 id、模型名、apiKey;
 * - 选「其他」:baseUrl / fullUrl / 厂家名由用户自行填写;
 * - 接口协议固定「OpenAI 兼容」(当前仅支持 OpenAI 格式接口);
 * - id 必填且不可重复(可中文);baseUrl 与 fullUrl 二选一(填了一个,另一个自动禁用;
 *   都不填校验不过);
 * - 编辑模式(editing 非空):id 只读(配置标识不可改,改名=删旧增新);apiKey 留空 =
 *   保持原 key 不变(明文不出 worker,前端仅见掩码,不回填);
 * - 提交结果由调用方(ModelConfigSection)经 config.model.add / config.model.update
 *   落盘 application-worker.yaml。
 */
import React from 'react'
import { Form, Input, Modal, Select } from 'antd'
import type { ModelConfigInfo } from '@/hub/modelConfigs'
import { PROVIDER_PRESETS, PROVIDER_OTHER_VALUE } from './providers'

export interface ModelConfigFormValues {
  configId: string
  provider: string
  baseUrl?: string
  fullUrl?: string
  model: string
  /** 编辑模式下留空 = 保持原 apiKey 不变。 */
  apiKey: string
}

interface ModelConfigFormDialogProps {
  open: boolean
  /** 编辑模式:被编辑的原始配置;null/缺省 = 添加模式。 */
  editing?: ModelConfigInfo | null
  /** 已有 configId 列表(查重提示;后端仍做最终校验)。 */
  existingIds: string[]
  submitting: boolean
  onCancel: () => void
  onSubmit: (values: ModelConfigFormValues) => Promise<void>
}

interface FormShape {
  providerPreset: string
  providerName?: string
  configId: string
  model: string
  apiKey: string
  baseUrl?: string
  fullUrl?: string
}

const providerOptions = [
  ...PROVIDER_PRESETS.map((p) => ({ value: p.value, label: p.label })),
  { value: PROVIDER_OTHER_VALUE, label: '其他' },
]

export default function ModelConfigFormDialog({
  open,
  editing,
  existingIds,
  submitting,
  onCancel,
  onSubmit,
}: ModelConfigFormDialogProps) {
  const [form] = Form.useForm<FormShape>()
  const providerPreset = Form.useWatch('providerPreset', form)
  const baseUrlValue = (Form.useWatch('baseUrl', form) ?? '').trim()
  const fullUrlValue = (Form.useWatch('fullUrl', form) ?? '').trim()
  const isOther = providerPreset === PROVIDER_OTHER_VALUE
  const isEdit = !!editing

  // 打开时按模式初始化表单:编辑模式回填已有配置(apiKey 不回填掩码),添加模式回到预设初值。
  // afterClose 的 resetFields 只回 initialValues,这里必须显式覆盖,避免「编辑 A → 关闭 → 编辑 B」串值。
  React.useEffect(() => {
    if (!open) return
    if (editing) {
      const preset = PROVIDER_PRESETS.find((p) => p.label === editing.provider)
      form.setFieldsValue({
        providerPreset: preset ? preset.value : PROVIDER_OTHER_VALUE,
        providerName: preset
          ? undefined
          : (editing.provider && editing.provider !== '其他' ? editing.provider : undefined),
        configId: editing.configId,
        model: editing.model,
        apiKey: '',
        baseUrl: editing.baseUrl ?? '',
        fullUrl: editing.fullUrl ?? '',
      })
    } else {
      form.resetFields()
    }
  }, [open, editing, form])

  // 切换厂家预设:自动带出 baseUrl(fullUrl 保持空,两者二选一);切「其他」则清空由用户自填。
  const handleProviderChange = (value: string) => {
    const preset = PROVIDER_PRESETS.find((p) => p.value === value)
    if (preset) {
      form.setFieldsValue({ baseUrl: preset.baseUrl, fullUrl: '', providerName: undefined })
    } else {
      form.setFieldsValue({ baseUrl: '', fullUrl: '' })
    }
  }

  const currentPreset = PROVIDER_PRESETS.find((p) => p.value === providerPreset)

  /** baseUrl / fullUrl 二选一校验(挂在两个字段上,互为 dependency 联动重校验)。 */
  const validateUrlPair = () => {
    if (baseUrlValue && fullUrlValue) {
      return Promise.reject(new Error('baseUrl 与 fullUrl 只能填一项'))
    }
    if (!baseUrlValue && !fullUrlValue) {
      return Promise.reject(new Error('baseUrl 与 fullUrl 必须填一项(二选一)'))
    }
    return Promise.resolve()
  }

  const handleOk = async () => {
    const raw = await form.validateFields()
    const preset = PROVIDER_PRESETS.find((p) => p.value === raw.providerPreset)
    const provider = preset ? preset.label : (raw.providerName?.trim() || '其他')
    await onSubmit({
      configId: (isEdit ? editing.configId : raw.configId).trim(),
      provider,
      baseUrl: raw.baseUrl?.trim() || undefined,
      fullUrl: raw.fullUrl?.trim() || undefined,
      model: raw.model.trim(),
      apiKey: raw.apiKey.trim(),
    })
  }

  return (
    <Modal
      title={isEdit ? '编辑模型配置' : '添加模型配置'}
      open={open}
      okText={isEdit ? '保存' : '添加'}
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
          fullUrl: '',
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
          extra={currentPreset ? '选中后自动填写 baseUrl' : '「其他」需自行填写 baseUrl 或 fullUrl(二选一)'}
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
          label={isEdit ? 'id(配置标识,不可修改)' : 'id(配置标识,可中文,不可重复)'}
          rules={[
            { required: true, whitespace: true, message: '请输入 id' },
            {
              validator: (_, value: string) => {
                if (isEdit) return Promise.resolve()
                return value && existingIds.includes(value.trim())
                  ? Promise.reject(new Error('id 已存在:' + value.trim()))
                  : Promise.resolve()
              },
            },
          ]}
        >
          <Input
            placeholder="如:DeepSeek-主力、deepseek-chat"
            allowClear={!isEdit}
            disabled={isEdit}
          />
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
          label={isEdit ? 'apiKey(留空保持不变)' : 'apiKey'}
          rules={isEdit ? [] : [{ required: true, whitespace: true, message: '请输入 apiKey' }]}
          extra={isEdit && editing?.apiKey ? '当前:' + editing.apiKey + '(掩码,明文不出 worker)' : undefined}
        >
          <Input.Password
            placeholder={isEdit ? '留空表示保持原 apiKey 不变' : 'apiKey(只存 worker,不出本机)'}
            autoComplete="new-password"
          />
        </Form.Item>
        <Form.Item
          name="baseUrl"
          label="baseUrl"
          tooltip="API 基础地址(如 https://api.deepseek.com);与 fullUrl 二选一,填了 fullUrl 后本项禁用"
          dependencies={['fullUrl']}
          rules={[{ validator: validateUrlPair }]}
        >
          <Input
            placeholder={fullUrlValue ? '已填 fullUrl,清空后方可填 baseUrl' : 'https://…'}
            allowClear
            disabled={!!fullUrlValue}
          />
        </Form.Item>
        <Form.Item
          name="fullUrl"
          label="fullUrl(完整端点 URL)"
          tooltip="完整端点 URL(如 https://api.deepseek.com/chat/completions);与 baseUrl 二选一,填了 baseUrl 后本项禁用"
          dependencies={['baseUrl']}
          rules={[{ validator: validateUrlPair }]}
        >
          <Input
            placeholder={baseUrlValue ? '已填 baseUrl,清空后方可填 fullUrl' : 'https://…/chat/completions'}
            allowClear
            disabled={!!baseUrlValue}
          />
        </Form.Item>
        <Form.Item label="接口协议">
          <Input value="OpenAI 兼容(当前仅支持 OpenAI 格式接口)" disabled />
        </Form.Item>
      </Form>
    </Modal>
  )
}
