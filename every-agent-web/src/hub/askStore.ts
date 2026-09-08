/**
 * ask(用户交互请求)前端存储。
 *
 * n 分支的 userInteractionRuntime 是「请求侧」:浏览器内工具发起 ask、挂起 Promise。
 * 本前端是「应答侧」:worker 的 agent 发起 ask(ask.create/ask.state 事件),
 * 用户在 UserInteractionHost 卡片作答,答案经 taskInput 频道 ask.reply 回传。
 *
 * 保持 n 的函数签名(getPendingXxx、submitXxx、cancelXxx),UserInteractionHost 与
 * PendingUserInteractionIndicator 两个组件零改动。
 */
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import { notifyApp, removeAppNotification } from '@/utils/appNotifications'
import type {
  UserInteractionRequest,
  UserInteractionResult,
  UserInteractionOption,
} from '@/types'
import {
  USER_INTERACTION_OTHER_OPTION_ID,
} from '@/types'

interface PendingAskEntry {
  taskId: string
  /** worker 侧 agentId(主 agent 事件无 agentId,子 agent ask 必带)。 */
  agentId: string
  request: UserInteractionRequest
  notificationId: string
}

/** worker ask.create / ask.state 的 payload 形状(架构 §5.4)。 */
export interface WorkerAskPayload {
  askId: string
  kind?: string
  question?: string
  options?: string[]
  /** 多问题选择题(ask_user 工具主路径);存在时优先于 question/options 渲染。 */
  questions?: WorkerAskQuestion[]
  status?: string
  agentId?: string
}

/** worker ask 单题形状(含 worker 分配的稳定 id,供答案回传配对)。 */
export interface WorkerAskQuestion {
  id?: string
  prompt?: string
  options?: string[]
}

const pendingAsks = new Map<string, PendingAskEntry>()
/** 已落定的 askId(本地作答或 worker 广播 resolved/timeout):迟到的 state 重发不再复活卡片。 */
const settledAskIds = new Set<string>()

/**
 * 用户作答草稿(实时缓存)。
 *
 * 用户在卡片上边填边把草稿写入这里(key = askId),「稍后再答」或窗口隐藏只收起弹层、
 * 不销毁草稿;重新打开同一 ask 时恢复,直到真正落定(submit/settle)才清除。
 */
export interface UserInteractionDraft {
  /** 每题选中的选项 ID(questionId → optionIds)。 */
  selections: Record<string, string[]>
  /** 每题「其他」输入框内容(questionId → text)。 */
  otherTexts: Record<string, string>
  /** 当前展示的问题下标(多问题逐题展示时恢复)。 */
  currentQuestionIndex: number
}

const askDrafts = new Map<string, UserInteractionDraft>()

/** 读取指定 ask 的作答草稿(不存在返回 null)。 */
export function getAskDraft(askId: string): UserInteractionDraft | null {
  return askDrafts.get(askId) ?? null
}

/** 实时写入指定 ask 的作答草稿(幂等,可反复覆盖)。 */
export function saveAskDraft(askId: string, draft: UserInteractionDraft): void {
  askDrafts.set(askId, draft)
}

function clearAskDraft(askId: string): void {
  askDrafts.delete(askId)
}

type ReplySender = (taskId: string, askId: string, answer: string) => void
let replySender: ReplySender | null = null

/** 由任务流层注册:实际把答案发到任务 input 频道(ask.reply)。 */
export function registerAskReplySender(sender: ReplySender | null): void {
  replySender = sender
}

/** 给选项列表追加固定的「其他」兜底选项(不可关闭,选中后自由输入)。 */
function withOtherOption(options: string[]): UserInteractionOption[] {
  const opts = options.map((label) => ({ id: label, label }))
  opts.push({ id: USER_INTERACTION_OTHER_OPTION_ID, label: '其他' })
  return opts
}

function toInteractionRequest(payload: WorkerAskPayload): UserInteractionRequest {
  const question = payload.question ?? ''
  const options = payload.options ?? []
  // 危险操作授权(worker PermissionGate):三选一(run/task/deny),不追加「其他」,
  // 答案以稳定 token 回传(worker 端宽容解析,兼容文案)。
  if (payload.kind === 'authorization') {
    const fullPrompt = payload.questions?.[0]?.prompt ?? question
    const firstLine = fullPrompt.split('\n')[0] || 'AI 请求授权'
    const workerOptions = payload.questions?.[0]?.options ?? options
    const authOptions = workerOptions.length > 0
      ? workerOptions.map((label) => ({
        id: label.includes('本任务') ? 'task' : label.includes('本轮') ? 'run' : 'deny',
        label,
      }))
      : [
        { id: 'run', label: '本轮运行内允许' },
        { id: 'task', label: '本任务全程允许' },
        { id: 'deny', label: '拒绝' },
      ]
    return {
      id: payload.askId,
      prompt: firstLine,
      details: fullPrompt,
      responseMode: 'authorization',
      options: authOptions,
      createdAt: Date.now(),
    }
  }
  // 多问题选择题(ask_user 主路径):每题自动追加「其他」选项。
  if (payload.questions && payload.questions.length > 0) {
    return {
      id: payload.askId,
      prompt: question || (payload.questions.length === 1 ? payload.questions[0].prompt ?? '' : ''),
      questions: payload.questions.map((q, idx) => ({
        id: q.id ?? `${payload.askId}_${idx}`,
        prompt: q.prompt ?? '',
        options: withOtherOption(q.options ?? []),
      })),
      createdAt: Date.now(),
    }
  }
  // 单问题选择题:自动追加「其他」选项(ask_user 单问题交互等价形态)。
  if (options.length > 0) {
    return {
      id: payload.askId,
      prompt: question,
      responseMode: 'single_choice',
      options: withOtherOption(options),
      createdAt: Date.now(),
    }
  }
  // 自由文本:走多问题模式的「其他」输入(无选项时仅一个「其他」)。
  return {
    id: payload.askId,
    prompt: question,
    questions: [{
      id: payload.askId,
      prompt: question,
      options: [{ id: USER_INTERACTION_OTHER_OPTION_ID, label: '其他' }],
    }],
    createdAt: Date.now(),
  }
}

/**
 * 把交互结果折成 ask.reply 的 answer 字符串(写回工具结果/模型上下文)。
 * 逐题输出「题干：答案」,答案含选中选项文案,选中「其他」时取自由输入文本。
 */
function toAnswerText(
  result: Omit<UserInteractionResult, 'interactionId' | 'submittedAt'>,
  request: UserInteractionRequest,
): string {
  // 授权:直接回传稳定 token(run/task/deny),worker 端精确匹配
  if (request.responseMode === 'authorization') {
    return result.selectedOptionIds?.[0] ?? 'deny'
  }
  if (typeof result.confirmed === 'boolean') {
    return result.confirmed ? 'yes' : 'no'
  }
  if (result.answers && result.answers.length > 0) {
    const promptById = new Map<string, string>()
    for (const q of request.questions ?? []) {
      promptById.set(q.id, q.prompt)
    }
    return result.answers.map((answer) => {
      const prompt = promptById.get(answer.questionId) ?? answer.prompt ?? ''
      const value = answer.otherSelected
        ? (answer.otherText ?? '')
        : answer.selectedOptions.map((option) => option.label).join('、')
      return `${prompt}：${value}`
    }).filter((line) => {
      const sep = line.indexOf('：')
      return sep >= 0 && line.substring(sep + 1).trim().length > 0
    }).join('\n')
  }
  if (result.selectedOptionIds && result.selectedOptionIds.length > 0) {
    const prompt = request.prompt
    const labels = request.options
      ?.filter((o) => result.selectedOptionIds!.includes(o.id))
      .map((o) => o.label)
      .join('、') ?? result.selectedOptionIds.join('、')
    return `${prompt}：${labels}`
  }
  return ''
}

function notifyAsk(entry: PendingAskEntry): void {
  removeAppNotification(entry.notificationId)
  notifyApp({
    id: entry.notificationId,
    title: entry.request.responseMode === 'authorization' ? 'AI 请求授权' : 'AI 等待你的回答',
    message: entry.request.prompt,
    tone: 'warning',
    onClick: () => {
      domainEventBus.emit(DOMAIN_EVENTS.WORKSPACE_OPEN_USER_INTERACTION_REQUESTED, {
        interactionId: entry.request.id,
      })
    },
  })
}

/**
 * 任务流事件入口:ask.create / ask.state(pending)。幂等,重放安全。
 * @param opts.silent 历史回放(DataPusher 磁盘全量推送,ext.initial=true)模式:只登记
 *  pendingAsks(「待回答」入口可见),不弹系统通知、不触发卡片弹窗。历史 ask 未必仍有效
 *  (worker 重启后 PendingAsks 已清空),自动弹窗会误报「当前交互请求已结束」;
 *  只有实时 ask.create/ask.state 走完整弹出路径。
 */
export function upsertPendingAsk(
  taskId: string,
  payload: WorkerAskPayload,
  opts?: { silent?: boolean },
): void {
  const silent = Boolean(opts?.silent)
  if (!payload.askId) return
  if (settledAskIds.has(payload.askId)) return
  if (pendingAsks.has(payload.askId)) {
    // ask.state 周期重发:仅刷新通知,不重建卡片。
    const existing = pendingAsks.get(payload.askId)!
    if (!silent) notifyAsk(existing)
    return
  }
  const request = toInteractionRequest(payload)
  const entry: PendingAskEntry = {
    taskId,
    agentId: payload.agentId ?? '',
    request,
    notificationId: `ask-${payload.askId}`,
  }
  pendingAsks.set(payload.askId, entry)
  if (!silent) {
    notifyAsk(entry)
    domainEventBus.emit(DOMAIN_EVENTS.USER_INTERACTION_REQUESTED, {
      taskId,
      agentId: entry.agentId,
      request: { ...request },
    })
  }
}

/** ask.resolved / ask.state(timeout) → 移除卡片。 */
export function settleAsk(askId: string): void {
  if (!askId) return
  settledAskIds.add(askId)
  // 真正落定(提交/超时/取消)时清掉作答草稿,避免旧草稿复活。
  clearAskDraft(askId)
  const entry = pendingAsks.get(askId)
  if (!entry) return
  pendingAsks.delete(askId)
  removeAppNotification(entry.notificationId)
  domainEventBus.emit(DOMAIN_EVENTS.USER_INTERACTION_CLEARED, {
    taskId: entry.taskId,
    agentId: entry.agentId,
    interactionId: askId,
  })
}

/** 任务终态时清掉该任务残留的挂起 ask(任务结束,问答无意义)。 */
export function clearAsksOfTask(taskId: string): void {
  for (const askId of Array.from(pendingAsks.keys())) {
    const entry = pendingAsks.get(askId)!
    if (entry.taskId === taskId) {
      settleAsk(askId)
    }
  }
}

// ---- n 组件消费的既有 API(签名不变) ----

export function getPendingRuntimeUserInteraction(interactionId: string): UserInteractionRequest | null {
  return pendingAsks.get(interactionId)?.request ?? null
}

export function getPendingRuntimeUserInteractions(): UserInteractionRequest[] {
  return Array.from(pendingAsks.values())
    .map((entry) => entry.request)
    .sort((left, right) => left.createdAt - right.createdAt)
}

export function submitRuntimeUserInteractionResult(
  interactionId: string,
  result: Omit<UserInteractionResult, 'interactionId' | 'submittedAt'>,
): {
  taskId: string
  agentId: string
  request: UserInteractionRequest
  result: UserInteractionResult
} {
  const entry = pendingAsks.get(interactionId)
  if (!entry) {
    throw new Error('当前交互请求不存在或已结束')
  }
  const resolvedResult: UserInteractionResult = {
    ...result,
    interactionId,
    submittedAt: Date.now(),
  }
  settleAsk(interactionId)
  replySender?.(entry.taskId, interactionId, toAnswerText(result, entry.request))
  domainEventBus.emit(DOMAIN_EVENTS.USER_INTERACTION_RESOLVED, {
    taskId: entry.taskId,
    agentId: entry.agentId,
    result: resolvedResult,
  })
  return {
    taskId: entry.taskId,
    agentId: entry.agentId,
    request: entry.request,
    result: resolvedResult,
  }
}

export function cancelRuntimeUserInteraction(interactionId: string, _reason = '用户交互已取消'): void {
  settleAsk(interactionId)
}
