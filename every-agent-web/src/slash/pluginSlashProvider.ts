/**
 * src/slash/pluginSlashProvider.ts
 *
 * 把插件贡献的 `/` 候选(经 `ui.slash_command_providers` 扩展点)接入本地
 * slashCommandRegistry。
 *
 * 插件在 activate() 时经 ctx.ui.registerSlashCommandProvider(def) 注册 loader;
 * 本桥接把「插件数据源」作为一个 provider 注册进注册中心:`/` 菜单打开时拉取
 * 全部插件 provider 的条目,映射为宿主 SlashCommandItem(与 worker 远端条目
 * 同表混排、按 id 去重)。无插件注册时返回空数组,零行为变化;
 * select 包装为透传宿主第二参 selectCtx(taskId/workspace/workerId)。
 */
import { slashCommandRegistry } from '@/slash/slashCommandRegistry'
import { pluginDispatcher } from '@/plugin/PluginDispatcher'
import type { SlashCommandItem } from '@/slash/types'
import type { PluginSlashCommandItem } from '@everyagent/plugin-api'

/**
 * 插件条目 → 宿主 SlashCommandItem。
 * select 包装为 `(_item, selectCtx) => def.select(selectCtx ?? {})`:
 * 宿主 applySlashSelection 传入选中上下文,插件 select 声明为可选 ctx(缺省补 {});
 * 返回值形态(单个/数组/Promise)与宿主 SlashSelectionResult 结构一致。
 */
function toHostItem(def: PluginSlashCommandItem): SlashCommandItem {
  return {
    id: def.id,
    title: def.title,
    subtitle: def.subtitle,
    icon: def.icon,
    group: def.group,
    defaultSelected: def.defaultSelected,
    select: (_item, selectCtx) => def.select(selectCtx ?? {}),
  }
}

/** 注册「插件数据源」provider;进程内幂等(同 id 覆盖)。 */
export function registerPluginSlashProvider(): void {
  slashCommandRegistry.registerProvider('plugins', async (workerId?: string) => {
    const defs = pluginDispatcher.listSlashCommandProviders()
    const loaded = await Promise.all(defs.map((def) => def.load(workerId)))
    return loaded.flat().map(toHostItem)
  })
}
