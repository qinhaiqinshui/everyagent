/**
 * 全局颜色主题可覆盖的 token 白名单（真相源）。
 *
 * 与 `docs/plugin-guide/web/ui-extensions.md` §19.4 一一对应（改一处必同步另一处）。
 *
 * 只有本表内的 token 可被主题的 `colors` 覆盖；**结构性 token**（间距 / 圆角 / 字号 /
 * 动效 / z-index / 字体族）不在表内，**不可**被主题覆盖。
 *
 * 本表是运行期值（web 自己持有），不依赖 `@everyagent/plugin-api` 的运行时代码——
 * 插件侧的 `ThemeDefinition.colors` 只是一个字符串键值袋，宿主按本表校验后落成
 * documentElement 的行内 CSS 变量。
 */
export const THEME_TOKEN_NAMES = [
  // ── 背景 / 表面 ──
  '--bg-primary',
  '--bg-secondary',
  '--bg-tertiary',
  '--bg-table',
  '--bg-hover',
  '--bg-active',
  '--bg-elevated',
  '--bg-sunken',
  '--bg-soft',
  // ── 边框 ──
  '--border',
  '--border-light',
  '--border-strong',
  // ── 文本 ──
  '--text-primary',
  '--text-secondary',
  '--text-muted',
  '--text-faint',
  // ── 强调色 ──
  '--accent-blue',
  '--accent-blue-dim',
  '--accent-blue-soft',
  '--accent-green',
  '--accent-green-dim',
  '--accent-purple',
  '--accent-purple-dim',
  '--accent-amber',
  '--accent-amber-dim',
  '--accent-red',
  '--accent-red-dim',
  '--accent-cyan',
  '--accent-cyan-dim',
  '--tool-slow-red',
  // ── 标签页（agent） ──
  '--agent-tab-bg',
  '--agent-tab-active-bg',
  '--agent-tab-border',
  '--agent-tab-border-active',
  '--agent-tab-text',
  '--agent-tab-text-active',
  '--agent-tab-icon',
  '--agent-tab-icon-active',
  // ── 标签页（file） ──
  '--file-tab-bg',
  '--file-tab-active-bg',
  '--file-tab-border',
  '--file-tab-border-active',
  '--file-tab-text',
  '--file-tab-text-active',
  '--file-tab-icon',
  '--file-tab-icon-active',
  // ── 阴影 / 遮罩 ──
  '--shadow-sm',
  '--shadow-md',
  '--shadow-lg',
  '--overlay',
  // ── 代码 ──
  '--code-bg',
  '--code-text',
  '--cm-selection-focused',
  '--cm-selection-unfocused',
  '--json-key',
  '--json-string',
  '--json-number',
  '--json-boolean',
  '--json-null',
  // ── 滚动条 ──
  '--scrollbar-thumb',
  '--scrollbar-thumb-hover',
  // ── 标题栏 ──
  '--titlebar-bg',
  '--titlebar-tab-bg',
  '--titlebar-tab-hover-bg',
  '--titlebar-tab-active-bg',
  '--titlebar-tab-border',
  // ── 文件阅读区（专供阅读类主题） ──
  '--reading-bg',
  '--reading-fg',
] as const

export type ThemeTokenName = (typeof THEME_TOKEN_NAMES)[number]

/** 白名单集合（O(1) 校验用）。 */
export const THEME_TOKEN_SET: ReadonlySet<string> = new Set<string>(THEME_TOKEN_NAMES)

/**
 * token → 显示名分组（设置页自定义主题编辑器按组渲染）。
 * 键是分组标题，值是该组的白名单 token（顺序即渲染顺序）。
 */
export const THEME_TOKEN_GROUPS: ReadonlyArray<{ title: string; tokens: ThemeTokenName[] }> = [
  {
    title: '背景 / 表面',
    tokens: [
      '--bg-primary', '--bg-secondary', '--bg-tertiary', '--bg-table',
      '--bg-hover', '--bg-active', '--bg-elevated', '--bg-sunken', '--bg-soft',
    ],
  },
  { title: '边框', tokens: ['--border', '--border-light', '--border-strong'] },
  { title: '文本', tokens: ['--text-primary', '--text-secondary', '--text-muted', '--text-faint'] },
  {
    title: '强调色',
    tokens: [
      '--accent-blue', '--accent-blue-dim', '--accent-blue-soft',
      '--accent-green', '--accent-green-dim',
      '--accent-purple', '--accent-purple-dim',
      '--accent-amber', '--accent-amber-dim',
      '--accent-red', '--accent-red-dim',
      '--accent-cyan', '--accent-cyan-dim',
      '--tool-slow-red',
    ],
  },
  {
    title: '标签页（Agent）',
    tokens: [
      '--agent-tab-bg', '--agent-tab-active-bg', '--agent-tab-border', '--agent-tab-border-active',
      '--agent-tab-text', '--agent-tab-text-active', '--agent-tab-icon', '--agent-tab-icon-active',
    ],
  },
  {
    title: '标签页（文件）',
    tokens: [
      '--file-tab-bg', '--file-tab-active-bg', '--file-tab-border', '--file-tab-border-active',
      '--file-tab-text', '--file-tab-text-active', '--file-tab-icon', '--file-tab-icon-active',
    ],
  },
  { title: '阴影 / 遮罩', tokens: ['--shadow-sm', '--shadow-md', '--shadow-lg', '--overlay'] },
  {
    title: '代码',
    tokens: [
      '--code-bg', '--code-text', '--cm-selection-focused', '--cm-selection-unfocused',
      '--json-key', '--json-string', '--json-number', '--json-boolean', '--json-null',
    ],
  },
  { title: '滚动条', tokens: ['--scrollbar-thumb', '--scrollbar-thumb-hover'] },
  {
    title: '标题栏',
    tokens: [
      '--titlebar-bg', '--titlebar-tab-bg', '--titlebar-tab-hover-bg',
      '--titlebar-tab-active-bg', '--titlebar-tab-border',
    ],
  },
  { title: '文件阅读区', tokens: ['--reading-bg', '--reading-fg'] },
]