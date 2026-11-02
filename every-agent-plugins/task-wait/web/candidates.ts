/**
 * 「选择要等待的任务」候选列表拉取。
 *
 * 数据源：worker `tasks.list` RPC（内存运行中 + 磁盘索引合并快照）。
 * 每条条目字段已核实（worker `TaskEntry.summaryJson()` → `TaskSummary` record 序列化）：
 * `taskId` / `title` / `status`（wire 上标题字段名就是 **title**；
 * `displayTitle` 是宿主前端 taskStore 自己的派生字段，wire 上不存在）。
 *
 * 过滤口径（规格 §1/§5）：status ∈ {running, waiting-user, created} 的非终态任务，
 * 排除当前任务自身（自等禁止）。RPC 抛错（含 RpcError）→ console.warn + 返回 []，
 * 上层走「选择失败」路径（返回空 token，相当于没使用 `/`）。
 */
import type { PluginSdk } from '@everyagent/plugin-api'

/** 选择框里的一条候选任务。 */
export interface PickerTask {
  taskId: string
  title: string
  status: string
}

/** 可等待（非终态）的 status 集合。 */
const PICKABLE_STATUSES = new Set(['running', 'waiting-user', 'created'])

/** tasks.list 应答条目的宽松读取形状（字段缺失按缺省处理）。 */
interface TaskListEntry {
  taskId?: unknown
  title?: unknown
  status?: unknown
}

/**
 * 拉取当前工作区（workspace 省略 = 全部）下所有进行中的任务候选。
 *
 * @param sdk           插件 SDK（rpc 通道）
 * @param workerId      目标 worker
 * @param workspace     工作区根（可选过滤；选择框按当前输入框所属工作区定向拉取）
 * @param excludeTaskId 排除的任务 ID（= 当前任务自身，防自等）
 */
export async function fetchRunningTasks(
  sdk: PluginSdk,
  workerId: string,
  workspace?: string,
  excludeTaskId?: string,
): Promise<PickerTask[]> {
  let raw: unknown
  try {
    raw = await sdk.rpc(workerId, 'tasks.list', workspace ? { workspace } : {})
  } catch (error) {
    // fail-safe：拉取失败 = 选择失败路径（返回空候选 → 上层返回空 token，不弹框）
    console.warn(
      `[task-wait] tasks.list 拉取失败(${workerId}${workspace ? `:${workspace}` : ''}):`,
      error,
    )
    return []
  }
  const tasks = (raw as { tasks?: unknown } | null)?.tasks
  if (!Array.isArray(tasks)) {
    return []
  }
  const picked: PickerTask[] = []
  for (const entry of tasks as TaskListEntry[]) {
    if (entry === null || typeof entry !== 'object') {
      continue
    }
    const taskId = typeof entry.taskId === 'string' ? entry.taskId : ''
    const status = typeof entry.status === 'string' ? entry.status : ''
    if (taskId.length === 0 || !PICKABLE_STATUSES.has(status)) {
      continue
    }
    if (excludeTaskId !== undefined && taskId === excludeTaskId) {
      continue
    }
    // wire 字段 = title；空标题兜底 taskId（胶囊 label / 标签标题不能为空）
    const title = typeof entry.title === 'string' && entry.title.trim().length > 0
      ? entry.title.trim()
      : taskId
    picked.push({ taskId, title, status })
  }
  return picked
}
