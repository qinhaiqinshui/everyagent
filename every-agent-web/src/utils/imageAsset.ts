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
}

/** 按文件名(或路径)推断图片 MIME;未知扩展名回退 image/png。 */
export function imageMimeOf(fileName: string): string {
  const lower = fileName.toLowerCase()
  const dotIndex = lower.lastIndexOf('.')
  const ext = dotIndex >= 0 ? lower.slice(dotIndex) : ''
  return MIME_BY_EXT[ext] ?? 'image/png'
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
