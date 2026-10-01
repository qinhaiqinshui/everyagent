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
import type { ToolViewProps } from '@/components/task/toolViews/types'
import type {
  ChatComposerDraftState,
  ChatComposerToken,
  RoundSummary,
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
  /**
   * 活动栏排序字段（float，越小越靠前，同值按贡献先后稳定排列）。
   * 内置项与插件贡献统一按此字段混排，不再依赖注册顺序；缺省视为 100。
   */
  order?: number
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
  /** 渲染图标（活动栏 / 标题栏用）。接收当前 tab,便于按文件名等动态选择图标。 */
  renderIcon: (tab: WorkspaceTab) => ReactNode
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
 * 工具调用视图接管定义（由 `ui.tool_call_views` 扩展点产出）。
 *
 * 与核心内置 `toolViews/` 目录注册表的 `ToolViewDefinition` 同构同地位：
 * 按工具名命中即**整体接管该工具调用的渲染**（折叠态 + 展开态），
 * 组件完全自治。解析优先级：插件注册的视图 > 内置目录注册表 > DefaultToolView。
 */
export interface ToolCallViewDefinition {
  /** 提供方插件 id。 */
  pluginId: string
  /** 以工具名作为 key（与核心 ToolViewDefinition.toolName 语义一致）。 */
  toolName: string
  /** 接管渲染的完整视图组件（折叠态 + 展开态）。 */
  Component: ComponentType<ToolViewProps>
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
  /** 选中某个 agent（传 null 恢复全部）。核心实现联动过滤，插件只调回调。 */
  selectAgent: (agentId: string | null) => void
  /** 调用 RPC 方法（如 task.agents）。 */
  rpc: (method: string, params: Record<string, unknown>) => Promise<unknown>
  /** 订阅任务流事件（agent.*、usage 等），返回取消订阅函数。 */
  subscribeTaskEvents: (handler: (event: string, agentId: string | null, payload: unknown) => void) => () => void
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

// ── 用户消息动作 / 提交贡献（ui.user_message_actions / task.submit_contributions）──

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

/** 轮末展示区组件的 props 契约。 */
export interface RoundTailPanelProps {
  /** 当前任务 ID。 */
  taskId: string
  /** 当前轮次 ID。 */
  roundId: string
  /** 任务所属 worker ID（可选，运行期动态标注，可能为 undefined）。 */
  workerId?: string
  /** 工作区根路径（可选）。 */
  workspaceRoot?: string
  /** 当前轮次摘要（核心不感知其中业务语义，插件自行解释）。 */
  round: RoundSummary
}

/** 轮末展示区定义（web 内部类型，对齐 plugin-api 的 UiRoundTailPanelDefinition）。 */
export interface UiRoundTailPanelDefinition {
  /** 提供方插件 ID。 */
  pluginId: string
  /** 渲染组件。 */
  Component: ComponentType<RoundTailPanelProps>
}
