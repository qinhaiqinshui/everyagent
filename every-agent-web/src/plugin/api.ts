/**
 * src/plugin/api.ts
 *
 * 插件 API 面——对标 VSCode 的 vscode.* 命名空间。
 *
 * 所有插件 API 类型已抽出到 @everyagent/plugin-api 包（every-agent-plugin-api/js）。
 * 本文件作为 re-export 入口，保持 web 内部 `import { xxx } from './api'`
 * 和 `import { xxx } from '@/plugin/api'` 的向后兼容。
 *
 * 与 VSCode 的映射：
 * - ctx.commands.registerCommand → vscode.commands.registerCommand
 * - ctx.ui.registerSidebarItem → vscode.window.registerWebviewViewProvider
 * - ctx.sdk.rpc → vscode.workspace.fs（经 worker RPC）
 * - Disposable → vscode.Disposable
 */

// 插件 API 类型全部来自 @everyagent/plugin-api
export * from '@everyagent/plugin-api'

// web 内部专用类型（不在插件 API 包中）仍从 ./types re-export
export type {
  ComposerToken,
  ComposerTokenResolution,
  UiComposerProviderDefinition,
} from './types'
