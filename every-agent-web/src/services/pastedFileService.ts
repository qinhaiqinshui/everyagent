/**
 * src/services/pastedFileService.ts
 *
 * 输入框「统一文件粘贴」服务（image-vision 方案步骤 1）：
 * 任何文件粘贴统一转为 `@` 引用胶囊，分三种落点：
 *
 * - **带宿主绝对路径且在工作区内**（Electron `File.path`）：不复制、不落盘，
 *   直接构造 `system.workspace_file` token 引用原文件。
 * - **带宿主绝对路径但在工作区外**：构造 `system.external_file` token
 *   （提交时由 worker 侧 ExternalFileTokenResolver 走授权裁决）。
 * - **无宿主路径**（剪贴板截图、浏览器粘贴的 File 无 `path`）：读出字节经
 *   `fs.write` 落到工作区内隐藏目录 `.everyagent/attachments/`，再构造
 *   `system.workspace_file` token（工作区相对路径，worker 文件工具零摩擦可读）。
 *
 * 说明（对方案原文的修正，见 plan-image-vision.md 审查记录 #7）：
 * 方案原拟「老任务直接写任务数据目录、草稿态建任务后 fs.move 迁移到任务数据目录」，
 * 但 fs.* RPC 被 jailed 到「工作区根 + 已授权 externalRoots」，任务数据目录
 * （`~/.everyagent/workspaces/<wsId>/tasks/<taskId>/`）在 homeDir 下、前端够不到；
 * 且提交后才迁移会弄断已上行 rawContent 中的路径引用。故统一写工作区内
 * `.everyagent/attachments/`，「随任务删除」语义留待 worker 侧后续实现。
 */

import { buildWorkspaceFileToken } from '@/composerToken/workspaceFileToken'
import { buildExternalFileToken } from '@/composerToken/externalFileToken'
import { workspaceGateway } from '@/platform/fs/workspaceGateway'
import { normalizeWorkspaceRelativePath, toBusinessAbsolutePath } from '@/platform/fs/pathUtils'
import type { ChatComposerToken } from '@/types'

/** 粘贴产生的临时文件统一存放目录（工作区内隐藏目录，工作区相对路径，无前导 `/`）。 */
export const PASTED_ATTACHMENT_DIR = '.everyagent/attachments'

/** MIME → 扩展名兜底映射（粘贴的 File 无文件名后缀时使用，如截图固定为 image.png 之外的场景）。 */
const MIME_EXTENSION: Record<string, string> = {
  'image/png': 'png',
  'image/jpeg': 'jpg',
  'image/gif': 'gif',
  'image/webp': 'webp',
  'image/bmp': 'bmp',
  'image/svg+xml': 'svg',
  'image/x-icon': 'ico',
  'application/pdf': 'pdf',
  'text/plain': 'txt',
  'text/markdown': 'md',
  'text/csv': 'csv',
  'application/json': 'json',
  'application/zip': 'zip',
  'application/msword': 'doc',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document': 'docx',
  'application/vnd.ms-excel': 'xls',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet': 'xlsx',
}

/** 取粘贴文件扩展名（小写、不含点）：文件名后缀 > MIME 映射 > `bin`。 */
function extensionOfFile(file: File): string {
  const match = /\.([A-Za-z0-9]{1,10})$/.exec(file.name ?? '')
  if (match) {
    return match[1].toLowerCase()
  }
  return MIME_EXTENSION[file.type] ?? 'bin'
}

/**
 * 读取 Electron 环境下 `File.path`（宿主绝对路径）；浏览器粘贴的 File 无该属性 → null。
 */
function hostPathOfFile(file: File): string | null {
  const raw = (file as unknown as { path?: unknown }).path
  return typeof raw === 'string' && raw.trim() ? raw.trim() : null
}

/** 规范化机器绝对路径用于比较（反斜杠归一 + 去尾部分隔符）。 */
function normalizeHostPath(value: string): string {
  return value.replace(/\\/g, '/').replace(/\/+$/, '')
}

/**
 * 判断机器绝对路径是否落在工作区内：在内返回工作区相对路径（无前导 `/`，
 * 与 workspace_file token 的 `path` 语义一致）；不在工作区内返回 null。
 * 前缀比较先按原样、未命中再按小写比较（Windows/macOS 文件系统大小写不敏感）。
 */
export function relativePathWithinWorkspace(workspaceRoot: string, absolutePath: string): string | null {
  const root = normalizeHostPath(workspaceRoot)
  const target = normalizeHostPath(absolutePath)
  if (!root || !target) {
    return null
  }
  const lowerRoot = root.toLowerCase()
  const lowerTarget = target.toLowerCase()
  if (lowerTarget === lowerRoot) {
    return ''
  }
  if (lowerTarget.startsWith(`${lowerRoot}/`)) {
    return normalizeWorkspaceRelativePath(target.slice(root.length + 1))
  }
  return null
}

/** 粘贴文件名序号（防同毫秒连续粘贴碰撞）。 */
let pasteSequence = 0

/** 生成粘贴落盘文件名：`paste-{epochMs}-{序号}.{扩展名}`。 */
function pastedStorageName(extension: string): string {
  pasteSequence = (pasteSequence + 1) % 1000
  return `paste-${Date.now()}-${pasteSequence}.${extension}`
}

/**
 * 把粘贴进来的单个文件统一转为引用 token（不落盘则直接引用，落盘则写
 * `.everyagent/attachments/` 后引用）。写盘失败抛错由调用方 toast 并跳过该文件。
 *
 * @param file 剪贴板文件项（`DataTransferItem.getAsFile()`）。
 * @param workspaceRoot 目标工作区根（worker 机器绝对路径）。
 */
export async function buildTokenForPastedFile(file: File, workspaceRoot: string): Promise<ChatComposerToken> {
  const hostPath = hostPathOfFile(file)
  if (hostPath) {
    const relative = relativePathWithinWorkspace(workspaceRoot, hostPath)
    if (relative) {
      // 工作区内：直接引用原文件，不复制。
      const name = relative.split('/').pop() ?? relative
      return buildWorkspaceFileToken({
        path: relative,
        name,
        kind: 'file',
        fullPath: toBusinessAbsolutePath(relative),
      })
    }
    // 工作区外：external_file token（worker 侧提交解析时注册授权根）。
    const normalized = normalizeHostPath(hostPath)
    return buildExternalFileToken({
      absolutePath: normalized,
      fileName: file.name || normalized.split('/').pop() || 'file',
      kind: 'file',
    })
  }
  // 无宿主路径（截图/浏览器粘贴）：读字节 → 写工作区隐藏目录 → workspace_file 引用。
  const extension = extensionOfFile(file)
  const storageName = pastedStorageName(extension)
  const relativePath = `${PASTED_ATTACHMENT_DIR}/${storageName}`
  const bytes = new Uint8Array(await file.arrayBuffer())
  await workspaceGateway.ensureDir(workspaceRoot, PASTED_ATTACHMENT_DIR)
  await workspaceGateway.writeBinaryFile(workspaceRoot, relativePath, bytes)
  return buildWorkspaceFileToken({
    path: relativePath,
    // 胶囊 label 用原始文件名（截图为 image.png 等通用名时退回落盘名，便于在文件树里找回）。
    name: file.name && file.name !== 'image.png' ? file.name : storageName,
    kind: 'file',
    fullPath: toBusinessAbsolutePath(relativePath),
  })
}
