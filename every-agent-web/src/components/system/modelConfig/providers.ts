/**
 * 内置模型厂家预设(设置页「添加模型配置」对话框):
 * 选中预设后自动带出 baseUrl / fullUrl,用户只需填 id、模型名与 apiKey;
 * 选「其他」则 baseUrl / fullUrl 由用户自行填写。接口协议目前均为 OpenAI 兼容格式。
 */

export interface ProviderPreset {
  /** 预设 id(表单内部值;落盘的 provider 字段存 label)。 */
  value: string
  /** 厂家展示名(同时写入配置 provider 字段)。 */
  label: string
  baseUrl: string
  fullUrl: string
  /** 模型名输入框占位提示(常见模型示例)。 */
  modelPlaceholder: string
}

export const PROVIDER_PRESETS: ReadonlyArray<ProviderPreset> = [
  {
    value: 'deepseek',
    label: 'DeepSeek',
    baseUrl: 'https://api.deepseek.com',
    fullUrl: 'https://api.deepseek.com/chat/completions',
    modelPlaceholder: 'deepseek-chat',
  },
  {
    value: 'zhipu',
    label: '智谱',
    baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
    fullUrl: 'https://open.bigmodel.cn/api/paas/v4/chat/completions',
    modelPlaceholder: 'glm-4.5',
  },
  {
    value: 'openai',
    label: 'OpenAI',
    baseUrl: 'https://api.openai.com/v1',
    fullUrl: 'https://api.openai.com/v1/chat/completions',
    modelPlaceholder: 'gpt-4o',
  },
  {
    value: 'dashscope',
    label: '阿里百炼',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    fullUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions',
    modelPlaceholder: 'qwen-plus',
  },
  {
    value: 'moonshot',
    label: 'Kimi',
    baseUrl: 'https://api.moonshot.cn/v1',
    fullUrl: 'https://api.moonshot.cn/v1/chat/completions',
    modelPlaceholder: 'kimi-k2-0905-preview',
  },
]

/** 下拉框「其他」选项值:用户自行填写 baseUrl / fullUrl / 厂家名。 */
export const PROVIDER_OTHER_VALUE = '__other__'
