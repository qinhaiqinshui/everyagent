/**
 * 前端本地凭证加密(防磁盘明文泄露,混淆级):
 *
 * - WebCrypto AES-GCM:随机 256 位密钥 + 12 字节 IV,密文与 IV 以 base64 合并存储;
 * - 密钥单独存 localStorage('ea.web.credkey'),与密文分离(同源混淆级,非抗本地攻击);
 * - 非安全上下文(http:// 局域网 IP)下 crypto.subtle 不可用,回退为 base64 编码
 *   (加前缀 'b64:' 标记),保证功能可用但强度退化。
 *
 * 应用场景:设置页输入的每一台 worker 的 apiKey,经 encryptSecret 加密后存进
 * HubConnectionConfig.workers[].apiKeyEnc;读取时 decryptSecret 还原为明文用于建连。
 */
const KEY_STORAGE = 'ea.web.credkey'

function b64encode(bytes: Uint8Array): string {
  let bin = ''
  for (const b of bytes) {
    bin += String.fromCharCode(b)
  }
  return btoa(bin)
}

function b64decode(s: string): Uint8Array {
  const bin = atob(s)
  const bytes = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) {
    bytes[i] = bin.charCodeAt(i)
  }
  return bytes
}

/** Uint8Array → WebCrypto BufferSource(处理 TS 泛型差异)。 */
function asBufferSource(data: Uint8Array): ArrayBuffer {
  return data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength) as ArrayBuffer
}

async function getOrCreateKey(): Promise<CryptoKey | null> {
  if (typeof globalThis === 'undefined' || !globalThis.crypto?.subtle) {
    return null
  }
  try {
    const stored = localStorage.getItem(KEY_STORAGE)
    if (stored) {
      return await globalThis.crypto.subtle.importKey('raw', asBufferSource(b64decode(stored)), 'AES-GCM', false, ['encrypt', 'decrypt'])
    }
    const key = await globalThis.crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt', 'decrypt'])
    const raw = await globalThis.crypto.subtle.exportKey('raw', key)
    localStorage.setItem(KEY_STORAGE, b64encode(new Uint8Array(raw)))
    return key
  } catch {
    return null
  }
}

/** 加密明文为可持久化字符串;空串原样返回。 */
export async function encryptSecret(plain: string): Promise<string> {
  if (!plain) return ''
  const key = await getOrCreateKey()
  if (!key) {
    // WebCrypto 不可用:base64 混淆(前缀标记,可解密)。
    return 'b64:' + b64encode(new TextEncoder().encode(plain))
  }
  const iv = globalThis.crypto.getRandomValues(new Uint8Array(12))
  const plainBytes = new TextEncoder().encode(plain)
  const cipher = await globalThis.crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, asBufferSource(plainBytes))
  const combined = new Uint8Array(iv.length + cipher.byteLength)
  combined.set(iv, 0)
  combined.set(new Uint8Array(cipher), iv.length)
  return 'v1:' + b64encode(combined)
}

/** 解密 encryptSecret 产出的字符串;空串原样返回。格式非法时抛错。 */
export async function decryptSecret(enc: string): Promise<string> {
  if (!enc) return ''
  if (enc.startsWith('b64:')) {
    return new TextDecoder().decode(b64decode(enc.slice(4)))
  }
  if (!enc.startsWith('v1:')) {
    throw new Error('无法识别的凭证格式')
  }
  const key = await getOrCreateKey()
  if (!key) {
    throw new Error('当前环境不支持解密凭证(WebCrypto 不可用且非 b64 格式)')
  }
  const combined = b64decode(enc.slice(3))
  const iv = combined.slice(0, 12)
  const data = combined.slice(12)
  const plain = await globalThis.crypto.subtle.decrypt({ name: 'AES-GCM', iv }, key, asBufferSource(data))
  return new TextDecoder().decode(plain)
}