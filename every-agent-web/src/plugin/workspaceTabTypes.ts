/**
 * 统一工作区标签类型注册表。
 *
 * 内置标签类型由各业务组件模块显式导出并在此处登记；
 * 插件标签经 `ui.workspace_tab_types` 扩展点注册到 PluginDispatcher，
 * 本注册表实时合并插件贡献（与文件内容编辑器合并同模式）。
 */

import type { WorkspaceTab } from '@/types'
import type { UiWorkspaceTabTypeDefinition, WorkspaceTabCreateInput } from './types'
import { pluginDispatcher } from './PluginDispatcher'
import { createWorkspacePluginTab } from '@/components/app/workspaceShellState'
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

/**
 * 插件型/未注册型的通用创建兜底：标签 id 由 `${tabTypeKey}:${data.id ?? 首个值 ?? tabTypeKey}`
 * 确定性构造（同一 key + 同一 data 恒为同一标签，重复打开即聚焦），data 原样进 `tab.data`。
 */
export function createPluginTabFallback(tabTypeKey: string, input: WorkspaceTabCreateInput): WorkspaceTab {
  const data = input.data
  const def = getWorkspaceTabTypeDefinition(tabTypeKey)
  const pluginId = def?.pluginId ?? tabTypeKey
  const key = data.id ?? Object.values(data)[0] ?? tabTypeKey
  const title = input.title && input.title.trim() ? input.title.trim() : (data.title ?? key)
  return createWorkspacePluginTab({
    id: `${tabTypeKey}:${key}` as const,
    pluginTabType: tabTypeKey,
    pluginId,
    data,
    title,
  })
}

/**
 * 取某标签类型 key 的创建器（内置型取自身 createTab；插件型/未注册型走通用兜底）。
 * 返回的创建器已绑定 tabTypeKey，调用方只需给参数袋。
 */
export function getWorkspaceTabCreator(tabTypeKey: string): (input: WorkspaceTabCreateInput) => WorkspaceTab {
  const def = getWorkspaceTabTypeDefinition(tabTypeKey)
  if (def?.createTab) {
    return def.createTab
  }
  return (input) => createPluginTabFallback(tabTypeKey, input)
}
