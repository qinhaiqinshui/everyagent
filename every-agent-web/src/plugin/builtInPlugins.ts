/**
 * 内置插件自动发现：用 import.meta.glob 扫描所有内置插件的 web/index.ts。
 * 删除插件目录 = glob 无匹配 = 零报错。
 */
import { pluginDispatcher } from './PluginDispatcher'
import type { PluginContext, PluginModule } from './api'

const pluginModules = import.meta.glob('@plugins/*/web/index.ts')

export async function loadBuiltInPlugins(): Promise<void> {
  for (const [path, importFn] of Object.entries(pluginModules)) {
    try {
      const mod = (await importFn()) as { default?: PluginModule }
      const pluginModule = mod.default
      if (!pluginModule || typeof pluginModule.activate !== 'function') {
        continue
      }
      // 内置插件通过 pluginDispatcher 注册（ui 注册表复用）。
      // 创建最小 PluginContext：内置插件只用 ctx.ui.register* 方法，
      // sdk/storage/commands 为占位实现（内置插件不依赖远程 RPC / 持久化 / 命令）。
      const ctx: PluginContext = {
        pluginId: path,
        extensionPath: path,
        sdk: {
          rpc: async () => undefined,
          workspace: { id: '', rootPath: '' },
          workerId: '',
        },
        storage: {
          get<T>(_key: string, defaultValue?: T): T | undefined {
            return defaultValue
          },
          set: () => {},
          delete: () => {},
        },
        commands: {
          registerCommand: () => ({ dispose() {} }),
          executeCommand: async () => undefined,
        },
        ui: pluginDispatcher,
      }
      await pluginModule.activate(ctx)
      console.log(`[plugins] 内置插件已加载: ${path}`)
    } catch (e) {
      console.warn(`[plugins] 内置插件加载失败: ${path}`, e)
    }
  }
}
