/**
 * 前端外观本地设置（主题 id + 基底）。
 *
 * 持久化在 worker 侧(preferences.json),localStorage 仅作首帧缓存避免闪烁;
 * worker 连接就绪后经 pref.get 同步,保存时经 pref.set 写回并广播 config.changed。
 *
 * **主题语义**：pref key `theme` 存**主题 id**（内建 `everyagent.dark` /
 * `everyagent.light`，或插件主题 / 用户自定义主题 id）；历史值 `dark` / `light`
 * 自动映射到内建 id（向后兼容）。首帧另缓存基底 `type`——插件主题尚未加载时先按
 * 基底渲染，避免闪烁。
 *
 * 自定义主题列表见 `themeStore.ts`，编辑器字体见 `editorFont.ts`（同一 pref.get 响应校正）。
 */
import type { ThemeMode } from '@/types'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import { hubSession } from '@/hub/session'
import { normalizeThemeId, themeTypeFallback } from '@/theme/builtinThemes'
import { resolveThemeById } from '@/theme/resolveThemes'
import { syncCustomThemesFromWorker } from './themeStore'
import { syncEditorFontFromWorker } from './editorFont'

const THEME_KEY = 'ea.web.theme'
const THEME_TYPE_KEY = 'ea.web.theme.type'

/** 首帧用：localStorage 缓存的主题 id（可能过时，连接 worker 后会校正）。 */
export function loadThemeId(): string {
  return normalizeThemeId(localStorage.getItem(THEME_KEY))
}

/** 首帧基底：localStorage 缓存的 type；缺失时按 id 推断。 */
export function loadThemeType(): ThemeMode {
  const cached = localStorage.getItem(THEME_TYPE_KEY)
  if (cached === 'dark' || cached === 'light') return cached
  return themeTypeFallback(loadThemeId())
}

/** 首个已连(open)worker 的 id；无则空串。 */
function firstConnectedWorkerId(): string {
  for (const [workerId, client] of hubSession.workerClients) {
    if (client.state === 'open') return workerId
  }
  return ''
}

/**
 * 从 worker 同步全部外观偏好（主题 id + 自定义主题 + 编辑器字体）。
 * worker 未连接时返回 null（保持当前外观）。
 */
export async function syncAppearanceFromWorker(): Promise<{ themeId: string; type: ThemeMode } | null> {
  const workerId = firstConnectedWorkerId()
  if (!workerId) return null
  try {
    const result = (await hubSession.rpcTo(workerId, 'pref.get')) as Record<string, unknown> | null
    const prefs = result ?? {}
    // 先校正自定义主题与编辑器字体，再解析主题 type（自定义主题可能来自 worker）。
    syncCustomThemesFromWorker(prefs)
    syncEditorFontFromWorker(prefs)
    const themeId = normalizeThemeId(typeof prefs.theme === 'string' ? prefs.theme : null)
    const theme = resolveThemeById(themeId)
    localStorage.setItem(THEME_KEY, themeId)
    localStorage.setItem(THEME_TYPE_KEY, theme.type)
    return { themeId, type: theme.type }
  } catch {
    // worker 不支持 pref.get(老版本)或连接断开：保持当前外观
    return null
  }
}

/** 保存主题：写 worker(事实源) + 更新 localStorage 缓存 + 发领域事件。 */
export async function saveThemeId(themeId: string, type: ThemeMode): Promise<void> {
  // 先发事件让 UI 立即响应(乐观更新)
  localStorage.setItem(THEME_KEY, themeId)
  localStorage.setItem(THEME_TYPE_KEY, type)
  domainEventBus.emit(DOMAIN_EVENTS.SETTINGS_THEME_PATCHED, { themeId, type })

  // 再异步写 worker(fire-and-forget,失败不影响 UI)
  const workerId = firstConnectedWorkerId()
  if (workerId) {
    hubSession.rpcTo(workerId, 'pref.set', { key: 'theme', value: themeId }).catch(() => {
      // worker 断开/不支持:主题已在本地生效,下次连接时同步
    })
  }
}