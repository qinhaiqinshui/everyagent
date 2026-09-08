/**
 * src/query/slashGateway.ts
 *
 * `/` 斜杠命令的后端网关:数据来源下沉 worker(动态注册中心),前端只负责
 * 渲染与插入。与老项目「前端 slashCommandRegistry 本地 provider」相比,
 * 唯一差异是条目的数据与 insertText(opaque token 或纯文本)由 worker 的
 * `slash.list` RPC 提供,前端零命令定义、零 token 构造逻辑。
 */
import { hubSession } from '@/hub/session'
import { taskStore } from '@/hub/taskStore'

/** worker `slash.list` 返回的单个候选项。 */
export interface RemoteSlashItem {
  /** 全局唯一 ID(用于 React key 与跨 provider 去重)。 */
  id: string
  /** 展示标题(必填)。 */
  title: string
  /** 副内容(可选)。 */
  subtitle?: string
  /** 内联 SVG 字符串(可选)。 */
  icon?: string
  /** 分组名(可选)。 */
  group?: string
  /** 新建任务时是否默认选中(默认 false)。 */
  defaultSelected?: boolean
  /**
   * 选中后要插入输入框的内容:
   * - 若为 opaque token 串(`isOpaqueTokenText(text)===true`)→ 渲染为胶囊;
   * - 否则 → 原样插入为纯文本。
   * slash 层只做形态判断,不解析内容(与老项目契约一致)。
   */
  insertText: string
}

/** worker `slash.select` 返回的单个选择结果。 */
export interface RemoteSlashSelectEntry {
  /** 胶囊归属条目 id（可选；多结果联动时用，缺省回退父条目 id）。 */
  id?: string
  /** 要插入的 opaque token 串或纯文本。 */
  token: string
  /** 放置位置：'inline' 原地插入；'bottom' 走底部胶囊。 */
  position: 'inline' | 'bottom'
}

/** worker `slash.select` 返回的选择结果（一次可返回多个，供联动场景循环消费）。 */
export interface RemoteSlashSelectResult {
  results: RemoteSlashSelectEntry[]
}

/** 从 worker 拉取全部 `/` 候选项。 */
export async function listRemoteSlashItems(workerId: string): Promise<RemoteSlashItem[]> {
  if (!workerId) throw new Error('请先选择 worker')
  const result = await hubSession.rpcTo(workerId, 'slash.list', {}) as {
    items?: RemoteSlashItem[]
  }
  return result.items ?? []
}

/** 选中一个远程 `/` 候选项：worker 注入 slashId 并可能返回 bottom 位置（一次可返回多个结果）。 */
export async function selectRemoteSlashItem(
  id: string,
  opts?: { workerId?: string; taskId?: string },
): Promise<RemoteSlashSelectResult> {
  const taskId = opts?.taskId
  let workerId = opts?.workerId
  if (taskId) {
    const owner = taskStore.get(taskId)?.workerId
    if (!owner) throw new Error('无法确定任务所属 worker')
    workerId = owner
  }
  if (!workerId) throw new Error('请先选择 worker')
  const result = await hubSession.rpcTo(workerId, 'slash.select', { id, taskId: taskId || undefined }) as {
    results?: RemoteSlashSelectEntry[]
    token?: string
    position?: 'inline' | 'bottom'
  }
  if (Array.isArray(result.results) && result.results.length > 0) {
    return { results: result.results }
  }
  // 兼容旧协议:无 results 数组时按单 result 的 token/position 字段兜底。
  return {
    results: [{
      id,
      token: result.token ?? '',
      position: result.position === 'bottom' ? 'bottom' : 'inline',
    }],
  }
}

/** 应用一个任务级 token（底部胶囊）到指定任务。 */
export async function applyRemoteTaskToken(params: {
  taskId: string
  id: string
  token: string
}): Promise<{ applied: boolean }> {
  const owner = taskStore.get(params.taskId)?.workerId
  if (!owner) throw new Error('无法确定任务所属 worker')
  const result = await hubSession.rpcTo(owner, 'slash.taskTokens.apply', params) as {
    applied?: boolean
  }
  return { applied: result.applied === true }
}

/** 取消一个已选中的 `/` 项（普通候选或任务级 token）。 */
export async function cancelRemoteSlashItem(params: {
  id?: string
  token: string
  taskId?: string
}): Promise<{ removed: boolean }> {
  const owner = params.taskId ? taskStore.get(params.taskId)?.workerId : undefined
  if (!owner) throw new Error('无法确定任务所属 worker')
  const result = await hubSession.rpcTo(owner, 'slash.cancel', params) as {
    removed?: boolean
  }
  return { removed: result.removed === true }
}
