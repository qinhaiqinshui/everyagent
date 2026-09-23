/**
 * src/plugin/api.ts
 *
 * 插件 API 面——对标 VSCode 的 vscode.* 命名空间。
 *
 * 插件开发者导入此文件的类型，实现 PluginModule 接口，
 * 在 activate(ctx) 中通过 ctx.ui.register* 注册 UI 扩展点。
 *
 * 与 VSCode 的映射：
 * - ctx.commands.registerCommand → vscode.commands.registerCommand
 * - ctx.ui.registerSidebarItem → vscode.window.registerWebviewViewProvider
 * - ctx.sdk.rpc → vscode.workspace.fs（经 worker RPC）
 * - Disposable → vscode.Disposable
 */

import type { ComponentType, ReactNode } from 'react'
import type {
  UiSidebarItemDefinition,
  UiWorkspaceTabTypeDefinition,
  UiFileSidebarPanelDefinition,
  UiComposerFooterControlDefinition,
  UiComposerAbovePanelDefinition,
  ToolCallDetailViewDefinition,
  TaskFileMoreAction,
} from './types'
import type { TraceTypeDefinition } from './traceTypeRegistry'
import type { OutputBlockHandler } from './outputBlockRegistry'
import type { Disposable } from './PluginDispatcher'

// ── SDK ──

/** 与 worker 通信的 SDK（经 hub RPC 管道）。 */
export interface PluginSdk {
  /** 调用 worker RPC（对标 vscode.commands.executeCommand）。 */
  rpc(workerId: string, method: string, params?: unknown): Promise<unknown>
  /** 当前工作区信息。 */
  readonly workspace: {
    readonly id: string
    readonly rootPath: string
  }
  /** 当前 worker id。 */
  readonly workerId: string
}

// ── 存储 ──

/** per-plugin 本地存储（对标 VSCode globalState）。 */
export interface PluginStorage {
  get<T>(key: string, defaultValue?: T): T | undefined
  set(key: string, value: unknown): void
  delete(key: string): void
}

// ── 命令注册 ──

export interface CommandRegistry {
  registerCommand(id: string, handler: (...args: unknown[]) => unknown | Promise<unknown>): Disposable
  executeCommand(id: string, ...args: unknown[]): Promise<unknown>
}

// ── UI 注册 ──

export interface UiRegistry {
  registerSidebarItem(def: UiSidebarItemDefinition): Disposable
  registerWorkspaceTabType(def: UiWorkspaceTabTypeDefinition): Disposable
  registerFileSidebarPanel(def: UiFileSidebarPanelDefinition): Disposable
  registerComposerFooterControl(def: UiComposerFooterControlDefinition): Disposable
  registerComposerAbovePanel(def: UiComposerAbovePanelDefinition): Disposable
  registerToolCallDetailView(def: ToolCallDetailViewDefinition): Disposable
  registerTaskFileMoreAction(action: TaskFileMoreAction): Disposable
  registerTraceType(def: TraceTypeDefinition): Disposable
  registerOutputBlock(tag: string, handler: OutputBlockHandler): Disposable
}

// ── PluginContext ──

/** 插件激活上下文（对标 VSCode ExtensionContext）。 */
export interface PluginContext {
  readonly pluginId: string
  readonly extensionPath: string
  readonly sdk: PluginSdk
  readonly storage: PluginStorage
  readonly commands: CommandRegistry
  readonly ui: UiRegistry
}

/** 插件入口函数签名（对标 VSCode activate/deactivate）。 */
export interface PluginModule {
  activate(ctx: PluginContext): void | Promise<void>
  deactivate?(): void | Promise<void>
}

// ── 便捷导出：别名让插件开发者不直接引用内部模块 ──

export type { Disposable }
export type {
  UiSidebarItemDefinition,
  UiWorkspaceTabTypeDefinition,
  UiFileSidebarPanelDefinition,
  UiComposerFooterControlDefinition,
  UiComposerAbovePanelDefinition,
  ToolCallDetailViewDefinition,
  TaskFileMoreAction,
} from './types'
export type { TraceTypeDefinition } from './traceTypeRegistry'
export type { OutputBlockHandler } from './outputBlockRegistry'
