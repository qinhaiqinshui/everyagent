import { workspaceGateway } from '@/platform/fs/workspaceGateway'
import type { FileTabResource } from '@/types/fileTabs'

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
}
