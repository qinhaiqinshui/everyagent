// ─── 核心类型定义 ─────────────────────────────────────────────────────────────

/** Token 用量统计。 */
export interface TokenUsageStats {
  /** 输入 token 数。 */
  promptTokens: number
  /** 输出 token 数。 */
  completionTokens: number
  /** 总 token 数。 */
  totalTokens: number
  /** 请求次数。 */
  requestCount: number
  /** provider 原始用量请求次数。 */
  providerRequestCount: number
  /** 估算用量请求次数。 */
  estimatedRequestCount: number
  /** 最近更新时间。 */
  lastUpdatedAt?: number
}

/** 任务协议 ID。 */
export type TaskProtocolId = string

/** 会话运行状态。 */
export type AgentStatus = 'idle' | 'running' | 'waiting-user' | 'completed' | 'stopped' | 'error'

/** 消息历史参与方式。 */
export type MessageHistoryMode = 'thread_and_prompt' | 'thread_only' | 'hidden'

/** 单次运行中用户输入内容快照。 */
export interface AgentRunUserInputSnapshot {
  /** 当前运行展示给用户看的输入文本。 */
  text: string
  /** 当前运行保留的原始输入内容。 */
  rawContent?: string
  /** 当前运行附带的 skill 输入。 */
  skillInputs?: Record<string, unknown>
}

/** 单次运行上下文快照。 */
export interface AgentRunContext {
  /** 当前运行使用的模型配置 ID。 */
  llmConfigId: string
  /** 当前运行使用的模型配置名称。 */
  llmProfileName?: string
  /** 当前运行使用的模型提供商。 */
  llmProvider?: string
  /** 当前运行使用的模型名。 */
  llmModel?: string
  /** 当前运行使用的协议 ID。 */
  protocolId?: TaskProtocolId
  /** 当前运行显式指定的思考强度。 */
  reasoningEffort?: string
  /** 当前运行的用户输入快照。 */
  userInput: AgentRunUserInputSnapshot
}

/** 单条会话记录。 */
export interface AgentRecord {
  /** 所属任务 ID。 */
  taskId: string
  /** 会话 ID。 */
  agentId: string
  /** 当前会话在 Task 内的稳定顺序。 */
  order: number
  /** 会话标题。 */
  title?: string
  /** 当前会话运行状态。 */
  status: AgentStatus
  /** 当前会话采用的协议 ID。 */
  protocolId?: TaskProtocolId
  /** 绑定的模型配置 ID。 */
  llmConfigId?: string
  /** 当前会话显式指定的思考强度覆盖值。 */
  reasoningEffort?: string
  /** 当前会话累计 token 用量。 */
  tokenUsage?: TokenUsageStats
  /** 当前会话最近一次上下文监控快照。 */
  contextMonitor?: ContextMonitorSnapshot | null
  /** 当前会话附加元数据。 */
  metadata?: Record<string, unknown>
  /** 创建时间。 */
  createdAt: number
  /** 更新时间。 */
  updatedAt: number
  /** 本次运行结束时间。 */
  endedAt?: number
  /** 正常完成时间。 */
  completedAt?: number
  /** 停止时间。 */
  stoppedAt?: number
  /** 错误信息。 */
  error?: string
}

/** 单条会话消息记录。 */
export interface AgentMessageRecord {
  /** 消息 ID。 */
  messageId: string
  /** 所属会话 ID。 */
  agentId: string
  /** 角色。 */
  role: 'system' | 'user' | 'assistant' | 'tool'
  /** 文本内容。 */
  content: string
  /** 原始内容。 */
  rawContent?: string
  /** 助手思考过程，仅用于线程展示，不进入 prompt。 */
  reasoning?: string
  /** 历史参与方式。 */
  historyMode: MessageHistoryMode
  /** 创建时间。 */
  createdAt: number
  /** 更新时间。 */
  updatedAt: number
  /** 单个会话内稳定递增序号。 */
  sequence: number
  /** 工具调用 ID。 */
  toolCallId?: string
  /** 工具名。 */
  toolName?: string
  /** 工具结果状态：运行时显式标记成功/失败，UI 与回放以该字段为准（旧记录可能缺失，由内容启发式兜底）。 */
  status?: 'success' | 'error'
  /** AI 调用日志 ID。 */
  aiCallId?: string
  /** 助手消息附带的工具调用（ReAct 工具循环需要回放）。 */
  toolCalls?: LLMToolCall[]
  /** 当前消息回放用的结构化 token 列表。 */
  composerTokens?: ChatComposerToken[]
  /** 附加元数据。 */
  metadata?: Record<string, unknown>
}

/** 主题模式。 */
export type ThemeMode = 'dark' | 'light'


/** 聊天输入 token。 */
export interface ChatComposerToken {
  /** 唯一实例 id（React key、DOM data-token-id、去重）。与 opaqueText 解耦，由 composer 生成。 */
  id: string
  /** token 类型（运行期 read 用，渲染层不读）。从 opaque 串解析得出。 */
  kind: string
  /** 胶囊显示文字（选中时取 opaque 串顶层 label 明文段）。 */
  label: string
  /** 胶囊补充信息（可选，取 opaque 串顶层 summary 明文段）。 */
  summary?: string
  /** 唯一序列化串（已删除 id 段；label/summary 在顶层明文段）。渲染与提交的唯一锚点。 */
  opaqueText: string
}

/** `@` 输入增强候选项。 */
export interface ChatComposerProvider {
  /** provider 全局唯一 ID。 */
  id: string
  /** 展示标题。 */
  title: string
  /** 展示说明。 */
  description?: string
  /** 建议触发字符。 */
  trigger?: '@'
  /** 可选附加元数据，用于生成结构化 token。 */
  metadata?: Record<string, unknown>
}

/** `/` 命令候选项。 */
export interface ChatCommandProvider {
  /** 命令 ID。 */
  id: string
  /** 命令显示标题。 */
  title: string
  /** slash 输入关键字，不包含 `/`。 */
  slash: string
  /** 命令说明。 */
  description?: string
  /** 命令选中后如何处理输入框剩余文本。 */
  draftTextMode?: 'clear' | 'consume_as_argument' | 'retain_as_input'
  /** 默认负载。 */
  payload?: Record<string, unknown>
  /** 命令附加元数据。 */
  metadata?: Record<string, unknown>
  /** 命令触发后的通用交互请求。 */
  interactionRequest?: ChatComposerInteractionRequest
}

/** 输入框内联交互选项。 */
export interface ChatComposerInteractionOption {
  /** 选项 ID。 */
  id: string
  /** 选项标题。 */
  label: string
  /** 选项说明。 */
  description?: string
  /** 选项对应的负载。 */
  payload?: Record<string, unknown>
}

/** 输入框内联交互请求。 */
export interface ChatComposerInteractionRequest {
  /** 交互请求 ID。 */
  id: string
  /** 交互标题。 */
  title: string
  /** 交互说明。 */
  description?: string
  /** 搜索占位提示。 */
  searchPlaceholder?: string
  /** 空状态提示。 */
  emptyText?: string
  /** 当前搜索词。 */
  query?: string
  /** 候选项列表。 */
  options: ChatComposerInteractionOption[]
}

/** 聊天输入草稿状态。 */
export interface ChatComposerDraftState {
  /** 文本输入内容。 */
  text: string
  /** 原始输入内容。 */
  rawContent: string
  /** 已插入的结构化 token 列表。 */
  tokens: ChatComposerToken[]
  /** 当前激活的 token ID。 */
  activeTokenId?: string
}

/** 标准化后的聊天提交结构。 */
export interface ChatComposerSubmission {
  /** 本次提交的纯文本内容。 */
  text: string
  /** 本次提交的原始输入内容。 */
  rawContent?: string
  /** 本次提交的结构化 token 列表。 */
  tokens: ChatComposerToken[]
  /** skill 额外输入集合。 */
  skillInputs?: Record<string, unknown>
  /** 提交前 token 解析得到的 Agent metadata 补丁。 */
  agentMetadataPatch?: Record<string, unknown>
  /** 仅本次提交生效的 skill 详细说明列表，不进入持久化快照。 */
  transientSkillInstructions?: Array<{
    /** 对应 skill ID。 */
    skillId: string
    /** skill 展示标题。 */
    title: string
    /** skill 对外暴露的 path。 */
    path?: string
    /** 本轮直接注入的详细说明正文。 */
    instructions: string
  }>
  /** 本次提交指定的协议 ID。 */
  protocolId?: TaskProtocolId
  /** 本次提交建议标题。 */
  title?: string
}

/** 应用通知。 */
export interface AppNotification {
  /** 通知 ID。 */
  id: string
  /** 通知标题。 */
  title?: string
  /** 通知内容。 */
  message: string
  /** 创建时间。 */
  createdAt: number
  /** 通知语气。 */
  tone?: 'info' | 'success' | 'warning' | 'error'
  /** 点击回调。 */
  onClick?: () => void
}

/**
 * 「其他」选项的固定 ID：旧格式兼容路径自动追加的兜底选项，
 * 用户选中后可在输入框填写自定义内容（结果经 otherText 回传）。
 * 新格式由 worker 侧 AskUserTool 自行追加 type="input" 选项，不再使用此常量。
 */
export const USER_INTERACTION_OTHER_OPTION_ID = '__other__'

/** 用户交互选项。 */
export interface UserInteractionOption {
  /** 选项 ID（label 即 ID，回传用）。 */
  id: string
  /** 选项标题。 */
  label: string
  /** 回传值。 */
  value: string
  /** 选项类型："radio" | "input"。 */
  type: string
  /** 选项补充说明。 */
  description?: string
}

/** 用户交互选择题（ask_user 多问题主模型，均为单选）。 */
export interface UserInteractionQuestion {
  /** 问题 ID（结果回传 answers 的键）。 */
  id: string
  /** 问题文本。 */
  prompt: string
  /** 补充说明。 */
  details?: string
  /** 结构化信息槽（「标签 → 值」，在 prompt 下、options 上渲染为信息块；空/缺省不渲染）。 */
  fields?: Record<string, string>
  /** 可选项列表（每项含 type："radio" 单选 / "input" 自由输入）。 */
  options: UserInteractionOption[]
  /** 「其他」选项文案（旧格式兼容，缺省「其他」）。 */
  otherLabel?: string
  /** 「其他」输入框占位提示（旧格式兼容）。 */
  otherPlaceholder?: string
}

/** 用户交互选择题回答。 */
export interface UserInteractionQuestionAnswer {
  /** 问题 ID。 */
  questionId: string
  /** 问题文本。 */
  prompt: string
  /** 选中的选项 ID 列表。 */
  selectedOptionIds: string[]
  /** 选中的选项明细。 */
  selectedOptions: { id: string; label: string; value?: string; description?: string }[]
  /** 是否选中了 input 类型选项。 */
  otherSelected: boolean
  /** input 类型选项的自定义输入内容。 */
  otherText?: string
}

/** 用户交互请求。 */
export interface UserInteractionRequest {
  /** 交互请求 ID。 */
  id: string
  /** 主问题（多问题选择题经 questions 提供；此处用于标题展示）。 */
  prompt: string
  /** 补充说明。 */
  details?: string
  /** 回答约束列表。 */
  constraints?: string[]
  /** 可选项列表（旧格式兼容：单问题选择题用）。 */
  options?: UserInteractionOption[]
  /** 多问题选择题列表（ask_user 工具主路径；存在时优先渲染）。 */
  questions?: UserInteractionQuestion[]
  /** 提交按钮文案。 */
  submitLabel?: string
  /** 创建时间。 */
  createdAt: number
}

/** 用户交互结果。 */
export interface UserInteractionResult {
  /** 对应的交互请求 ID。 */
  interactionId: string
  /** 选中的选项 ID 列表（单问题选择题，兼容内部调用方）。 */
  selectedOptionIds?: string[]
  /** 多问题选择题回答列表。 */
  answers?: UserInteractionQuestionAnswer[]
  /** 原始结构化结果。 */
  raw: {
    /** 选中的选项 ID 列表。 */
    selectedOptionIds?: string[]
    /** 多问题选择题回答列表。 */
    answers?: UserInteractionQuestionAnswer[]
  }
  /** 提交时间。 */
  submittedAt: number
}

/** 系统内置左侧活动栏面板 ID。 */
export type BuiltinSidebarPanelId = 'tasks' | 'files' | 'search'

/** 左侧活动栏面板 ID。 */
export type SidebarPanelId = BuiltinSidebarPanelId | string

/** 可打开的顶层页面 ID。 */
export type TopLevelPageId = 'settings'

/** 顶层页面标签。 */
export interface WorkspacePageTab {
  /** 标签 ID。 */
  id: `page:${TopLevelPageId}`
  /** 工作区标签类型。 */
  tabType: 'page'
  /** 页面 ID。 */
  pageId: TopLevelPageId
}

/** 文件标签打开模式。 */
export type FileTabOpenMode = 'readwrite' | 'readonly'

/** 文件内容编辑器类型标识。需要支持后续通过新增编辑器模块直接扩展。 */
export type FileEditorKind = string

/** 顶层文件标签。 */
export interface GlobalWorkspaceFileTab {
  /** 文件标签 ID。 */
  id: `file:${string}`
  /** 所属工作区根(worker 机器上的绝对路径;多工作区并行,同名相对路径分属不同文件)。 */
  workspaceRoot: string
  /** 文件路径(工作区内业务绝对路径)。 */
  filePath: string
  /** 文件名。 */
  fileName: string
  /** 文件打开模式。 */
  mode?: FileTabOpenMode
  /** 当前匹配到的编辑器类型。 */
  editorKind?: FileEditorKind
  /** 所属任务 ID。 */
  taskId?: string
  /** 重载键。 */
  reloadKey: number
  /** 请求进入重命名态的时间。 */
  nameEditRequestedAt?: number
  /** 请求定位到的目标行号。 */
  lineNumber?: number
  /** 请求定位到目标行号的时间戳。 */
  lineLocateRequestedAt?: number
}

/** 工作区文件标签。 */
export interface WorkspaceFileTab extends GlobalWorkspaceFileTab {
  /** 工作区标签类型。 */
  tabType: 'file'
}

/** 顶层日志标签。 */
export interface GlobalLogTab {
  /** 日志标签 ID。 */
  id: `log:${string}`
  /** 初始任务过滤值，可为空表示不过滤。 */
  taskId?: string
  /** 初始 AI 调用 ID 过滤值。 */
  callId?: string
  /** 初始关键词过滤值。 */
  query?: string
  /** 标签标题。 */
  title: string
  /** 触发日志聚焦的版本号。 */
  focusKey: number
}

/** 工作区日志标签。 */
export interface WorkspaceLogTab extends GlobalLogTab {
  /** 工作区标签类型。 */
  tabType: 'log'
}

/** 工作区 Task 聊天标签。 */
export interface WorkspaceTaskChatTab {
  /** 标签 ID。 */
  id: string
  /** 工作区标签类型。 */
  tabType: 'task'
  /** 任务 ID。 */
  taskId: string
  /** 标签标题。 */
  title: string
  /** 可选：当前标签定位到的会话 ID（复用同一任务标签时切换会话视图）。 */
  agentId?: string
}

/**
 * 插件自定义工作区标签。
 *
 * 判别式 `tabType` 固定为 `'plugin'`，保持顶层 `WorkspaceTab` 联合的可判别性；
 * 真正的插件标签种类由 `pluginTabType` 标识。插件通过 `ui.workspace_tab_types`
 * 扩展点注册 `pluginTabType` 对应的渲染器与图标；核心 Layout/TitleBar 只按
 * `pluginTabType` 查注册表渲染，不感知具体业务语义。`data` 为标签业务私有数据
 * （如小说 ID），由插件的渲染器解释。
 */
export interface WorkspacePluginTab {
  /** 标签 ID，形如 `<pluginTabType>:<key>`。 */
  id: string
  /** 工作区标签类型判别式，固定为 'plugin'。 */
  tabType: 'plugin'
  /** 插件标签种类，由插件通过扩展点注册。 */
  pluginTabType: string
  /** 提供该标签的插件 ID。 */
  pluginId: string
  /** 标签标题（展示用）。 */
  title: string
  /** 业务私有数据，由插件渲染器解释。 */
  data: Record<string, string>
}

/** 顶级文件差异对比标签（FileDiffPanel 升级为内置标签，只认展示参数，不感知来源）。 */
export interface WorkspaceDiffTab {
  /** 标签 ID，形如 `diff:${filePath}`。 */
  id: `diff:${string}`
  /** 标签类型常量。 */
  tabType: 'diff'
  /** 文件路径。 */
  filePath: string
  /** 差异所属的工作区根(「打开文件」定位用;缺省时回退注册表首项)。 */
  workspaceRoot?: string
  /** 文件名（用于标签显示）。 */
  fileName: string
  /** 标签标题。 */
  title: string
  /** 变更类型(含 deleted)。 */
  changeType: 'created' | 'updated' | 'deleted'
  /** 变更前内容。 */
  beforeContent: string
  /** 变更后内容。 */
  afterContent: string
  /** 是否为二进制文件(历史提交详情)。 */
  binary?: boolean
  /** 是否允许「恢复此版本」(仅 Git 历史提交详情)。 */
  allowRestore?: boolean
}


/** 内嵌终端标签：worker 侧 PTY 会话(termId 即标签内生成,打开时先 sub 频道再 term.open)。 */
export interface WorkspaceTerminalTab {
  /** 标签 ID，形如 `term:<uuid>`。 */
  id: `term:${string}`
  /** 标签类型常量。 */
  tabType: 'terminal'
  /** 所属工作区根(worker 机器绝对路径)。 */
  workspaceRoot: string
  /** 来源 worker(term.* RPC 定向目标)。 */
  workerId: string
  /** 终端工作目录(工作区相对路径,空串 = 工作区根)。 */
  path: string
  /** 目录名(标签显示用)。 */
  name: string
  /** 标签标题。 */
  title: string
}

/** 统一顶层工作区标签。 */
export type WorkspaceTab =
  | WorkspacePageTab
  | WorkspaceFileTab
  | WorkspaceLogTab
  | WorkspaceTaskChatTab
  | WorkspacePluginTab
  | WorkspaceDiffTab
  | WorkspaceTerminalTab

/** 打开工作区文件选项。 */
export interface OpenWorkspaceFileOptions {
  /** 是否立即进入文件名编辑态。 */
  startNameEditing?: boolean
  /** 打开模式；默认只读。 */
  mode?: FileTabOpenMode
  /** 打开后定位到的目标行号。 */
  lineNumber?: number
}

/** 上下文监控快照（真实用量：调用结束后由 provider 实测 usage 构造，无估算）。 */
export interface ContextMonitorSnapshot {
  /** 输入 token 数。 */
  promptTokens?: number
  /** 输出 token 数。 */
  completionTokens?: number
  /** 总 token 数。 */
  totalTokens?: number
  /** 上限 token。 */
  maxTokens: number
  /** 使用率。 */
  usageRatio: number
  /** 最近更新时间。 */
  lastUpdatedAt: number
  /** 请求类型。 */
  requestType: 'chat' | 'chatStream'
  /** 模型名。 */
  model: string
}

// ─── 工具类型 ──────────────────────────────────────────────────────────────────

/** 工具定义。 */
export interface Tool {
  /** 工具名。 */
  name: string
  /** 工具描述。 */
  description: string
  /** 工具分类。 */
  category: string
  /** 参数定义。 */
  parameters: ToolParameter[]
  /** 执行函数。 */
  execute: (params: Record<string, unknown>) => Promise<unknown>
}

/** 工具参数定义。 */
export interface ToolParameter {
  /** 参数名。 */
  name: string
  /** 参数类型。 */
  type: 'string' | 'number' | 'boolean' | 'array' | 'object'
  /** 参数描述。 */
  description: string
  /** 是否必填。 */
  required: boolean
}

/** 工具调用。 */
export type ToolCall = {
  /** 工具名。 */
  tool: string
  /** 参数。 */
  params: Record<string, unknown>
}

// ─── LLM 类型 ─────────────────────────────────────────────────────────────────

/** LLM 配置。 */
export interface LLMConfig {
  /** provider。 */
  provider: 'openai' | 'deepseek' | 'qwen' | 'kimi' | 'zhipu' | 'doubao' | 'agnes' | 'sensenova' | 'custom'
  /** API Key。 */
  apiKey: string
  /** Base URL。 */
  baseUrl?: string
  /** 模型名。 */
  model: string
  /** 推理强度。由 provider 自己解释具体取值。 */
  reasoningEffort?: string
  /** 是否通过 AI 代理转发请求。 */
  useProxy?: boolean
  /** 温度。 */
  temperature: number
  /** 上下文总量。 */
  contextWindowTokens?: number
  /** 最大 token。 */
  maxTokens: number
}

/** 单个可持久化 LLM 配置项。 */
export interface LLMConfigProfile extends LLMConfig {
  /** 配置 ID。 */
  id: string
  /** 配置名称。 */
  name: string
  /** 创建时间。 */
  createdAt: number
  /** 更新时间。 */
  updatedAt: number
}

/**
 * 运行护栏全局配置。
 * 控制单次运行的上限与触顶时的交互行为，全局生效，不随模型配置档切换。
 */
export interface GuardrailSettings {
  /** 单次运行内允许连续执行的工具调用轮数上限。 */
  maxToolRounds: number
  /** 单次运行内允许连续出现「完全相同工具调用」的最大轮数；超过则判定为死循环提前结束。 */
  maxRepeatedToolRounds: number
  /** 单个工具调用的最大执行时长（毫秒）；超过则判定超时并以错误结果回灌循环。 */
  toolExecutionTimeoutMs: number
  /** 空响应（既无正文也无工具调用）的最大重试次数；超过则判定为 empty_response_retry_exceeded。 */
  maxEmptyResponseRetries: number
  /** 可重试瞬时错误（限流 / 5xx / 网络抖动等）的最大退避重试次数。 */
  maxRequestRetries: number
  /** 退避算法策略：fixed = 固定间隔重试；exponential = 指数退避（base × factor^(n-1)）。 */
  retryStrategy: 'fixed' | 'exponential'
  /** 重试退避的基础间隔（毫秒）；fixed 策略下即每次重试的固定间隔。 */
  retryBackoffBaseMs: number
  /** 指数退避重试的增长系数（仅 exponential 策略生效）。 */
  retryBackoffFactor: number
  /** 超限弹窗的超时时间（毫秒）；到点未作答按「中止」处理。 */
  limitPromptTimeoutMs: number
}

/** 运行护栏全局配置默认值（全局生效，不随模型配置档切换）。 */
export const GUARDRAIL_SETTINGS_DEFAULTS: GuardrailSettings = {
  maxToolRounds: 30,
  maxRepeatedToolRounds: 3,
  toolExecutionTimeoutMs: 300000,
  maxEmptyResponseRetries: 2,
  maxRequestRetries: 30,
  retryStrategy: 'fixed',
  retryBackoffBaseMs: 3000,
  retryBackoffFactor: 5,
  limitPromptTimeoutMs: 3600000,
}

/** LLM 连接测试提示词配置。 */
export interface LLMConnectionTestPromptConfig {
  /** 连接测试系统提示词。 */
  systemPrompt?: string
  /** 连接测试用户提示词。 */
  userPrompt?: string
}

/** JSON Schema 基础类型名（跨厂商工具参数统一真相源）。 */
export type JsonSchemaTypeName =
  | 'string'
  | 'number'
  | 'integer'
  | 'boolean'
  | 'array'
  | 'object'
  | 'null'

/** JSON Schema 定义（采用运行时当前需要的公共子集）。 */
export interface JsonSchemaDefinition {
  /** 标题。 */
  title?: string
  /** 描述。 */
  description?: string
  /** JSON Schema 类型。 */
  type?: JsonSchemaTypeName | JsonSchemaTypeName[]
  /** 枚举值。 */
  enum?: unknown[]
  /** 常量值。 */
  const?: unknown
  /** 默认值。 */
  default?: unknown
  /** 字符串格式提示。 */
  format?: string
  /** 正则约束。 */
  pattern?: string
  /** 最小字符串长度。 */
  minLength?: number
  /** 最大字符串长度。 */
  maxLength?: number
  /** 最小数值。 */
  minimum?: number
  /** 最大数值。 */
  maximum?: number
  /** 最小数组长度。 */
  minItems?: number
  /** 最大数组长度。 */
  maxItems?: number
  /** 数组元素定义。 */
  items?: JsonSchemaDefinition
  /** 对象属性定义。 */
  properties?: Record<string, JsonSchemaDefinition>
  /** 对象必填字段。 */
  required?: string[]
  /** 是否允许额外属性，或额外属性的 schema。 */
  additionalProperties?: boolean | JsonSchemaDefinition
  /** 任一分支。 */
  anyOf?: JsonSchemaDefinition[]
  /** 单一分支。 */
  oneOf?: JsonSchemaDefinition[]
  /** 全部满足分支。 */
  allOf?: JsonSchemaDefinition[]
}

/** JSON Schema 对象定义。 */
export interface JsonSchemaObjectDefinition extends JsonSchemaDefinition {
  /** 顶层工具输入统一为 object。 */
  type: 'object'
  /** 对象属性定义。 */
  properties: Record<string, JsonSchemaDefinition>
  /** 对象必填字段。 */
  required?: string[]
  /** 是否允许额外属性，或额外属性的 schema。 */
  additionalProperties?: boolean | JsonSchemaDefinition
}

/** LLM 工具定义。 */
export interface LLMToolDefinition {
  /** 定义类型。 */
  type: 'function'
  /** 函数名。 */
  name: string
  /** 函数描述。 */
  description: string
  /** 厂商无关的输入 schema（标准 JSON Schema 对象定义）。 */
  inputSchema: JsonSchemaObjectDefinition
  /** 是否启用严格参数生成。由 provider 自己决定如何映射。 */
  strict?: boolean
}

/** LLM 工具调用。 */
export interface LLMToolCall {
  /** 调用 ID。 */
  id: string
  /** 类型。 */
  type: 'function'
  /** 函数信息。 */
  function: {
    /** 函数名。 */
    name: string
    /** 参数 JSON 文本。 */
    arguments: string
  }
}

/**
 * LLM 消息。
 *
 * `createdAt` 由 Agent 层统一写入（每条消息均带，作为本轮 prompt 按时间排序的真相源，
 * 见 ctx.messages 统一收口方案）；AI 层在把消息归一化为模型请求体时会剔除该字段
 * （与 `messageId` 同属内存态元数据，绝不进入模型上下文），故此处为可选。
 */
export type LLMMessage =
  | { role: 'system' | 'user'; content: string; createdAt?: number; messageId?: string; metadata?: Record<string, unknown> }
  | { role: 'assistant'; content: string; createdAt?: number; tool_calls?: LLMToolCall[]; messageId?: string; metadata?: Record<string, unknown> }
  | { role: 'tool'; content: string; tool_call_id: string; createdAt?: number; messageId?: string; metadata?: Record<string, unknown> }

/** LLM 对话响应。 */
export interface LLMChatResponse {
  /** 输出文本。 */
  content: string
  /** 工具调用。 */
  toolCalls: LLMToolCall[]
  /** 思考过程。 */
  reasoning?: string
  /** 用量。 */
  usage?: LLMUsage
}

/** LLM 用量。 */
export interface LLMUsage {
  /** 输入 token。 */
  prompt_tokens?: number
  /** 输出 token。 */
  completion_tokens?: number
  /** 总 token。 */
  total_tokens?: number
}

/** 流式事件。 */
export type LLMStreamEvent =
  | { type: 'content'; content: string }
  | { type: 'reasoning'; content: string }
  | { type: 'tool_calls'; toolCalls: LLMToolCall[] }
  | { type: 'usage'; usage: LLMUsage }
