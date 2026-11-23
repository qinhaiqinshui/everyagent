/**
 * 模型配置缓存（config.get 拉取，按 worker 分份）。
 *
 * 插件收不到 config.changed 领域事件（宿主只对自身 modelConfigs 服务广播），
 * 故策略=「打开编辑器时重拉」：每次调用 refreshModelConfigs 都真实打 RPC 覆盖缓存
 * （计划风险⑸的对策），不做 TTL——个人规模下拉框拉取频率无害。
 */
import { getPluginContext } from './pluginRuntime'

/** config.get 返回的模型配置条目（宿主 ModelConfigInfo 子集：插件只消费展示字段）。 */
export interface WorkflowModelConfig {
  configId: string
  provider?: string | null
  model?: string | null
  baseUrl?: string | null
  isDefault?: boolean | null
}

/** workerId → 配置列表缓存。 */
const cacheByWorker = new Map<string, WorkflowModelConfig[]>()

/** 拉取（并覆盖缓存）指定 worker 的模型配置列表。 */
export async function refreshModelConfigs(workerId: string): Promise<WorkflowModelConfig[]> {
  const sdk = getPluginContext().sdk
  const result = (await sdk.rpc(workerId, 'config.get')) as { models?: unknown } | null
  const list: WorkflowModelConfig[] = Array.isArray(result?.models)
    ? (result!.models as WorkflowModelConfig[]).filter(c => c && typeof c.configId === 'string')
    : []
  cacheByWorker.set(workerId, list)
  return list
}

/** 同步读缓存（未拉取过返回空表——调用方应先 refresh）。 */
export function cachedModelConfigs(workerId: string): WorkflowModelConfig[] {
  return cacheByWorker.get(workerId) ?? []
}

/** 下拉 option 文案：configId · model（默认配置加标记）。 */
export function modelConfigOptionLabel(c: WorkflowModelConfig): string {
  const parts = [c.configId]
  if (c.model) parts.push(c.model)
  if (c.isDefault) parts.push('默认')
  return parts.join(' · ')
}
