import { workspaceGateway } from '@/platform/fs/workspaceGateway'
import type { FileTabResource } from '@/types/fileTabs'
import { bytesToDataUrl, imageMimeOf } from '@/utils/imageAsset'

/**
 * 文件页查询服务。
 * 统一承接文件页读取文本查询。
 *
 * 工作区模型下文件以 `filePath`（完整业务路径）为唯一身份：
 * UI 是人工操作,直接经远程网关读 worker 工作区。
 */
export const fileTabQueryService = {
  /**
   * 读取文件页当前文件的文本内容(落标签所属工作区)。
   */
  async readTextContent(file: FileTabResource): Promise<string> {
    return workspaceGateway.readTextFile(file.workspaceRoot, file.filePath)
  },

  /**
   * 读取文件页当前文件为 data URL（图片等二进制只读编辑器）。
   * 经 fs.read 读原始字节（内联/分批自动合并）→ 按扩展名推断 MIME → data URL。
   * 超出预览上限抛错，由外壳以 error 呈现，避免超大图拖垮前端。
   */
  async readBinaryDataUrl(file: FileTabResource): Promise<string> {
    const bytes = await workspaceGateway.readBinaryFile(file.workspaceRoot, file.filePath)
    if (bytes.length > MAX_IMAGE_PREVIEW_BYTES) {
      throw new Error(`图片过大（${formatBytes(bytes.length)}），超过预览上限 ${formatBytes(MAX_IMAGE_PREVIEW_BYTES)}`)
    }
    return bytesToDataUrl(bytes, imageMimeOf(file.fileName))
  },
}

/** 图片预览大小上限（字节）。 */
export const MAX_IMAGE_PREVIEW_BYTES = 20 * 1024 * 1024

function formatBytes(value: number): string {
  if (value >= 1024 * 1024) {
    return `${(value / (1024 * 1024)).toFixed(1)} MB`
  }
  return `${Math.round(value / 1024)} KB`
}
