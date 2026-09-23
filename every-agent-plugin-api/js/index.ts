/**
 * @everyagent/plugin-api — 前端插件 API 类型包（对标 @types/vscode）。
 *
 * 本文件只导出 **类型**（interface / type），零运行时代码。
 * 运行时（PluginDispatcher、registerTraceType、outputBlockRegistry 等）
 * 留在 web 模块。
 *
 * 对于引用 web 内部类型的字段，本包定义最小化接口替代，
 * 外部插件按需强转即可。
 */

import type { ComponentType, ReactNode } from 'react'

// ─── 最小化替代接口（替代 web 内部类型） ──────────────────────────────────

/**
 * 最小化工作区标签接口（替代 web 内部 `WorkspaceTab` 联合类型）。
 * 插件按需强转为具体 tab 类型。
 */
export interface PluginWorkspaceTab {
  /** 标签 ID。 */
  id: string
  /** 标签类型判别式。 */
  tabType: string
  /** 标签标题。 */
  title?: string
  /** 业务私有数据（仅 plugin 类型标签有）。 */
  data?: Record<string, string>
}

/**
 * 最小化 trace 记录接口（替代 web 内部 `TaskTraceRecord`）。
 */
export interface PluginTraceRecord {
  /** 追踪 ID。 */
  traceId: string
  /** 所属任务 ID。 */
  taskId: string
  /** 所属会话 ID（trace 关联到的 agent）。 */
  agentId?: string
  /** 追踪类型。 */
  kind: string
  /** 追踪图标 key。 */
  icon?: string
  /** 追踪标题。 */
  title?: string
  /** 追踪摘要。 */
  summary?: string
  /** 追踪正文。 */
  content?: unknown
  /** 创建时间。 */
  createdAt?: number
  /** 附加元数据。 */
  metadata?: Record<string, unknown>
}

/**
 * 最小化文件资源接口（替代 web 内部 `FileTabResource`）。
 */
export interface PluginFileResource {
  /** 文件标签 ID。 */
  id: string
  /** 所属工作区根。 */
  workspaceRoot: string
  /** 文件相对路径。 */
  filePath: string
  /** 文件显示名称。 */
  fileName: string
  /** 关联任务 ID。 */
  taskId?: string
}

/**
 * 最小化草稿状态接口（替代 web 内部 `ChatComposerDraftState`）。
 */
export interface PluginComposerDraftState {
  /** 文本输入内容。 */
  text: string
  /** 原始输入内容。 */
  rawContent: string
  /** 已插入的结构化 token 列表。 */
  tokens: unknown[]
}

/** 左侧活动栏面板 ID（替代 web 内部 `SidebarPanelId`）。 */
export type SidebarPanelId = string

// ─── Disposable ──────────────────────────────────────────────────────────

/** 可释放资源接口（对标 VSCode Disposable）。 */
export interface Disposable {
  dispose(): void
}

// ─── SDK / 存储 / 命令 ────────────────────────────────────────────────────

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

/** per-plugin 本地存储（对标 VSCode globalState）。 */
export interface PluginStorage {
  get<T>(key: string, defaultValue?: T): T | undefined
  set(key: string, value: unknown): void
  delete(key: string): void
}

export interface CommandRegistry {
  registerCommand(id: string, handler: (...args: unknown[]) => unknown | Promise<unknown>): Disposable
  executeCommand(id: string, ...args: unknown[]): Promise<unknown>
}

// ─── UI 扩展点定义 ────────────────────────────────────────────────────────

/** 侧边栏入口定义（由 `ui.sidebar_items` 扩展点产出）。 */
export interface UiSidebarItemDefinition {
  /** 唯一稳定 id（同时作为 SidebarPanelId 使用）。 */
  id: string
  /** 展示标题（活动栏 tooltip / 面板标题）。 */
  title: string
  /** 活动栏图标（React 节点）。 */
  icon: ReactNode
  /** 点击入口后展示的面板组件。 */
  Panel: ComponentType
  /** 可选徽标数量（>0 时在活动栏图标上显示角标）。 */
  badgeCount?: number
  /** 可选：活动栏角标渲染组件（插件自管订阅与刷新）。缺省不渲染角标。 */
  Badge?: ComponentType
}

/** 壳层传给标签渲染 / 生命周期的上下文。 */
export interface WorkspaceTabRenderContext {
  /** 关闭某个标签。 */
  closeTab: (tabId: string) => void
  /** 同步侧栏"当前选中文件"状态。 */
  setSelectedFilePath: (path: string | null) => void
  /** 可选：全局轻提示。 */
  showToast?: (message: string, type?: 'success' | 'error' | 'info') => void
  /** 当前标签是否处于激活态。 */
  isActiveTab?: boolean
}

/**
 * 统一工作区标签类型定义（由 `ui.workspace_tab_types` 扩展点产出）。
 *
 * 泛型 `T` 为标签类型，默认 `PluginWorkspaceTab`；
 * web 内部使用时可传入强类型 `WorkspaceTab`。
 */
export interface UiWorkspaceTabTypeDefinition<T extends PluginWorkspaceTab = PluginWorkspaceTab> {
  /** 注册表 key。 */
  tabTypeKey: string
  /** 提供方：插件填插件 id，内置填 'core'。 */
  pluginId: string
  /** 渲染标签主体。 */
  renderTab: (tab: T, ctx: WorkspaceTabRenderContext) => ReactNode
  /** 渲染图标。 */
  renderIcon: (tab: T) => ReactNode
  /** 短标签（标题栏文字）。 */
  getLabel: (tab: T) => string
  /** 可选：自定义标签显示内容。 */
  renderLabel?: (tab: T) => ReactNode
  /** 完整标题（tooltip）。 */
  getTitle: (tab: T) => string
  /** 关闭按钮无障碍文案。 */
  getCloseAriaLabel: (tab: T) => string
  /** 可选：激活该标签时壳层同步到的侧栏 activity id。 */
  getSidebarActivityId?: (tab: T) => SidebarPanelId | null
  /** 可选：标签成为激活态时的内容副作用。 */
  onActivate?: (tab: T, ctx: WorkspaceTabRenderContext) => void
  /** 可选：关闭标签时的内容清理。 */
  onClose?: (tab: T, ctx: WorkspaceTabRenderContext) => void
}

/**
 * 文件页侧栏面板定义（由 `ui.file_sidebar_panels` 扩展点产出）。
 *
 * 泛型 `F` 为文件资源类型，默认 `PluginFileResource`。
 */
export interface UiFileSidebarPanelDefinition<F extends PluginFileResource = PluginFileResource> {
  /** 全局唯一面板 id。 */
  id: string
  /** 更多菜单展示标题。 */
  title: string
  /** 当前文件下是否可展示；缺省表示可展示。 */
  isAvailable?: (file: F) => boolean
  /** 渲染侧栏内容。 */
  Panel: ComponentType<{
    file: F
    requestRefresh: () => void
  }>
}

/** 任务文件列表"更多"菜单操作上下文。 */
export interface TaskFileMoreActionContext {
  /** 当前任务 ID。 */
  taskId: string
  /** 文件相对任务文件根目录的路径。 */
  fileRelativePath: string
  /** 文件名。 */
  fileName: string
}

/** 任务文件列表"更多"菜单动作的执行结果。 */
export interface TaskFileMoreActionResult {
  /** 是否成功。 */
  ok: boolean
  /** 反馈文案。 */
  message: string
}

/** 任务文件列表"更多"菜单的可扩展操作项。 */
export interface TaskFileMoreAction {
  /** 全局唯一动作 id。 */
  id: string
  /** 菜单展示文案。 */
  label: string
  /** 可选图标。 */
  icon?: ReactNode
  /** 可选说明。 */
  description?: string
  /** 点击后的执行回调。 */
  invoke?: (
    ctx: TaskFileMoreActionContext,
  ) => void | Promise<void | TaskFileMoreActionResult | null>
}

// ─── 工具调用视图接管（ui.tool_call_views） ────────────────────────────────

/**
 * 最小化工具调用详情（替代 web 内部 `AggregatedToolDetail`）。
 * 插件按需强转为具体形态；字段与核心契约逐字对齐。
 */
export interface PluginToolCallDetail {
  /** 判别式，恒为 'tool_call'。 */
  type: 'tool_call'
  /** 工具展示名（已解析）。 */
  toolName: string
  /** 原始工具调用 ID。 */
  toolCallId?: string | null
  /** 状态（success / error / ...）。 */
  status?: string
  /** 下发参数；无参数时为 null。 */
  arguments?: Record<string, unknown> | null
  /** 调用结果（成功且可解析为 JSON 时为对象，否则为原始文本）。 */
  result?: unknown
}

/** 插件工具视图组件的 props（与核心 ToolViewProps 最小化对齐）。 */
export interface PluginToolCallViewProps {
  /** 聚合后的工具调用数组（每条 merged tool 消息一个元素）。 */
  details: PluginToolCallDetail[]
}

/**
 * 工具调用视图接管定义（由 `ui.tool_call_views` 扩展点产出）。
 *
 * 与核心内置 toolViews 目录注册表同构同地位：按工具名命中即**整体接管
 * 该工具调用的渲染**（折叠态 + 展开态），组件完全自治。
 * 解析优先级：插件注册的视图 > 内置目录注册表 > DefaultToolView。
 */
export interface ToolCallViewDefinition {
  /** 提供方插件 id。 */
  pluginId: string
  /** 以工具名作为 key。 */
  toolName: string
  /** 接管渲染的完整视图组件（折叠态 + 展开态）。 */
  Component: ComponentType<PluginToolCallViewProps>
}

// ─── 任务列表分组 ─────────────────────────────────────────────────────────

/** 分组函数接收的任务条目最小契约。 */
export interface TaskListGroupItem {
  taskId: string
  metadata?: Record<string, unknown>
}

/** 任务列表组级动作。 */
export interface TaskListGroupAction {
  /** 全局唯一动作 id。 */
  id: string
  /** 动作文案。 */
  label: string
  /** 点击回调。 */
  onSelect: () => void
}

/** 任务列表分组。 */
export interface TaskListGroup<T = TaskListGroupItem> {
  /** 稳定 key。 */
  key: string
  /** 组标题。 */
  label: string
  /** 组标题悬浮完整名。 */
  title?: string
  /** 组内任务。 */
  tasks: T[]
  /** 组级动作。 */
  actions?: TaskListGroupAction[]
  /** 是否默认折叠。 */
  defaultCollapsed?: boolean
}

// ─── 任务阻塞状态 / 线程条目增强 ──────────────────────────────────────────

/** 任务阻塞状态。 */
export interface TaskBlockingState {
  blocked: boolean
  reason?: string
}

/** 线程条目视图增强。 */
export interface ThreadItemViewEnhancement {
  badges?: string[]
  annotation?: string
}

// ─── 输入框扩展点 ─────────────────────────────────────────────────────────

/** 输入框下方、模型选择行的插件控件定义。 */
export interface UiComposerFooterControlDefinition {
  /** 全局唯一控件 id。 */
  id: string
  /** 渲染控件。 */
  render: (ctx: {
    value: unknown
    onChange: (next: unknown) => void
    isMobile: boolean
  }) => ReactNode
}

/** 输入框上方 UI 槽位的上下文。 */
export interface ComposerPanelCtx {
  /** 当前任务 ID（草稿态为 undefined）。 */
  taskId: string | undefined
  /** 当前草稿状态。 */
  draft: PluginComposerDraftState
  /** 当前任务是否处于 running。 */
  isRunning: boolean
}

/** 输入框上方 UI 定义。 */
export interface UiComposerAbovePanelDefinition {
  /** 全局唯一控件 id。 */
  id: string
  /** 渲染组件（接收 ComposerPanelCtx 作为 props）。 */
  Component: ComponentType<ComposerPanelCtx>
}

// ─── Trace 类型 ──────────────────────────────────────────────────────────

/** trace 详情渲染上下文。 */
export interface TraceContentRenderContext {
  /** 当前任务 ID。 */
  taskId: string
}

/**
 * trace 类型定义。
 *
 * 使用 `PluginTraceRecord` 替代 web 内部 `TaskTraceRecord`，
 * web 内部使用时可传入强类型。
 */
export interface TraceTypeDefinition<R extends PluginTraceRecord = PluginTraceRecord> {
  /** trace 类型 key。 */
  kind: string
  /** 渲染展开态内容。 */
  renderContent?: (trace: R, ctx: TraceContentRenderContext) => ReactNode
  /** 旧数据摘要适配器。 */
  getSummary?: (trace: R) => string | undefined
  /** 旧数据图标适配器。 */
  getIcon?: (trace: R) => string | undefined
  /** 收起态不渲染 title。 */
  hideTitle?: boolean
  /** 是否允许展开。 */
  canExpand?: (trace: R) => boolean
}

// ─── 输出块渲染 ───────────────────────────────────────────────────────────

/** 输出块渲染上下文。 */
export interface OutputBlockContext {
  taskId?: string
  messageId?: string
}

/** 输出块渲染处理器。 */
export type OutputBlockHandler = (
  content: string,
  context: OutputBlockContext,
) => ReactNode

// ─── 文件内容编辑器扩展点 ─────────────────────────────────────────────────

/**
 * 文件内容编辑器 props（插件版，对齐 web 内部 FileContentEditorProps）。
 *
 * 二进制只读编辑器（readonly=true）的 `content` 为 data URL；
 * 其余为文本内容。
 */
export interface PluginFileContentEditorProps {
  /** 当前文件资源。 */
  file: PluginFileResource
  /** 打开模式（'readonly' | 'readwrite'）。 */
  mode: string
  /** 文件内容（二进制只读编辑器为 data URL）。 */
  content: string
  /** 草稿内容（可编辑态）。 */
  draftContent: string
  /** 是否加载中。 */
  loading: boolean
  /** 加载错误信息。 */
  error: string
  /** 请求定位到的目标行号。 */
  lineNumber?: number
  /** 请求定位到目标行号的时间戳。 */
  lineLocateRequestedAt?: number
  /** 行定位已应用回调。 */
  onLineLocateApplied?: () => void
  /** 草稿变更回调。 */
  onDraftChange: (nextValue: string) => void
  /** 标题栏动作变更回调（结构对齐 web 内部 FileContentHeaderAction）。 */
  onHeaderActionsChange?: (actions: unknown[]) => void
  /** 只读态请求进入可编辑态。 */
  onRequestEditMode?: () => void
}

/**
 * 文件内容编辑器描述符（由 `ui.file_content_editors` 扩展点产出）。
 *
 * 插件注册后，核心编辑器注册表按扩展名匹配时同时查找插件编辑器；
 * 插件编辑器优先于内置编辑器（同名扩展名可覆盖）。
 */
export interface PluginFileContentEditorDescriptor {
  /** 编辑器类型 ID。 */
  kind: string
  /** 用户可见名称。 */
  label: string
  /** 支持的扩展名列表，全部小写，包含点（如 ['.pdf']）。 */
  extensions: string[]
  /** 是否作为未知扩展名兜底编辑器（插件编辑器不建议开启）。 */
  isFallback?: boolean
  /**
   * 二进制只读类编辑器（图片/PDF 等）：不支持可编辑态。
   * 外壳据此屏蔽「编辑/保存/查找」入口，并走二进制读取（data URL）而非文本解码。
   */
  readonly?: boolean
  /** 编辑器组件。 */
  Component: ComponentType<PluginFileContentEditorProps>
}

// ─── 文件树右键菜单扩展点 ─────────────────────────────────────────────────

/** 文件树右键菜单上下文（与核心 WorkspaceExplorerContextTarget 对齐）。 */
export interface FileExplorerActionContext {
  /** 所属工作区根(worker 机器绝对路径)。 */
  workspaceRoot: string
  /** 节点路径(工作区相对)。 */
  path: string
  /** 节点名称。 */
  name: string
  /** 节点类型。 */
  type: 'file' | 'directory'
}

/**
 * 文件树右键菜单动作（由 `ui.file_explorer_actions` 扩展点产出）。
 * 核心在构建右键菜单时收集所有注册项，按 isVisible 过滤后追加到内置菜单项尾部。
 */
export interface FileExplorerAction {
  /** 全局唯一动作 id。 */
  id: string
  /** 菜单展示文案。 */
  label: string
  /** 可选图标（React 节点）。 */
  icon?: ReactNode
  /** 当前上下文下是否显示；缺省恒显示。 */
  isVisible?: (ctx: FileExplorerActionContext) => boolean
  /** 点击回调。 */
  invoke?: (ctx: FileExplorerActionContext) => void
}

// ─── UI 注册表 ────────────────────────────────────────────────────────────

export interface UiRegistry {
  registerSidebarItem(def: UiSidebarItemDefinition): Disposable
  registerWorkspaceTabType(def: UiWorkspaceTabTypeDefinition): Disposable
  registerFileSidebarPanel(def: UiFileSidebarPanelDefinition): Disposable
  registerComposerFooterControl(def: UiComposerFooterControlDefinition): Disposable
  registerComposerAbovePanel(def: UiComposerAbovePanelDefinition): Disposable
  registerToolCallView(def: ToolCallViewDefinition): Disposable
  registerTaskFileMoreAction(action: TaskFileMoreAction): Disposable
  registerTraceType(def: TraceTypeDefinition): Disposable
  registerOutputBlock(tag: string, handler: OutputBlockHandler): Disposable
  registerFileContentEditor(def: PluginFileContentEditorDescriptor): Disposable
  registerFileExplorerAction(action: FileExplorerAction): Disposable
}

// ─── PluginContext / PluginModule ─────────────────────────────────────────

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
