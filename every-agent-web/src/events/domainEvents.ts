import type {
  AppNotification,
  ContextMonitorSnapshot,
  FileTabOpenMode,
  AgentMessageRecord,
  AgentRecord,
  TaskRecord,
  TaskProtocolStateData,
  TaskTraceRecord,
  TaskStatus,
  ThemeMode,
  UserInteractionRequest,
  UserInteractionResult,
} from '@/types'

/**
 * `Agent` 运行期对外公布的可见事件（step-14：trace 收口）。
 *
 * `Agent` 只负责「产生可见事件」，不直接控制任务页结构、也不直达任务级 trace；
 * 由 `Task`（`TaskRunner`）在存在时订阅这些事件，把它们转成任务 trace 与流式占位消息。
 * `Task` 不存在（如被更上层直接调用）时，这些事件只是无人消费，不阻断运行。
 *
 * 注意：本事件不引入新的 trace DTO，流式占位与重试都复用现有 `AgentMessageRecord` /
 * `TaskTraceRecord` 语义（见改造方案 plan §6.3 / step-14）。
 */
export type AgentRunEvent =
  | { kind: 'streaming_update'; taskId: string; agentId: string; message: AgentMessageRecord }
  | { kind: 'streaming_clear'; taskId: string; agentId: string; messageId: string }
  | {
      kind: 'assistant_retry'
      taskId: string
      agentId: string
      error: string
      attempt: number
      maxAttempts: number
      delayMs: number
      createdAt: number
    }
  | {
      kind: 'assistant_retry_progress'
      taskId: string
      agentId: string
      error: string
      attempt: number
      maxAttempts: number
      delayMs: number
      elapsedMs: number
      remainingMs: number
      createdAt: number
    }
  | { kind: 'assistant_retry_resolved'; taskId: string; agentId: string }

/**
 * `Agent` 层产出的运行事件负载（不含所属上层标识，由 task 层事件桥补填 `taskId` 后发往总线）。
 *
 * 前 5 种与 `AgentRunEvent` 一一对应（仅去掉 `taskId`），经 `AGENT_RUN_EVENT` 领域事件发布；
 * `agent_message_appended` / `context_monitor_changed` 则由 task 层事件桥**直接**落库并发布
 * 对应领域事件，不再经过 `AGENT_RUN_EVENT` 中转。这样 `Agent` 层只产出负载、不持有上层对象，
 * 上行能力全部收口在 task 层注入的链节点 / 事件桥（见 src/task/agentRunEventBridge.ts）。
 */
export type AgentRunEventPayload =
  | { kind: 'streaming_update'; agentId: string; message: AgentMessageRecord }
  | { kind: 'streaming_clear'; agentId: string; messageId: string }
  | {
      kind: 'assistant_retry'
      agentId: string
      error: string
      attempt: number
      maxAttempts: number
      delayMs: number
      createdAt: number
    }
  | {
      kind: 'assistant_retry_progress'
      agentId: string
      error: string
      attempt: number
      maxAttempts: number
      delayMs: number
      elapsedMs: number
      remainingMs: number
      createdAt: number
    }
  | { kind: 'assistant_retry_resolved'; agentId: string }
  | { kind: 'agent_message_appended'; agentId: string; message: AgentMessageRecord }
  | { kind: 'context_monitor_changed'; agentId: string; snapshot: ContextMonitorSnapshot | null }
  | { kind: 'task_trace_recorded'; agentId: string; trace: TaskTraceRecord }

export const DOMAIN_EVENTS = {
  AGENT_UPDATED: 'agent-updated',
  AGENT_MESSAGE_APPENDED: 'agent-message-appended',
  AGENT_MESSAGE_STREAMING: 'agent-message-streaming',
  TASK_CREATED: 'task-created',
  TASK_DELETED: 'task-deleted',
  TASK_STATUS_CHANGED: 'task-status-changed',
  TASK_TRACE_CHANGED: 'task-trace-changed',
  TASK_TOKEN_USAGE_CHANGED: 'task-token-usage-changed',
  TASK_CONTEXT_MONITOR_CHANGED: 'task-context-monitor-changed',
  TASK_PROTOCOL_STATE_CHANGED: 'task-protocol-state-changed',
  SETTINGS_THEME_PATCHED: 'settings-theme-patched',
  SETTINGS_LLM_PROFILES_PATCHED: 'settings-llm-profiles-patched',
  SETTINGS_GUARDRAIL_PATCHED: 'settings-guardrail-patched',
  SETTINGS_PROMPT_TEMPLATES_PATCHED: 'settings-prompt-templates-patched',
  RUNTIME_CONFIG_ERROR: 'runtime-config-error',
  WORKSPACE_FOCUS_TASK_REQUESTED: 'workspace-focus-task-requested',
  WORKSPACE_OPEN_FILE_REQUESTED: 'workspace-open-file-requested',
  WORKSPACE_CLOSE_FILE_REQUESTED: 'workspace-close-file-requested',
  WORKSPACE_RELOAD_ALL_FILES_REQUESTED: 'workspace-reload-all-files-requested',
  WORKSPACE_FILE_CHANGED: 'workspace-file-changed',
  WORKSPACE_REGISTRY_CHANGED: 'workspace-registry-changed',
  SIDEBAR_PANEL_SHOWN: 'sidebar-panel-shown',
  APP_NOTIFICATION_ADDED: 'app-notification-added',
  APP_NOTIFICATION_REMOVED: 'app-notification-removed',
  WORKSPACE_OPEN_AI_CALL_LOG_REQUESTED: 'workspace-open-ai-call-log-requested',
  USER_INTERACTION_REQUESTED: 'user-interaction-requested',
  USER_INTERACTION_RESOLVED: 'user-interaction-resolved',
  USER_INTERACTION_CLEARED: 'user-interaction-cleared',
  WORKSPACE_OPEN_USER_INTERACTION_REQUESTED: 'workspace-open-user-interaction-requested',
  FILE_CONTENT_SAVED: 'file-content-saved',
  TASK_TURN_STARTED: 'task-turn-started',
  TASK_TURN_COMPLETED: 'task-turn-completed',
  AGENT_RUN_EVENT: 'agent-run-event',
  PLUGINS_LOADED: 'plugins-loaded',
} as const

export type DomainEventMap = {
  [DOMAIN_EVENTS.AGENT_UPDATED]: {
    /** 所属 Task ID。 */
    taskId: string
    /** 发生变化的 Agent ID。 */
    agentId: string
    /** 最新 Agent 快照。 */
    agent?: AgentRecord
  }
  [DOMAIN_EVENTS.AGENT_MESSAGE_APPENDED]: {
    /** 所属 Task ID。 */
    taskId: string
    /** 发生变化的 Agent ID。 */
    agentId: string
    /** 新追加的消息快照。 */
    message?: import('@/types').AgentMessageRecord
  }
  [DOMAIN_EVENTS.AGENT_MESSAGE_STREAMING]: {
    /** 所属 Task ID。 */
    taskId: string
    /** 发生变化的 Agent ID。 */
    agentId: string
    /** 流式消息 ID。 */
    messageId: string
    /** 当前动作。 */
    action: 'update' | 'clear'
    /** 最新消息快照。 */
    message?: import('@/types').AgentMessageRecord
  }
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
  [DOMAIN_EVENTS.TASK_TRACE_CHANGED]: {
    taskId: string
    action: 'append' | 'replace' | 'remove'
    trace?: TaskTraceRecord
  }
  [DOMAIN_EVENTS.TASK_TOKEN_USAGE_CHANGED]: {
    taskId: string
    tokenUsage: import('@/types').TokenUsageStats | undefined
  }
  [DOMAIN_EVENTS.TASK_CONTEXT_MONITOR_CHANGED]: {
    /** 所属 Task ID。 */
    taskId: string
    /** 发生变化的 Agent ID（任务级收口时可为空串）。 */
    agentId: string
    /** 最新上下文监控快照。 */
    snapshot: ContextMonitorSnapshot
  }
  [DOMAIN_EVENTS.TASK_PROTOCOL_STATE_CHANGED]: {
    taskId: string
    protocolId?: string
    data: TaskProtocolStateData | null
  }
  [DOMAIN_EVENTS.SETTINGS_THEME_PATCHED]: {
    themeMode: ThemeMode
  }
  [DOMAIN_EVENTS.SETTINGS_LLM_PROFILES_PATCHED]: {
    /** 设置页需要重查 LLM 配置列表。 */
    changedAt: number
  }
  [DOMAIN_EVENTS.SETTINGS_GUARDRAIL_PATCHED]: {
    /** 运行护栏全局配置已更新。 */
    changedAt: number
  }
  [DOMAIN_EVENTS.SETTINGS_PROMPT_TEMPLATES_PATCHED]: { changedAt: number }
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
     * （task-file-changes 插件）自行从袋里取出。新增 workflowId 等业务字段时，
     * 无需改动事件契约与底层。
     */
    metadata?: Record<string, unknown>
  }
  [DOMAIN_EVENTS.SIDEBAR_PANEL_SHOWN]: {
    /** 被显示出来的侧边栏面板 ID。 */
    panelId: string
  }
  [DOMAIN_EVENTS.WORKSPACE_REGISTRY_CHANGED]: {
    /** 默认工作区根(worker 机器上的绝对路径,始终在册)。 */
    defaultRoot: string
    /** 注册表(按注册时间升序)。 */
    workspaces: Array<{ root: string; addedAt: number }>
  }
  [DOMAIN_EVENTS.APP_NOTIFICATION_ADDED]: {
    notification: AppNotification
  }
  [DOMAIN_EVENTS.APP_NOTIFICATION_REMOVED]: {
    notificationId: string
  }
  [DOMAIN_EVENTS.WORKSPACE_OPEN_AI_CALL_LOG_REQUESTED]: {
    taskId?: string
    callId: string
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
  [DOMAIN_EVENTS.FILE_CONTENT_SAVED]: {
    /** 所属 Task ID（可能缺省）。 */
    taskId?: string
    /** 触发保存的 Agent ID（仅元数据，不参与筛选）。 */
    agentId?: string
    /** 完整文件路径。 */
    filePath: string
    /** 改动前内容（create 模式为空串）。 */
    before: string
    /** 改动后内容。 */
    after: string
    /** 改动类型（create_file / update_file 语义：created / modified）。 */
    changeType: 'created' | 'modified'
    /** 保存发生时间戳。 */
    ts: number
  }
  [DOMAIN_EVENTS.TASK_TURN_STARTED]: {
    /** 所属 Task ID。 */
    taskId: string
    /** 主 agent ID（关窗锚定依据）。 */
    mainAgentId: string
    /** 用户本次提交时间戳。 */
    ts: number
  }
  [DOMAIN_EVENTS.TASK_TURN_COMPLETED]: {
    /** 所属 Task ID。 */
    taskId: string
    /** 主 agent ID。 */
    mainAgentId: string
    /** 主 agent 执行最终完成时间戳。 */
    ts: number
  }
  [DOMAIN_EVENTS.PLUGINS_LOADED]: {
    /** 已装载的插件数量（含动态插件）。 */
    count: number
  }
  [DOMAIN_EVENTS.AGENT_RUN_EVENT]: AgentRunEvent
}

export type DomainEventName = keyof DomainEventMap
