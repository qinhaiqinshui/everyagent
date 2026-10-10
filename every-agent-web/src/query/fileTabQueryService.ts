import { workspaceGateway } from '@/platform/fs/workspaceGateway'
import type { FileTabResource } from '@/types/fileTabs'
import { bytesToDataUrl, mimeOf } from '@/utils/imageAsset'

/**
 * 文件页查询服务。
 * 统一承接文件页读取文本查询。
 *
 * 文件标签页是用户操作(非 AI 工具调用),不经沙箱/权限链路,统一走 fs.readRaw
 * (工作区内文件由网关内部拼 workspaceRoot + rel 为机器绝对路径;工作区外
 * 绝对路径直接使用)。应答形态与 fs.read 一致(内联 base64 / rpc.data 分批)。
 */
export const fileTabQueryService = {
  /** 读取文件页当前文件的文本内容。 */
  async readTextContent(file: FileTabResource): Promise<string> {
    return workspaceGateway.readTextFileRaw(file.workspaceRoot, file.filePath)
  },

  /**
   * 读取文件页当前文件为 data URL（图片/PDF 等二进制只读编辑器）。
   * 经 fs.readRaw 读原始字节（内联/分批自动合并）→ 按扩展名推断 MIME → data URL。
   * 超出预览上限抛错，由外壳以 error 呈现，避免超大文件拖垮前端。
   */
  async readBinaryDataUrl(file: FileTabResource): Promise<string> {
    const bytes = await workspaceGateway.readBytesRaw(file.workspaceRoot, file.filePath)
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
