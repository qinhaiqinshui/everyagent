/**
 * 颜色主题解析：合并「内建 + 插件 + 用户自定义」，按 id 去重（先到者胜）。
 *
 * 优先级（决定同 id 谁胜）：
 *  1. 内建深 / 浅色（`builtinThemes.ts`）——插件不能顶替内建 id；
 *  2. 插件注册（`ui.themes` 扩展点，`pluginDispatcher.listRegisteredThemes()`）；
 *  3. 用户自定义（`themeStore.loadCustomThemes()`）。
 */
import type { ThemeDefinition } from '@everyagent/plugin-api'
import { pluginDispatcher } from '@/plugin/PluginDispatcher'
import { BUILTIN_THEMES, normalizeThemeId } from './builtinThemes'
import { loadCustomThemes } from '@/settings/themeStore'

/** 全部可选主题（去重后，顺序 = 内建 → 插件 → 自定义）。 */
export function resolveThemes(): ThemeDefinition[] {
  const byId = new Map<string, ThemeDefinition>()
  for (const theme of BUILTIN_THEMES) byId.set(theme.id, theme)
  for (const theme of pluginDispatcher.listRegisteredThemes()) {
    if (theme && theme.id && !byId.has(theme.id)) byId.set(theme.id, theme)
  }
  for (const theme of loadCustomThemes()) {
    if (!byId.has(theme.id)) byId.set(theme.id, theme)
  }
  return [...byId.values()]
}

/** 按 id 解析主题；未知 / 空 id 回落到深色内建主题。 */
export function resolveThemeById(id: string | null | undefined): ThemeDefinition {
  const normalized = normalizeThemeId(id)
  return resolveThemes().find((theme) => theme.id === normalized) ?? BUILTIN_THEMES[0]
}