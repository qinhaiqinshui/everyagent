/**
 * 搜索类型注册表（替代旧 `components/search/targets/**`）。
 *
 * 把旧「写死的 files/tasks 二值目标」重构为开放注册表：
 * - 内置三类型 `file-content`（文本文件内容）/ `file-name`（文件名）/ `task`（任务内容）；
 * - 合并插件经 `ui.search_types` 注册的类型（`pluginDispatcher.listRegisteredSearchTypes()`）；
 * - 提供「全部」伪类型（`ALL_SEARCH_TYPE_ID`）：选中它 = `kinds` 不传 = 全部已注册类型（含任务）。
 *
 * 类型定义（`UiSearchTypeDefinition`）与 plugin-api JS 契约一致：
 * `{ id, label, pluginId, order?, kinds[], filters?, FilterView?, ResultView? }`。
 * 过滤字段（`filters`）为声明式 schema，核心面板按 `type` 默认渲染、按
 * `${kind}.${field}` 装袋透传，**核心不硬编码任何类型字段**。
 *
 * 新增搜索类型 = 增补一个注册项（插件走 `ctx.ui.registerSearchType`）；核心面板与
 * 状态机按注册表驱动，不再出现类型二值分支。
 */
import { pluginDispatcher } from '@/plugin/PluginDispatcher'
import type { UiSearchTypeDefinition } from '@/plugin/types'
import type { SearchFilterField } from '@everyagent/plugin-api'

/** 「全部」伪类型 id（选中它时 `kinds` 不传 = 全部已注册类型）。 */
export const ALL_SEARCH_TYPE_ID = 'all'

/** 内置三类型的 kind 常量。 */
export const FILE_CONTENT_KIND = 'file-content'
export const FILE_NAME_KIND = 'file-name'
export const TASK_KIND = 'task'

/** 内置类型与插件类型缺省排序值（内置占用小值，未声明 order 的插件类型落在此之后）。 */
const DEFAULT_SEARCH_TYPE_ORDER = 100

/** 文件类（内容 / 文件名）过滤字段声明：与旧文件搜索语义对齐，去掉了核心自动追加 `.git`。 */
const FILE_FILTERS: SearchFilterField[] = [
  { key: 'isRegex', label: '.*', help: '使用正则表达式 (Alt+R)', type: 'boolean', section: 'inline' },
  { key: 'caseSensitive', label: 'Aa', help: '区分大小写 (Alt+C)', type: 'boolean', section: 'inline' },
  { key: 'wholeWord', label: 'ab|', help: '全字匹配 (Alt+W)', type: 'boolean', section: 'inline' },
  {
    key: 'scope',
    label: '搜索范围',
    type: 'path',
    section: 'inline',
    placeholder: '搜索范围目录，例：/src 或 src/components（留空为工作区根）',
  },
  {
    key: 'include',
    label: '包含的文件',
    type: 'text',
    section: 'more',
    placeholder: '包含的文件，例：*.ts, src/**',
  },
  {
    key: 'exclude',
    label: '排除的文件',
    type: 'text',
    section: 'more',
    placeholder: '排除的文件，例：*.css, dist/**',
  },
]

/** 任务内容过滤字段声明（范围/包含/排除属文件语义，任务类型不声明）。 */
const TASK_FILTERS: SearchFilterField[] = [
  { key: 'isRegex', label: '.*', help: '使用正则表达式 (Alt+R)', type: 'boolean', section: 'inline' },
  { key: 'wholeWord', label: 'ab|', help: '全字匹配 (Alt+W)', type: 'boolean', section: 'inline' },
]

/** 「全部」伪类型（kinds 为空 = 不传 kinds）。 */
export const ALL_SEARCH_TYPE: UiSearchTypeDefinition = {
  id: ALL_SEARCH_TYPE_ID,
  label: '全部',
  pluginId: 'core',
  order: 0,
  kinds: [],
}

/** 内置三类型（文件内容 / 文件名 / 任务内容）。 */
export const BUILTIN_SEARCH_TYPES: UiSearchTypeDefinition[] = [
  {
    id: FILE_CONTENT_KIND,
    label: '文本文件内容',
    pluginId: 'core',
    order: 10,
    kinds: [FILE_CONTENT_KIND],
    filters: FILE_FILTERS,
  },
  {
    id: FILE_NAME_KIND,
    label: '文件名',
    pluginId: 'core',
    order: 20,
    kinds: [FILE_NAME_KIND],
    filters: FILE_FILTERS,
  },
  {
    id: TASK_KIND,
    label: '任务内容',
    pluginId: 'core',
    order: 30,
    kinds: [TASK_KIND],
    filters: TASK_FILTERS,
  },
]

/** 旧 target 值 → 新类型 id 兼容映射（files/tasks 各保留一轮）。 */
const LEGACY_TARGET_MAP: Record<string, string> = {
  files: FILE_CONTENT_KIND,
  tasks: TASK_KIND,
}

/**
 * 合并内置与插件注册的搜索类型，按 `order` 升序混排；「全部」伪类型恒在首位。
 * 每次调用现读插件注册表（配合 `subscribeExtensionsChanged` 版本号重渲染即上屏）。
 */
export function listSearchTypes(): UiSearchTypeDefinition[] {
  const merged = [...BUILTIN_SEARCH_TYPES, ...pluginDispatcher.listRegisteredSearchTypes()]
  const ordered = merged
    .map((type, index) => ({ type, index }))
    .sort((a, b) => (
      (a.type.order ?? DEFAULT_SEARCH_TYPE_ORDER) - (b.type.order ?? DEFAULT_SEARCH_TYPE_ORDER)
      || a.index - b.index
    ))
    .map((entry) => entry.type)
  return [ALL_SEARCH_TYPE, ...ordered]
}

/** 按 id 查类型（含「全部」伪类型）；未命中返回 undefined。 */
export function getSearchType(
  id: string | undefined,
  types: UiSearchTypeDefinition[] = listSearchTypes(),
): UiSearchTypeDefinition | undefined {
  if (!id) return undefined
  return types.find((type) => type.id === id)
}

/** 按 id 查类型，未知/缺省回落「全部」。 */
export function resolveSearchType(
  id: string | undefined,
  types: UiSearchTypeDefinition[] = listSearchTypes(),
): UiSearchTypeDefinition {
  return getSearchType(id, types) ?? ALL_SEARCH_TYPE
}

/**
 * 归一化类型 id：旧 target 值（`files` / `tasks`）映射到新类型 id
 * （`file-content` / `task`）；其余原样返回（含未知名，由调用方查表校验）。
 */
export function normalizeSearchTypeId(id: string | undefined): string {
  if (!id) return ALL_SEARCH_TYPE_ID
  return LEGACY_TARGET_MAP[id] ?? id
}

/** 该类型是否声明了「全部」语义（kinds 为空 = 不传 kinds）。 */
export function isAllSearchType(type: UiSearchTypeDefinition): boolean {
  return type.id === ALL_SEARCH_TYPE_ID || type.kinds.length === 0
}

/** kind → 拥有它的第一个类型（用于结果分组归属与 `ResultView` 派发）。 */
export function mapKindsToTypes(
  types: UiSearchTypeDefinition[],
): Map<string, UiSearchTypeDefinition> {
  const map = new Map<string, UiSearchTypeDefinition>()
  for (const type of types) {
    if (type.id === ALL_SEARCH_TYPE_ID) continue
    for (const kind of type.kinds) {
      if (!map.has(kind)) map.set(kind, type)
    }
  }
  return map
}