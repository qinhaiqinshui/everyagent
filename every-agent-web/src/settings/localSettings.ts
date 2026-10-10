/**
 * 前端本地设置。
 *
 * 主题持久化在 worker 侧(preferences.json),localStorage 仅作首帧缓存避免闪烁;
 * worker 连接就绪后经 pref.get 同步,保存时经 pref.set 写回并广播 config.changed。
 */
import type { ThemeMode } from '@/types'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import { hubSession } from '@/hub/session'

const THEME_KEY = 'ea.web.theme'

/** 首帧渲染用:localStorage 缓存(可能过时,连接 worker 后会校正)。 */
export function loadThemeMode(): ThemeMode {
  return localStorage.getItem(THEME_KEY) === 'dark' ? 'dark' : 'light'
}

/** 从 worker 同步主题偏好(pref.get);worker 未连接时返回 null(保持当前主题)。 */
export async function syncThemeFromWorker(): Promise<ThemeMode | null> {
  const workerId = firstConnectedWorkerId()
  if (!workerId) return null
  try {
    const result = await hubSession.rpcTo(workerId, 'pref.get') as Record<string, string>
    const theme = result?.theme === 'dark' ? 'dark' : 'light'
    localStorage.setItem(THEME_KEY, theme)
    return theme
  } catch {
    // worker 不支持 pref.get(老版本)或连接断开:保持当前主题
    return null
  }
}

/** 保存主题:写 worker(事实源) + 更新 localStorage 缓存 + 发领域事件。 */
export async function saveThemeMode(mode: ThemeMode): Promise<void> {
  // 先发事件让 UI 立即响应(乐观更新)
  localStorage.setItem(THEME_KEY, mode)
  domainEventBus.emit(DOMAIN_EVENTS.SETTINGS_THEME_PATCHED, { themeMode: mode })

  // 再异步写 worker(fire-and-forget,失败不影响 UI)
  const workerId = firstConnectedWorkerId()
  if (workerId) {
    hubSession.rpcTo(workerId, 'pref.set', { key: 'theme', value: mode }).catch(() => {
      // worker 断开/不支持:主题已在本地生效,下次连接时同步
    })
  }
}

/** 首个已连(open)worker 的 id;无则空串。 */
function firstConnectedWorkerId(): string {
  for (const [workerId, client] of hubSession.workerClients) {
    if (client.state === 'open') return workerId
  }
  return ''
}

