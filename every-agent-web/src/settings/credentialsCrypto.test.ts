/**
 * credentialsCrypto 行为护栏(计划步骤 12「web 端凭据策略统一」,审计 M-9):
 *
 * - 安全上下文:encryptSecret/decryptSecret AES-GCM roundtrip('v1:' 前缀,密文不含明文,
 *   随机 IV 保证同明文两次加密结果不同);
 * - 非安全上下文(无 crypto.subtle):encryptSecret 返回 null —— 不再回退 'b64:' 明文落盘;
 * - 解密侧保留历史遗留 'b64:' 值的读取兼容(老用户数据还能用,且不需要 crypto.subtle)。
 *
 * 环境说明:Node ≥ 20 自带 globalThis.crypto(WebCrypto);vitest 环境为 node,无
 * localStorage,用内存 stub(密钥与密文同存一处,与浏览器行为一致)。
 * 非安全上下文通过整体替换 globalThis.crypto(去掉 subtle)模拟。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  decryptSecret,
  encryptSecret,
  isSecretCryptoAvailable,
} from './credentialsCrypto'

const KEY_STORAGE = 'ea.web.credkey'

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
    // 保留 getRandomValues 等非加密能力,仅去掉 subtle(模拟 http 局域网 IP 场景)。
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

beforeEach(() => {
  vi.stubGlobal('localStorage', new MemoryStorage())
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('安全上下文:AES-GCM 加解密 roundtrip', () => {
  it('Node 环境自带 WebCrypto(前置:测试环境的 subtle 可用)', () => {
    expect(isSecretCryptoAvailable()).toBe(true)
  })

  it('encrypt → decrypt 还原明文;密文带 v1: 前缀且不含明文', async () => {
    const enc = await encryptSecret('hub-secret-key-01')
    expect(enc).toMatch(/^v1:/)
    expect(enc).not.toContain('hub-secret-key-01')
    expect(await decryptSecret(enc!)).toBe('hub-secret-key-01')
  })

  it('随机 IV:同一明文两次加密产生不同密文(且都能解回)', async () => {
    const a = await encryptSecret('same-plain')
    const b = await encryptSecret('same-plain')
    expect(a).not.toBe(b)
    expect(await decryptSecret(a!)).toBe('same-plain')
    expect(await decryptSecret(b!)).toBe('same-plain')
  })

  it('空串原样返回(空串不算凭据)', async () => {
    expect(await encryptSecret('')).toBe('')
    expect(await decryptSecret('')).toBe('')
  })

  it('加密密钥存于独立 localStorage 键,与密文分离', async () => {
    await encryptSecret('separated-key')
    const keyRaw = (globalThis as unknown as { localStorage: MemoryStorage }).localStorage.getItem(KEY_STORAGE)
    expect(keyRaw).not.toBeNull()
    expect(keyRaw).not.toContain('separated-key')
  })
})

describe('非安全上下文(无 crypto.subtle):加密拒绝,不再产出 b64 明文', () => {
  it('encryptSecret 返回 null(调用方据此不持久化凭据)', async () => {
    await withoutSubtle(async () => {
      expect(isSecretCryptoAvailable()).toBe(false)
      expect(await encryptSecret('plain-http-key')).toBeNull()
    })
  })

  it('空串仍原样返回(空值不触发加密能力判定)', async () => {
    await withoutSubtle(async () => {
      expect(await encryptSecret('')).toBe('')
    })
  })
})

describe('历史遗留 b64 值:解密侧读取兼容(新写入已禁止)', () => {
  it('解密旧版写入的 b64: 值无需 crypto.subtle(非安全上下文也能用老数据)', async () => {
    await withoutSubtle(async () => {
      const legacy = 'b64:' + btoa('legacy-worker-key')
      expect(await decryptSecret(legacy)).toBe('legacy-worker-key')
    })
  })

  it('安全上下文同样能读旧 b64 值;密文格式非法仍抛错', async () => {
    expect(await decryptSecret('b64:' + btoa('old-key'))).toBe('old-key')
    await expect(decryptSecret('garbage-not-a-format')).rejects.toThrow('无法识别的凭证格式')
  })
})
