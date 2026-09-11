import { workspaceGateway } from '@/platform/fs/workspaceGateway'
import { shouldHideWorkspacePath } from '@/platform/fs/pathUtils'
import JSZip from 'jszip'
import { downloadBlob } from '@/utils/downloadBlob'
import { getFileRelativePath } from '@/utils/filePicker'

/**
 * 工作区资源树命令服务。
 * 统一承接工作区根目录文件树上的写操作。
 *
 * 多工作区并行(D16):每个操作的第一个参数都是 workspace 根(worker 机器
 * 绝对路径)——文件操作落对应工作区。写操作一律经远程网关(fs.* RPC):
 * 网关在每次写成功后统一广播 `WORKSPACE_FILE_CHANGED`(带 workspaceRoot)
 * 领域事件，驱动资源管理器树、已打开标签页等订阅方即时刷新。
 */
export const workspaceExplorerCommandService = {
  /**
   * 删除工作区根目录下的文件或目录。
   */
  async deletePath(workspaceRoot: string, path: string): Promise<void> {
    await workspaceGateway.deletePath(workspaceRoot, path)
  },

  /**
   * 重命名工作区根目录下的文件或目录（UI 人工操作，位置不变、仅改名称）。
   * 返回重命名后的完整相对路径。
   */
  async renamePath(workspaceRoot: string, oldPath: string, nextName: string): Promise<string> {
    const cleanName = (nextName ?? '').replace(/[/\\]/g, '').trim()
    if (!cleanName) {
      throw new Error('名称不能为空')
    }
    const parentDir = oldPath.includes('/') ? oldPath.slice(0, oldPath.lastIndexOf('/')) : ''
    const newPath = parentDir ? `${parentDir}/${cleanName}` : cleanName
    await workspaceGateway.rename(workspaceRoot, oldPath, newPath)
    return newPath
  },

  /**
   * 将工作区文件或目录移动到目标目录下（名称不变）。
   * 返回移动后的完整相对路径。
   */
  async movePath(workspaceRoot: string, oldPath: string, targetDir: string): Promise<string> {
    const sourceName = (oldPath.split('/').pop() ?? '').trim()
    if (!sourceName) {
      throw new Error('无法解析待移动项名称')
    }
    // 目标目录清洗：去掉首尾分隔符，使根目录用空串表示，与 explorer 路径坐标系一致。
    const normalizedTargetDir = (targetDir ?? '').replace(/^\/+|\/+$/g, '').trim()
    const newPath = normalizedTargetDir ? `${normalizedTargetDir}/${sourceName}` : sourceName

    if (newPath === oldPath) {
      throw new Error('目标位置与当前位置相同')
    }
    // 不能把目录移动到自身或其子目录下，否则会形成循环嵌套。
    if (normalizedTargetDir === oldPath || normalizedTargetDir.startsWith(`${oldPath}/`)) {
      throw new Error('不能移动到自身或其子目录下')
    }
    if (await workspaceGateway.exists(workspaceRoot, newPath)) {
      throw new Error('目标位置已存在同名文件或目录')
    }

    await workspaceGateway.rename(workspaceRoot, oldPath, newPath)
    return newPath
  },

  /**
   * 在指定目录下创建空文件。
   * 返回新建文件的完整相对路径。
   */
  async createFile(workspaceRoot: string, parentPath: string, name: string): Promise<string> {
    const fullPath = joinWorkspacePath(parentPath, name)
    if (await workspaceGateway.exists(workspaceRoot, fullPath)) {
      throw new Error('同名文件或目录已存在')
    }
    await workspaceGateway.writeTextFile(workspaceRoot, fullPath, '')
    return fullPath
  },

  /**
   * 在指定目录下创建空目录。
   * 返回新建目录的完整相对路径。
   */
  async createDirectory(workspaceRoot: string, parentPath: string, name: string): Promise<string> {
    const fullPath = joinWorkspacePath(parentPath, name)
    if (await workspaceGateway.exists(workspaceRoot, fullPath)) {
      throw new Error('同名文件或目录已存在')
    }
    await workspaceGateway.ensureDir(workspaceRoot, fullPath)
    return fullPath
  },

  /**
   * 下载工作区文件或目录。
   * 文件直接以原始内容下载；目录递归收集子项并打包为 zip 后下载。
   * 内部保留目录（如 .git）会被过滤，与资源管理器树展示保持一致。
   */
  async downloadPath(workspaceRoot: string, path: string): Promise<void> {
    const stat = await workspaceGateway.stat(workspaceRoot, path)
    if (!stat.isDirectory) {
      const content = await workspaceGateway.readBinaryFile(workspaceRoot, path)
      downloadBlob(new Blob([toArrayBuffer(content)]), stat.name)
      return
    }
    const zip = new JSZip()
    await collectFilesIntoZip(workspaceRoot, zip, path, stat.name)
    const blob = await zip.generateAsync({ type: 'blob' })
    downloadBlob(blob, `${stat.name}.zip`)
  },

  /**
   * 在系统文件管理器中显示(对标 VSCode Reveal in File Explorer):
   * 在运行 worker 的机器上打开文件管理器并选中/定位目标;目录定位目录本身。
   */
  async revealInOsFileManager(workspaceRoot: string, path: string): Promise<void> {
    await workspaceGateway.revealInOsFileManager(workspaceRoot, path)
  },

  /**
   * 上传文件到工作区目标目录下。
   * 多选文件时按文件名平铺写入；目录上传时按 webkitRelativePath 保持原层级。
   * 同名文件会被覆盖（二进制写入）。
   */
  async uploadFiles(workspaceRoot: string, targetPath: string, files: File[]): Promise<void> {
    if (files.length === 0) return
    for (const file of files) {
      const relativePath = getFileRelativePath(file)
      const targetFilePath = targetPath ? `${targetPath}/${relativePath}` : relativePath
      const buffer = new Uint8Array(await file.arrayBuffer())
      await workspaceGateway.writeBytes(workspaceRoot, targetFilePath, buffer)
    }
  },
}

/**
 * 将父目录路径与名称拼接为工作区相对路径，并清洗名称中的分隔符。
 */
function joinWorkspacePath(parentPath: string, name: string): string {
  const cleanName = (name ?? '').replace(/[/\\]/g, '').trim()
  if (!cleanName) {
    throw new Error('名称不能为空')
  }
  return parentPath ? `${parentPath}/${cleanName}` : cleanName
}

/**
 * 递归把目录下所有文件写入 zip，以 zipPathPrefix 为根保留完整目录层级。
 */
async function collectFilesIntoZip(workspaceRoot: string, zip: JSZip, fsPath: string, zipPathPrefix: string): Promise<void> {
  const rows = await workspaceGateway.listDir(workspaceRoot, fsPath)
  for (const row of rows) {
    if (shouldHideWorkspacePath(row.path)) {
      continue
    }
    const entryZipPath = `${zipPathPrefix}/${row.name}`
    if (row.isDirectory) {
      await collectFilesIntoZip(workspaceRoot, zip, row.path, entryZipPath)
      continue
    }
    const content = await workspaceGateway.readBinaryFile(workspaceRoot, row.path)
    zip.file(entryZipPath, toArrayBuffer(content))
  }
}

/**
 * 将 Uint8Array 转换为 Blob / zip 兼容的 ArrayBuffer。
 */
function toArrayBuffer(content: Uint8Array): ArrayBuffer {
  return content.buffer.slice(content.byteOffset, content.byteOffset + content.byteLength) as ArrayBuffer
}
