/**
 * 统一工作区标签类型注册表。
 *
 * 内置标签类型由各业务组件模块显式导出并在此处登记；
 * 插件标签经 `ui.workspace_tab_types` 扩展点注册到 PluginDispatcher，
 * 本注册表实时合并插件贡献（与文件内容编辑器合并同模式）。
 */

import type { WorkspaceTab } from '@/types'
import type { UiWorkspaceTabTypeDefinition } from './types'
import { pluginDispatcher } from './PluginDispatcher'
import { fileTabType } from '@/components/files/fileTabType'
import { diffTabType } from '@/components/files/FileDiffTabType'
import { terminalTabType } from '@/components/files/terminalTabType'
import { taskChatTabType } from '@/components/task/taskChatTabType'
import { pageTabTypes } from '@/components/system/pageTabTypes'

/** 内置标签类型（不含 git-history——已由 git 插件经扩展点注册）。 */
const builtinDefinitions: UiWorkspaceTabTypeDefinition[] = [
  fileTabType,
  diffTabType,
  terminalTabType,
  taskChatTabType,
  ...pageTabTypes,
]

/**
 * 合并内置定义与插件注册的定义（插件优先——同名 key 可覆盖内置）。
 * 每次调用实时合并，插件在 activate() 中注册后立即生效。
 */
function allDefinitions(): UiWorkspaceTabTypeDefinition[] {
  return [...pluginDispatcher.listRegisteredWorkspaceTabTypes(), ...builtinDefinitions]
}

/** 兼容旧调用方:内置定义同步可用,无需异步装载。 */
export async function loadWorkspaceTabTypeDefinitions(): Promise<UiWorkspaceTabTypeDefinition[]> {
  return allDefinitions()
}

/**
 * 由标签对象解析其注册表 key。
 * page -> `page:<pageId>`;plugin -> `tab.pluginTabType`;其余 -> tabType 本身。
 */
export function getTabTypeKey(tab: WorkspaceTab): string {
  if (tab.tabType === 'page') return `page:${tab.pageId}`
  if (tab.tabType === 'plugin') return tab.pluginTabType
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
  return allDefinitions().find((def) => def.tabTypeKey === tabTypeKey)
}
