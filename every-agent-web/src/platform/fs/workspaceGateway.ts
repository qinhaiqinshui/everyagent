/**
 * 远程工作区网关:n 分支 zenfsWorkspaceGateway 的同形替代(架构 §3.5/§5.9 fs.*)。
 *
 * 同一接口(exists/ensureDir/readTextFile/.../listDir),实现从浏览器 ZenFS
 * 换成经 hub 管道对 worker 的 fs.* RPC——文件树、文件标签页、编辑器、搜索
 * 等上层 UI 无感切换。
 *
 * 多工作区并行(D16):worker 侧 fs.* 必带 `workspace` 参数(机器绝对路径)指定
 * 沙箱根,本网关所有方法的第一参数即 workspace——文件操作落对应工作区。
 *
 * 事件契约:文件变更统一以 DOMAIN_EVENTS.WORKSPACE_FILE_CHANGED 广播
 * (payload.workspaceRoot 标明所属工作区),两个来源:
 * 1. 本网关自身的写操作成功后(RPC 落盘即事实);
 * 2. worker 的 fs.changed 广播(agent 工具写文件、其他前端写文件)。
 */
import { channels } from '@every-agent/client'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import { hubSession } from '@/hub/session'
import { workspaceRegistry } from '@/hub/workspaceRegistry'
import { normalizeWorkspaceRelativePath, toBusinessAbsolutePath } from './pathUtils'

/** 按工作区根反查来源 worker 后定向 RPC(多 worker 并行,fs.* 必带 workspace)。 */
function rpcForWorkspace(workspace: string, method: string, params: Record<string, unknown>, opts?: { timeoutMs?: number; onData?: (batch: any[], hasMore: boolean) => void }): Promise<any> {
  const workerId = workspaceRegistry.workerIdOfRoot(workspace)
  if (workerId) {
    return hubSession.rpcTo(workerId, method, { ...params, workspace }, opts)
  }
  throw new Error('无法确定该工作区所属 worker(工作区未注册或 worker 离线)')
}

export interface WorkspaceFileStat {
  path: string
  name: string
  isDirectory: boolean
  size: number
  mtimeMs: number
}

/** fs.read 应答:小文件内联 base64,大文件分批。 */
interface FsReadResult {
  path?: string
  size?: number
  base64?: string
  offset?: number
}

// ---- base64 与二进制互转(UTF-8 安全) ----

function bytesToBase64(bytes: Uint8Array): string {
  let binary = ''
  const CHUNK = 0x8000
  for (let i = 0; i < bytes.length; i += CHUNK) {
    binary += String.fromCharCode(...bytes.subarray(i, i + CHUNK))
  }
  return btoa(binary)
}

function base64ToBytes(base64: string): Uint8Array {
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i)
  }
  return bytes
}

function textToBytes(text: string): Uint8Array {
  return new TextEncoder().encode(text)
}

function bytesToText(bytes: Uint8Array): string {
  return new TextDecoder('utf-8').decode(bytes)
}

// ---- 路径辅助 ----

function dirname(path: string): string {
  const normalized = normalizeWorkspaceRelativePath(path)
  const index = normalized.lastIndexOf('/')
  return index < 0 ? '' : normalized.slice(0, index)
}

function basename(path: string): string {
  const normalized = normalizeWorkspaceRelativePath(path)
  const index = normalized.lastIndexOf('/')
  return index < 0 ? normalized : normalized.slice(index + 1)
}

/** worker 侧路径以 '.' 表示根;空串归一为 '.'。 */
function toWorkerPath(path: string): string {
  const normalized = normalizeWorkspaceRelativePath(path)
  return normalized || '.'
}

function joinPath(parent: string, name: string): string {
  const normalizedParent = normalizeWorkspaceRelativePath(parent)
  return normalizedParent ? `${normalizedParent}/${name}` : name
}

function emitFileChanged(workspaceRoot: string, path: string, operation: 'create' | 'modify' | 'delete' | 'rename', isDirectory: boolean): void {
  domainEventBus.emit(DOMAIN_EVENTS.WORKSPACE_FILE_CHANGED, {
    filePath: toBusinessAbsolutePath(path),
    workspaceRoot,
    operation,
    isDirectory,
  })
}

// ---- fs.changed 订阅(worker 侧写文件 → 前端树刷新) ----

let fsChangedWired = false

/**
 * 订阅 worker 的 fs.changed 广播(幂等)。网关写操作会自动接线;
 * 应用启动时也调一次,让 agent 工具写文件能即时刷新文件树/编辑器。
 */
export function wireFsChanged(): void {
  if (fsChangedWired) return
  fsChangedWired = true
  hubSession.onFrame((frame) => {
    if (frame.event !== 'fs.changed') return
    const workerId = hubSession.workerIdOfFrame(frame)
    if (!workerId) return
    const client = hubSession.workerClients.get(workerId)
    if (!client || !client.k) return
    if (frame.channel !== channels.workerEvt(client.k, workerId)) return
    const workspaceRoot = String(frame.payload?.workspace ?? '')
    const path = normalizeWorkspaceRelativePath(String(frame.payload?.path ?? ''))
    if (!path || !workspaceRoot) return
    const kind = String(frame.payload?.kind ?? 'write')
    if (kind === 'delete') {
      emitFileChanged(workspaceRoot, path, 'delete', false)
    } else if (kind === 'mkdir') {
      emitFileChanged(workspaceRoot, path, 'create', true)
    } else {
      emitFileChanged(workspaceRoot, path, 'modify', false)
    }
  })
}

// ---- 网关实现 ----

export const workspaceGateway = {
  normalizePath(path: string): string {
    return normalizeWorkspaceRelativePath(path)
  },

  resolvePath(path: string): string {
    return toBusinessAbsolutePath(path)
  },

  async exists(workspaceRoot: string, path: string): Promise<boolean> {
    const stat = await this.stat(workspaceRoot, path).catch(() => null)
    return stat !== null
  },

  async ensureDir(workspaceRoot: string, path: string): Promise<void> {
    wireFsChanged()
    const normalized = normalizeWorkspaceRelativePath(path)
    if (!normalized) return
    const parentStat = await this.stat(workspaceRoot, normalized).catch(() => null)
    if (parentStat?.isDirectory) return
    await rpcForWorkspace(workspaceRoot, 'fs.mkdir', { path: normalized })
    emitFileChanged(workspaceRoot, normalized, 'create', true)
  },

  /** 读文件(自动合并分批 base64),返回字节。 */
  async readBytes(workspaceRoot: string, path: string): Promise<Uint8Array> {
    wireFsChanged()
    const normalized = normalizeWorkspaceRelativePath(path)
    const parts: { offset: number; data: Uint8Array }[] = []
    const result = await rpcForWorkspace(workspaceRoot, 'fs.read', { path: normalized }, {
      timeoutMs: 120_000,
      onData: (batch) => {
        for (const item of batch as FsReadResult[]) {
          if (item.base64 !== undefined) {
            parts.push({ offset: item.offset ?? 0, data: base64ToBytes(item.base64) })
          }
        }
      },
    }) as FsReadResult
    if (result.base64 !== undefined) {
      return base64ToBytes(result.base64)
    }
    if (parts.length === 0) {
      throw new Error(`读取文件失败: ${normalized}`)
    }
    parts.sort((a, b) => a.offset - b.offset)
    const total = parts.reduce((sum, part) => sum + part.data.length, 0)
    const merged = new Uint8Array(total)
    let cursor = 0
    for (const part of parts) {
      merged.set(part.data, cursor)
      cursor += part.data.length
    }
    return merged
  },

  async readTextFile(workspaceRoot: string, path: string): Promise<string> {
    return bytesToText(await this.readBytes(workspaceRoot, path))
  },

  async readBinaryFile(workspaceRoot: string, path: string): Promise<Uint8Array> {
    return this.readBytes(workspaceRoot, path)
  },

  async writeBytes(workspaceRoot: string, path: string, content: Uint8Array): Promise<void> {
    wireFsChanged()
    const normalized = normalizeWorkspaceRelativePath(path)
    await rpcForWorkspace(workspaceRoot, 'fs.write', { path: normalized, contentBase64: bytesToBase64(content) }, {
      timeoutMs: 120_000,
    })
    emitFileChanged(workspaceRoot, normalized, 'modify', false)
  },

  async writeTextFile(workspaceRoot: string, path: string, content: string): Promise<void> {
    await this.writeBytes(workspaceRoot, path, textToBytes(content))
  },

  async writeBinaryFile(workspaceRoot: string, path: string, content: Uint8Array): Promise<void> {
    await this.writeBytes(workspaceRoot, path, content)
  },

  /** worker 无 append 原语:读-改-写。未存在时直接写入。 */
  async appendTextFile(workspaceRoot: string, path: string, content: string): Promise<void> {
    const existing = await this.readTextFile(workspaceRoot, path).catch(() => null)
    await this.writeTextFile(workspaceRoot, path, (existing ?? '') + content)
  },

  async deletePath(workspaceRoot: string, path: string): Promise<void> {
    wireFsChanged()
    const normalized = normalizeWorkspaceRelativePath(path)
    if (!normalized) {
      throw new Error('不能删除工作区根目录')
    }
    const stat = await this.stat(workspaceRoot, normalized).catch(() => null)
    await rpcForWorkspace(workspaceRoot, 'fs.delete', { path: normalized })
    emitFileChanged(workspaceRoot, normalized, 'delete', stat?.isDirectory ?? false)
  },

  /** 复制:worker 无 copy 原语,读+写(v1 可接受,大文件场景后续给 worker 补方法)。 */
  async copyFile(workspaceRoot: string, sourcePath: string, targetPath: string): Promise<void> {
    const content = await this.readBytes(workspaceRoot, sourcePath)
    await this.writeBytes(workspaceRoot, targetPath, content)
  },

  async rename(workspaceRoot: string, oldPath: string, newPath: string): Promise<void> {
    wireFsChanged()
    const from = normalizeWorkspaceRelativePath(oldPath)
    const to = normalizeWorkspaceRelativePath(newPath)
    if (!from || !to) {
      throw new Error('重命名路径不能为空')
    }
    await rpcForWorkspace(workspaceRoot, 'fs.move', { from, to })
    domainEventBus.emit(DOMAIN_EVENTS.WORKSPACE_FILE_CHANGED, {
      filePath: toBusinessAbsolutePath(to),
      workspaceRoot,
      operation: 'rename',
      isDirectory: false,
      oldFilePath: toBusinessAbsolutePath(from),
    })
  },

  /** 经父目录 list 推导 stat(worker 无独立 fs.stat)。 */
  async stat(workspaceRoot: string, path: string): Promise<WorkspaceFileStat> {
    wireFsChanged()
    const normalized = normalizeWorkspaceRelativePath(path)
    if (!normalized) {
      return { path: '/', name: '', isDirectory: true, size: 0, mtimeMs: 0 }
    }
    const parent = dirname(normalized)
    const name = basename(normalized)
    const rows = await this.listDir(workspaceRoot, parent)
    const hit = rows.find((row) => row.name === name)
    if (!hit) {
      throw new Error(`路径不存在: ${toBusinessAbsolutePath(normalized)}`)
    }
    return hit
  },

  async listDir(workspaceRoot: string, path = ''): Promise<WorkspaceFileStat[]> {
    wireFsChanged()
    const normalized = normalizeWorkspaceRelativePath(path)
    const result = await rpcForWorkspace(workspaceRoot, 'fs.list', { path: toWorkerPath(normalized) }) as {
      entries?: Array<{ name: string; dir: boolean; size: number; modifiedTs: number }>
    }
    const entries = result.entries ?? []
    // 行路径沿 n 约定带前导斜杠(如 /data/novels)——资源树节点、expandAncestors
    // 祖先回写、文件标签 id 都以该形态为键;入参则两种形态都接受。
    return entries.map((entry) => ({
      path: toBusinessAbsolutePath(joinPath(normalized, entry.name)),
      name: entry.name,
      isDirectory: entry.dir,
      size: entry.size,
      mtimeMs: entry.modifiedTs,
    }))
  },

  /**
   * 定位文件/目录(懒加载树的 reveal 模式):输入工作区内路径,
   * worker 沿路径逐段 stat 返回节点链(旁支零查找),用于「打开文件定位到目录树」。
   * 返回从根到目标的逐级 stat(不含根本身),最后一项为目标;目标不存在抛 RPC 错误。
   */
  async reveal(workspaceRoot: string, path: string): Promise<WorkspaceFileStat[]> {
    wireFsChanged()
    const normalized = normalizeWorkspaceRelativePath(path)
    const result = await rpcForWorkspace(workspaceRoot, 'fs.reveal', { path: normalized }) as {
      chain?: Array<{ name: string; dir: boolean; size: number; modifiedTs: number }>
    }
    const chain = result.chain ?? []
    const segments = normalized.split('/').filter(Boolean)
    return chain.map((entry, index) => ({
      path: toBusinessAbsolutePath(segments.slice(0, index + 1).join('/')),
      name: entry.name,
      isDirectory: entry.dir,
      size: entry.size,
      mtimeMs: entry.modifiedTs,
    }))
  },

  /**
   * 浏览目录(方案 B):不经 workspace 沙箱,用于「新建工作区选目录」前的逐层浏览。
   * 第一层(path 缺省)返回文件系统所有根/盘符;否则返回该绝对路径下的直接子目录。
   * 依赖运行 worker 进程的文件系统权限;无权限/路径非法时抛 RPC 错误。
   */
  async browseRoots(workerId: string): Promise<Array<{ path: string; name: string }>> {
    if (!workerId) throw new Error('请先选择 worker')
    const result = await hubSession.rpcTo(workerId, 'fs.browse', {}) as {
      entries?: Array<{ path: string; name: string }>
    }
    return result.entries ?? []
  },

  /** 浏览某绝对路径下的直接子目录(逐层下钻)。 */
  async browseDir(workerId: string, absPath: string): Promise<Array<{ path: string; name: string }>> {
    const result = await hubSession.rpcTo(workerId, 'fs.browse', { path: absPath }) as {
      entries?: Array<{ path: string; name: string }>
    }
    return result.entries ?? []
  },
}
