/**
 * Worker 显示别名(纯前端本地偏好,存 localStorage)。
 *
 * 显示名解析优先级:用户别名 > worker 上报的 hostname > workerId。
 * 别名只改善本端可读性,不写入 worker/hub;清除浏览器数据后回退 hostname/workerId。
 */

const STORAGE_KEY = 'ea.web.workerAliases'

export type WorkerAliasMap = Record<string, string>

type Listener = (aliases: WorkerAliasMap) => void

/** 只需 workerId/hostname 即可解析显示名(WorkerInfo 结构兼容)。 */
export interface WorkerNameSource {
  workerId: string
  hostname?: string
}

const listeners = new Set<Listener>()

let cache: WorkerAliasMap | null = null

export function loadWorkerAliases(): WorkerAliasMap {
  if (cache) return cache
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    cache = raw ? (JSON.parse(raw) as WorkerAliasMap) : {}
  } catch {
    cache = {}
  }
  return cache ?? {}
}

export function getWorkerAlias(workerId: string): string {
  return loadWorkerAliases()[workerId] ?? ''
}

/** 设置别名;空串(或纯空白)视为清除,回退 hostname/workerId。 */
export function setWorkerAlias(workerId: string, alias: string): void {
  const next = { ...loadWorkerAliases() }
  const trimmed = alias.trim()
  if (trimmed) {
    next[workerId] = trimmed
  } else {
    delete next[workerId]
  }
  cache = next
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
  } catch {
    // 存储失败(隐私模式等)不阻断,仅本次会话生效
  }
  for (const fn of listeners) fn(next)
}

/** 订阅别名变化(组件据此重渲染)。 */
export function onWorkerAliasesChanged(fn: Listener): () => void {
  listeners.add(fn)
  return () => listeners.delete(fn)
}

/** worker 显示名:别名 > hostname > workerId。 */
export function workerDisplayName(worker: WorkerNameSource): string {
  return getWorkerAlias(worker.workerId) || worker.hostname?.trim() || worker.workerId
}
