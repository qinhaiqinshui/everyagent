/**
 * hub 连接配置(前端本地设置,存 localStorage)。
 *
 * 双道鉴权模型:
 * - hubKey 保护 hub(连接 hub 的凭证,与 worker 进程一致);
 * - 每台 worker 一条前端连接,用该 worker 自己的 apiKey(保护 worker 数据)。
 *
 * 凭据策略(与 apiKey 统一):hubKey 是 hub 级凭据(保护整条总线),落盘只存密文
 * (hubKeyEnc;安全上下文为 'v1:' AES-GCM 密文,非安全上下文 http 回退 'b64:' 混淆,
 * 凭据仍持久化,设置页提示建议 https);明文 hubKey 仅存在于运行时内存——
 * saveConnectionConfig 按 allowlist 序列化({hubUrl, hubKeyEnc, workers}),运行时
 * 明文字段一律剥离。旧版明文结构({hubUrl, hubKey, workers})由 hydrateConnectionConfig
 * 一次性迁移(加密、重写存储、删除明文字段)。
 */
import { decryptSecret, encryptSecret } from '@/settings/credentialsCrypto'
import type { HubConnectionConfig, WorkerCredential } from './types'

export type { HubConnectionConfig }

const STORAGE_KEY = 'ea.web.connection'

/** 读 localStorage 原始 JSON;缺失/损坏/非对象返回 null。 */
function readRawConfig(): Record<string, unknown> | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object') return null
    return parsed as Record<string, unknown>
  } catch {
    return null
  }
}

/** workers 数组读取(宽松):非数组按空表处理。
 *  workers 里的 ownerKey 字段随 JSON 自然读写即可;旧配置没有 ownerKey 时按 undefined
 *  处理,session 层做 presence 指纹匹配时会对 undefined 做兼容(跳过指纹匹配)。 */
function readWorkers(parsed: Record<string, unknown>): HubConnectionConfig['workers'] {
  return Array.isArray(parsed.workers) ? (parsed.workers as HubConnectionConfig['workers']) : []
}

export function loadConnectionConfig(): HubConnectionConfig | null {
  const parsed = readRawConfig()
  if (!parsed) return null
  const hubUrl = typeof parsed.hubUrl === 'string' ? parsed.hubUrl : ''
  // 新版:{hubUrl, hubKeyEnc, workers}(密文落盘)。明文 hubKey 此处恒为空串,
  // 由 hydrateConnectionConfig 异步解密回填(建连/展示前必须先 hydrate)。
  if (hubUrl && typeof parsed.hubKeyEnc === 'string') {
    return { hubUrl, hubKey: '', hubKeyEnc: parsed.hubKeyEnc, workers: readWorkers(parsed) }
  }
  // 旧版:{hubUrl, hubKey(明文), workers} → 先带明文供本次会话使用,
  // 由 hydrateConnectionConfig 一次性迁移为密文(迁移失败不阻断,存储保持原样)。
  if (hubUrl && typeof parsed.hubKey === 'string') {
    return { hubUrl, hubKey: parsed.hubKey, hubKeyEnc: '', workers: readWorkers(parsed) }
  }
  // 更旧版迁移:{hubUrl, apiKey, workerId} → 保留 hubUrl;apiKey 是单一身份语义,
  // 无法自动对应到 worker,需用户在 Worker 列表重新填写。
  if (hubUrl && typeof parsed.apiKey === 'string') {
    return { hubUrl, hubKey: '', hubKeyEnc: '', workers: [] }
  }
  return null
}

export function saveConnectionConfig(config: HubConnectionConfig): void {
  // allowlist 序列化:只写 {hubUrl, hubKeyEnc, workers[{workerId, apiKeyEnc, enabled,
  // ownerKey?}]} —— 运行时明文 hubKey 及未来新增的内存字段一律不落盘(密文/混淆值
  // 之外的凭据形态禁止进入 localStorage)。
  const persisted = {
    hubUrl: config.hubUrl,
    hubKeyEnc: config.hubKeyEnc,
    workers: config.workers.map((w) => {
      const item: Pick<WorkerCredential, 'workerId' | 'apiKeyEnc' | 'enabled'> & {
        ownerKey?: string
      } = { workerId: w.workerId, apiKeyEnc: w.apiKeyEnc, enabled: w.enabled }
      if (w.ownerKey !== undefined) item.ownerKey = w.ownerKey
      return item
    }),
  }
  localStorage.setItem(STORAGE_KEY, JSON.stringify(persisted))
}

/**
 * 凭据异步初始化(幂等;应用启动时调用,对应旧结构的「一次性迁移」):
 *
 * 1. 旧明文结构(hubKey 字段存在)→ 加密为 hubKeyEnc、重写存储(明文字段随之删除,
 *    已有 workers[].apiKeyEnc 结构不动);非安全上下文(http)加密回退 b64 混淆,
 *    迁移同样完成(凭据仍持久化);
 * 2. 新结构 → 解密 hubKeyEnc 回填运行时明文 hubKey;解密失败(本地加密密钥变更等)
 *    置空明文并告警,用户在设置页重新输入,不抛错阻断启动。
 *
 * 返回运行时配置(含明文 hubKey);无配置/无 hubUrl 返回 null。
 */
export async function hydrateConnectionConfig(): Promise<HubConnectionConfig | null> {
  const parsed = readRawConfig()
  if (!parsed) return null
  const hubUrl = typeof parsed.hubUrl === 'string' ? parsed.hubUrl : ''
  if (!hubUrl) return null
  const workers = readWorkers(parsed)

  // 旧明文结构 → 一次性迁移为密文(密文已存在时忽略残留的明文字段,下次保存自然清除)。
  if (
    typeof parsed.hubKey === 'string' &&
    parsed.hubKey &&
    typeof parsed.hubKeyEnc !== 'string'
  ) {
    // 非安全上下文回退 b64 混淆,迁移同样完成落盘(凭据仍持久化)。
    const enc = await encryptSecret(parsed.hubKey)
    const migrated: HubConnectionConfig = {
      hubUrl,
      hubKey: parsed.hubKey,
      hubKeyEnc: enc,
      workers,
    }
    saveConnectionConfig(migrated)
    return migrated
  }

  const hubKeyEnc = typeof parsed.hubKeyEnc === 'string' ? parsed.hubKeyEnc : ''
  let hubKey = ''
  if (hubKeyEnc) {
    try {
      hubKey = await decryptSecret(hubKeyEnc)
    } catch (error) {
      console.warn(
        '[hub] hubKeyEnc 解密失败(本地加密密钥可能已变更,请在设置页重新输入 hub key):',
        error,
      )
    }
  }
  return { hubUrl, hubKey, hubKeyEnc, workers }
}
