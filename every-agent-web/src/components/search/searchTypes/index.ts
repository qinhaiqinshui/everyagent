/**
 * 搜索类型注册表模块（barrel）。
 */
export {
  ALL_SEARCH_TYPE,
  ALL_SEARCH_TYPE_ID,
  BUILTIN_SEARCH_TYPES,
  FILE_CONTENT_KIND,
  FILE_NAME_KIND,
  TASK_KIND,
  getSearchType,
  isAllSearchType,
  listSearchTypes,
  mapKindsToTypes,
  normalizeSearchTypeId,
  resolveSearchType,
} from './registry'
export { buildSearchResultGroups, type GroupAdapterCallbacks } from './groupAdapter'