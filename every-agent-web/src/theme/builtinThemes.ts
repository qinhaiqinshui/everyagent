/** 内建颜色主题（原「深色 / 浅色」升级为主题表内建项）。
 *
 * 两者 `colors` 均为空——即完全由 `type` 基底（`ui-tokens.css` 的 `:root` /
 * `:root[data-theme='light']` 整批 token）决定，视觉与历史行为一致。
 *
 * id 用 `everyagent.` 前缀，避免与插件主题（建议自带插件前缀）撞名；解析时内建
 * 优先，插件不能顶替这两个 id。
 */
import type { ThemeDefinition } from '@everyagent/plugin-api'

export const THEME_ID_DARK = 'everyagent.dark'
export const THEME_ID_LIGHT = 'everyagent.light'

export const BUILTIN_THEMES: ThemeDefinition[] = [
  { id: THEME_ID_DARK, name: '深色', type: 'dark' },
  { id: THEME_ID_LIGHT, name: '浅色', type: 'light' },
]

/**
 * 归一化任意主题 id / 历史值到合法主题 id。
 * - 历史 `pref` 值 `'dark'` / `'light'`（旧 ThemeMode）→ 内建 id（向后兼容）；
 * - 空 / 未知 → 深色内建 id。
 */
export function normalizeThemeId(raw: string | null | undefined): string {
  if (raw === 'dark') return THEME_ID_DARK
  if (raw === 'light') return THEME_ID_LIGHT
  if (raw === THEME_ID_DARK || raw === THEME_ID_LIGHT) return raw
  return raw && raw.trim() ? raw : THEME_ID_DARK
}

/** 由主题 id 推断基底 type（历史值与非内建 id 的兜底）；供首帧缓存使用。 */
export function themeTypeFallback(id: string): 'dark' | 'light' {
  if (id === THEME_ID_LIGHT || id === 'light') return 'light'
  return 'dark'
}