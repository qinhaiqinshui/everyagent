import type {
  FileTabOpenMode,
  ThemeMode,
  UserInteractionRequest,
  UserInteractionResult,
} from '@/types'
import type { TaskRecord, TaskStatus } from '@/task/types'

/**
 * 领域事件名与载荷的唯一登记处。
 *
 * 收录口径（known-issues #4 清理后）：只登记**真实有 emit 调用点**的事件，
 * 以及少量「宿主监听、供插件反向 emit 驱动宿主」的请求通道（`*-requested`
 * 后缀与 `runtime-config-error`）——被折叠器/镜像订阅取代设计位的旧条目
 * （agent 消息/trace 增量、token 用量、轮开始结束等）已随清理删除；
 * 需要感知任务消息/trace 细粒度变化请走 taskStream 内部通道或 UI 扩展点，
 * 不要在本表重新登记零 emit 的事件名。
 */
export const DOMAIN_EVENTS = {
  TASK_CREATED: 'task-created',
  TASK_DELETED: 'task-deleted',
  TASK_STATUS_CHANGED: 'task-status-changed',
  SETTINGS_THEME_PATCHED: 'settings-theme-patched',
  RUNTIME_CONFIG_ERROR: 'runtime-config-error',
  WORKSPACE_FOCUS_TASK_REQUESTED: 'workspace-focus-task-requested',
  WORKSPACE_OPEN_FILE_REQUESTED: 'workspace-open-file-requested',
  WORKSPACE_CLOSE_FILE_REQUESTED: 'workspace-close-file-requested',
  WORKSPACE_RELOAD_ALL_FILES_REQUESTED: 'workspace-reload-all-files-requested',
  WORKSPACE_FILE_CHANGED: 'workspace-file-changed',
  WORKSPACE_REGISTRY_CHANGED: 'workspace-registry-changed',
  SIDEBAR_PANEL_SHOWN: 'sidebar-panel-shown',
  WORKSPACE_SEARCH_PANEL_REQUESTED: 'workspace-search-panel-requested',
  USER_INTERACTION_REQUESTED: 'user-interaction-requested',
  USER_INTERACTION_RESOLVED: 'user-interaction-resolved',
  USER_INTERACTION_CLEARED: 'user-interaction-cleared',
  WORKSPACE_OPEN_USER_INTERACTION_REQUESTED: 'workspace-open-user-interaction-requested',
  TASK_ROUND_CLOSED: 'task-round-closed',
  PLUGINS_LOADED: 'plugins-loaded',
  WORKER_DATA_CHANGED: 'worker-data-changed',
  WORKSPACE_TAB_CLOSED: 'workspace-tab-closed',
} as const

export type DomainEventMap = {
  [DOMAIN_EVENTS.TASK_CREATED]: {
    taskId: string
  }
  [DOMAIN_EVENTS.TASK_DELETED]: {
    taskId: string
  }
  [DOMAIN_EVENTS.TASK_STATUS_CHANGED]: {
    taskId: string
    status: TaskStatus
    error?: string
    endTime?: number
    task?: TaskRecord
    displayTitle?: string
  }
  [DOMAIN_EVENTS.SETTINGS_THEME_PATCHED]: {
    themeMode: ThemeMode
  }
  [DOMAIN_EVENTS.RUNTIME_CONFIG_ERROR]: {
    message: string
  }
  [DOMAIN_EVENTS.WORKSPACE_FOCUS_TASK_REQUESTED]: {
    taskId: string
  }
  [DOMAIN_EVENTS.WORKSPACE_OPEN_FILE_REQUESTED]: {
    /** 文件路径。 */
    filePath: string
    /** 所属工作区根(可选;未带时落当前选中工作区)。 */
    workspaceRoot?: string
    /** 是否立即进入重命名态。 */
    startNameEditing?: boolean
    /** 打开模式。 */
    mode?: FileTabOpenMode
    /** 打开后定位到的目标行号。 */
    lineNumber?: number
  }
  [DOMAIN_EVENTS.WORKSPACE_CLOSE_FILE_REQUESTED]: {
    filePath?: string
    fileTabId?: `file:${string}`
    force?: boolean
  }
  [DOMAIN_EVENTS.WORKSPACE_RELOAD_ALL_FILES_REQUESTED]: {
    /** 是否要求忽略未保存态强制重载。 */
    force?: boolean
  }
  [DOMAIN_EVENTS.WORKSPACE_FILE_CHANGED]: {
    /** 发生变化的文件完整业务路径（重命名后为新路径）。 */
    filePath: string
    /** 变更所属工作区根(worker 机器上的绝对路径;多工作区并行,订阅方按分组过滤)。 */
    workspaceRoot: string
    /** 操作类型：新增 / 修改 / 删除 / 重命名。 */
    operation: 'create' | 'modify' | 'delete' | 'rename'
    /** 是否为目录。 */
    isDirectory: boolean
    /** 重命名前的旧路径（仅 operation === 'rename' 时存在）。 */
    oldFilePath?: string
    /**
     * 调用方原样透传的不透明业务袋（如 AI 工具的 TaskToolExecutionContext）。
     * 下层（fs / 网关）不解释其中键名；需要 taskId / agentId 等字段的上层订阅方
     * （文件变更轮末展示区插件）自行从袋里取出。新增 workflowId 等业务字段时，
     * 无需改动事件契约与底层。
     */
    metadata?: Record<string, unknown>
  }
  [DOMAIN_EVENTS.SIDEBAR_PANEL_SHOWN]: {
    /** 被显示出来的侧边栏面板 ID。 */
    panelId: string
  }
  [DOMAIN_EVENTS.WORKSPACE_SEARCH_PANEL_REQUESTED]: {
    /** 范围所属工作区的 worker。 */
    workerId: string
    /** 搜索范围所属工作区根（worker 机器绝对路径）。 */
    workspaceRoot: string
    /** 搜索根路径（业务绝对形态；空串表示工作区根）。 */
    rootPath: string
    /** 范围显示名（目录名或「工作区根目录」）。 */
    label: string
    /** 搜索目标 id：files = 工作区文件（默认，资源管理器跳转）；tasks = 任务内容（任务列表工作区组跳转）。未知 id 由接收方查注册表校验后回落 files。 */
    target?: string
  }
  [DOMAIN_EVENTS.WORKSPACE_REGISTRY_CHANGED]: {
    /** 默认工作区根(worker 机器上的绝对路径,始终在册)。 */
    defaultRoot: string
    /** 注册表(按注册时间升序)。 */
    workspaces: Array<{ root: string; addedAt: number }>
  }
  [DOMAIN_EVENTS.USER_INTERACTION_REQUESTED]: {
    /** 所属 Task ID。 */
    taskId: string
    /** 所属 Agent ID。 */
    agentId: string
    /** 交互请求体。 */
    request: UserInteractionRequest
  }
  [DOMAIN_EVENTS.USER_INTERACTION_RESOLVED]: {
    /** 所属 Task ID。 */
    taskId: string
    /** 所属 Agent ID。 */
    agentId: string
    /** 已提交的交互结果。 */
    result: UserInteractionResult
  }
  [DOMAIN_EVENTS.USER_INTERACTION_CLEARED]: {
    /** 所属 Task ID。 */
    taskId: string
    /** 所属 Agent ID。 */
    agentId: string
    /** 被清理的交互请求 ID。 */
    interactionId: string
  }
  [DOMAIN_EVENTS.WORKSPACE_OPEN_USER_INTERACTION_REQUESTED]: {
    /** 需要打开的交互请求 ID。 */
    interactionId: string
  }
  [DOMAIN_EVENTS.TASK_ROUND_CLOSED]: {
    /** 所属 Task ID。 */
    taskId: string
    /** 闭合轮起点 seq（worker round.closed 瞬态信号透传）。 */
    startSeq: string
    /** 闭合轮终点 seq。 */
    endSeq: string
  }
  [DOMAIN_EVENTS.PLUGINS_LOADED]: {
    /** 已装载的插件数量（含动态插件）。 */
    count: number
  }
  [DOMAIN_EVENTS.WORKER_DATA_CHANGED]: Record<string, never>
  [DOMAIN_EVENTS.WORKSPACE_TAB_CLOSED]: { tabId: string }
}

export type DomainEventName = keyof DomainEventMap
