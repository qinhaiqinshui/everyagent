/**
 * hubSession 凭据策略测试(web 端凭据策略统一):
 *
 * - applyConfig:hubKey 内存保留明文、落盘只存 hubKeyEnc 密文(hubKey 与 apiKey 同策略);
 * - 重启流程:新会话经 ensureHydrated 从密文还原明文 hubKey,configured 不丢;
 * - 旧明文结构:ensureHydrated 自动迁移(hubKey → hubKeyEnc),连接不丢;
 * - 非安全上下文(http,无 crypto.subtle):凭据回退 b64 混淆**仍落盘**
 *  (b64: 前缀、明文不直接出现、可解回),directory.hasApiKey 正常。
 *
 * 网络层整体 mock('@every-agent/client' 的 HubClient:connect 恒成功、不触网),
 * channels/ownerKey 用真实实现(纯函数,无副作用);每条用例经 vi.resetModules +
 * 动态 import 重建 hubSession 单例(其类字段初始化即读 localStorage)。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@every-agent/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@every-agent/client')>()
  return {
    ...actual,
    HubClient: class {
      state: 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed' = 'idle'
      k = 'test-k'
      onStateChange: ((s: unknown) => void) | undefined
      onMessage: ((f: unknown) => void) | undefined
      onReconnect: (() => void) | undefined
      onError: ((f: unknown) => void) | undefined
      constructor(_opts: unknown) {}
      async connect(): Promise<void> {}
      close(): void {
        this.state = 'closed'
      }
      sub(_channel: string): void {}
    },
  }
})

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

/** 重建 hubSession 单例(可预置 localStorage 种子;storage 返回给断言用)。 */
async function freshSession(seed?: Record<string, unknown>) {
  vi.resetModules()
  const storage = new MemoryStorage()
  if (seed) storage.setItem(STORAGE_KEY, JSON.stringify(seed))
  vi.stubGlobal('localStorage', storage)
  const { hubSession } = await import('./session')
  return { hubSession, storage }
}

/** 动态取当前模块图里的 decryptSecret(resetModules 后与被测代码同一实例)。 */
async function currentDecrypt(): Promise<(enc: string) => Promise<string>> {
  const { decryptSecret } = await import('@/settings/credentialsCrypto')
  return decryptSecret
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('applyConfig:hubKey 加密落盘(与 apiKey 同策略)', () => {
  it('内存保留明文供建连,localStorage 只存 hubKeyEnc 密文', async () => {
    const { hubSession, storage } = await freshSession()
    await hubSession.applyConfig({
      hubUrl: HUB_URL,
      hubKey: 'hub-plain-key',
      hubKeyEnc: '',
      workers: [],
    })
    // 运行时内存:明文在(configured 判定/建连均可用)。
    expect(hubSession.config?.hubKey).toBe('hub-plain-key')
    expect(hubSession.configured).toBe(true)
    // 落盘:密文形态,明文不出现。
    const raw = storage.getItem(STORAGE_KEY)!
    expect(raw).not.toContain('hub-plain-key')
    const parsed = JSON.parse(raw) as Record<string, unknown>
    expect(parsed.hubKeyEnc).toMatch(/^v1:/)
    expect(parsed.hubKey).toBeUndefined()
    // 密文可解回(加密通道本身正确)。
    const decrypt = await currentDecrypt()
    expect(await decrypt(parsed.hubKeyEnc as string)).toBe('hub-plain-key')
  })

  it('非安全上下文:applyConfig 落盘 b64 混淆密文,明文不直接出现且可解回', async () => {
    const { hubSession, storage } = await freshSession()
    await withoutSubtle(async () => {
      await hubSession.applyConfig({
        hubUrl: HUB_URL,
        hubKey: 'http-plain-key',
        hubKeyEnc: '',
        workers: [],
      })
      // 运行时:明文在,configured 可用。
      expect(hubSession.config?.hubKey).toBe('http-plain-key')
      expect(hubSession.configured).toBe(true)
      // 落盘:b64 混淆(凭据仍持久化),明文不以明文形态出现。
      const parsed = JSON.parse(storage.getItem(STORAGE_KEY)!) as Record<string, unknown>
      expect(parsed.hubKeyEnc).toMatch(/^b64:/)
      expect(storage.getItem(STORAGE_KEY)!).not.toContain('http-plain-key')
      // 混淆值可解回(无需 crypto.subtle,重启后仍可用)。
      const decrypt = await currentDecrypt()
      expect(await decrypt(parsed.hubKeyEnc as string)).toBe('http-plain-key')
    })
  })
})

describe('重启(hydrate)流程:密文还原、旧明文迁移', () => {
  it('上个会话保存的密文,新会话 ensureHydrated 后明文还原、configured 保持 true', async () => {
    // 会话 1:保存配置。
    const first = await freshSession()
    await first.hubSession.applyConfig({
      hubUrl: HUB_URL,
      hubKey: 'persisted-key',
      hubKeyEnc: '',
      workers: [],
    })
    const saved = first.storage.getItem(STORAGE_KEY)!

    // 会话 2:新模块实例模拟重开页面(同一浏览器:credkey 加密密钥与密文同在 localStorage)。
    vi.resetModules()
    vi.stubGlobal('localStorage', first.storage)
    const { hubSession: s2 } = await import('./session')
    // 未 hydrate:明文未回填,configured 为 false(防止带空 key 去连 hub)。
    expect(s2.config?.hubKey).toBe('')
    expect(s2.configured).toBe(false)
    await s2.ensureHydrated()
    expect(s2.config?.hubKey).toBe('persisted-key')
    expect(s2.configured).toBe(true)
  })

  it('旧明文 hubKey 结构:启动自动迁移为密文,连接不丢', async () => {
    const { hubSession, storage } = await freshSession({
      hubUrl: HUB_URL,
      hubKey: 'legacy-plain',
      workers: [{ workerId: 'w1', apiKeyEnc: 'v1:worker-cipher', enabled: true }],
    })
    expect(hubSession.config?.hubKey).toBe('legacy-plain')
    await hubSession.ensureHydrated()
    // 运行时:明文仍在,configured 可用(连接不丢)。
    expect(hubSession.config?.hubKey).toBe('legacy-plain')
    expect(hubSession.configured).toBe(true)
    // 落盘:明文删除、密文写入,workers 原样。
    const raw = storage.getItem(STORAGE_KEY)!
    expect(raw).not.toContain('legacy-plain')
    const parsed = JSON.parse(raw) as Record<string, unknown>
    expect(parsed.hubKeyEnc).toMatch(/^v1:/)
    expect(parsed.hubKey).toBeUndefined()
    expect((parsed.workers as Array<Record<string, unknown>>)[0]?.apiKeyEnc).toBe('v1:worker-cipher')
  })
})

describe('非安全上下文:setWorkerApiKey 落盘 b64 混淆(凭据仍持久化)', () => {
  it('b64: 前缀混淆值落盘、明文不直接出现、可解回,directory 仍视为已配置', async () => {
    const { hubSession, storage } = await freshSession({
      hubUrl: HUB_URL,
      hubKeyEnc: '',
      hubKey: '',
      workers: [],
    })
    // 补上 hub 配置(hubSession.config 为公开字段,直接注入运行时明文)。
    hubSession.config = { hubUrl: HUB_URL, hubKey: 'hub-plain', hubKeyEnc: '', workers: [] }

    await withoutSubtle(async () => {
      const result = await hubSession.setWorkerApiKey('w1', 'worker-http-key')
      // 落盘:b64 混淆(凭据仍持久化),明文不以明文形态出现。
      const raw = storage.getItem(STORAGE_KEY)!
      expect(raw).not.toContain('worker-http-key')
      const enc = (JSON.parse(raw).workers as Array<{ workerId: string; apiKeyEnc: string }>)[0]
        .apiKeyEnc
      expect(enc).toMatch(/^b64:/)
      // 混淆值可解回(无需 crypto.subtle,重启后仍可用)。
      const decrypt = await currentDecrypt()
      expect(await decrypt(enc)).toBe('worker-http-key')
      // 目录显示已配置,操作结果不算失败(worker 离线属预期)。
      expect(hubSession.directory.find((w) => w.workerId === 'w1')?.hasApiKey).toBe(true)
      expect(result.ok).toBe(true)
      // 启用校验放行落盘凭据(不再报 NO_CREDENTIAL)。
      const enabled = await hubSession.setWorkerEnabled('w1', true)
      expect(enabled.error?.code).not.toBe('NO_CREDENTIAL')
    })
  })

  it('安全上下文:setWorkerApiKey 落盘为密文(回归确认,不走内存通道)', async () => {
    const { hubSession, storage } = await freshSession()
    await hubSession.applyConfig({
      hubUrl: HUB_URL,
      hubKey: 'hub-plain',
      hubKeyEnc: '',
      workers: [],
    })
    await hubSession.setWorkerApiKey('w1', 'worker-secure-key')
    const raw = storage.getItem(STORAGE_KEY)!
    expect(raw).not.toContain('worker-secure-key')
    expect((JSON.parse(raw).workers as Array<Record<string, unknown>>)[0]?.apiKeyEnc).toMatch(/^v1:/)
    // 密文可解回。
    const decrypt = await currentDecrypt()
    const enc = (JSON.parse(raw).workers as Array<{ apiKeyEnc: string }>)[0].apiKeyEnc
    expect(await decrypt(enc)).toBe('worker-secure-key')
  })
})
