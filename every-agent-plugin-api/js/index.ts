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
 * 当前激活的工作区标签（最小化快照，仅判别字段）。
 * 由宿主壳层在激活标签变化时同步；插件经 `ctx.ui.getActiveTab()` 读取。
 */
export interface PluginActiveTabInfo {
  /** 标签 ID。 */
  id: string
  /** 标签类型判别式（'task' / 'file' / 'terminal' / 'page' / 'diff' / 'log' / 'plugin' ...）。 */
  tabType: string
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

/** 工作区注册表条目（最小化，替代 web 内部 WorkspaceEntry 的必要字段子集）。 */
export interface PluginWorkspaceEntry {
  /** 工作区根（worker 机器上的绝对路径，即身份键）。 */
  root: string
  /** 来源 worker id。 */
  workerId: string
  /** 稳定工作区 id（defaultworkspace 或 w_xxxxx）。 */
  id?: string
  /** 注册时间（ms）。 */
  addedAt?: number
  /** 最后活动时间（ms）。 */
  lastActivityAt?: number
  /** 目录已被移动/删除标记。 */
  missing?: boolean
}

/** 文件条目统计信息（最小化，替代 web 内部 WorkspaceFileStat 的必要字段子集）。 */
export interface PluginFileStat {
  /** 业务绝对路径（带前导 /）。 */
  path: string
  /** 节点名称。 */
  name: string
  /** 是否为目录。 */
  isDirectory: boolean
  /** 文件大小（字节）。 */
  size: number
  /** 修改时间（ms）。 */
  mtimeMs: number
  /** 创建时间（ms）。 */
  createdTs?: number
}

/** 与 worker 通信的 SDK（经 hub RPC 管道）。 */
export interface PluginSdk {
  /** 调用 worker RPC（对标 vscode.commands.executeCommand）。 */
  rpc(workerId: string, method: string, params?: unknown): Promise<unknown>
  /** 当前工作区信息与多工作区注册表能力。 */
  readonly workspace: {
    /** 插件加载时所归属工作区的稳定 id。 */
    readonly id: string
    /** 插件加载时所归属工作区的根路径（worker 机器绝对路径）。 */
    readonly rootPath: string
    /** 列出全部已注册工作区（多 worker 合并；无连接时返回空数组）。 */
    list(): Promise<PluginWorkspaceEntry[]>
    /** 按工作区根反查所属 worker id；未找到返回 undefined。 */
    workerIdOfRoot(root: string): string | undefined
  }
  /** 当前 worker id。 */
  readonly workerId: string
}

/** 领域事件名（与宿主 DOMAIN_EVENTS 同名值，字符串字面量联合；全部有真实 emit）。 */
export type PluginDomainEvent =
  | 'workspace-file-changed'
  | 'workspace-registry-changed'
  | 'sidebar-panel-shown'
  | 'task-created'
  | 'task-deleted'
  | 'task-status-changed'
  | 'task-round-closed'
  | 'plugins-loaded'
  | 'workspace-tab-activated'
  | 'workspace-tab-closed'
  | (string & {})

/** 领域事件总线（对标 vscode.event；委托宿主 domainEventBus）。 */
export interface PluginEvents {
  /** 订阅领域事件，返回可释放资源。 */
  on(eventName: PluginDomainEvent, handler: (payload: unknown) => void): Disposable
  /** 发布领域事件（payload 形状由事件名决定，插件按需强转）。 */
  emit(eventName: PluginDomainEvent, payload: unknown): void
}

/** 工作区文件系统网关（对标宿主 platform/fs/workspaceGateway 的最小子集）。 */
export interface PluginFs {
  /** 列出指定目录下的文件/目录条目（dir 为工作区相对路径，空串 = 工作区根）。 */
  listDir(workspaceRoot: string, dir: string): Promise<PluginFileStat[]>
  /** 删除指定文件或目录（工作区相对路径）。 */
  delete(workspaceRoot: string, path: string): Promise<void>
}

/** diff 标签页输入（对齐宿主 WorkspaceShellActions.openDiffTab 入参）。 */
export interface PluginDiffTabInput {
  /** 文件路径。 */
  filePath: string
  /** 文件名。 */
  fileName: string
  /** 变更类型（含 deleted）。 */
  changeType: 'created' | 'updated' | 'deleted'
  /** 变更前内容。 */
  beforeContent: string
  /** 变更后内容。 */
  afterContent: string
  /** 差异所属的工作区根；缺省时回退注册表首项。 */
  workspaceRoot?: string
  /** 标签标题。 */
  title?: string
  /** 是否为二进制文件。 */
  binary?: boolean
  /** 是否允许「恢复此版本」（仅 Git 历史提交详情）。 */
  allowRestore?: boolean
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

/**
 * 侧边栏入口定义（由 `ui.sidebar_items` 扩展点产出）。
 *
 * 活动栏条目由内置项与所有插件贡献**统一按 `order` 升序混排**（见 §8.5），
 * 贡献方不再隐含「内置在前、插件在后」的注册顺序假设。
 */
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
  /**
   * 活动栏排序字段（float，越小越靠前，同值按贡献先后稳定排列）。
   * 支持小数（如 4.5），便于在既有两项之间插入而不必整体重排。
   * 缺省视为 `DEFAULT_SIDEBAR_ORDER`（100），即未声明顺序的插件项落在内置项之后。
   */
  order?: number
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

// ─── 输入框扩展点 ─────────────────────────────────────────────────────────

// ─── 输入框上方面板 ─────────────────────────────────────────────────────
export interface ComposerPanelCtx {
  /** 当前任务 ID（草稿态为 undefined）。 */
  taskId: string | undefined
  /** 当前草稿状态。 */
  draft: PluginComposerDraftState
  /** 当前任务是否处于 running。 */
  isRunning: boolean
  /** 选中某个 agent（传 null 恢复全部）。核心实现联动过滤，插件只调回调。 */
  selectAgent: (agentId: string | null) => void
  /** 调用 RPC 方法（如 task.agents）。 */
  rpc: (method: string, params: Record<string, unknown>) => Promise<unknown>
  /** 订阅任务流事件（agent.*、usage 等），返回取消订阅函数。 */
  subscribeTaskEvents: (handler: (event: string, agentId: string | null, payload: unknown) => void) => () => void
}

/** 输入框上方 UI 定义。 */
export interface UiComposerAbovePanelDefinition {
  /** 全局唯一控件 id。 */
  id: string
  /** 渲染组件（接收 ComposerPanelCtx 作为 props）。 */
  Component: ComponentType<ComposerPanelCtx>
}

// ─── 轮末展示区（ui.round_tail_panels） ─────────────────────────────────

/** 轮末展示区组件的 props（通用轮次上下文，不含任何文件变更专属字段）。 */
export interface RoundTailPanelProps {
  /** 当前任务 ID。 */
  taskId: string
  /** 当前轮次 ID。 */
  roundId: string
  /** 任务挂靠的 worker ID。 */
  workerId?: string
  /** 任务挂靠的工作区根路径。 */
  workspaceRoot?: string
  /** 当前轮次数据（核心不感知具体结构，插件按需强转提取字段）。 */
  round: unknown
}

/** 轮末展示区定义（由 `ui.round_tail_panels` 扩展点产出）。 */
export interface UiRoundTailPanelDefinition {
  /** 提供方插件 ID。 */
  pluginId: string
  /** 渲染组件（接收 RoundTailPanelProps 作为 props）。 */
  Component: ComponentType<RoundTailPanelProps>
}

// ─── 用户消息动作 / 提交贡献 ─────────────────────────────────────────────

/** 用户消息动作按钮的 props（由核心在渲染用户消息时传入）。 */
export interface UserMessageActionProps {
  /** 当前任务 ID。 */
  taskId?: string
  /** 用户消息 seq（字符串雪花 ID，从 messageId `m-<seq>` 提取）。 */
  seq: string
  /** 消息纯文本内容。 */
  content: string
  /** 原始内容（含 opaque token 串）。 */
  rawContent?: string
}

/**
 * 用户消息动作定义（由 `ui.user_message_actions` 扩展点产出）。
 *
 * 核心在每条用户消息气泡旁渲染所有已注册的动作组件（如编辑重发按钮），
 * 不感知动作业务语义。
 */
export interface UiUserMessageActionDefinition {
  /** 全局唯一动作 id。 */
  id: string
  /** 渲染动作按钮的组件。 */
  Component: ComponentType<UserMessageActionProps>
}

/**
 * task.run 提交贡献（由 `task.submit_contributions` 扩展点产出）。
 *
 * 插件在提交前向 task.run 追加 metadata（核心透传不解释），
 * 并可覆盖提交按钮的展示（如编辑重发的「重新发送」）。
 */
export interface TaskRunSubmitContribution {
  /** 追加到 task.run 的 metadata（如 { editSeq }）。 */
  metadata?: Record<string, unknown>
  /** 覆盖提交按钮文案（如「重新发送」）。 */
  submitLabel?: string
  /** 提交中按钮文案（如「重新发送中...」）。 */
  submittingLabel?: string
  /** 提交按钮使用 danger 样式。 */
  submitDanger?: boolean
}

/**
 * task.run 提交贡献 provider。
 *
 * 核心在提交前调用 `getContribution` 收集贡献（提交时实时读取），
 * 提交成功后回调 `onSubmitted`；`subscribe` 用于贡献变化时通知核心重渲染
 * （如进入/退出编辑模式影响提交按钮文案）。
 */
export interface TaskRunSubmitContributionProvider {
  /** 全局唯一 provider id。 */
  id: string
  /** 提交前取贡献；返回 null 表示无贡献。 */
  getContribution: (taskId: string) => TaskRunSubmitContribution | null
  /** 贡献变化时通知核心重渲染；返回取消订阅函数。 */
  subscribe: (listener: () => void) => () => void
  /** 提交成功后回调（插件清理自身状态，如清除编辑目标）。 */
  onSubmitted?: (taskId: string) => void
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
 * 文件内容编辑器标题栏动作（插件版，对齐 web 内部 FileContentHeaderAction）。
 */
export interface PluginFileContentHeaderAction {
  id: string
  label: string
  onClick: () => void
  title?: string
  disabled?: boolean
  active?: boolean
  groupId?: string
  /** 标题栏动作展示位置；默认进入编辑器动作区，`save-adjacent` 固定靠近保存按钮。 */
  placement?: 'default' | 'save-adjacent'
}

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
  onHeaderActionsChange?: (actions: PluginFileContentHeaderAction[]) => void
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

// ─── 搜索类型扩展点（ui.search_types） ─────────────────────────────────────

/**
 * 最小化统一搜索结果项（替代 web 内部 `UnifiedSearchItem`）。
 *
 * 统一 `search` RPC 的命中项为**平铺**列表，`kind` 判别类别；其余字段随 kind
 * 不同，插件按需强转读取。核心不解释字段内容。
 */
export interface PluginSearchItem {
  /** 结果类别（开放集合：`file-content` / `file-name` / `task` / 插件自定）。 */
  kind: string
  /** 结果来源 provider id（缺省/`builtin.*` 内置静默，插件用其自身 id）。 */
  providerId?: string
  /** 可选相关性分数（仅排序提示，无语义承诺）。 */
  score?: number
  /** 文件类命中：文件路径（工作区相对）。 */
  path?: string
  /** 内容类命中：1-based 行号。 */
  lineNumber?: number
  /** 内容类命中：命中行正文。 */
  line?: string
  /** 命中片段在行内的起始列（0-based）。 */
  matchIndex?: number
  /** 命中片段文本。 */
  matchText?: string
  /** 任务类命中：任务 ID。 */
  taskId?: string
  /** 任务类命中：任务标题。 */
  title?: string
  /** 任务类命中：状态串。 */
  status?: string
  /** 组头显示名（可选，覆盖默认 key）。 */
  groupLabel?: string
  /** 任务类命中：轮次序号。 */
  roundIndex?: number
  /** 任务类命中：命中字段。 */
  field?: string
  /** 其余字段随 kind 不同（插件 provider 自定义）。 */
  [key: string]: unknown
}

/**
 * 搜索过滤字段声明（核心默认渲染器按 `type` 出控件，收集值进不透明 `filters` 袋）。
 *
 * 值与键的约定：运行时键为 `${kind}.${field.key}`（命名空间隔离）；核心不解释值。
 */
export interface SearchFilterField {
  /** 字段 key（同类型内唯一；运行时以 `${kind}.${key}` 装袋）。 */
  key: string
  /** 字段展示名（控件 label / 菜单项文案）。 */
  label: string
  /** 控件类型（核心按此选择默认渲染控件）。 */
  type: 'text' | 'textarea' | 'boolean' | 'select' | 'radio' | 'number' | 'path'
  /** select / radio 的候选项。 */
  options?: Array<{ label: string; value: string }>
  /** 默认值（缺省空）。 */
  default?: unknown
  /** 占位提示。 */
  placeholder?: string
  /** 帮助文本（悬停提示）。 */
  help?: string
  /** 展示位置：`inline` 显示在选项行，`more` 收在「更多」菜单（缺省 `inline`）。 */
  section?: 'inline' | 'more'
}

/** 自定义过滤区渲染组件的 props（`FilterView`：整块替换某类型的字段区）。 */
export interface SearchFilterViewProps {
  /** 当前类型声明的过滤字段。 */
  fields: SearchFilterField[]
  /** 当前过滤值（键为 `${kind}.${field}` 命名空间）。 */
  values: Record<string, unknown>
  /** 更新某字段值（键为命名空间键）。 */
  setValue: (key: string, value: unknown) => void
  /** 当前选中工作区根。 */
  workspaceRoot: string
}

/** 自定义结果渲染组件的 props（`ResultView`：该类型区块交给插件组件渲染）。 */
export interface SearchTypeResultViewProps {
  /** 本类型的命中项（平铺）。 */
  items: PluginSearchItem[]
  /** 当前搜索词。 */
  pattern: string
  /** 当前选中工作区根。 */
  workspaceRoot: string
  /** 打开文件（工作区相对/业务路径 + 可选行号）。 */
  openFile: (path: string, lineNumber?: number) => void
  /** 打开任务聊天页。 */
  openTask: (taskId: string, title?: string) => void
}

/**
 * 搜索类型定义（由 `ui.search_types` 扩展点产出）。
 *
 * 一个搜索类型 = 类型选择器里的一项（如「文本文件内容」「文件名」「任务内容」，
 * 或插件自定的「图片」）。核心面板遍历注册表渲染类型选择器与过滤区，并按
 * `kinds` 组装统一 `search` RPC 入参；插件可经 `FilterView` / `ResultView`
 * 自定义渲染（不自定义则用核心默认渲染器 / 默认结果树）。
 */
export interface SearchTypeDefinition {
  /** 类型 id（唯一；作为类型选择器 value）。 */
  id: string
  /** 类型选择器文案（如「文本文件内容」）。 */
  label: string
  /** 提供方：内置填 'core'，插件填插件 id。 */
  pluginId: string
  /** 排序字段（float，升序混排；缺省 100，内置占用小值）。 */
  order?: number
  /** 该类型向统一 `search` 声明要搜的 kinds（后端 provider 的 kind 声明）。 */
  kinds: string[]
  /** 过滤字段声明式 schema（核心按 type 默认渲染）。 */
  filters?: SearchFilterField[]
  /** 可选：整块自定义过滤区渲染（取代核心默认渲染器）。 */
  FilterView?: ComponentType<SearchFilterViewProps>
  /** 可选：自定义结果渲染（缺省用核心默认结果树）。 */
  ResultView?: ComponentType<SearchTypeResultViewProps>
}

// ─── UI 注册表 ────────────────────────────────────────────────────────────

export interface UiRegistry {
  registerSidebarItem(def: UiSidebarItemDefinition): Disposable
  registerWorkspaceTabType(def: UiWorkspaceTabTypeDefinition): Disposable
  registerFileSidebarPanel(def: UiFileSidebarPanelDefinition): Disposable
  registerComposerAbovePanel(def: UiComposerAbovePanelDefinition): Disposable
  registerToolCallView(def: ToolCallViewDefinition): Disposable
  registerUserMessageAction(def: UiUserMessageActionDefinition): Disposable
  registerTaskRunSubmitContributionProvider(provider: TaskRunSubmitContributionProvider): Disposable
  registerTraceType(def: TraceTypeDefinition): Disposable
  registerOutputBlock(tag: string, handler: OutputBlockHandler): Disposable
  registerFileContentEditor(def: PluginFileContentEditorDescriptor): Disposable
  registerFileExplorerAction(action: FileExplorerAction): Disposable
  /** 注册轮末展示区组件（由 `ui.round_tail_panels` 扩展点产出）。 */
  registerRoundTailPanel(def: UiRoundTailPanelDefinition): Disposable
  /** 注册搜索类型（由 `ui.search_types` 扩展点产出）。 */
  registerSearchType(def: SearchTypeDefinition): Disposable
  /**
   * 查询当前激活的工作区标签（同步读宿主壳层镜像）；无激活标签时返回 null。
   * 变更通知请配 `workspace-tab-activated` / `workspace-tab-closed` 领域事件。
   */
  getActiveTab(): PluginActiveTabInfo | null
  /** 打开插件自定义标签（替代宿主 useWorkspaceShell().openPluginTab）。 */
  openPluginTab(type: string, data: Record<string, string>, title?: string): void
  /** 打开顶层文件标签（替代宿主 useWorkspaceShell().openGlobalFileTab）。 */
  openFileTab(workspaceRoot: string, filePath: string, options?: { mode?: string }): void
  /** 打开顶层 diff 对比标签（替代宿主 useWorkspaceShell().openDiffTab）。 */
  openDiffTab(input: PluginDiffTabInput): void
  /** 向当前输入框草稿末尾追加纯文本（替代宿主 composerDraftBridge）。 */
  appendComposerText(text: string): void
  /** 用 rawContent（可能含 opaque token 串）替换整个草稿（编辑重发回填用）。 */
  setComposerRawContent(rawContent: string): void
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
  /** 领域事件总线（委托宿主 domainEventBus）。 */
  readonly events: PluginEvents
  /** 工作区文件系统网关（委托宿主 workspaceGateway）。 */
  readonly fs: PluginFs
}

/** 插件入口函数签名（对标 VSCode activate/deactivate）。 */
export interface PluginModule {
  activate(ctx: PluginContext): void | Promise<void>
  deactivate?(): void | Promise<void>
}
