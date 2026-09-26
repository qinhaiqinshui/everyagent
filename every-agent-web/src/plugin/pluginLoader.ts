/**
 * src/plugin/pluginLoader.ts
 *
 * 统一插件加载器：所有插件（内置 + 外部）统一经 plugin.webSource RPC
 * 获取预编译 JS 源码 → 重写 bare import 为 window 全局 → blob URL → import()。
 *
 * 加载流程：
 * 1. 调 plugin.list RPC → 拿到完整目录（plugins 数组）+ disabledIds
 * 2. 过滤：active + hasWebMain 且不在 disabledIds 中的插件
 * 3. 对每个插件：
 *    - 调 plugin.webSource RPC 拿 web/index.js 预编译产物
 *    - 重写 bare import（react/antd 等）为 window.__EA_* 全局引用
 *      （保证插件使用宿主同一 React 实例，避免 hooks 报错）
 *    - 创建 blob URL → import() → revoke
 * 4. 调用 activate(ctx) → 插件经 ctx.ui.register* 注册扩展点
 *
 * 宿主在模块加载时将 React/react-dom/antd/@ant-design/icons 暴露为
 * window.__EA_REACT__/window.__EA_REACT_DOM__/window.__EA_antd__/window.__EA_ICONS__，
 * 供 bare import 重写使用。
 *
 * worker 不可达时静默降级（不加载任何插件，不报错）。
 * 禁用的插件不加载。
 */

import React from 'react'
import * as ReactDOMNS from 'react-dom'
import * as antd from 'antd'
import * as Icons from '@ant-design/icons'
import { hubSession } from '@/hub/session'
import { workspaceRegistry } from '@/hub/workspaceRegistry'
import { domainEventBus } from '@/events/eventBus'
import { workspaceGateway } from '@/platform/fs/workspaceGateway'
import { pluginDispatcher } from './PluginDispatcher'
import type {
  PluginContext,
  PluginModule,
  PluginSdk,
  PluginStorage,
  PluginEvents,
  PluginFs,
  CommandRegistry,
  Disposable,
} from '@everyagent/plugin-api'

// ── 宿主全局暴露（供 blob URL 中的插件 bare import 重写使用） ──────────────

/**
 * 将宿主的 React/react-dom/antd/@ant-design/icons 实例暴露到 window，
 * 使经过 bare import 重写的插件 JS 能通过 `window.__EA_REACT__` 等获取
 * 与宿主完全相同的实例（避免多 React 实例导致 hooks 报错）。
 */
const _g = window as unknown as Record<string, unknown>
_g.__EA_REACT__ = React
_g.__EA_REACT_DOM__ = ReactDOMNS
_g.__EA_antd__ = antd
_g.__EA_ICONS__ = Icons

// ── bare import → window 全局引用重写 ─────────────────────────────────────

/**
 * bare import 模块名 → window 全局变量名映射。
 *
 * esbuild 构建插件时将 react/react-dom/antd/@ant-design/icons 设为 external，
 * 产出的 JS 中保留 `import React2 from "react"` 等 bare import。
 * blob URL 中的 import() 无法解析 bare import，需重写为 const 引用 window 全局。
 */
const BARE_IMPORT_MAP: Record<string, string> = {
  'react': 'window.__EA_REACT__',
  'react-dom': 'window.__EA_REACT_DOM__',
  'antd': 'window.__EA_antd__',
  '@ant-design/icons': 'window.__EA_ICONS__',
}

/**
 * 将插件 JS 中的 bare import 重写为 const 引用 window 全局。
 *
 * 处理 esbuild 产出的标准 ESM import 语句：
 * - `import React2 from "react"` → `const React2 = window.__EA_REACT__`
 * - `import { Tree, Input } from "antd"` → `const { Tree, Input } = window.__EA_antd__`
 * - `import * as React from "react"` → `const React = window.__EA_REACT__`
 * - `import React, { useState } from "react"` → 拆分为两条 const
 */
function rewriteBareImports(source: string): string {
  let result = source
  for (const [specifier, globalExpr] of Object.entries(BARE_IMPORT_MAP)) {
    const esc = specifier.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const q = `["']`

    // 混合：import X, { Y, Z } from "spec"
    result = result.replace(
      new RegExp(`import\\s+(\\w+)\\s*,\\s*\\{([^}]+)\\}\\s+from\\s+${q}${esc}${q}`, 'g'),
      `const $1 = ${globalExpr}; const { $2 } = ${globalExpr}`,
    )
    // 命名空间：import * as X from "spec"
    result = result.replace(
      new RegExp(`import\\s+\\*\\s+as\\s+(\\w+)\\s+from\\s+${q}${esc}${q}`, 'g'),
      `const $1 = ${globalExpr}`,
    )
    // 默认：import X from "spec"
    result = result.replace(
      new RegExp(`import\\s+(\\w+)\\s+from\\s+${q}${esc}${q}`, 'g'),
      `const $1 = ${globalExpr}`,
    )
    // 命名：import { X, Y } from "spec"
    result = result.replace(
      new RegExp(`import\\s+\\{([^}]+)\\}\\s+from\\s+${q}${esc}${q}`, 'g'),
      `const { $1 } = ${globalExpr}`,
    )
    // 副作用：import "spec"（移除）
    result = result.replace(
      new RegExp(`import\\s+${q}${esc}${q}`, 'g'),
      '',
    )
  }
  return result
}

// ── 辅助工厂（从原 pluginBootstrap.ts 复用） ─────────────────────────────

/** per-plugin 本地存储（localStorage，按 pluginId 前缀隔离）。 */
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
  const commands = new Map<string, (...args: unknown[]) => unknown | Promise<unknown>>()
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
    rpc: async (targetWorkerId: string, method: string, params?: unknown) => {
      return hubSession.rpcTo(targetWorkerId || workerId, method, params as Record<string, unknown>)
    },
    workspace: {
      id: workspaceId,
      rootPath: workspaceRoot,
      list: async () =>
        (workspaceRegistry.current?.workspaces ?? []).map((entry) => ({
          root: entry.root,
          workerId: entry.workerId,
          id: entry.id,
          addedAt: entry.addedAt,
          lastActivityAt: entry.lastActivityAt,
          missing: entry.missing,
        })),
      workerIdOfRoot: (root: string) => workspaceRegistry.workerIdOfRoot(root) ?? undefined,
    },
    workerId,
  }
}

/** 领域事件总线实现（委托宿主 domainEventBus）。 */
function createPluginEvents(): PluginEvents {
  return {
    on(eventName, handler) {
      const unsubscribe = domainEventBus.subscribe(
        eventName as Parameters<typeof domainEventBus.subscribe>[0],
        handler as (payload: unknown) => void,
      )
      return { dispose: unsubscribe }
    },
    emit(eventName, payload) {
      domainEventBus.emit(
        eventName as Parameters<typeof domainEventBus.emit>[0],
        payload as Parameters<typeof domainEventBus.emit>[1],
      )
    },
  }
}

/** 文件系统网关实现（委托宿主 workspaceGateway）。 */
function createPluginFs(): PluginFs {
  return {
    listDir: (workspaceRoot, dir) =>
      workspaceGateway.listDir(workspaceRoot, dir).then((rows) =>
        rows.map((row) => ({
          path: row.path,
          name: row.name,
          isDirectory: row.isDirectory,
          size: row.size,
          mtimeMs: row.mtimeMs,
          createdTs: row.createdTs,
        })),
      ),
    delete: (workspaceRoot, path) => workspaceGateway.deletePath(workspaceRoot, path),
  }
}

// ── 已加载插件跟踪（供卸载） ───────────────────────────────────────────────

const loadedPlugins: Map<string, { module: PluginModule; disposables: Disposable[] }> = new Map()

// ── 插件清单条目类型（plugin.list RPC 返回） ──────────────────────────────

interface PluginListEntry {
  id: string
  name: string
  version: string
  description: string
  author: string
  source: string
  active: boolean
  hasMain: boolean
  hasWebMain: boolean
}

// ── 核心加载逻辑 ───────────────────────────────────────────────────────────

/**
 * 加载插件：从 plugin.list RPC 发现并激活插件。
 *
 * worker 不可达时静默降级（不加载任何插件，不报错）。
 * 禁用的插件（在 disabledIds 中）不加载。
 *
 * 幂等：重复调用安全（已加载的插件不会重复 activate）。
 */
export async function loadPlugins(): Promise<void> {
  // 1. 找到第一个已连接 worker
  let workerId: string | null = null
  hubSession.forEachConnectedWorker((wid: string) => {
    if (!workerId) workerId = wid
  })
  if (!workerId) {
    // worker 不可达，静默降级
    return
  }

  // 2. 获取工作区信息（sys.info 失败不阻塞）
  let workspaceId = 'defaultworkspace'
  let workspaceRoot = ''
  try {
    const info = await hubSession.rpcTo(workerId, 'sys.info') as { workspaceRoot?: string }
    workspaceRoot = info?.workspaceRoot ?? ''
  } catch {
    // sys.info 失败不阻塞插件加载
  }

  // 3. 调 plugin.list RPC 拿插件清单
  let plugins: PluginListEntry[] = []
  let disabledIds: string[] = []
  try {
    const result = await hubSession.rpcTo(workerId, 'plugin.list') as {
      plugins?: PluginListEntry[]
      disabledIds?: string[]
    }
    plugins = result?.plugins ?? []
    disabledIds = result?.disabledIds ?? []
  } catch {
    // worker 不支持 plugin.list 或未连接，静默降级
    return
  }

  const disabledSet = new Set(disabledIds)

  // 4. 过滤：active + hasWebMain + 未禁用
  const webPlugins = plugins.filter(
    (p) => p.active && p.hasWebMain && !disabledSet.has(p.id),
  )

  // 5. 逐个加载并激活
  for (const plugin of webPlugins) {
    // 幂等：已加载的跳过
    if (loadedPlugins.has(plugin.id)) {
      continue
    }

    try {
      const mod = await loadPluginModule(workerId, plugin)
      const pluginModule = mod.default ?? mod
      if (typeof pluginModule.activate !== 'function') {
        continue
      }

      const ctx: PluginContext = {
        pluginId: plugin.id,
        extensionPath: 'web/index.js',
        sdk: createPluginSdk(workerId, workspaceId, workspaceRoot),
        storage: createPluginStorage(plugin.id),
        commands: createCommandRegistry(),
        events: createPluginEvents(),
        fs: createPluginFs(),
        // pluginDispatcher 使用 web 内部强类型（WorkspaceTab/FileTabResource 等），
        // 与 @everyagent/plugin-api 的最小化接口在 ComponentType 上因不变性不兼容，
        // 运行时行为一致，此处安全强转。
        ui: pluginDispatcher as unknown as PluginContext['ui'],
      }

      await pluginModule.activate(ctx)
      loadedPlugins.set(plugin.id, { module: pluginModule, disposables: [] })
      console.log(`[plugins] 插件已激活: ${plugin.id} (${plugin.name})`)
    } catch (e) {
      console.warn(`[plugins] 插件 ${plugin.id} 加载失败:`, e)
    }
  }
}

/**
 * 统一加载插件模块：所有插件（内置 + 外部）均经 plugin.webSource RPC
 * 获取预编译 JS → 重写 bare import → blob URL → import()。
 */
async function loadPluginModule(
  workerId: string,
  plugin: PluginListEntry,
): Promise<{ default: PluginModule }> {
  const result = await hubSession.rpcTo(workerId, 'plugin.webSource', {
    pluginId: plugin.id,
    path: 'web/index.js',
  }) as { content?: string }

  const source = result?.content
  if (!source) {
    throw new Error(`插件 ${plugin.id} 无 web/index.js 源码`)
  }

  // 重写 bare import 为 window 全局引用，使 blob URL 中不残留无法解析的 bare import。
  const rewritten = rewriteBareImports(source)

  const blob = new Blob([rewritten], { type: 'text/javascript' })
  const url = URL.createObjectURL(blob)
  try {
    const mod = await import(/* @vite-ignore */ url) as { default?: PluginModule }
    return mod as { default: PluginModule }
  } finally {
    URL.revokeObjectURL(url)
  }
}

/** 卸载所有已加载插件（页面卸载时调用）。 */
export async function shutdownPlugins(): Promise<void> {
  for (const [id, { module }] of loadedPlugins) {
    try {
      await module.deactivate?.()
    } catch {
      // 忽略
    }
    console.log(`[plugins] 插件已卸载: ${id}`)
  }
  loadedPlugins.clear()
}
