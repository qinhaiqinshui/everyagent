/**
 * 安全上下文(HTTPS 或 localhost)下 `crypto.randomUUID` 可用；
 * 但在浏览器通过局域网 IP 以 http:// 访问时属于非安全上下文，
 * `crypto.randomUUID` 不存在，直接调用会抛 `TypeError: crypto.randomUUID is not a function`。
 * 这里提供一个带回退的版本：优先用原生 `randomUUID`，否则用 `crypto.getRandomValues`
 * 手搓 RFC 4122 v4，再不行用 `Math.random` 兜底。
 */
export function randomUUID(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }

  // 用 getRandomValues 构造 v4 UUID
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    const bytes = new Uint8Array(16)
    crypto.getRandomValues(bytes)
    // version 4 / variant 1
    bytes[6] = (bytes[6] & 0x0f) | 0x40
    bytes[8] = (bytes[8] & 0x3f) | 0x80
    const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0'))
    return `${hex.slice(0, 4).join('')}-${hex.slice(4, 6).join('')}-${hex
      .slice(6, 8)
      .join('')}-${hex.slice(8, 10).join('')}-${hex.slice(10, 16).join('')}`
  }

  // 最后兜底：Math.random
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}
