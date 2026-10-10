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
 * 卸载（known-issues #8）：激活时 ctx.ui / ctx.commands / ctx.events 均包一层
 * 收集代理，注册方法返回的 Disposable 全部记入该插件的 disposables；页面卸载
 * （pagehide，涵盖刷新/关闭/跳转）时统一调用 module.deactivate() 再逆序 dispose
 * 全部注册项——与后端 worker 优雅关闭（@PreDestroy → deactivate）对齐的停用钩子。
 *
 * worker 不可达时静默降级（不加载任何插件，不报错）。
 * 禁用的插件不加载。
 */

import React from 'react'
import * as ReactDOMNS from 'react-dom'
import * as antd from 'antd'
import * as Icons from '@ant-design/icons'
import * as ReactJSXRuntime from 'react/jsx-runtime'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { hubSession } from '@/hub/session'
import { workspaceRegistry } from '@/hub/workspaceRegistry'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
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
_g.__EA_REACT_JSX__ = ReactJSXRuntime
// react-markdown / remark-gfm 挂的是各自包的 default export（组件 / 插件函数），
// 插件侧只可用默认导入形态（import Markdown from 'react-markdown'）；
// 供插件渲染 Markdown 复用宿主同一份实例（plugin-manager 扩展详情页 README 区）。
_g.__EA_REACT_MARKDOWN__ = Markdown
_g.__EA_REMARK_GFM__ = remarkGfm

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
  'react/jsx-runtime': 'window.__EA_REACT_JSX__',
  'antd': 'window.__EA_antd__',
  '@ant-design/icons': 'window.__EA_ICONS__',
  'react-markdown': 'window.__EA_REACT_MARKDOWN__',
  'remark-gfm': 'window.__EA_REMARK_GFM__',
}

/**
 * 将 import 命名子句转换为解构子句。
 *
 * esbuild 在命名冲突时产出重命名形式 `import { jsx as jsx2, jsxs } from ...`，
 * 直接内插到 `const { ... }` 会因 JS 解构没有 `as` 语法而抛
 * SyntaxError，须转换为解构重命名 `jsx: jsx2`。
 */
function toDestructureClause(names: string): string {
  return names
    .split(',')
    .map((part) => part.trim())
    .filter(Boolean)
    .map((part) => part.replace(/\s+as\s+/, ': '))
    .join(', ')
}

/**
 * 将插件 JS 中的 bare import 重写为 const 引用 window 全局。
 *
 * 处理 esbuild 产出的标准 ESM import 语句：
 * - `import React2 from "react"` → `const React2 = window.__EA_REACT__`
 * - `import { Tree, Input } from "antd"` → `const { Tree, Input } = window.__EA_antd__`
 * - `import { jsx as jsx2 } from "react/jsx-runtime"` → `const { jsx: jsx2 } = window.__EA_REACT_JSX__`
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
      (_m, def: string, names: string) =>
        `const ${def} = ${globalExpr}; const { ${toDestructureClause(names)} } = ${globalExpr}`,
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
    // 命名：import { X, Y as Z } from "spec"
    result = result.replace(
      new RegExp(`import\\s+\\{([^}]+)\\}\\s+from\\s+${q}${esc}${q}`, 'g'),
      (_m, names: string) => `const { ${toDestructureClause(names)} } = ${globalExpr}`,
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

/**
 * 解析插件 RPC 的目标 worker:优先用「加载期记下的那台」(若它的连接还在),
 * 否则回落到当前已连的那台。
 *
 * <p>为什么不能在闭包里一次性捕获 workerId:插件的 sdk 是在某台 worker 连接期建立的,
 * 用户切换启用的 worker 后闭包里的 id 就指向一条已关闭的连接,此后插件的每一次 RPC
 * (如轮末面板拉 file-changes)都会打到不存在的 worker 上报错(§8.2)。
 */
function resolvePluginWorkerId(loadedFor: string): string {
  if (loadedFor && hubSession.workerClients.has(loadedFor)) return loadedFor
  for (const [workerId, client] of hubSession.workerClients) {
    if (client.state === 'open') return workerId
  }
  return loadedFor
}

/** SDK 实现（经 hubSession RPC）。 */
function createPluginSdk(workerId: string, workspaceId: string, workspaceRoot: string): PluginSdk {
  return {
    rpc: async (targetWorkerId: string, method: string, params?: unknown) => {
      return hubSession.rpcTo(targetWorkerId || resolvePluginWorkerId(workerId), method, params as Record<string, unknown>)
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
    // 读取期解析:切换 worker 后插件看到的自身宿主 worker 身份随之更新。
    get workerId() {
      return resolvePluginWorkerId(workerId)
    },
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

// ── Disposable 收集与卸载（known-issues #8） ───────────────────────────────

/** 判断返回值是否为 Disposable 形状（有 dispose 函数的对象）。 */
function isDisposable(value: unknown): value is Disposable {
  return !!value && typeof value === 'object' && typeof (value as Disposable).dispose === 'function'
}

/**
 * 包一层注册表代理：透传全部方法调用，凡返回 Disposable 的（ui.register*、
 * commands.registerCommand、events.on）自动收集进 disposables——宿主代管的
 * dispose 收集循环。插件自己持有的 Disposable 依然有效：各注册点的 dispose
 * 均为 Map/Set 删除或数组 splice，重复调用幂等无害。
 */
function trackDisposables<T extends object>(registry: T, disposables: Disposable[]): T {
  return new Proxy(registry, {
    get(target, prop) {
      const value = Reflect.get(target, prop)
      if (typeof value !== 'function') return value
      const fn = value as (...args: unknown[]) => unknown
      return function tracked(...args: unknown[]) {
        const ret = fn.apply(target, args)
        if (isDisposable(ret)) disposables.push(ret)
        return ret
      }
    },
  })
}

/**
 * 卸载单个已激活插件：先调 `module.deactivate()`（对标 VSCode 的停用钩子），
 * 再逆序 dispose 宿主收集的全部 Disposable（注册项/命令/事件订阅），最后移除
 * 注入的插件 CSS。幂等（未加载的插件直接返回）；单点异常只 WARN 不扩散，
 * 不影响其余插件与注册项的清理。
 */
export async function unloadPlugin(pluginId: string): Promise<void> {
  const entry = loadedPlugins.get(pluginId)
  if (!entry) return
  loadedPlugins.delete(pluginId)

  if (typeof entry.module.deactivate === 'function') {
    try {
      await entry.module.deactivate()
    } catch (e) {
      console.warn(`[plugins] 插件 ${pluginId} deactivate 抛错:`, e)
    }
  }

  for (const disposable of [...entry.disposables].reverse()) {
    try {
      disposable.dispose()
    } catch (e) {
      console.warn(`[plugins] 插件 ${pluginId} 清理注册项抛错:`, e)
    }
  }

  document.getElementById(`plugin-css:${pluginId}`)?.remove()
}

/** 卸载全部已激活插件（页面卸载时的统一停用点）。 */
export async function unloadAllPlugins(): Promise<void> {
  for (const pluginId of [...loadedPlugins.keys()]) {
    await unloadPlugin(pluginId)
  }
}

/**
 * 页面卸载停用接线（幂等，首个插件激活成功时挂上）。
 *
 * 生命周期挂点的取舍：浏览器没有「插件宿主关闭」事件，前端真实存在的卸载点
 * 只有页面离开（刷新/关闭/跳转——插件面板「重新加载」按钮触发的 location.reload()
 * 也落在这一刻）。在 pagehide 时统一 deactivate + dispose，与后端 worker 优雅
 * 关闭（@PreDestroy → deactivate）对齐；运行期禁用插件不做热卸载——worker 侧
 * Java 贡献要到下一次启动才摘除，前端单独摘除会造成两侧不同步，统一维持
 * 「重启 worker + 刷新页面」的生效边界。注意这是「尽力而为」钩子：同步清理
 * 一定执行，异步 deactivate（await 后续）不保证在页面卸载前完成。
 */
let teardownWired = false
function wirePageTeardown(): void {
  if (teardownWired) return
  teardownWired = true
  window.addEventListener('pagehide', () => {
    void unloadAllPlugins()
  })
}


// ── 并发保护 ────────────────────────────────────────────────────────────────

/**
 * 正在进行中的加载 Promise（单例）。
 *
 * <p>为什么需要它：worker 首次连接时，{@code HubClient.onReconnect}（welcome 触发）
 * 与 {@code hubSession.onWorkerConnectionsChanged}（connectWorker finally 触发）几乎同时
 * 回调到 main.tsx 的两处 {@code loadPlugins()}。两次调用并发执行，第一次的异步 RPC
 * （plugin.list）尚未返回、{@code loadedPlugins.set} 尚未执行，第二次进入时幂等检查
 * 全部 miss，于是同一插件被 {@code activate()} 两次，注册表不去重 → 侧边栏出现两个
 * git/扩展图标、轮末出现两个文件变更块。
 *
 * <p>用 Promise 锁把「正在加载」语义坐实：第二次调用直接复用第一次的 Promise，不重复执行。
 */
let loadingPromise: Promise<void> | null = null

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
  /** 清单 webMain 原始值（源码路径，如 "web/index.ts"；旧 worker 可能缺省为空串）。 */
  webMain: string
}

/**
 * 把清单 `webMain`（源码路径，如 `"web/index.ts"`）映射为 web 产物路径：
 * 去掉最后一个扩展名后拼 `.js`。空值/无扩展名一律回退约定产物位 `web/index.js`。
 * （构建侧 build-plugins.mjs 固定输出 `<id>/web/index.js`，故仓内约定值换算后不变。）
 */
function webEntryJsPath(webMain: string): string {
  const trimmed = (webMain ?? '').trim()
  if (!trimmed) return 'web/index.js'
  return trimmed.replace(/\.[^./\\]+$/, '') + '.js'
}

// ── 核心加载逻辑 ───────────────────────────────────────────────────────────

/**
 * 加载插件：从 plugin.list RPC 发现并激活插件。
 *
 * worker 不可达时静默降级（不加载任何插件，不报错）。
 * 禁用的插件（在 disabledIds 中）不加载。
 *
 * 幂等：重复调用安全（已加载的插件不会重复 activate）。
 *
 * 并发保护：worker 首次连接时 onReconnect 与 onWorkerConnectionsChanged 几乎同时触发
 * 两次调用，用 Promise 锁保证只执行一次（见 loadingPromise 注释）。
 */
export async function loadPlugins(): Promise<void> {
  if (loadingPromise) return loadingPromise
  loadingPromise = doLoadPlugins().finally(() => {
    loadingPromise = null
  })
  return loadingPromise
}

/**
 * 实际加载逻辑（由 loadPlugins 包裹并发保护后调用）。
 */
async function doLoadPlugins(): Promise<void> {
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

      // 宿主代管的 Disposable 收集桶：激活期间（及之后）经注册方法返回的全部
      // Disposable 都会进这里，页面卸载时统一 dispose（unloadPlugin）。
      const disposables: Disposable[] = []

      const ctx: PluginContext = {
        pluginId: plugin.id,
        extensionPath: webEntryJsPath(plugin.webMain),
        sdk: createPluginSdk(workerId, workspaceId, workspaceRoot),
        storage: createPluginStorage(plugin.id),
        commands: trackDisposables(createCommandRegistry(), disposables),
        events: trackDisposables(createPluginEvents(), disposables),
        fs: createPluginFs(),
        // pluginDispatcher 使用 web 内部强类型（WorkspaceTab/FileTabResource 等），
        // 与 @everyagent/plugin-api 的最小化接口在 ComponentType 上因不变性不兼容，
        // 运行时行为一致，此处安全强转；外面再包一层收集代理（#8）。
        ui: trackDisposables(pluginDispatcher, disposables) as unknown as PluginContext['ui'],
      }

      // 加载插件 CSS（esbuild 将 CSS 提取到与入口同名的 .css，需单独注入）
      try {
        await loadPluginCss(workerId, plugin.id, webEntryJsPath(plugin.webMain).replace(/\.js$/, '.css'))
      } catch {
        // 插件无 CSS 或加载失败，不阻塞
      }

      await pluginModule.activate(ctx)
      loadedPlugins.set(plugin.id, { module: pluginModule, disposables })
      wirePageTeardown()
      console.log(`[plugins] 插件已激活: ${plugin.id} (${plugin.name})`)
    } catch (e) {
      console.warn(`[plugins] 插件 ${plugin.id} 加载失败:`, e)
    }
  }

  // 本轮加载流程结束：广播 plugins-loaded（数量含此前已装载的插件）。
  // 注意 fire-and-forget：晚于本 emit 才激活的插件收不到它（事件无重放）。
  domainEventBus.emit(DOMAIN_EVENTS.PLUGINS_LOADED, { count: loadedPlugins.size })
}

/**
 * 统一加载插件模块：所有插件（内置 + 外部）均经 plugin.webSource RPC
 * 获取预编译 JS → 重写 bare import → blob URL → import()。
 */
async function loadPluginModule(
  workerId: string,
  plugin: PluginListEntry,
): Promise<{ default: PluginModule }> {
  // 消费清单 webMain 推导产物路径(源码路径后缀换 .js);旧 worker 不下发 webMain 时
  // 回退约定产物位 web/index.js,行为与硬编码时代一致。
  const entryPath = webEntryJsPath(plugin.webMain)
  const result = await hubSession.rpcTo(workerId, 'plugin.webSource', {
    pluginId: plugin.id,
    path: entryPath,
  }) as { content?: string }

  const source = result?.content
  if (!source) {
    throw new Error(`插件 ${plugin.id} 无 ${entryPath} 源码`)
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

/**
 * 加载插件 CSS：经 plugin.webSource RPC 获取与入口同名的 .css 产物，
 * 注入为带 data-plugin 属性的 <style> 元素（幂等，不重复注入）。
 */
async function loadPluginCss(workerId: string, pluginId: string, cssPath: string): Promise<void> {
  const styleId = `plugin-css:${pluginId}`
  if (document.getElementById(styleId)) return // 幂等

  const result = await hubSession.rpcTo(workerId, 'plugin.webSource', {
    pluginId,
    path: cssPath,
  }) as { content?: string }

  const css = result?.content
  if (!css || !css.trim()) return

  const style = document.createElement('style')
  style.id = styleId
  style.setAttribute('data-plugin', pluginId)
  style.textContent = css
  document.head.appendChild(style)
}


