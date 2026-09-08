/**
 * 统一工作区标签类型注册表(自 n 分支迁移,插件部分裁剪)。
 *
 * 内置标签类型由各业务组件模块显式导出并在此处登记;n 的插件标签收集
 * (`ui.workspace_tab_types` 扩展点)随插件底座裁剪,只保留内置定义。
 */

import type { WorkspaceTab } from '@/types'
import type { UiWorkspaceTabTypeDefinition } from './types'
import { fileTabType } from '@/components/files/fileTabType'
import { diffTabType } from '@/components/files/FileDiffTabType'
import { taskChatTabType } from '@/components/task/taskChatTabType'
import { pageTabTypes } from '@/components/system/pageTabTypes'

const cachedDefinitions: UiWorkspaceTabTypeDefinition[] = [
  fileTabType,
  diffTabType,
  taskChatTabType,
  ...pageTabTypes,
]

/** 兼容旧调用方:内置定义同步可用,无需异步装载。 */
export async function loadWorkspaceTabTypeDefinitions(): Promise<UiWorkspaceTabTypeDefinition[]> {
  return cachedDefinitions
}

/**
 * 由标签对象解析其注册表 key。
 * page -> `page:<pageId>`;其余 -> tabType 本身。
 */
export function getTabTypeKey(tab: WorkspaceTab): string {
  if (tab.tabType === 'page') return `page:${tab.pageId}`
  return tab.tabType
}

/** 按标签对象取对应的类型定义(统一查表入口)。 */
export function getTabDefinition(tab: WorkspaceTab | null): UiWorkspaceTabTypeDefinition | undefined {
  if (!tab) return undefined
  return getWorkspaceTabTypeDefinition(getTabTypeKey(tab))
}

/** 按 `tabTypeKey` 同步取单个标签类型定义。 */
export function getWorkspaceTabTypeDefinition(
  tabTypeKey: string,
): UiWorkspaceTabTypeDefinition | undefined {
  return cachedDefinitions.find((def) => def.tabTypeKey === tabTypeKey)
}
