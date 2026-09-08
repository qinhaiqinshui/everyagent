/**
 * 工具美化视图注册表（目录即注册表）。
 *
 * 扫描同目录下所有 `.tsx`（除本文件与各视图实现外的无关文件会因缺少
 * `toolView`/`toolViews` 导出而被类型守卫跳过），收集自描述的视图定义，
 * 按 `toolName` 建表。命中返回专用视图，未命中回退 `DefaultToolView`
 * （= 现有默认样子）。新增工具美化只需在目录下新建一个 `.tsx`。
 */

import type { ComponentType } from 'react'
import DefaultToolView from './DefaultToolView'
import type { ToolViewDefinition, ToolViewModuleDefinition, ToolViewProps } from './types'

const modules = import.meta.glob<ToolViewModuleDefinition>('./*.tsx', { eager: true })

const registry = new Map<string, ComponentType<ToolViewProps>>()
for (const mod of Object.values(modules)) {
  const defs: ToolViewDefinition[] = []
  if (mod?.toolView?.toolName && mod.toolView.component) {
    defs.push(mod.toolView)
  }
  if (Array.isArray(mod?.toolViews)) {
    for (const def of mod.toolViews) {
      if (def?.toolName && def.component) {
        defs.push(def)
      }
    }
  }
  for (const def of defs) {
    registry.set(def.toolName, def.component)
  }
}

/** 按工具名取美化视图组件；未注册则返回默认视图。 */
export function getToolView(toolName: string): ComponentType<ToolViewProps> {
  return registry.get(toolName) ?? DefaultToolView
}

/** 判断某工具是否注册了专用美化视图。 */
export function hasToolView(toolName: string): boolean {
  return registry.has(toolName)
}
