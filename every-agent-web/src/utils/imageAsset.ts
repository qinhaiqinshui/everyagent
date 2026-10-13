/**
 * 图片资源工具:扩展名 → MIME、字节 → data URL。
 * 供图片文件编辑器(plugins/image)与 Markdown 内嵌图片(plugins/markdown)共用,
 * 保证两处对「什么算图片」的判定与 MIME 推断完全一致。
 */

/** 支持预览的图片扩展名(小写,含点)。 */
export const IMAGE_EXTENSIONS = [
  '.png',
  '.jpg',
  '.jpeg',
  '.gif',
  '.webp',
  '.svg',
  '.ico',
  '.bmp',
  '.avif',
] as const

const MIME_BY_EXT: Record<string, string> = {
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.bmp': 'image/bmp',
  '.avif': 'image/avif',
  '.pdf': 'application/pdf',
}

/** 按文件名(或路径)推断图片 MIME;未知扩展名回退 image/png。 */
export function imageMimeOf(fileName: string): string {
  const lower = fileName.toLowerCase()
  const dotIndex = lower.lastIndexOf('.')
  const ext = dotIndex >= 0 ? lower.slice(dotIndex) : ''
  return MIME_BY_EXT[ext] ?? 'image/png'
}

/**
 * 按文件名(或路径)推断通用二进制 MIME(图片/PDF 等);
 * 未知扩展名回退 application/octet-stream。
 */
export function mimeOf(fileName: string): string {
  const lower = fileName.toLowerCase()
  const dotIndex = lower.lastIndexOf('.')
  const ext = dotIndex >= 0 ? lower.slice(dotIndex) : ''
  return MIME_BY_EXT[ext] ?? 'application/octet-stream'
}

/** 判断文件名是否为受支持的图片(文件树/标签页/Markdown 统一判定)。 */
export function isImageFileName(fileName: string): boolean {
  const lower = fileName.toLowerCase()
  const dotIndex = lower.lastIndexOf('.')
  const ext = dotIndex >= 0 ? lower.slice(dotIndex) : ''
  return IMAGE_EXTENSIONS.includes(ext as (typeof IMAGE_EXTENSIONS)[number])
}

/** 字节 → data URL(分块 btoa,避免大数组爆调用栈)。 */
export function bytesToDataUrl(bytes: Uint8Array, mime: string): string {
  const CHUNK = 0x8000
  let binary = ''
  for (let i = 0; i < bytes.length; i += CHUNK) {
    binary += String.fromCharCode(...bytes.subarray(i, i + CHUNK))
  }
  return `data:${mime};base64,${btoa(binary)}`
}

/**
 * data URL → Blob(手工解析,不经 fetch)。
 * 宿主 CSP 的 connect-src 未放行 data:,`fetch(dataUrl)` 会被浏览器直接拦截并抛错
 * (浏览器与桌面 Electron 同一 CSP),需要拿到字节时必须自行 base64 解码。
 */
export function dataUrlToBlob(dataUrl: string): Blob {
  const commaIndex = dataUrl.indexOf(',')
  if (!dataUrl.startsWith('data:') || commaIndex < 0) {
    throw new Error('非法 data URL')
  }
  const meta = dataUrl.slice('data:'.length, commaIndex)
  const payload = dataUrl.slice(commaIndex + 1)
  const mime = meta.split(';')[0] || 'application/octet-stream'
  const bytes = /;base64/i.test(meta)
    ? base64ToBytes(payload)
    : new TextEncoder().encode(decodeURIComponent(payload))
  return new Blob([toArrayBuffer(bytes)], { type: mime })
}

/** base64 → 字节(逐字符拷贝,UTF-8 安全)。 */
function base64ToBytes(base64: string): Uint8Array {
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i)
  }
  return bytes
}

/** 视图 → 精确长度 ArrayBuffer(Blob 兼容;规避 lib 对 Uint8Array 泛型的推断差异)。 */
function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer
}
