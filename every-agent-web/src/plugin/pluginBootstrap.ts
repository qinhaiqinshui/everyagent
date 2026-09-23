/**
 * src/plugin/pluginBootstrap.ts
 *
 * 前端插件启动：从 worker 拉取已激活插件清单，
 * 对有 web 入口的插件动态加载并调用 activate()。
 *
 * 对标 VSCode 的 extension host 启动流程。
 */

import { hubSession } from '@/hub/session'
import { pluginDispatcher } from './PluginDispatcher'
import type { PluginContext, PluginModule, PluginSdk, PluginStorage, CommandRegistry, Disposable } from './api'

/** 已加载的 Web 插件（供调试与卸载）。 */
const loadedWebPlugins: Map<string, { module: PluginModule; disposables: Disposable[] }> = new Map()

/** 命令注册表（per-session）。 */
const commands = new Map<string, (...args: unknown[]) => unknown | Promise<unknown>>()

/** per-plugin 本地存储。 */
function createPluginStorage(pluginId: string): PluginStorage {
  const prefix = `plugin:${pluginId}:`
  return {
    get<T>(key: string, defaultValue?: T): T | undefined {
      try {
        const raw = localStorage.getItem(prefix + key)
        return raw ? (JSON.parse(raw) as T) : defaultValue
      } catch {
        return defaultValue
      }
    },
    set(key: string, value: unknown) {
      try {
        localStorage.setItem(prefix + key, JSON.stringify(value))
      } catch {
        // localStorage 满或不可用，忽略
      }
    },
    delete(key: string) {
      localStorage.removeItem(prefix + key)
    },
  }
}

/** 命令注册表实现。 */
function createCommandRegistry(): CommandRegistry {
  return {
    registerCommand(id: string, handler: (...args: unknown[]) => unknown | Promise<unknown>): Disposable {
      commands.set(id, handler)
      return {
        dispose() {
          commands.delete(id)
        },
      }
    },
    async executeCommand(id: string, ...args: unknown[]): Promise<unknown> {
      const handler = commands.get(id)
      if (!handler) {
        throw new Error(`未知命令: ${id}`)
      }
      return handler(...args)
    },
  }
}

/** SDK 实现（经 hubSession RPC）。 */
function createPluginSdk(workerId: string, workspaceId: string, workspaceRoot: string): PluginSdk {
  return {
    rpc: async (_workerId: string, method: string, params?: unknown) => {
      return hubSession.rpcTo(_workerId || workerId, method, params as Record<string, unknown>)
    },
    workspace: {
      id: workspaceId,
      rootPath: workspaceRoot,
    },
    workerId,
  }
}

/**
 * 启动前端插件系统：
 * 1. 从 worker 拉取 plugin.list
 * 2. 对有 web 入口的插件动态加载
 * 3. 调用 activate(ctx)
 *
 * @param workerId 当前连接的 worker id
 * @param workspaceId 当前工作区 id
 * @param workspaceRoot 当前工作区根路径
 */
export async function bootWebPlugins(
  workerId: string,
  workspaceId: string,
  workspaceRoot: string,
): Promise<void> {
  let pluginList: Array<{
    id: string
    name: string
    version: string
    active: boolean
    webEntry?: string
  }> = []

  try {
    const result = await hubSession.rpcTo(workerId, 'plugin.list') as {
      plugins?: Array<{ id: string; name: string; version: string; active: boolean; webEntry?: string }>
    }
    pluginList = result?.plugins ?? []
  } catch {
    // worker 不支持 plugin.list 或未连接，静默降级
    return
  }

  for (const plugin of pluginList) {
    if (!plugin.active || !plugin.webEntry) {
      continue
    }

    try {
      // 动态加载 web 入口（ES Module）
      const mod = await import(/* @vite-ignore */ plugin.webEntry)
      const pluginModule: PluginModule = mod.default ?? mod
      if (typeof pluginModule.activate !== 'function') {
        continue
      }

      const ctx: PluginContext = {
        pluginId: plugin.id,
        extensionPath: plugin.webEntry,
        sdk: createPluginSdk(workerId, workspaceId, workspaceRoot),
        storage: createPluginStorage(plugin.id),
        commands: createCommandRegistry(),
        ui: {
          registerSidebarItem: (def) => pluginDispatcher.registerSidebarItem(def),
          registerWorkspaceTabType: (def) => pluginDispatcher.registerWorkspaceTabType(def),
          registerFileSidebarPanel: (def) => pluginDispatcher.registerFileSidebarPanel(def),
          registerComposerFooterControl: (def) => pluginDispatcher.registerComposerFooterControl(def),
          registerComposerAbovePanel: (def) => pluginDispatcher.registerComposerAbovePanel(def),
          registerToolCallDetailView: (def) => pluginDispatcher.registerToolCallDetailView(def),
          registerTaskFileMoreAction: (action) => pluginDispatcher.registerTaskFileMoreAction(action),
          registerTraceType: (def) => pluginDispatcher.registerTraceType(def),
          registerOutputBlock: (tag, handler) => pluginDispatcher.registerOutputBlock(tag, handler),
        },
      }

      await pluginModule.activate(ctx)
      loadedWebPlugins.set(plugin.id, { module: pluginModule, disposables: [] })
      console.log(`[plugins] web 插件已激活: ${plugin.id} (${plugin.name})`)
    } catch (e) {
      console.warn(`[plugins] web 插件 ${plugin.id} 加载失败:`, e)
    }
  }
}

/** 卸载所有 web 插件（页面卸载时调用）。 */
export async function shutdownWebPlugins(): Promise<void> {
  for (const [id, { module }] of loadedWebPlugins) {
    try {
      await module.deactivate?.()
    } catch {
      // 忽略
    }
    console.log(`[plugins] web 插件已卸载: ${id}`)
  }
  loadedWebPlugins.clear()
}
