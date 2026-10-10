import { workspaceGateway } from '@/platform/fs/workspaceGateway'
import type { FileTabResource } from '@/types/fileTabs'

/**
 * 文件页保存请求。
 * 把页面上的重命名与内容写入统一收口到命令层。
 */
export interface FileTabSaveRequest {
  /** 当前正在保存的文件资源。 */
  file: FileTabResource
  /** 保存后的目标文件名。 */
  nextFileName: string
  /** 保存后的目标文本内容。 */
  nextContent: string
}

/**
 * 文件页保存结果。
 * 页面据此刷新本地状态与标签信息。
 */
export interface FileTabSaveResult {
  /** 保存后的文件路径。 */
  filePath: string
  /** 保存后的文件名。 */
  fileName: string
  /** 已落盘的文本内容。 */
  content: string
}

/**
 * 文件页命令服务。
 * 统一承接文件保存、重命名等写操作。
 * 文件标签页是用户操作(非 AI 工具调用),不经沙箱/权限链路,统一走 fs.writeRaw
 * (网关内部解析 workspaceRoot + path 为机器绝对路径)。
 * 工作区外绝对路径暂不支持重命名(需 worker 补 fs.moveRaw,v1 先跳过)。
 */
export const fileTabCommandService = {
  /**
   * 保存文件页当前内容，并按需要执行重命名。
   */
  async saveFile(request: FileTabSaveRequest): Promise<FileTabSaveResult> {
    const { file, nextFileName, nextContent } = request
    let nextFilePath = file.filePath
    let resolvedFileName = file.fileName

    if (nextFileName !== file.fileName) {
      const renameResult = await renameFile(file, nextFileName)
      nextFilePath = renameResult.filePath
      resolvedFileName = renameResult.fileName
    }

    await workspaceGateway.writeTextFileRaw(file.workspaceRoot, nextFilePath, nextContent)

    return {
      filePath: nextFilePath,
      fileName: resolvedFileName,
      content: nextContent,
    }
  },
}

/**
 * 执行文件重命名（移动到同目录下的新文件名）。
 * 工作区外绝对路径暂不支持重命名(需 worker 补 fs.moveRaw,v1 先跳过)。
 */
async function renameFile(file: FileTabResource, nextFileName: string): Promise<{ filePath: string; fileName: string }> {
  if (/^[A-Za-z]:\//.test(file.filePath.replace(/^\/+/, ''))) {
    throw new Error('工作区外文件暂不支持重命名')
  }
  const parentPath = getParentPath(file.filePath)
  const currentExtension = getExtension(file.fileName)
  const normalizedName = appendExtensionIfMissing(sanitizePathSegment(nextFileName), currentExtension)
  const nextPath = parentPath ? `${parentPath}/${normalizedName}` : normalizedName
  // 同名 no-op 直接拒绝:fs.move(from,from) 会静默成功但名字不变(Windows Files.move 同路径不抛错)。
  if (nextPath === file.filePath) {
    throw new Error('新名称与当前名称相同')
  }
  await workspaceGateway.rename(file.workspaceRoot, file.filePath, nextPath)
  return {
    filePath: nextPath,
    fileName: getFileNameFromPath(nextPath),
  }
}

/**
 * 获取父目录路径。
 */
function getParentPath(path: string): string {
  const slashIndex = path.lastIndexOf('/')
  return slashIndex < 0 ? '' : path.slice(0, slashIndex)
}

/**
 * 获取文件扩展名。
 */
function getExtension(fileName: string): string {
  const dotIndex = fileName.lastIndexOf('.')
  return dotIndex <= 0 ? '' : fileName.slice(dotIndex)
}

/**
 * 缺少扩展名时补齐原扩展名。
 */
function appendExtensionIfMissing(fileName: string, extension: string): string {
  if (!extension) {
    return fileName
  }
  return fileName.toLowerCase().endsWith(extension.toLowerCase()) ? fileName : `${fileName}${extension}`
}

/**
 * 安全化文件名片段。
 */
function sanitizePathSegment(value: string): string {
  return value.trim().replace(/[<>:"|?*\\/]/g, '_')
}

/**
 * 从路径读取文件名。
 */
function getFileNameFromPath(filePath: string): string {
  const slashIndex = filePath.lastIndexOf('/')
  return slashIndex >= 0 ? filePath.slice(slashIndex + 1) : filePath
}
