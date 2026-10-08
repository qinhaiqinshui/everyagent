/**
 * 搜索目标注册表。
 *
 * - SEARCH_TARGETS:全部注册目标(面板「更多」菜单的目标切换项按此渲染);
 * - getSearchTarget(id):精确查表,未知/缺省 id 返回 undefined(状态机据此报错);
 * - resolveSearchTarget(id):查表并回落 files(事件接收等「未知 id 不能报错」的场景)。
 *
 * 新增搜索目标 = 增补一个 SearchTargetDefinition 注册项;面板与状态机按定义驱动,
 * 不再出现 target 二值分支。
 */
import { filesTarget } from './filesTarget'
import { tasksTarget } from './tasksTarget'
import type { SearchTargetDefinition } from './types'

export type {
  FilesSearchExecution,
  SearchTargetDefinition,
  SearchTargetExecution,
  SearchTargetParamError,
  SearchTargetResultGroup,
  SearchTargetSupports,
  SearchTargetTextState,
  TasksSearchExecution,
} from './types'
export { filesTarget } from './filesTarget'
export { tasksTarget } from './tasksTarget'

/** 全部注册目标(顺序即目标切换菜单的展示顺序)。 */
export const SEARCH_TARGETS: readonly SearchTargetDefinition[] = [filesTarget, tasksTarget]

/** files 目标 id(默认目标与未知 id 的回落值)。 */
export const FILES_TARGET_ID = filesTarget.id

/** tasks 目标 id。 */
export const TASKS_TARGET_ID = tasksTarget.id

/** 按 id 查注册表;未知/缺省 id 返回 undefined(调用方决定报错或回落)。 */
export function getSearchTarget(id: string | undefined): SearchTargetDefinition | undefined {
  if (!id) return undefined
  return SEARCH_TARGETS.find((target) => target.id === id)
}

/** 按 id 查注册表,未知/缺省 id 回落 files(事件接收处校验用,保持发射方零改动)。 */
export function resolveSearchTarget(id: string | undefined): SearchTargetDefinition {
  return getSearchTarget(id) ?? filesTarget
}
