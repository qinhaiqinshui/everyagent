/**
 * 前端本地设置(localStorage)。
 *
 * n 分支的设置读写走 ZenFS 上的 TOML 配置文件;本前端运行时配置在 worker
 * (config.*),前端自身只保留「主题」这类纯展示偏好,存 localStorage。
 */
import type { ThemeMode } from '@/types'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'

const THEME_KEY = 'ea.web.theme'

export function loadThemeMode(): ThemeMode {
  return localStorage.getItem(THEME_KEY) === 'dark' ? 'dark' : 'light'
}

export function saveThemeMode(mode: ThemeMode): void {
  localStorage.setItem(THEME_KEY, mode)
  domainEventBus.emit(DOMAIN_EVENTS.SETTINGS_THEME_PATCHED, { themeMode: mode })
}
