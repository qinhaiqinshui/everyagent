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
} from '@everyagent/plugin-api'
import type {
  UiSidebarItemDefinition,
  UiWorkspaceTabTypeDefinition,
  UiFileSidebarPanelDefinition,
  UiComposerFooterControlDefinition,
  UiComposerAbovePanelDefinition,
  ToolCallViewDefinition,
  TaskFileMoreAction,
} from './types'
import type { FileContentEditorDescriptor } from '@/components/files/file-tab-types'
import { registerTraceType as registerToTraceRegistry } from './traceTypeRegistry'
import { outputBlockRegistry } from './outputBlockRegistry'

// ── 扩展点注册表 ──

const sidebarItems: UiSidebarItemDefinition[] = []
const workspaceTabTypes: UiWorkspaceTabTypeDefinition[] = []
const fileSidebarPanels: UiFileSidebarPanelDefinition[] = []
const composerFooterControls: UiComposerFooterControlDefinition[] = []
const composerAbovePanels: UiComposerAbovePanelDefinition[] = []
const toolCallViews: ToolCallViewDefinition[] = []
const taskFileMoreActions: TaskFileMoreAction[] = []
const traceTypes: TraceTypeDefinition[] = []
const outputBlocks = new Map<string, OutputBlockHandler>()
/** 插件注册的文件内容编辑器（如 PDF 插件）。 */
const fileContentEditors: FileContentEditorDescriptor[] = []
/** 插件注册的文件树右键菜单动作（如 git 插件的「显示 Git 历史」）。 */
const fileExplorerActions: FileExplorerAction[] = []

// ── Disposable 工具 ──

function makeDisposable(array: unknown[], item: unknown): Disposable {
  return {
    dispose() {
      const i = array.indexOf(item)
      if (i >= 0) array.splice(i, 1)
    },
  }
}

// ── PluginDispatcher 真实实现 ──

export interface RealPluginDispatcher {
  /** 通用扩展点分发（按扩展点名查询）。 */
  dispatch: <T>(extensionPoint: string) => Promise<T[]>
  /** 侧边栏项。 */
  getSidebarItems: () => Promise<UiSidebarItemDefinition[]>
  /** 工作区标签类型。 */
  getWorkspaceTabTypes: () => Promise<UiWorkspaceTabTypeDefinition[]>
  /** 文件页侧栏面板。 */
  getFileSidebarPanels: () => Promise<UiFileSidebarPanelDefinition[]>
  /** 输入框下方控件。 */
  getComposerFooterControls: () => Promise<UiComposerFooterControlDefinition[]>
  /** 输入框上方面板。 */
  getComposerAbovePanels: () => Promise<UiComposerAbovePanelDefinition[]>
  /** 已注册的工具调用视图接管（供 toolViews 注册表解析合并，同步）。 */
  listRegisteredToolCallViews: () => ToolCallViewDefinition[]
  /** 任务文件更多操作。 */
  getTaskFileMoreActions: () => Promise<TaskFileMoreAction[]>
  /** 注册侧边栏项。 */
  registerSidebarItem: (def: UiSidebarItemDefinition) => Disposable
  /** 注册工作区标签类型。 */
  registerWorkspaceTabType: (def: UiWorkspaceTabTypeDefinition) => Disposable
  /** 注册文件页侧栏面板。 */
  registerFileSidebarPanel: (def: UiFileSidebarPanelDefinition) => Disposable
  /** 注册输入框下方控件。 */
  registerComposerFooterControl: (def: UiComposerFooterControlDefinition) => Disposable
  /** 注册输入框上方面板。 */
  registerComposerAbovePanel: (def: UiComposerAbovePanelDefinition) => Disposable
  /** 注册工具调用视图接管。 */
  registerToolCallView: (def: ToolCallViewDefinition) => Disposable
  /** 注册任务文件更多操作。 */
  registerTaskFileMoreAction: (action: TaskFileMoreAction) => Disposable
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
}

export const pluginDispatcher: RealPluginDispatcher = {
  async dispatch<T>(extensionPoint: string): Promise<T[]> {
    // 按扩展点名分发到对应注册表
    switch (extensionPoint) {
      case 'ui.sidebar_items':
        return sidebarItems as T[]
      case 'ui.workspace_tab_types':
        return workspaceTabTypes as T[]
      case 'ui.file_sidebar_panels':
        return fileSidebarPanels as T[]
      case 'ui.composer_footer_controls':
        return composerFooterControls as T[]
      case 'ui.composer_above_panel':
        return composerAbovePanels as T[]
      case 'ui.tool_call_views':
        return toolCallViews as unknown as T[]
      case 'ui.task_file_more_actions':
        return taskFileMoreActions as T[]
      case 'ui.file_content_editors':
        return fileContentEditors as T[]
      case 'ui.file_explorer_actions':
        return fileExplorerActions as T[]
      default:
        return []
    }
  },

  async getSidebarItems() {
    return [...sidebarItems]
  },
  async getWorkspaceTabTypes() {
    return [...workspaceTabTypes]
  },
  async getFileSidebarPanels() {
    return [...fileSidebarPanels]
  },
  async getComposerFooterControls() {
    return [...composerFooterControls]
  },
  async getComposerAbovePanels() {
    return [...composerAbovePanels]
  },
  listRegisteredToolCallViews() {
    return [...toolCallViews]
  },
  async getTaskFileMoreActions() {
    return [...taskFileMoreActions]
  },

  registerSidebarItem(def) {
    sidebarItems.push(def)
    return makeDisposable(sidebarItems, def)
  },
  registerWorkspaceTabType(def) {
    workspaceTabTypes.push(def)
    return makeDisposable(workspaceTabTypes, def)
  },
  registerFileSidebarPanel(def) {
    fileSidebarPanels.push(def)
    return makeDisposable(fileSidebarPanels, def)
  },
  registerComposerFooterControl(def) {
    composerFooterControls.push(def)
    return makeDisposable(composerFooterControls, def)
  },
  registerComposerAbovePanel(def) {
    composerAbovePanels.push(def)
    return makeDisposable(composerAbovePanels, def)
  },
  registerToolCallView(def) {
    toolCallViews.push(def)
    return makeDisposable(toolCallViews, def)
  },
  registerTaskFileMoreAction(action) {
    taskFileMoreActions.push(action)
    return makeDisposable(taskFileMoreActions, action)
  },
  registerTraceType(def) {
    traceTypes.push(def)
    // 同时注册到 traceTypeRegistry（保持向后兼容）
    registerToTraceRegistry(def)
    return makeDisposable(traceTypes, def)
  },
  registerOutputBlock(tag, handler) {
    outputBlocks.set(tag.toLowerCase(), handler)
    // 同时注册到 outputBlockRegistry（保持向后兼容）
    outputBlockRegistry.register(tag, handler)
    return {
      dispose() {
        outputBlocks.delete(tag.toLowerCase())
      },
    }
  },
  registerFileContentEditor(def) {
    fileContentEditors.push(def)
    return makeDisposable(fileContentEditors, def)
  },
  listRegisteredFileContentEditors() {
    return [...fileContentEditors]
  },
  listRegisteredSidebarItems() {
    return [...sidebarItems]
  },
  listRegisteredWorkspaceTabTypes() {
    return [...workspaceTabTypes]
  },
  listRegisteredFileExplorerActions() {
    return [...fileExplorerActions]
  },
  registerFileExplorerAction(action) {
    fileExplorerActions.push(action)
    return makeDisposable(fileExplorerActions, action)
  },
}
