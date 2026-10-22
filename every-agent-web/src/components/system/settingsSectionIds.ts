/**
 * 设置页内置段锚点 id 常量（与 SettingsPanel 内置 section 一一对应，键序即渲染顺序）。
 *
 * 目录导航（useSettingsSectionDirectory）按 `data-settings-section-id` 属性扫描段落：
 * 内置段用本常量打标；插件段锚点 id 即扩展点注册的 def.id（PluginSettingsSections 打标）。
 * 本常量仅供打标与块级跳转链路引用（如 openSettingsPage(sectionId)），目录本身零硬编码清单。
 */
export const SETTINGS_SECTION_ID = {
  HUB_CONNECTION: 'hub-connection',
  WORKERS: 'workers',
  MODELS: 'models',
  SKILLS: 'skills',
  RESTART_WORKER: 'restart-worker',
  APPEARANCE: 'appearance',
  EDITOR_FONT: 'editor-font',
  NOTIFICATIONS: 'notifications',
  ABOUT: 'about',
} as const

/** 内置设置段锚点 id 联合类型（插件段 id 为扩展点自定义字符串，不受此类型约束）。 */
export type SettingsSectionId = (typeof SETTINGS_SECTION_ID)[keyof typeof SETTINGS_SECTION_ID]

/** 设置段落锚点属性名（目录扫描选择器与打标约定共用；JSX 打标处以字面量属性书写）。 */
export const SETTINGS_SECTION_ID_ATTR = 'data-settings-section-id'
