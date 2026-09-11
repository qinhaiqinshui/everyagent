import { workspaceGateway } from '@/platform/fs/workspaceGateway'
import { INTERNAL_DIR_NAMES } from '@/platform/fs/pathUtils'

/**
 * 递归收集目录下全部文件路径（文件直接返回自身），返回业务绝对形态（前导 `/`）。
 *
 * 自资源管理器面板(OpenFilesSidebarPanel)提取的 UI 搜索专用枚举：直读
 * workspaceGateway（人工操作，走 worker 的 fs.* RPC），不走 AI 权限网关；
 * 限定在指定工作区内；includeInternalFiles=false 时跳过内部保留目录（如 .git）。
 */
export async function walkWorkspaceFiles(
  workspaceRoot: string,
  rootPath: string,
  includeInternalFiles = false,
): Promise<string[]> {
  const stat = await workspaceGateway.stat(workspaceRoot, rootPath)
  if (!stat.isDirectory) {
    return [rootPath]
  }
  const result: string[] = []
  async function recurse(dir: string): Promise<void> {
    const rows = await workspaceGateway.listDir(workspaceRoot, dir)
    for (const row of rows) {
      if (row.isDirectory) {
        // 跳过内部保留目录（如 .git 仓库元数据），不纳入搜索枚举。
        if (!includeInternalFiles && INTERNAL_DIR_NAMES.has(row.name)) continue
        await recurse(row.path)
      } else {
        result.push(row.path)
      }
    }
  }
  await recurse(rootPath)
  return result
}
