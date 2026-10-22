/**
 * hub 连接配置凭据策略测试(web 端凭据策略统一):
 *
 * - 落盘 allowlist:saveConnectionConfig 剥离运行时明文 hubKey,localStorage 只存
 *   {hubUrl, hubKeyEnc, workers} 结构(hubKey 为密文);
 * - hubKeyEnc 加密保存/读取 roundtrip:load 只有密文,hydrate 解密回填运行时明文;
 * - 旧明文结构({hubUrl, hubKey, workers})一次性自动迁移:加密、重写存储、删除明文
 *   字段,已有 workers[].apiKeyEnc 结构不动,连接配置不丢(hubUrl/workers 保留);
 * - 非安全上下文:迁移同样完成 —— 落盘 b64 混淆密文(凭据仍持久化)。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { decryptSecret, encryptSecret } from '@/settings/credentialsCrypto'
import {
  hydrateConnectionConfig,
  loadConnectionConfig,
  saveConnectionConfig,
} from './connection'

const STORAGE_KEY = 'ea.web.connection'
const HUB_URL = 'ws://192.168.1.10:6101/ws'

/** 内存 localStorage stub(仅测试用)。 */
class MemoryStorage {
  private map = new Map<string, string>()
  getItem(key: string): string | null {
    return this.map.has(key) ? this.map.get(key)! : null
  }
  setItem(key: string, value: string): void {
    this.map.set(key, String(value))
  }
  removeItem(key: string): void {
    this.map.delete(key)
  }
  clear(): void {
    this.map.clear()
  }
}

/** 在「无 crypto.subtle」(非安全上下文 http)下执行 fn,结束后恢复。 */
async function withoutSubtle<T>(fn: () => Promise<T>): Promise<T> {
  const realCrypto = globalThis.crypto
  Object.defineProperty(globalThis, 'crypto', {
    configurable: true,
    value: { getRandomValues: realCrypto.getRandomValues.bind(realCrypto) },
  })
  try {
    return await fn()
  } finally {
    Object.defineProperty(globalThis, 'crypto', {
      configurable: true,
      get: () => realCrypto,
    })
  }
}

let storage: MemoryStorage

beforeEach(() => {
  storage = new MemoryStorage()
  vi.stubGlobal('localStorage', storage)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

/** 读取落盘原始 JSON(测试断言用)。 */
function readStored(): Record<string, unknown> {
  const raw = storage.getItem(STORAGE_KEY)
  expect(raw).not.toBeNull()
  return JSON.parse(raw!) as Record<string, unknown>
}

describe('落盘 allowlist:明文 hubKey 永不进入 localStorage', () => {
  it('saveConnectionConfig 只写 {hubUrl, hubKeyEnc, workers},明文 hubKey 与内存杂字段被剥离', () => {
    saveConnectionConfig({
      hubUrl: HUB_URL,
      hubKey: 'plain-hub-key',
      hubKeyEnc: 'v1:fake-cipher',
      workers: [
        { workerId: 'w1', apiKeyEnc: 'v1:worker-cipher', enabled: true, ownerKey: 'a'.repeat(64) },
      ],
    })
    const raw = storage.getItem(STORAGE_KEY)!
    expect(raw).not.toContain('plain-hub-key')
    const parsed = readStored()
    expect(Object.keys(parsed).sort()).toEqual(['hubKeyEnc', 'hubUrl', 'workers'])
    expect(parsed.hubKeyEnc).toBe('v1:fake-cipher')
    expect(parsed.hubKey).toBeUndefined()
    expect((parsed.workers as Array<Record<string, unknown>>)[0]).toEqual({
      workerId: 'w1',
      apiKeyEnc: 'v1:worker-cipher',
      enabled: true,
      ownerKey: 'a'.repeat(64),
    })
  })

  it('无 ownerKey 的 worker 凭证落盘不带 ownerKey 键(结构不变)', () => {
    saveConnectionConfig({
      hubUrl: HUB_URL,
      hubKey: 'k',
      hubKeyEnc: 'v1:c',
      workers: [{ workerId: 'w2', apiKeyEnc: '', enabled: false }],
    })
    expect((readStored().workers as Array<Record<string, unknown>>)[0]).toEqual({
      workerId: 'w2',
      apiKeyEnc: '',
      enabled: false,
    })
  })
})

describe('hubKeyEnc 加密保存/读取 roundtrip', () => {
  it('保存(加密)→ 新页面 load(只有密文)→ hydrate(解密回填运行时明文)', async () => {
    // 保存路径:明文经 encryptSecret 加密为 hubKeyEnc 后落盘(与 applyConfig 同策略)。
    const enc = await encryptSecret('roundtrip-hub-key')
    saveConnectionConfig({ hubUrl: HUB_URL, hubKey: 'roundtrip-hub-key', hubKeyEnc: enc, workers: [] })
    expect(storage.getItem(STORAGE_KEY)!).not.toContain('roundtrip-hub-key')

    // 新页面载入:同步 load 不做解密,明文为空、密文完整。
    const loaded = loadConnectionConfig()!
    expect(loaded.hubUrl).toBe(HUB_URL)
    expect(loaded.hubKey).toBe('')
    expect(loaded.hubKeyEnc).toBe(enc)

    // hydrate:解密回填运行时明文(建连可用的形态)。
    const hydrated = (await hydrateConnectionConfig())!
    expect(hydrated.hubKey).toBe('roundtrip-hub-key')
    expect(hydrated.hubKeyEnc).toBe(enc)
  })
})

describe('旧明文 hubKey 自动迁移(一次性)', () => {
  /** 预置旧版明文结构(迁移前用户数据)。 */
  function seedLegacy(): void {
    storage.setItem(STORAGE_KEY, JSON.stringify({
      hubUrl: HUB_URL,
      hubKey: 'legacy-plain-key',
      workers: [
        { workerId: 'w1', apiKeyEnc: 'v1:existing-worker-cipher', enabled: true },
      ],
    }))
  }

  it('hydrate 加密重写存储:明文字段删除、hubKeyEnc 为可解密密文、连接配置不丢', async () => {
    seedLegacy()
    // load 旧结构:明文先带出供本次会话使用。
    expect(loadConnectionConfig()?.hubKey).toBe('legacy-plain-key')

    const hydrated = (await hydrateConnectionConfig())!
    expect(hydrated.hubKey).toBe('legacy-plain-key')
    expect(hydrated.hubKeyEnc).toMatch(/^v1:/)
    expect(await decryptSecret(hydrated.hubKeyEnc)).toBe('legacy-plain-key')

    // 存储已重写:明文删除、已有 apiKeyEnc 结构不动、hubUrl/workers 保留(连接不丢)。
    const raw = storage.getItem(STORAGE_KEY)!
    expect(raw).not.toContain('legacy-plain-key')
    const parsed = readStored()
    expect(parsed.hubKey).toBeUndefined()
    expect(parsed.hubUrl).toBe(HUB_URL)
    expect((parsed.workers as Array<Record<string, unknown>>)[0]?.apiKeyEnc)
      .toBe('v1:existing-worker-cipher')
  })

  it('迁移后再次 hydrate 幂等(已是新结构,直接解密)', async () => {
    seedLegacy()
    await hydrateConnectionConfig()
    const again = (await hydrateConnectionConfig())!
    expect(again.hubKey).toBe('legacy-plain-key')
    expect(again.hubKeyEnc).toMatch(/^v1:/)
  })

  it('非安全上下文:迁移同样完成 —— 落盘 b64 混淆密文,明文字段删除', async () => {
    seedLegacy()
    await withoutSubtle(async () => {
      const hydrated = (await hydrateConnectionConfig())!
      // 迁移完成:明文回填运行时,密文为 b64 混淆(凭据仍持久化)且可解回。
      expect(hydrated.hubKey).toBe('legacy-plain-key')
      expect(hydrated.hubKeyEnc).toMatch(/^b64:/)
      expect(await decryptSecret(hydrated.hubKeyEnc)).toBe('legacy-plain-key')
      // 存储已重写:明文字段删除,hubUrl/workers 保留(连接不丢)。
      const raw = storage.getItem(STORAGE_KEY)!
      expect(raw).not.toContain('legacy-plain-key')
      const parsed = readStored()
      expect(parsed.hubKey).toBeUndefined()
      expect(parsed.hubUrl).toBe(HUB_URL)
    })
  })
})

describe('其余读取分支回归', () => {
  it('更旧版 {hubUrl, apiKey, workerId}:保留 hubUrl,凭据清空待用户重填', () => {
    storage.setItem(STORAGE_KEY, JSON.stringify({ hubUrl: HUB_URL, apiKey: 'old-single-key', workerId: 'w1' }))
    const loaded = loadConnectionConfig()!
    expect(loaded.hubUrl).toBe(HUB_URL)
    expect(loaded.hubKey).toBe('')
    expect(loaded.hubKeyEnc).toBe('')
    expect(loaded.workers).toEqual([])
  })

  it('无配置/损坏 JSON 返回 null,不抛错', async () => {
    expect(loadConnectionConfig()).toBeNull()
    expect(await hydrateConnectionConfig()).toBeNull()
    storage.setItem(STORAGE_KEY, '{not-json')
    expect(loadConnectionConfig()).toBeNull()
    expect(await hydrateConnectionConfig()).toBeNull()
  })
})
