/**
 * src/query/workspaceFileQueryService.ts
 *
 * 聊天输入框 `@` 文件引用的前端列举服务(工作区模型,方案 §5.8)。
 *
 * hub 版:与 n 分支同形(WorkspaceFileEntry / listWorkspaceFiles 签名一致),
 * 数据源从浏览器 ZenFS 换成 worker 的 `mention.query` RPC(真相源在 worker)。
 * 多工作区并行(架构 §5.9/D16):列举落调用方显式传入的工作区(任务态传任务自身
 * workspace,草稿态传预设工作区),未带时回退注册表首项;taskId 参数仅保留签名兼容。
 *
 * 性能红线(沿用 n 约定):
 * - query 为空且未下钻时只返回工作区顶层,避免一次性拉全量文件导致卡顿。
 * - 用户输入过滤词或下钻某目录时才列举对应目录。
 * - 子序列模糊匹配、隐藏规则(/plugins、.git)与条数截断(最多 10 条)全部在
 *   worker 侧完成(用户约束),前端零递归、零本地匹配、零条数上限。
 *
 * 路径语义:
 * - `path` 为工作区内相对路径(无前导 `/`,下钻 / 交给 AI 的引用用);
 * - `fullPath` 为带前导 `/` 的业务绝对路径(仅用于展示与点击打开,不进入 AI 上下文)。
 */

import { hubSession } from '@/hub/session'
import { workspaceRegistry } from '@/hub/workspaceRegistry'
import { normalizeWorkspaceRelativePath } from '@/platform/fs/pathUtils'

/** 单个文件/目录条目(已带上完整业务路径)。 */
export interface WorkspaceFileEntry {
  /** 工作区内相对路径(展示 / 下钻用)。 */
  path: string
  /** 条目名称(最后一段)。 */
  name: string
  /** 条目类型。 */
  kind: 'file' | 'directory'
  /** 带前导 / 的业务绝对路径(仅用于展示与点击打开,不进入 AI 上下文)。 */
  fullPath: string
}

/** worker `mention.query` 返回的单条结果(后端已完成匹配/隐藏过滤/截断)。 */
interface RemoteMentionEntry {
  path: string
  name: string
  kind: 'file' | 'directory'
  fullPath: string
}

/**
 * 列举当前文件范围内的文件/目录。
 * 落点:opts.workspace 显式指定,否则注册表首项(默认工作区)。
 *
 * 两种模式(匹配逻辑均在 worker 侧):
 * - 搜索(`query` 非空):广度优先收集后按匹配质量排序(文件名/连续命中优先于
 *   松散子序列,大小写不敏感),最多返回 10 条。
 * - 浏览(`query` 为空):列 `relativePath` 顶层(文件 + 目录,可层层下钻)。
 */
export async function listWorkspaceFiles(opts: {
  /** 当前任务 ID(hub 版无按任务范围,仅保留签名兼容)。 */
  taskId?: string
  /** 范围内相对目录路径(可空,表示根目录;仅浏览模式生效)。 */
  relativePath?: string
  /** 搜索词(非空时递归子序列匹配完整路径,大小写不敏感)。 */
  query?: string
  /** 显式指定工作区根(缺省用注册表首项)。 */
  workspace?: string
}): Promise<WorkspaceFileEntry[]> {
  void opts.taskId
  const workspaceRoot = opts.workspace?.trim()
    || workspaceRegistry.primaryRoot()
    || ''
  if (!workspaceRoot) return []
  const dirRel = normalizeWorkspaceRelativePath(opts.relativePath ?? '')
  const query = (opts.query ?? '').trim()

  const workerId = workspaceRegistry.workerIdOfRoot(workspaceRoot)
  if (!workerId) {
    throw new Error('无法确定该工作区所属 worker(工作区未注册或 worker 离线)')
  }
  const result = (await hubSession.rpcTo(workerId, 'mention.query', {
    workspace: workspaceRoot,
    path: dirRel || '.',
    query,
  })) as { entries?: RemoteMentionEntry[] }

  return (result.entries ?? []).map((entry) => ({
    path: normalizeWorkspaceRelativePath(entry.path),
    name: entry.name,
    kind: entry.kind,
    fullPath: entry.fullPath,
  }))
}
