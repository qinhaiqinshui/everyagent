/**
 * src/plugin/types.ts
 *
 * 插件底座的 UI 扩展契约类型。
 *
 * 这些类型描述 UI 输入阶段的扩展点（skill 菜单项、`@` 候选、`/` 命令）
 * 的标准化结构。对应改造方案第 2 步 / 第 13 步。
 *
 * 说明：
 * - 本文件只放“契约类型”，不放运行逻辑。
 * - 方案文档里的 `ComposerToken` / `PluginInteractionRequest` 在代码库中
 *   分别就是 `ChatComposerToken` / `ChatComposerInteractionRequest`，
 *   这里用类型别名保持与文档一致，避免散落字符串语义。
 */

import type { ComponentType, ReactNode } from 'react'
import type {
  ChatComposerDraftState,
  ChatComposerToken,
  SidebarPanelId,
  WorkspaceTab,
} from '@/types'

/** 统一别名：方案文档中的 `ComposerToken` 在代码库中就是 `ChatComposerToken`。 */
export type ComposerToken = ChatComposerToken

/**
 * token 提交前解析结果，由 provider 的 `resolveToken` 产出。
 * 统一调度层只收集这些结果，不在统一层写业务分支。
 */
export interface ComposerTokenResolution {
  selectedSkillIds?: string[]
  agentMetadataPatch?: Record<string, unknown>
}

/** 任务阻塞状态（由 `task.get_blocking_state` 扩展点产出）。 */
export interface TaskBlockingState {
  /** 当前是否处于阻塞（如等待用户确认）。 */
  blocked: boolean
  /** 阻塞原因说明。 */
  reason?: string
}

/** 线程条目视图增强（由 `ui.thread_item_view` 扩展点产出）。 */
export interface ThreadItemViewEnhancement {
  /** 在条目上展示的徽标文本（如来源 skill / 命令）。 */
  badges?: string[]
  /** 附加说明文本。 */
  annotation?: string
}

/**
 * 工具调用详情增强（由 `ui.tool_call_detail_view` 扩展点产出）。
 *
 * 仅影响工具调用展开后的详情渲染；折叠摘要继续使用核心默认视图。
 */
export interface ToolCallDetailEnhancement {
  /** 工具展示名；默认回退为原工具名。 */
  toolName?: string
  /** 附加说明文本。 */
  annotation?: string
  /** 可点击的路径标签。 */
  path?: {
    /** 展示文本。 */
    label: string
    /** 完整路径。 */
    fullPath: string
  }
}

/**
 * 侧边栏入口定义（由 `ui.sidebar_items` 扩展点产出）。
 *
 * 让插件可以在左侧活动栏注册一个图标入口，点击后在侧边面板槽位里
 * 渲染该插件提供的 `Panel`。统一调度层只收集这些定义，不在调度层写
 * 任何业务分支（如按插件名做二次匹配）。
 */
export interface UiSidebarItemDefinition {
  /** 唯一稳定 id（同时作为 SidebarPanelId 使用）。 */
  id: string
  /** 展示标题（活动栏 tooltip / 面板标题）。 */
  title: string
  /** 活动栏图标（React 节点，建议用统一 Icon 组件以保证描边风格一致）。 */
  icon: ReactNode
  /** 点击入口后展示的面板组件。 */
  Panel: ComponentType
  /** 可选徽标数量（>0 时在活动栏图标上显示角标）。 */
  badgeCount?: number
}

/** `@` 候选 provider 定义（注册时必须携带 `buildToken`）。 */
export interface UiComposerProviderDefinition {
  id: string
  title: string
  description?: string
  trigger: '@'
  buildToken: (ctx: {
    providerId: string
    draftText: string
  }) => ComposerToken
}

/**
 * 壳层传给标签渲染 / 生命周期的上下文（全部由壳层提供，内容不感知）。
 */
export interface WorkspaceTabRenderContext {
  /** 关闭某个标签（通用，壳层统一收口 next-active 计算）。 */
  closeTab: (tabId: WorkspaceTab['id']) => void
  /** 同步侧栏"当前选中文件"状态（仅文件类定义用到）。 */
  setSelectedFilePath: (path: string | null) => void
  /** 可选：全局轻提示（仅内置定义关闭分支等需要兜底反馈时用）。 */
  showToast?: (message: string, type?: 'success' | 'error' | 'info') => void
  /** 当前标签是否处于激活态（壳层按 activeWorkspaceTabId 派生）。内容组件据此判断「首次进入」与「切回」，例如任务页据此决定切回时保留滚动位置还是首次进入滚到底部。 */
  isActiveTab?: boolean
}

/**
 * 统一工作区标签类型定义（由 `ui.workspace_tab_types` 扩展点产出）。
 *
 * 内置标签（file / log / task / page:*）与插件标签（plugin）走**同一注册表**：
 * 核心 `Layout` / `TitleBar` 只按 `tabTypeKey` 查表渲染，不感知具体业务语义。
 * 内置定义由核心静态 seed，插件定义经 `PluginDispatcher` 贡献（同名 key 插件可覆盖）。
 */
export interface UiWorkspaceTabTypeDefinition {
  /** 注册表 key。插件用 pluginTabType；内置用种类本身：'file' | 'log' | 'task' | 'page:<pageId>'。 */
  tabTypeKey: string
  /** 提供方：插件填插件 id，内置填 'core'。 */
  pluginId: string
  /** 渲染标签主体。内置读 tab 强类型字段，插件读 tab.data。 */
  renderTab: (tab: WorkspaceTab, ctx: WorkspaceTabRenderContext) => ReactNode
  /** 渲染图标（活动栏 / 标题栏用）。 */
  renderIcon: () => ReactNode
  /** 短标签（标题栏文字）。 */
  getLabel: (tab: WorkspaceTab) => string
  /**
   * 可选：自定义标签显示内容（ReactNode），优先级高于 `getLabel`。
   * 用于需要内联状态点 / 图标等富内容的标签（如启动台标签随激活任务动态显示状态与任务名）。
   */
  renderLabel?: (tab: WorkspaceTab) => ReactNode
  /** 完整标题（tooltip）。 */
  getTitle: (tab: WorkspaceTab) => string
  /** 关闭按钮无障碍文案。 */
  getCloseAriaLabel: (tab: WorkspaceTab) => string
  /** 可选：激活该标签时壳层同步到的侧栏 activity id；返回 null 表示不映射。 */
  getSidebarActivityId?: (tab: WorkspaceTab) => SidebarPanelId | null
  /**
   * 标签复用（去重）不在此声明——由打开方构造的 `tab.id` 是否相同决定。
   * 类型定义只负责内容渲染与生命周期，不感知"是否复用"这一层语义。
   */
  /** 可选：标签成为激活态时的内容副作用（如文件标签同步选中路径）。 */
  onActivate?: (tab: WorkspaceTab, ctx: WorkspaceTabRenderContext) => void
  /** 可选：关闭标签时的内容清理（如任务标签停掉会话）。 */
  onClose?: (tab: WorkspaceTab, ctx: WorkspaceTabRenderContext) => void
}

/**
 * 文件页侧栏面板定义（由 `ui.file_sidebar_panels` 扩展点产出）。
 *
 * 插件可为文件页贡献可切换侧栏，例如版本历史、预览辅助信息等。
 * 核心文件页只负责收集、开关与渲染，不感知面板业务来源。
 */
export interface UiFileSidebarPanelDefinition {
  /** 全局唯一面板 id。 */
  id: string
  /** 更多菜单展示标题。 */
  title: string
  /** 当前文件下是否可展示；缺省表示可展示。 */
  isAvailable?: (file: import('@/types/fileTabs').FileTabResource) => boolean
  /** 渲染侧栏内容。 */
  Panel: ComponentType<{
    file: import('@/types/fileTabs').FileTabResource
    requestRefresh: () => void
  }>
}

/**
 * 任务文件列表“更多”菜单操作上下文（核心在调用动作时传入）。
 * 不含任何业务语义，仅描述被操作文件的位置与名称。
 */
export interface TaskFileMoreActionContext {
  /** 当前任务 ID。 */
  taskId: string
  /** 文件相对任务文件根目录的路径（如 `chapter1.md` 或 `sub/x.md`）。 */
  fileRelativePath: string
  /** 文件名（用于展示与提示）。 */
  fileName: string
}

/**
 * 任务文件列表“更多”菜单动作的执行结果。
 * 动作通过返回值上报结果，由核心统一做 toast 反馈。
 */
export interface TaskFileMoreActionResult {
  /** 是否成功。 */
  ok: boolean
  /** 反馈文案（成功或失败提示）。 */
  message: string
}

/**
 * 任务文件列表“更多”菜单的可扩展操作项（由 `ui.task_file_more_actions` 扩展点产出）。
 * 动作携带运行期回调 `invoke`，核心不感知具体业务（如复制到小说空间）。
 */
export interface TaskFileMoreAction {
  /** 全局唯一动作 id。 */
  id: string
  /** 菜单展示文案。 */
  label: string
  /** 可选图标（React 节点）。 */
  icon?: ReactNode
  /** 可选说明。 */
  description?: string
  /** 点击后的执行回调；返回结果对象用于 toast，返回 null 表示静默（如用户取消）。 */
  invoke?: (
    ctx: TaskFileMoreActionContext,
  ) => void | Promise<void | TaskFileMoreActionResult | null>
}

/**
 * 工具调用详情增强上下文（由 `ui.tool_call_detail_view` 扩展点传入）。
 */
export interface ToolCallDetailViewContext {
  /** 工具名。 */
  toolName: string
  /** 解析后的工具参数。 */
  args: Record<string, unknown>
  /** 原始工具调用 ID。 */
  toolCallId?: string
}

/**
 * 工具调用详情增强条目（由 `ui.tool_call_detail_view` 扩展点产出）。
 */
export interface ToolCallDetailViewDefinition {
  /** 提供方插件 id。 */
  pluginId: string
  /** 以工具名作为 key。 */
  toolName: string
  /** 渲染详情增强。 */
  render: (ctx: ToolCallDetailViewContext) => ToolCallDetailEnhancement | null
}

// ── 工作区相关契约（任务列表分组 / ui.composer_footer_controls）──

/** 分组函数接收的任务条目最小契约（避免 plugin/types 反向依赖 query 层导致循环）。 */
export interface TaskListGroupItem {
  taskId: string
  metadata?: Record<string, unknown>
}

/** 任务列表组级动作（如"在此工作区新建任务"），由分组定义贡献。 */
export interface TaskListGroupAction {
  /** 全局唯一动作 id。 */
  id: string
  /** 动作文案。 */
  label: string
  /** 点击回调。 */
  onSelect: () => void
}

/** 任务列表分组（D16 按 workspace 分组，由 TasksPanel 内置分组器产出）。 */
export interface TaskListGroup<T = TaskListGroupItem> {
  /** 稳定 key（组内折叠态记忆用）。 */
  key: string
  /** 组标题。 */
  label: string
  /** 组标题悬浮完整名（如工作区完整根路径），缺省用 label。 */
  title?: string
  /** 组内任务。 */
  tasks: T[]
  /** 组级动作（如"在此新建任务"）。 */
  actions?: TaskListGroupAction[]
  /** 是否默认折叠。 */
  defaultCollapsed?: boolean
}

/**
 * 输入框下方、模型选择行的插件控件（由 `ui.composer_footer_controls` 扩展点产出）。
 * 核心在模型选择行收集并渲染；插件自管草稿态，经 onChange 上报。
 */
export interface UiComposerFooterControlDefinition {
  /** 全局唯一控件 id（同时作为草稿值存储 key）。 */
  id: string
  /** 渲染控件。 */
  render: (ctx: {
    /** 当前草稿值。 */
    value: unknown
    /** 草稿值变化时上报（由核心桥接到提交流程）。 */
    onChange: (next: unknown) => void
    /** 是否移动端。 */
    isMobile: boolean
  }) => ReactNode
}

/**
 * 输入框上方 UI 槽位的上下文（由核心在 dispatch 时传入）。
 * 供插件读取当前草稿、感知任务运行状态。
 *
 * 注意：回填草稿（编辑场景）不再经本上下文暴露 `setDraft`。
 * 改为由插件声明 `composer` 权限、运行期经 `usePermission('composer').set(...)` 注入，
 * 插件不感知 `setDraft` 定义在哪。
 */
export interface ComposerPanelCtx {
  /** 当前任务 ID（草稿态为 undefined）。 */
  taskId: string | undefined
  /** 当前草稿状态（rawContent 含内联 opaque token，tokens 为胶囊列表）。 */
  draft: ChatComposerDraftState
  /** 当前任务是否处于 running。 */
  isRunning: boolean
}

/**
 * 输入框上方 UI 定义（由 `ui.composer_above_panel` 扩展点产出）。
 * 核心在输入框上方收集并渲染所有实现的 Component；插件 Component 自管状态。
 */
export interface UiComposerAbovePanelDefinition {
  /** 全局唯一控件 id。 */
  id: string
  /** 渲染组件（接收 ComposerPanelCtx 作为 props）。 */
  Component: ComponentType<ComposerPanelCtx>
}
