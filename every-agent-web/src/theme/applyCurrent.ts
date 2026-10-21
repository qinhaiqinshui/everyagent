/**
 * 一次性应用「当前外观」：颜色主题（内建 / 插件 / 自定义）+ 编辑器字体。
 *
 * 宿主在「首屏后 / 主题变更 / 插件加载出新主题 / 自定义主题变更 / 字体变更」时调用。
 * 严格按内存里的现实（`loadThemeId` + `resolveThemes`）重算，不做增量。
 */
import { applyEditorFont, applyTheme } from './applyTheme'
import { resolveThemeById } from './resolveThemes'
import { loadThemeId } from '@/settings/localSettings'
import { loadEditorFont } from '@/settings/editorFont'

export function applyCurrentAppearance(): void {
  const theme = resolveThemeById(loadThemeId())
  applyTheme(theme)
  const font = loadEditorFont()
  applyEditorFont(font.fontFamily, font.fontSize)
}