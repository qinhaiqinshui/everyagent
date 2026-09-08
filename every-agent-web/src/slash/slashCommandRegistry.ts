/**
 * src/slash/slashCommandRegistry.ts
 *
 * `/` 斜杠命令的专用注册中心。
 *
 * 设计要点：
 * - 与 task 无关，不挂在 taskQueryService，也不走 plugin 扩展点调度。
 * - 任何插件/模块在初始化时通过 registerProvider 注册一个「异步 loader」，
 *   返回 SlashCommandItem 列表；loader 可异步（例如 skill 来自 IndexedDB 的 /skills）。
 * - 输入框在 `/` 浮层打开时调用 list() 取全量条目，按 group 分组渲染。
 * - 每个条目只暴露展示字段（title/subtitle/icon/group）与一个 select 回调；
 *   select 返回「要插入到 / 位置的内容」字符串：若 `isOpaqueTokenText(str)===true`
 *   则渲染为胶囊，否则原样插入纯文本。slash 层不解析/不关心内容本质，只负责展示与插入。
 * - 注册路径：各来源统一由 `Plugin.slashCommandProviders` 字段声明，
 *   在 `registerPlugin()` 阶段经 `registerProviderDefinition(...)` 登记（id → loader）；删插件即自动注销。
 */

import type {
  SlashCommandItem,
  SlashCommandProvider,
  SlashCommandProviderDefinition,
} from '@/slash/types'

export type {
  SlashCommandItem,
  SlashCommandProvider,
  SlashCommandProviderDefinition,
} from '@/slash/types'

const providers = new Map<string, SlashCommandProvider>()

export const slashCommandRegistry = {
  /** 注册一个来源（同 id 覆盖）。 */
  registerProvider(id: string, loader: SlashCommandProvider): void {
    providers.set(id, loader)
  },
  /** 注册一个可发现的 `/` 命令来源定义。 */
  registerProviderDefinition(definition: SlashCommandProviderDefinition): void {
    providers.set(definition.id, definition.load)
  },
  /** 注销一个来源。 */
  unregisterProvider(id: string): void {
    providers.delete(id)
  },
  /** 取全部来源的条目（按 id 去重，后者覆盖）。 */
  async list(workerId?: string): Promise<SlashCommandItem[]> {
    const loaded = await Promise.all([...providers.values()].map((loader) => loader(workerId)))
    const byId = new Map<string, SlashCommandItem>()
    for (const items of loaded) {
      for (const item of items) {
        byId.set(item.id, item)
      }
    }
    return [...byId.values()]
  },
}
