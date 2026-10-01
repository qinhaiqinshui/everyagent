/**
 * 插件调度器——真实实现。
 *
 * 扩展点注册表 + 分发逻辑：插件在 activate() 时通过 ctx.ui.register* 注册
 * 扩展点实现，核心在 dispatch(extPoint) 时遍历所有已注册实现并返回结果数组。
 *
 * 对标 VSCode 的 extension host：核心不写业务分支，只收集与分发。
 */

import type {
  Disposable,
  TraceTypeDefinition,
  OutputBlockHandler,
  FileExplorerAction,
  PluginDiffTabInput,
} from '@everyagent/plugin-api'
import { getComposerBridge, getShellBridge } from './pluginRuntimeBridge'
import type {
  UiSidebarItemDefinition,
  UiWorkspaceTabTypeDefinition,
  UiFileSidebarPanelDefinition,
  UiComposerAbovePanelDefinition,
  ToolCallViewDefinition,
  UiUserMessageActionDefinition,
  TaskRunSubmitContributionProvider,
  UiRoundTailPanelDefinition,
} from './types'
import type { FileContentEditorDescriptor } from '@/components/files/file-tab-types'
import { registerTraceType as registerToTraceRegistry } from './traceTypeRegistry'
import { outputBlockRegistry } from './outputBlockRegistry'
import {
  ExtensionRegistry,
  ExtensionRegistryFactory,
  DefaultExtensionRegistryFactory,
} from './ExtensionRegistry'

// ── 扩展点名常量 ──

const EXT_UI_SIDEBAR_ITEMS = 'ui.sidebar_items'
const EXT_UI_WORKSPACE_TAB_TYPES = 'ui.workspace_tab_types'
const EXT_UI_FILE_SIDEBAR_PANELS = 'ui.file_sidebar_panels'
const EXT_UI_COMPOSER_ABOVE_PANEL = 'ui.composer_above_panel'
const EXT_UI_TOOL_CALL_VIEWS = 'ui.tool_call_views'
const EXT_UI_USER_MESSAGE_ACTIONS = 'ui.user_message_actions'
const EXT_TASK_SUBMIT_CONTRIBUTIONS = 'task.submit_contributions'
const EXT_UI_FILE_CONTENT_EDITORS = 'ui.file_content_editors'
const EXT_UI_FILE_EXPLORER_ACTIONS = 'ui.file_explorer_actions'
const EXT_UI_ROUND_TAIL_PANELS = 'ui.round_tail_panels'
const EXT_UI_TRACE_TYPES = 'ui.trace_types'
const EXT_UI_OUTPUT_BLOCKS = 'ui.output_blocks'

// ── 扩展点注册表管理 ──

let registryFactory: ExtensionRegistryFactory = new DefaultExtensionRegistryFactory()
let factoryLocked = false

const registries = new Map<string, ExtensionRegistry<any>>()

function getRegistry<T>(extensionPoint: string): ExtensionRegistry<T> {
  let reg = registries.get(extensionPoint)
  if (!reg) {
    reg = registryFactory.create<T>(extensionPoint)
    registries.set(extensionPoint, reg)
  }
  return reg as ExtensionRegistry<T>
}

// 特殊处理：traceTypes 和 outputBlocks 除了走 ExtensionRegistry 外，
// 还需要同步到 traceTypeRegistry / outputBlockRegistry
const outputBlocksMap = new Map<string, OutputBlockHandler>()

// ── PluginDispatcher 真实实现 ──

export interface RealPluginDispatcher {
  /** 设置扩展点注册表工厂（仅允许插件激活前调用一次）。 */
  setExtensionRegistryFactory: (factory: ExtensionRegistryFactory) => void
  /** 通用扩展点分发（按扩展点名查询）。 */
  dispatch: <T>(extensionPoint: string) => Promise<T[]>
  /** 侧边栏项。 */
  getSidebarItems: () => Promise<UiSidebarItemDefinition[]>
  /** 工作区标签类型。 */
  getWorkspaceTabTypes: () => Promise<UiWorkspaceTabTypeDefinition[]>
  /** 文件页侧栏面板。 */
  getFileSidebarPanels: () => Promise<UiFileSidebarPanelDefinition[]>
  /** 输入框上方面板。 */
  getComposerAbovePanels: () => Promise<UiComposerAbovePanelDefinition[]>
  /** 已注册的工具调用视图接管（供 toolViews 注册表解析合并，同步）。 */
  listRegisteredToolCallViews: () => ToolCallViewDefinition[]
  /** 注册侧边栏项。 */
  registerSidebarItem: (def: UiSidebarItemDefinition) => Disposable
  /** 注册工作区标签类型。 */
  registerWorkspaceTabType: (def: UiWorkspaceTabTypeDefinition) => Disposable
  /** 注册文件页侧栏面板。 */
  registerFileSidebarPanel: (def: UiFileSidebarPanelDefinition) => Disposable
  /** 注册输入框上方面板。 */
  registerComposerAbovePanel: (def: UiComposerAbovePanelDefinition) => Disposable
  /** 注册工具调用视图接管。 */
  registerToolCallView: (def: ToolCallViewDefinition) => Disposable
  /** 注册用户消息动作。 */
  registerUserMessageAction: (def: UiUserMessageActionDefinition) => Disposable
  /** 同步获取插件注册的用户消息动作列表（供消息线程渲染合并）。 */
  listRegisteredUserMessageActions: () => UiUserMessageActionDefinition[]
  /** 注册 task.run 提交贡献 provider。 */
  registerTaskRunSubmitContributionProvider: (provider: TaskRunSubmitContributionProvider) => Disposable
  /** 同步获取已注册的 task.run 提交贡献 provider 列表（供提交流程收集）。 */
  listRegisteredTaskRunSubmitContributionProviders: () => TaskRunSubmitContributionProvider[]
  /** 注册 trace 类型。 */
  registerTraceType: (def: TraceTypeDefinition) => Disposable
  /** 注册输出块渲染。 */
  registerOutputBlock: (tag: string, handler: OutputBlockHandler) => Disposable
  /** 注册文件内容编辑器（插件扩展点）。 */
  registerFileContentEditor: (def: FileContentEditorDescriptor) => Disposable
  /** 同步获取插件注册的文件内容编辑器列表（供编辑器注册表合并）。 */
  listRegisteredFileContentEditors: () => FileContentEditorDescriptor[]
  /** 同步获取插件注册的侧边栏项列表（供活动栏合并）。 */
  listRegisteredSidebarItems: () => UiSidebarItemDefinition[]
  /** 同步获取插件注册的工作区标签类型列表（供标签注册表合并）。 */
  listRegisteredWorkspaceTabTypes: () => UiWorkspaceTabTypeDefinition[]
  /** 同步获取插件注册的文件树右键菜单动作列表（供右键菜单合并）。 */
  listRegisteredFileExplorerActions: () => FileExplorerAction[]
  /** 注册文件树右键菜单动作。 */
  registerFileExplorerAction: (action: FileExplorerAction) => Disposable
  /** 注册轮末展示区组件。 */
  registerRoundTailPanel: (def: UiRoundTailPanelDefinition) => Disposable
  /** 同步获取已注册的轮末展示区组件列表。 */
  listRegisteredRoundTailPanels: () => UiRoundTailPanelDefinition[]
  /** 打开插件自定义标签（委托宿主 WorkspaceShellContext）。 */
  openPluginTab: (type: string, data: Record<string, string>, title?: string) => void
  /** 打开顶层文件标签（委托宿主 WorkspaceShellContext）。 */
  openFileTab: (workspaceRoot: string, filePath: string, options?: { mode?: string }) => void
  /** 打开顶层 diff 对比标签（委托宿主 WorkspaceShellContext）。 */
  openDiffTab: (input: PluginDiffTabInput) => void
  /** 向当前输入框草稿末尾追加纯文本（委托宿主 ComposerDraftBridge）。 */
  appendComposerText: (text: string) => void
  /** 用 rawContent（可能含 opaque token 串）替换整个草稿（编辑重发回填用）。 */
  setComposerRawContent: (rawContent: string) => void
}

export const pluginDispatcher: RealPluginDispatcher = {
  setExtensionRegistryFactory(factory: ExtensionRegistryFactory) {
    if (factoryLocked) {
      throw new Error('ExtensionRegistryFactory has already been set and locked')
    }
    registryFactory = factory
    factoryLocked = true
  },

  async dispatch<T>(extensionPoint: string): Promise<T[]> {
    // 按扩展点名分发到对应注册表
    switch (extensionPoint) {
      case EXT_UI_SIDEBAR_ITEMS:
        return getRegistry<T>(extensionPoint).getAll()
      case EXT_UI_WORKSPACE_TAB_TYPES:
        return getRegistry<T>(extensionPoint).getAll()
      case EXT_UI_FILE_SIDEBAR_PANELS:
        return getRegistry<T>(extensionPoint).getAll()
      case EXT_UI_COMPOSER_ABOVE_PANEL:
        return getRegistry<T>(extensionPoint).getAll()
      case EXT_UI_TOOL_CALL_VIEWS:
        return getRegistry<T>(extensionPoint).getAll()
      case EXT_UI_USER_MESSAGE_ACTIONS:
        return getRegistry<T>(extensionPoint).getAll()
      case EXT_TASK_SUBMIT_CONTRIBUTIONS:
        return getRegistry<T>(extensionPoint).getAll()
      case EXT_UI_FILE_CONTENT_EDITORS:
        return getRegistry<T>(extensionPoint).getAll()
      case EXT_UI_FILE_EXPLORER_ACTIONS:
        return getRegistry<T>(extensionPoint).getAll()
      case EXT_UI_ROUND_TAIL_PANELS:
        return getRegistry<T>(extensionPoint).getAll()
      default:
        return []
    }
  },

  async getSidebarItems() {
    return getRegistry<UiSidebarItemDefinition>(EXT_UI_SIDEBAR_ITEMS).getAll()
  },
  async getWorkspaceTabTypes() {
    return getRegistry<UiWorkspaceTabTypeDefinition>(EXT_UI_WORKSPACE_TAB_TYPES).getAll()
  },
  async getFileSidebarPanels() {
    return getRegistry<UiFileSidebarPanelDefinition>(EXT_UI_FILE_SIDEBAR_PANELS).getAll()
  },
  async getComposerAbovePanels() {
    return getRegistry<UiComposerAbovePanelDefinition>(EXT_UI_COMPOSER_ABOVE_PANEL).getAll()
  },
  listRegisteredToolCallViews() {
    return getRegistry<ToolCallViewDefinition>(EXT_UI_TOOL_CALL_VIEWS).getAll()
  },

  registerSidebarItem(def) {
    return getRegistry<UiSidebarItemDefinition>(EXT_UI_SIDEBAR_ITEMS).register('', def)
  },
  registerWorkspaceTabType(def) {
    return getRegistry<UiWorkspaceTabTypeDefinition>(EXT_UI_WORKSPACE_TAB_TYPES).register('', def)
  },
  registerFileSidebarPanel(def) {
    return getRegistry<UiFileSidebarPanelDefinition>(EXT_UI_FILE_SIDEBAR_PANELS).register('', def)
  },
  registerComposerAbovePanel(def) {
    return getRegistry<UiComposerAbovePanelDefinition>(EXT_UI_COMPOSER_ABOVE_PANEL).register('', def)
  },
  registerToolCallView(def) {
    return getRegistry<ToolCallViewDefinition>(EXT_UI_TOOL_CALL_VIEWS).register('', def)
  },
  registerUserMessageAction(def) {
    return getRegistry<UiUserMessageActionDefinition>(EXT_UI_USER_MESSAGE_ACTIONS).register('', def)
  },
  listRegisteredUserMessageActions() {
    return getRegistry<UiUserMessageActionDefinition>(EXT_UI_USER_MESSAGE_ACTIONS).getAll()
  },
  registerTaskRunSubmitContributionProvider(provider) {
    return getRegistry<TaskRunSubmitContributionProvider>(EXT_TASK_SUBMIT_CONTRIBUTIONS).register('', provider)
  },
  listRegisteredTaskRunSubmitContributionProviders() {
    return getRegistry<TaskRunSubmitContributionProvider>(EXT_TASK_SUBMIT_CONTRIBUTIONS).getAll()
  },
  registerTraceType(def) {
    const disposable = getRegistry<TraceTypeDefinition>(EXT_UI_TRACE_TYPES).register('', def)
    // 同时注册到 traceTypeRegistry（保持向后兼容）
    registerToTraceRegistry(def)
    return disposable
  },
  registerOutputBlock(tag, handler) {
    const key = tag.toLowerCase()
    outputBlocksMap.set(key, handler)
    // 同时注册到 outputBlockRegistry（保持向后兼容）
    outputBlockRegistry.register(tag, handler)
    return {
      dispose() {
        outputBlocksMap.delete(key)
      },
    }
  },
  registerFileContentEditor(def) {
    return getRegistry<FileContentEditorDescriptor>(EXT_UI_FILE_CONTENT_EDITORS).register('', def)
  },
  listRegisteredFileContentEditors() {
    return getRegistry<FileContentEditorDescriptor>(EXT_UI_FILE_CONTENT_EDITORS).getAll()
  },
  listRegisteredSidebarItems() {
    return getRegistry<UiSidebarItemDefinition>(EXT_UI_SIDEBAR_ITEMS).getAll()
  },
  listRegisteredWorkspaceTabTypes() {
    return getRegistry<UiWorkspaceTabTypeDefinition>(EXT_UI_WORKSPACE_TAB_TYPES).getAll()
  },
  listRegisteredFileExplorerActions() {
    return getRegistry<FileExplorerAction>(EXT_UI_FILE_EXPLORER_ACTIONS).getAll()
  },
  registerFileExplorerAction(action) {
    return getRegistry<FileExplorerAction>(EXT_UI_FILE_EXPLORER_ACTIONS).register('', action)
  },
  listRegisteredRoundTailPanels() {
    return getRegistry<UiRoundTailPanelDefinition>(EXT_UI_ROUND_TAIL_PANELS).getAll()
  },
  registerRoundTailPanel(def) {
    return getRegistry<UiRoundTailPanelDefinition>(EXT_UI_ROUND_TAIL_PANELS).register('', def)
  },
  openPluginTab(type, data, title) {
    getShellBridge()?.openPluginTab(type, data, title)
  },
  openFileTab(workspaceRoot, filePath, options) {
    getShellBridge()?.openFileTab(workspaceRoot, filePath, options)
  },
  openDiffTab(input) {
    getShellBridge()?.openDiffTab(input)
  },
  appendComposerText(text) {
    getComposerBridge()?.appendText(text)
  },
  setComposerRawContent(rawContent) {
    getComposerBridge()?.setRawContent(rawContent)
  },
}
