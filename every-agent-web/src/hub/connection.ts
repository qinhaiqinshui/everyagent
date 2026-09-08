/**
 * hub 连接配置(前端本地设置,存 localStorage)。
 *
 * 双道鉴权模型:
 * - hubKey 保护 hub(连接 hub 的凭证,与 worker 进程一致);
 * - 每台 worker 一条前端连接,用该 worker 自己的 apiKey(保护 worker 数据)。
 * 因此配置 = {hubUrl, hubKey} + workers[]({workerId, apiKeyEnc(加密), enabled})。
 */
import type { HubConnectionConfig } from './types'

export type { HubConnectionConfig }

const STORAGE_KEY = 'ea.web.connection'

export function loadConnectionConfig(): HubConnectionConfig | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Record<string, unknown>
    const hubUrl = typeof parsed.hubUrl === 'string' ? parsed.hubUrl : ''
    // 新版:{hubUrl, hubKey, workers:[]}
    // workers 里的 ownerKey 字段随 JSON 自然读写即可;旧配置没有 ownerKey 时按 undefined 处理,
    // session 层做 presence 指纹匹配时会对 undefined 做兼容(跳过指纹匹配)。
    if (hubUrl && typeof parsed.hubKey === 'string') {
      return {
        hubUrl,
        hubKey: parsed.hubKey,
        workers: Array.isArray(parsed.workers)
          ? (parsed.workers as HubConnectionConfig['workers'])
          : [],
      }
    }
    // 旧版迁移:{hubUrl, apiKey, workerId} → 保留 hubUrl;apiKey 是单一身份语义,
    // 无法自动对应到 worker,需用户在 Worker 列表重新填写。
    if (hubUrl && typeof parsed.apiKey === 'string') {
      return { hubUrl, hubKey: '', workers: [] }
    }
    return null
  } catch {
    return null
  }
}

export function saveConnectionConfig(config: HubConnectionConfig): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(config))
}