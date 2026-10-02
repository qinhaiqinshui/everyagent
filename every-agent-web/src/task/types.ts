/**
 * Task 域 DTO 类型定义。
 *
 * 从 src/types/index.ts 收敛而来：task 业务相关的类型统一归属 task 域，
 * 通用类型（ChatComposerToken、TokenUsageStats、ContextMonitorSnapshot、TaskProtocolId 等）
 * 仍留在 @/types，本文件按需引用。
 */
import type {
  ChatComposerSubmission,
  ChatComposerToken,
  ContextMonitorSnapshot,
  TaskProtocolId,
  TokenUsageStats,
} from '@/types'

// ─── 任务运行状态类型 ─────────────────────────────────────────────────────────

/** 任务状态。 */
export type TaskStatus = 'idle' | 'running' | 'waiting-user' | 'completed' | 'stopped' | 'error'

/** 文件差异展示数据。 */
export interface TaskFileChange {
  /** 文件路径。 */
  filePath: string
  /** 变更类型(含 deleted:历史提交中的删除文件,after 为空)。 */
  changeType: 'created' | 'updated' | 'deleted'
  /** 变更前内容。 */
  beforeContent?: string
  /** 变更后内容。 */
  afterContent?: string
  /** 是否为二进制文件(历史提交详情;二进制不读全文、不可恢复)。 */
  binary?: boolean
  /** 是否允许「恢复此版本」(仅 Git 历史提交详情开启;SCM/任务 diff 不显示)。 */
  allowRestore?: boolean
}

// ─── 任务轮次索引类型（task.rounds / rounds.jsonl，plan-rounds-jsonl 步骤 5）─────

/**
 * 完整 user.message 事件 payload（rounds.jsonl 每轮 userMessage 项，懒加载骨架起点）。
 * 统一事件模型：worker 写 content（AI 可见明文）；rawContent 在 data.rawContent 中
 * （原始输入含 opaque token 串，仅用于前端回放还原胶囊），旧 worker / 纯文本输入缺失。
 * 预留宽松索引兼容未来扩展。
 */
export interface UserMessagePayload {
  /** AI 可见明文（统一事件模型：原 text → content）。 */
  content: string
  /** kind 专属数据（rawContent 等在此）。 */
  data?: { rawContent?: string; [key: string]: unknown }
  [key: string]: unknown
}

/**
 * 轮次内单个子 Agent 活动区间（rounds.jsonl 每轮 subs 项，与 worker wire 严格对齐）。
 * seq 一律字符串（64 位 Snowflake 超 JS 安全整数，同 task.poll wire 口径）。
 */
export interface RoundSubSummary {
  /** 子 Agent ID。 */
  agentId: string
  /** 子 Agent 标题。 */
  title: string
  /** 子 Agent 活动起点 seq（agent.started；空串 = 缺失）。 */
  startSeq: string
  /** 子 Agent 活动终点 seq（终态事件；空串 = 未闭合）。 */
  endSeq: string
}

/**
 * 单个任务轮次摘要（rounds.jsonl 每行 / task.rounds 应答 rounds 项）。
 * 一轮 = 主 Agent 一条 user.message 起点到其后第一条最终回复（无工具调用且有正文）终点。
 */
export interface RoundSummary {
  /** 轮次 ID（rounds.jsonl 每行 roundId；本方案不兼容旧数据，前端按必填声明）。 */
  roundId: string
  /** 轮次序号（从 1 起，升序）。 */
  index: number
  /** 轮起点 seq（主 Agent user.message 事件）。 */
  startSeq: string
  /** 轮终点 seq（最终回复事件；空串 = 未闭合，中断/失败/取消的尾轮）。 */
  endSeq: string
  /** 用户输入文本。 */
  user: string
  /** AI 最终回复正文（不含 thinking）。 */
  finalReply: string
  /**
   * 完整 user.message 事件 payload（rounds.jsonl userMessage 项；旧行缺失/为 null）。
   * 前端用它直接构造平铺线程骨架的 user 气泡（懒加载改造后不再单独补拉起点）。
   */
  userMessage?: UserMessagePayload
  /** 该轮内子 Agent 活动区间（恒为数组；无子 Agent 为空数组）。 */
  subs: RoundSubSummary[]
  /**
   * 本轮用户任务端到端耗时（毫秒；worker 开轮时落盘 startedAt、闭合时以当前时间减磁盘
   * startedAt 计算，随 rounds.jsonl 每行携带；旧行/未记录缺省视为 0）。0 表示无耗时数据，
   * 前端折叠时折叠图标左侧不显示。
   */
  durationMs?: number
  /**
   * 该轮文件变更摘要（轻量数组，仅 filePath/fileName/changeType/saveCount；rounds.jsonl 每行
   * 携带，无变更时字段缺失/undefined）。全文需通过 task.fileChanges 按 roundId 拉取。
   */
  fileChanges?: unknown[]
}

/**
 * 单个文件变更摘要（rounds.jsonl 每轮 fileChanges 项，轻量级）。
 * 全文内容不落 rounds.jsonl，需通过 task.fileChanges 按 roundId 拉取。
 */
export interface RoundFileChangeSummary {
  /** 文件路径。 */
  filePath: string
  /** 文件名。 */
  fileName: string
  /** 变更类型。 */
  changeType: 'created' | 'updated' | 'deleted'
  /** 保存次数。 */
  saveCount: number
}

/**
 * 单个文件变更全文项（task.fileChanges 应答 changes 项）。
 * 在轻量摘要基础上补充变更前/后全文内容。
 */
export interface TaskFileChangeFull extends RoundFileChangeSummary {
  /** 变更前内容。 */
  beforeContent: string
  /** 变更后内容。 */
  afterContent: string
}

/** task.fileChanges rpc.ok 应答（与 worker wire 严格对齐）。 */
export interface TaskFileChangesResult {
  /** 该轮全部文件变更全文项。 */
  changes: TaskFileChangeFull[]
}

/** 运行中任务的当前未闭合轮起点（task.rounds 应答 open；终态任务为 null，未闭合尾轮已落盘进 rounds）。 */
export interface TaskRoundsOpenRound {
  /** 未闭合轮起点 seq（主 Agent user.message 事件，前端自此拉到末尾做实时增量）。 */
  startSeq: string
  /** 未闭合轮的用户输入文本。 */
  user: string
  /** 未闭合轮起点的完整 user.message payload（懒加载骨架起点；旧 worker 缺失）。 */
  userMessage?: UserMessagePayload
}

/** task.rounds rpc.ok 应答（与 worker wire 严格对齐；seq 一律字符串）。 */
export interface TaskRoundsResult {
  /** 已闭合轮列表（rounds.jsonl 全量行，index 升序；旧任务首次调用由 worker 惰性全量生成）。 */
  rounds: RoundSummary[]
  /** 运行中任务的当前未闭合轮起点；终态任务为 null。 */
  open: TaskRoundsOpenRound | null
  /** 任务状态字串（与 task.poll 应答 task.status 同口径）。 */
  status: string
  /** 任务是否运行中（与 task.poll 应答 live 同口径）。 */
  live: boolean
}

/** 任务提交请求。 */
export interface TaskSubmitRequest {
  /** 目标任务 ID；为空时表示新建任务。 */
  taskId?: string
  /** 标准化提交体。 */
  submission: ChatComposerSubmission
}

/** 任务初始输入快照。 */
export interface TaskInitialInputSnapshot {
  /** 初始纯文本内容。 */
  text: string
  /** 初始原始输入内容。 */
  rawContent?: string
  /** 初始结构化 token 列表。 */
  tokens?: ChatComposerToken[]
  /** 初始模型配置 ID。 */
  llmConfigId?: string
  /** 初始思考强度。 */
  reasoningEffort?: string
  /** 初始协议 ID。 */
  protocolId?: TaskProtocolId
  /** 初始 skill 输入。 */
  skillInputs?: Record<string, unknown>
}

/** 任务级模型配置（含思考强度），作为 agent 取用与下一轮默认值的真相源。 */
export interface TaskModelConfig {
  /** 提供商标识（如 openai）。 */
  provider: string
  /** 模型标识。 */
  model: string
  /** 思考强度（provider 支持时）。 */
  reasoningEffort?: string
}

/** 顶层任务主记录。 */
export interface TaskRecord {
  /** 任务 ID。 */
  taskId: string
  /** 顶层任务状态。 */
  status: TaskStatus
  /** 当前任务采用的协议 ID。 */
  protocolId?: TaskProtocolId
  /** 当前任务已挂载的 skill ID 列表。 */
  attachedSkillIds: string[]
  /** 当前任务已挂载的 capability ID 列表。 */
  attachedCapabilityIds?: string[]
  /** 初始输入快照。 */
  initialInputSnapshot?: TaskInitialInputSnapshot
  /** 当前任务使用的模型配置（含思考强度）；agent 从此读取，用户切换时更新并记录 trace。 */
  modelConfig?: TaskModelConfig
  /** 最近一次用户输入。 */
  latestUserInput?: string
  /** 创建时间。 */
  createdAt: number
  /** 更新时间。 */
  updatedAt: number
  /** 首次启动时间。 */
  startedAt?: number
  /** 完成时间。 */
  completedAt?: number
  /** 停止时间。 */
  stoppedAt?: number
  /** 错误信息。 */
  error?: string
  /**
   * 通用元数据槽（核心只提供容器，不语义化任何 key）。
   * 供插件存放插件私有数据（如工作区插件存 `metadata.workspace`）；
   * 删除对应插件后该 key 不再被写入或读取，历史数据无害残留。
   */
  metadata?: Record<string, unknown>
}

/** 任务运行数据。 */
export interface TaskRuntimeData {
  /** 所属任务 ID。 */
  taskId: string
  /** 最终结果文本。 */
  result?: string
  /** 聚合 token 用量快照。 */
  tokenUsageSnapshot?: TokenUsageStats
  /** 聚合上下文监控快照。 */
  contextMonitorSnapshot?: ContextMonitorSnapshot | null
  /** 创建时间。 */
  createdAt: number
  /** 更新时间。 */
  updatedAt: number
}

/** 任务追踪类型。 */
export type TaskTraceKind = string

/** 任务追踪 JSON 对象。 */
export interface TaskTraceJsonObject {
  /** 任意键值。 */
  [key: string]: TaskTraceJsonValue
}

/** 任务追踪 JSON 值。 */
export type TaskTraceJsonValue =
  | string
  | number
  | boolean
  | null
  | TaskTraceJsonObject
  | TaskTraceJsonValue[]

/** 任务追踪内容。 */
export type TaskTraceContent = string | TaskTraceJsonValue | TaskTraceJsonValue[]

/** 任务追踪记录。 */
export interface TaskTraceRecord {
  /** 追踪 ID。 */
  traceId: string
  /** 所属任务 ID。 */
  taskId: string
  /** 所属会话 ID（trace 关联到的 agent，用于前端按 agent 过滤）。 */
  agentId?: string
  /** 追踪类型（必填）。 */
  kind: TaskTraceKind
  /** 追踪图标，收起态缺省时不展示。 */
  icon?: string
  /** 追踪标题。 */
  title?: string
  /** 追踪摘要，收起态使用；缺省时由展示层从 content 派生。 */
  summary?: string
  /** 追踪正文，可为字符串或结构化数据。缺省表示无展开正文（不可折叠/展开）。 */
  content?: TaskTraceContent
  /** 创建时间。 */
  createdAt?: number
  /** 附加元数据。 */
  metadata?: Record<string, unknown>
}

/** 任务 trace 持久化写入器。 */
export interface TaskTraceWriter {
  /** 追加一条任务 trace。 */
  append(taskId: string, trace: TaskTraceRecord): Promise<void>
  /** 更新一条既有任务 trace。 */
  update(taskId: string, traceId: string, patch: Partial<TaskTraceRecord>): Promise<void>
}

/** 协议生命周期状态。 */
export type TaskProtocolLifecycleStatus = 'running' | 'completed' | 'stopped' | 'error' | 'awaiting_confirmation'

/** 规划步骤状态。 */
export type TaskProtocolPlanStepStatus = 'pending' | 'running' | 'completed' | 'error'

/** 规划协议单个步骤。 */
export interface TaskProtocolPlanStep {
  /** 步骤 ID。 */
  stepId: string
  /** 步骤标题。 */
  title: string
  /** 步骤执行说明。 */
  instruction: string
  /** 当前步骤状态。 */
  status: TaskProtocolPlanStepStatus
  /** 负责执行该步骤的 Agent ID。 */
  agentId?: string
  /** 步骤执行结果。 */
  result?: string
  /** 步骤开始时间。 */
  startedAt?: number
  /** 步骤完成时间。 */
  completedAt?: number
  /** 步骤错误信息。 */
  error?: string
}

/** 规划协议历史轮次记录。 */
export interface TaskProtocolPlanHistoryEntry {
  /** 规划轮次序号。 */
  round: number
  /** 规划 Agent ID。 */
  plannerAgentId: string
  /** 本轮规划文本。 */
  planText: string
  /** 生成本轮规划时的提交文本。 */
  submissionText: string
  /** 本轮生成时间。 */
  createdAt: number
}

/** 任务协议结构化状态。 */
export interface TaskProtocolStateData {
  /** 当前协议生命周期状态。 */
  lifecycleStatus: TaskProtocolLifecycleStatus
  /** 规划协议当前轮次。 */
  roundCount?: number
  /** 当前正在执行的步骤 ID。 */
  currentStepId?: string
  /** 规划协议最近一次产出的规划文本。 */
  planText?: string
  /** 当前结构化步骤列表。 */
  steps?: TaskProtocolPlanStep[]
  /** 规划协议历史轮次。 */
  history?: TaskProtocolPlanHistoryEntry[]
  /** 最近一次规划 Agent ID。 */
  plannerAgentId?: string
  /** 最近一次执行 Agent ID。 */
  executorAgentId?: string
}

/** 任务协议状态记录。 */
export interface TaskProtocolStateRecord {
  /** 所属任务 ID。 */
  taskId: string
  /** 协议 ID。 */
  protocolId: TaskProtocolId
  /** 协议结构化状态。 */
  data: TaskProtocolStateData
  /** 创建时间。 */
  createdAt: number
  /** 更新时间。 */
  updatedAt: number
}

/** 打开 Task 聊天标签时的最小输入。 */
export interface TaskChatTabInput {
  /** 任务 ID。 */
  taskId: string
  /** 标签标题。 */
  title: string
  /** 可选：打开时定位到的会话 ID（复用同一任务标签时切换会话视图）。 */
  agentId?: string
}
