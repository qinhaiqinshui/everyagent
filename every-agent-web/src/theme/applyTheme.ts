/**
 * 主题应用器：把一套 `ThemeDefinition` 落到 DOM。
 *
 * 两步（继承模型的核心）：
 *  1. `documentElement[data-theme] = type` —— 选定**基底**（内建深色 = `:root`、
 *     浅色 = `[data-theme='light']` 的整批 token）。
 *  2. `colors` 逐项 `documentElement.style.setProperty(token, value)` —— 以
 *     **行内样式**（优先级高于 `:root` / `[data-theme='light']`）覆盖；只写
 *     `colors` 里列出的项，故未列出的 token 自然继承 `type` 基底。
 *
 * 切换主题前先清除上一主题设过的行内 token，避免残留。
 * 白名单外的键（结构性 token 等）一律忽略。
 */
import type { ThemeDefinition } from '@everyagent/plugin-api'
import { THEME_TOKEN_NAMES } from './themeTokens'

/** 上一次应用时写入 documentElement 的行内 token（切换时先清除）。 */
let appliedTokenNames: string[] = []

/** 当前已应用的主题 id（供渲染/调试读取；不参与持久化）。 */
let currentThemeId: string | null = null

export function getAppliedThemeId(): string | null {
  return currentThemeId
}

export function applyTheme(theme: ThemeDefinition): void {
  if (typeof document === 'undefined') return
  const root = document.documentElement

  // 1. 清除上一主题写过的行内 token（避免与新主题叠加）。
  for (const name of appliedTokenNames) {
    root.style.removeProperty(name)
  }
  appliedTokenNames = []

  // 2. 选定基底：documentElement 与 body 都设，供 `[data-theme='light']` 的
  //    全局与 `body[data-theme='light']` 规则命中。
  root.setAttribute('data-theme', theme.type)
  if (document.body) document.body.setAttribute('data-theme', theme.type)

  // 3. 覆盖 colors（仅白名单内、非空值的项）。
  const colors = theme.colors ?? {}
  for (const name of THEME_TOKEN_NAMES) {
    const value = colors[name]
    if (typeof value === 'string' && value.trim()) {
      root.style.setProperty(name, value)
      appliedTokenNames.push(name)
    }
  }

  currentThemeId = theme.id
}

/**
 * 仅应用基底（首帧用：插件主题尚未加载，先按缓存的 type 渲染，避免闪烁）。
 * 会清除任何已写的行内 token。
 */
export function applyThemeBase(type: 'dark' | 'light'): void {
  if (typeof document === 'undefined') return
  const root = document.documentElement
  for (const name of appliedTokenNames) root.style.removeProperty(name)
  appliedTokenNames = []
  root.setAttribute('data-theme', type)
  if (document.body) document.body.setAttribute('data-theme', type)
  currentThemeId = null
}

/**
 * 应用「编辑器字体 / 字号」到 documentElement 行内变量（独立于主题）。
 * 传空值时移除行内覆盖，回落到 `ui-tokens.css` 的默认值。
 * 字体族同时写 `--editor-font-family`（代码类）与 `--reading-font-family`（阅读类 prose），
 * 使一次设置对两类文本视图一致生效。
 */
export function applyEditorFont(fontFamily: string, fontSize: string): void {
  if (typeof document === 'undefined') return
  const root = document.documentElement
  const family = fontFamily?.trim() ?? ''
  if (family) {
    root.style.setProperty('--editor-font-family', fontFamily)
    root.style.setProperty('--reading-font-family', fontFamily)
  } else {
    root.style.removeProperty('--editor-font-family')
    root.style.removeProperty('--reading-font-family')
  }
  if (fontSize && fontSize.trim()) {
    root.style.setProperty('--editor-font-size', fontSize)
  } else {
    root.style.removeProperty('--editor-font-size')
  }
}