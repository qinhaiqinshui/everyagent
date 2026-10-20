/**
 * 自定义颜色主题的存取（用户自建主题）。
 *
 * 持久化口径与主题一致：worker 侧 `preferences.json` 为事实源（pref key
 * `theme.custom`，值为自定义主题数组的 JSON 字符串），localStorage 作首帧缓存。
 *
 * 变更经模块级订阅通知（`subscribeThemeStore`）——宿主组件据此重渲染并重应用主题。
 */
import type { ThemeDefinition } from '@everyagent/plugin-api'
import { hubSession } from '@/hub/session'

/** worker pref key。 */
const PREF_KEY = 'theme.custom'
/** localStorage 缓存 key。 */
const LS_KEY = 'ea.web.theme.custom'

const listeners = new Set<() => void>()

export function subscribeThemeStore(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

function notify(): void {
  for (const listener of [...listeners]) listener()
}

function sanitize(input: unknown): ThemeDefinition[] {
  if (!Array.isArray(input)) return []
  const out: ThemeDefinition[] = []
  for (const item of input) {
    if (!item || typeof item !== 'object') continue
    const raw = item as Record<string, unknown>
    const id = typeof raw.id === 'string' ? raw.id : ''
    const name = typeof raw.name === 'string' ? raw.name : ''
    const type = raw.type === 'light' ? 'light' : raw.type === 'dark' ? 'dark' : null
    if (!id || !name || !type) continue
    const colors: Record<string, string> = {}
    if (raw.colors && typeof raw.colors === 'object') {
      for (const [k, v] of Object.entries(raw.colors as Record<string, unknown>)) {
        if (typeof v === 'string' && v.trim()) colors[k] = v
      }
    }
    out.push(Object.keys(colors).length > 0 ? { id, name, type, colors } : { id, name, type })
  }
  return out
}

/** 读自定义主题（localStorage 缓存，可能过时；连接 worker 后校正）。 */
export function loadCustomThemes(): ThemeDefinition[] {
  try {
    const raw = localStorage.getItem(LS_KEY)
    if (!raw) return []
    return sanitize(JSON.parse(raw))
  } catch {
    return []
  }
}

/** 写缓存 + 通知（不走 worker）。 */
function writeCache(themes: ThemeDefinition[]): void {
  try {
    localStorage.setItem(LS_KEY, JSON.stringify(themes))
  } catch {
    // localStorage 满或不可用，忽略
  }
  notify()
}

function firstConnectedWorkerId(): string {
  for (const [workerId, client] of hubSession.workerClients) {
    if (client.state === 'open') return workerId
  }
  return ''
}

/** 保存自定义主题列表：写 worker(事实源) + 更新缓存 + 通知。 */
export async function saveCustomThemes(themes: ThemeDefinition[]): Promise<void> {
  writeCache(themes)
  const workerId = firstConnectedWorkerId()
  if (workerId) {
    hubSession
      .rpcTo(workerId, 'pref.set', { key: PREF_KEY, value: JSON.stringify(themes) })
      .catch(() => {
        // worker 断开/不支持：已在本地生效，下次连接时同步
      })
  }
}

/**
 * 从 worker 校正自定义主题（pref.get 的 `theme.custom` 字段）。
 * 返回是否发生了变化（用于决定是否广播）。
 */
export function syncCustomThemesFromWorker(prefs: Record<string, unknown>): boolean {
  const raw = prefs[PREF_KEY]
  if (typeof raw !== 'string') return false
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return false
  }
  const themes = sanitize(parsed)
  const next = JSON.stringify(themes)
  if (next === JSON.stringify(loadCustomThemes())) return false
  writeCache(themes)
  return true
}