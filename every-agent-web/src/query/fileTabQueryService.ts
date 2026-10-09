import { workspaceGateway } from '@/platform/fs/workspaceGateway'
import { isAbsoluteBusinessPath } from '@/platform/fs/pathUtils'
import { workspaceRegistry } from '@/hub/workspaceRegistry'
import type { FileTabResource } from '@/types/fileTabs'
import { bytesToDataUrl, mimeOf } from '@/utils/imageAsset'

/**
 * 文件页查询服务。
 * 统一承接文件页读取文本查询。
 *
 * 工作区模型下文件以 `filePath`（完整业务路径）为唯一身份：
 * UI 是人工操作,直接经远程网关读 worker 工作区。
 * 工作区外绝对路径(如 `/C:/Users/...`)走 fs.readRaw(不经沙箱,用户操作非 AI 工具)。
 */

/** 文件标签页当前文件所在 worker ID(按 workspaceRoot 反查;外部文件用注册表首项兜底)。 */
function workerIdForFile(file: FileTabResource): string {
  const workerId = workspaceRegistry.workerIdOfRoot(file.workspaceRoot)
  if (workerId) return workerId
  const primary = workspaceRegistry.primaryWorkerId()
  if (primary) return primary
  throw new Error('无法确定该文件所属 worker(工作区未注册或 worker 离线)')
}

export const fileTabQueryService = {
  /**
   * 读取文件页当前文件的文本内容。
   * 工作区内文件走 fs.read(jailed 到工作区根);工作区外绝对路径走 fs.readRaw(不经沙箱)。
   */
  async readTextContent(file: FileTabResource): Promise<string> {
    if (isAbsoluteBusinessPath(file.filePath)) {
      return workspaceGateway.readTextFileRaw(workerIdForFile(file), file.filePath)
    }
    return workspaceGateway.readTextFile(file.workspaceRoot, file.filePath)
  },

  /**
   * 读取文件页当前文件为 data URL（图片/PDF 等二进制只读编辑器）。
   * 经 fs.read / fs.readRaw 读原始字节（内联/分批自动合并）→ 按扩展名推断 MIME → data URL。
   * 超出预览上限抛错，由外壳以 error 呈现，避免超大文件拖垮前端。
   */
  async readBinaryDataUrl(file: FileTabResource): Promise<string> {
    const bytes = isAbsoluteBusinessPath(file.filePath)
      ? await workspaceGateway.readBytesRaw(workerIdForFile(file), file.filePath)
      : await workspaceGateway.readBinaryFile(file.workspaceRoot, file.filePath)
    if (bytes.length > MAX_BINARY_PREVIEW_BYTES) {
      throw new Error(`文件过大（${formatBytes(bytes.length)}），超过预览上限 ${formatBytes(MAX_BINARY_PREVIEW_BYTES)}`)
    }
    return bytesToDataUrl(bytes, mimeOf(file.fileName))
  },
}

/** 二进制文件预览大小上限（字节）。 */
export const MAX_BINARY_PREVIEW_BYTES = 20 * 1024 * 1024

function formatBytes(value: number): string {
  if (value >= 1024 * 1024) {
    return `${(value / (1024 * 1024)).toFixed(1)} MB`
  }
  return `${Math.round(value / 1024)} KB`
}
