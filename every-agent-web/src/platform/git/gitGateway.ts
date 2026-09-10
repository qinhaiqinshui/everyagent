/**
 * 远程 git 网关:n 分支 isomorphic-git 浏览器内跑的替代——一切 git 操作经 hub 管道
 * 对 worker 的 git.* RPC,worker 端 JGit 落盘,凭证走 worker 本机 git 配置(架构 §13.6),
 * 前端仅作遥控器(前端不接触任何凭据)。
 *
 * 多工作区并行:所有方法第一参数 workspace 指定沙箱根(必填)。
 * 仅暴露 worker 已实现的 git.* 能力;未开放的(分支管理/stash/merge 编辑器)即能力门控项。
 */
import { hubSession } from '@/hub/session'
import { workspaceRegistry } from '@/hub/workspaceRegistry'
import { RpcError } from '@every-agent/client'
import type { TaskFileChange } from '@/types'

export interface GitRemote {
  name: string
  url: string
}

/** 本次调用的临时凭证(前端 AUTH_REQUIRED 弹窗输入,仅本次请求生效,不落盘)。 */
export interface GitCredential {
  username: string
  password: string
}

/** 仓库未初始化(git.open 失败)。 */
export class GitNotInitializedError extends Error {}

/**
 * 远端需要认证(worker 抛 AUTH_REQUIRED):携带 host,调用方弹窗收集账号密码后重试。
 * 消息格式统一为「需要认证: <host>」。
 */
export class GitAuthRequiredError extends Error {
  readonly host: string
  constructor(host: string) {
    super(`需要认证: ${host}`)
    this.host = host
  }
}

/** 解析 worker rpc.err 消息中的 host(「需要认证: xxx」)。 */
function parseAuthHost(message: string): string {
  const m = /需要认证:\s*(.+)/.exec(message)
  return m ? m[1].trim() : message
}

function normalizeError(err: unknown): never {
  // worker 业务错误码 AUTH_REQUIRED → 弹窗凭证收集信号
  if (err instanceof RpcError && err.code === 'AUTH_REQUIRED') {
    throw new GitAuthRequiredError(parseAuthHost(err.message))
  }
  const message = err instanceof Error ? err.message : String(err)
  if (/不是 git 仓库|not a git repository|repository not found/i.test(message)) {
    throw new GitNotInitializedError(message)
  }
  throw err instanceof Error ? err : new Error(message)
}

/**
 * 按工作区归属的 worker 定向 RPC(多 worker 合并后,工作区可能属于非选中 worker)。
 * 注册表能反查到 workerId → rpcTo 定向;归属缺失(工作区未注册或 worker 离线)直接抛错,
 * 不再回退默认 RPC( hubSession.rpc 已删除,错误路由到非归属 worker 是静默错误源)。
 */
function rpcForWorkspace(workspace: string, method: string, params: Record<string, unknown>): Promise<any> {
  const workerId = workspaceRegistry.workerIdOfRoot(workspace)
  if (workerId) {
    return hubSession.rpcTo(workerId, method, params)
  }
  throw new Error('无法确定该工作区所属 worker(工作区未注册或 worker 离线)')
}

export const gitGateway = {
  /** 读取仓库状态(未初始化时抛 GitNotInitializedError;含相对上游的 ahead/behind 提交数,纯本地不触网)。 */
  async status(workspace: string): Promise<{
    branch?: string
    added?: string[]
    changed?: string[]
    modified?: string[]
    removed?: string[]
    missing?: string[]
    untracked?: string[]
    conflicting?: string[]
    /** 当前分支领先上游(已提交未推送)的提交数;无上游/非跟踪分支为 0。 */
    ahead?: number
    /** 当前分支落后上游的提交数;无上游/非跟踪分支为 0。 */
    behind?: number
  }> {
    try {
      return (await rpcForWorkspace(workspace, 'git.status', { workspace })) as never
    } catch (err) {
      normalizeError(err)
    }
  },

  /** 读取提交历史。 */
  async log(workspace: string, max = 30): Promise<Array<{
    id: string
    shortId: string
    author: string
    email?: string
    ts: number
    message: string
  }>> {
    try {
      const result = await rpcForWorkspace(workspace, 'git.log', { workspace, max }) as { commits?: unknown[] }
      return (result.commits ?? []) as never
    } catch (err) {
      normalizeError(err)
    }
  },

  /** 读取某文件相对 HEAD 的完整变更内容(before = HEAD 文本,after = 工作区文本;对齐 old 链路,不经 unified diff 往返)。 */
  async diff(workspace: string, path: string): Promise<TaskFileChange> {
    try {
      const result = await rpcForWorkspace(workspace, 'git.diff', { workspace, path })
      // 防御:worker 旧版本(未重启/未重新部署)的 git.diff 仍返回 unified diff 文本结构({ diff }),
      // 缺 filePath 会让调用方 undefined.lastIndexOf 直接崩溃——这里统一转成可读错误。
      if (!result || typeof result !== 'object' || typeof (result as TaskFileChange).filePath !== 'string') {
        throw new Error('git.diff 返回结构异常:worker 仍在运行旧版本(仅返回 unified diff 文本),请重启 worker 后重试')
      }
      return result as TaskFileChange
    } catch (err) {
      normalizeError(err)
    }
  },

  /** 提交(可指定部分路径,否则全量)。 */
  async commit(workspace: string, message: string, paths?: string[]): Promise<{ commitId: string; shortId: string; message: string }> {
    try {
      return (await rpcForWorkspace(workspace, 'git.commit', {
        workspace,
        message,
        ...(paths && paths.length > 0 ? { paths } : {}),
      })) as never
    } catch (err) {
      normalizeError(err)
    }
  },

  /** 拉取(凭证走 worker 解析链:本机默认 → 工作区加密凭证 → AUTH_REQUIRED 弹窗)。 */
  async pull(workspace: string, credential?: GitCredential): Promise<{ successful: boolean; mergeStatus?: string; fetchMessages?: string }> {
    try {
      return (await rpcForWorkspace(workspace, 'git.pull', {
        workspace,
        ...(credential ?? {}),
      })) as never
    } catch (  err) {
      normalizeError(err)
    }
  },

  /** 放弃指定路径的更改(恢复为 HEAD 内容;未跟踪/已暂存新增的文件跳过)。 */
  async discard(workspace: string, paths: string[]): Promise<{ discarded: string[]; skipped: string[] }> {
    try {
      return (await rpcForWorkspace(workspace, 'git.discard', { workspace, paths })) as never
    } catch (err) {
      normalizeError(err)
    }
  },

  /** 推送(凭证走 worker 解析链:本机默认 → 工作区加密凭证 → AUTH_REQUIRED 弹窗)。 */
  async push(workspace: string, credential?: GitCredential): Promise<{ updates: Array<{ remote: string; ref: string; status: string }> }> {
    try {
      return (await rpcForWorkspace(workspace, 'git.push', {
        workspace,
        ...(credential ?? {}),
      })) as never
    } catch (err) {
      normalizeError(err)
    }
  },

  /** 在工作区根初始化本地仓库(可指定初始分支名,默认 main)。 */
  async init(workspace: string, initialBranch?: string): Promise<{ initialized: boolean; branch: string }> {
    return (await rpcForWorkspace(workspace, 'git.init', {
      workspace,
      ...(initialBranch ? { initialBranch } : {}),
    })) as never
  },

  /**
   * 克隆远程仓库到工作区内目录(dir 为工作区内相对路径,缺省即工作区根)。
   * 目标必须为空目录(非空由 worker 拒绝),凭证走 worker 解析链
   * (本机默认 → 工作区加密凭证 → AUTH_REQUIRED 弹窗)。
   */
  async clone(workspace: string, url: string, dir?: string, credential?: GitCredential): Promise<{ cloned: boolean; dir: string }> {
    try {
      return (await rpcForWorkspace(workspace, 'git.clone', {
        workspace,
        url,
        ...(dir ? { dir } : {}),
        ...(credential ?? {}),
      })) as never
    } catch (err) {
      normalizeError(err)
    }
  },

  /**
   * 保存 git 远端凭证到工作区(worker 加密落盘 .git-credentials.enc,仅写不读回)。
   * 前端在 AUTH_REQUIRED 弹窗勾选保存后调用,后续 clone/pull/push 自动复用。
   * host 直接传(AUTH_REQUIRED 错误自带);clone 场景也可传 url 由 worker 提取。
   */
  async saveCredential(workspace: string, username: string, password: string, hostOrUrl: string): Promise<{ saved: boolean; host: string }> {
    const params: Record<string, string> = { workspace, username, password }
    if (hostOrUrl.includes('://') || hostOrUrl.includes('@')) {
      params.url = hostOrUrl
    } else {
      params.host = hostOrUrl
    }
    return (await rpcForWorkspace(workspace, 'git.credential.save', params)) as never
  },

  /** 关联远程仓库(推送前若无远程,前端引导填入)。 */
  async addRemote(workspace: string, url: string, name = 'origin'): Promise<{ added: boolean; name: string; url: string }> {
    return (await rpcForWorkspace(workspace, 'git.remote.add', { workspace, name, url })) as never
  },

  /** 列出已关联远程(供前端判断是否需要引导关联)。 */
  async listRemotes(workspace: string): Promise<GitRemote[]> {
    try {
      const result = await rpcForWorkspace(workspace, 'git.remote.list', { workspace }) as { remotes?: GitRemote[] }
      return result.remotes ?? []
    } catch (err) {
      normalizeError(err)
    }
  },
}
