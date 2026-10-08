/**
 * 任务聊天页(hub 版,布局/样式照搬 n 分支同位置组件)。
 *
 * 视觉结构与 n 版一致:ChatShell(线程滚动区 + composer dock)+ TaskComposerSurface
 * 富输入框 + task-composer-footer(上下文电池 + 模型选择 + 发送/停止)。
 *
 * 消息区 = TaskRoundsPanel 连续线程视图:轮次结构(user/final/折叠标记/耗时)来自
 * taskStream 的 rounds 快照;每轮过程内容由 RoundDetail 按 startSeq/endSeq 从单一
 * items 切片渲染(懒加载:展开/尾轮滚动到占位元素才续拉)。worker 定向推送的流式事件与
 * 懒加载折入的事件共用同一份 items 并按 seq 去重排序。
 * taskStream 同时承担实时信号链:ask 卡片(全局 UserInteractionHost)、
 * agent 列表状态(agentStates)、上下文电池(usage)、taskModel、输入入队
 * (cancel)与重连校准。
 * 输入 = 纯文本(task.run 新建 / 运行中 task.input 入队 / 终态 task.run 续跑)。
 *
 * 草稿态:taskId 为 DRAFT_TASK_ID 时渲染 TaskDraftComposerPanel(与 n 版
 * 启动台草稿面板同构),首次发送经 task.run 建任务后,把当前草稿标签
 * 替换为真实任务标签。
 */
import React from 'react'
import type { ChatComposerDraftState, ChatComposerToken, LLMConfigProfile } from '@/types'
import ChatShell from './ChatShell'
import TaskRoundsPanel from './TaskRoundsPanel'
import ContextBattery from './ContextBattery'
import AgentListPanel, { type AgentListItem } from './AgentListPanel'
import TaskDraftComposerPanel from '@/components/taskComposer/TaskDraftComposerPanel'
import TaskComposerSurface, { applyComposerDraftChange, type ComposerDraftChange } from '@/components/taskComposer/TaskComposerSurface'
import TaskModelControls from '@/components/taskComposer/TaskModelControls'
import HScrollArea from '@/components/shared/HScrollArea'
import { ArrowDownIcon, ArrowRightIcon, StopIcon } from '../shared/AppGlyphs'
import { Button, InlineSpinner } from '@/components/shared/ui'
import { pluginDispatcher } from '@/plugin/PluginDispatcher'
import type { TaskRunSubmitContribution, TaskRunSubmitContributionProvider, ComposerPanelCtx } from '@/plugin/types'
import { ComposerDraftBridgeContext, type ComposerDraftBridgeValue } from '@/plugin/composerDraftBridge'
import { setComposerBridge } from '@/plugin/pluginRuntimeBridge'
import { useWorkspaceShell } from '@/components/app/WorkspaceShellContext'
import { useResponsiveViewport } from '@/hooks/useResponsiveViewport'
import { parseOpaqueTokenText, replaceComposerTokensForSubmission } from '@/composerToken/composerOpaqueToken'
import { parseTaskScopeTokens, upsertTaskToken, removeTaskToken, applyTaskToken, cancelTaskToken, extractSlashId, listTaskTokens } from '@/slash/taskScopedTokens'
import { slashCommandRegistry } from '@/slash/slashCommandRegistry'
import { createSnowflakeId } from '@/utils/snowflakeId'
import { taskQueryService } from '@/query/taskQueryService'
import { isTaskActive } from '@/task/taskStatusPresentation'
import type { AgentMetaSnapshot, TaskThreadItem } from '@/task/eventFolder'
import { taskStore } from '@/task/taskStore'
import { taskStreamManager, type TaskStreamHandle } from '@/task/taskStream'
import { workspaceRegistry, type WorkspaceEntry } from '@/hub/workspaceRegistry'
import { modelConfigs, type ModelConfigInfo } from '@/hub/modelConfigs'
import { useHub } from '@/hub/HubProvider'
import type { WorkerInfo } from '@/hub/session'
import { hubSession } from '@/hub/session'
import { DRAFT_TASK_ID, getDraftPreset, subscribeDraftPreset } from './taskChatDraft'
import '@/components/task/chatPanel.css'

/** 草稿任务 ID 再导出(定义见 ./taskChatDraft,常量消费方从那里导入)。 */
export { DRAFT_TASK_ID }

/**
 * 距容器底部多少像素内视为「已到底部」：
 * 滚回该范围即恢复自动跟随(userControll 置 false)，并隐藏「滚动到底部」按钮。
 */
const THREAD_SCROLL_TO_BOTTOM_THRESHOLD = 24

/**
 * Task 聊天页属性。
 */
export interface TaskChatProps {
  /** 当前打开的 taskId。 */
  taskId: string
  /** 可选：外部透传的会话 ID（复用同一任务标签时切换会话视图）。 */
  agentId?: string
  /** 该任务在当前主区是否处于聚焦显示状态。进入任务（从任务列表点开 / 切回已打开的任务）时自动滚动到底部。 */
  isActive?: boolean
}

/**
 * Task 聊天页(hub 版)。
 * 消息区 = TaskRoundsPanel 连续线程视图(rounds 快照 + RoundDetail 从单一 items 按轮切片);
 * taskStream 同时保留实时信号链(agentStates/usage/ask/输入/停止),
 * 任务状态/标题 = taskStore 镜像;界面更新依赖订阅推送。
 */
export default function TaskChat({ taskId, agentId, isActive = false }: TaskChatProps) {
  const effectiveTaskId = taskId.trim()
  if (!effectiveTaskId) {
    throw new Error('Task 聊天页缺少有效 taskId')
  }
  const isDraft = effectiveTaskId === DRAFT_TASK_ID
  const shell = useWorkspaceShell()
  const { isMobile } = useResponsiveViewport()
  const hub = useHub()

  /**
   * 用户滚动控制标识（本次改造的核心单一语义）：
   * - false（默认）：只要线程内容长高（轮次内容新增/未闭合轮流式输出，经
   *   ResizeObserver 感知）就自动贴底；
   * - true：用户在自行浏览（wheel / touchstart / pointerdown 触发），即使内容长高也不滚动；
   * - 用户滚回底部（距底 <= THREAD_SCROLL_TO_BOTTOM_THRESHOLD）→ 置回 false 恢复自动跟随。
   *
   * 程序化 scrollTo 绝不会把它置 true：scroll 事件只做「到底部 → false」，
   * 置 true 只来自用户意图事件——这正是旧实现用 armUserScrollIntent 的原因，
   * 让 scrollTo 自我关停（旧 userScrollIntent 时间窗机制）不再需要。
   */
  const userControllRef = React.useRef(false)
  // 用回调 ref 持有滚动容器节点：保证容器真正挂载后才绑定 scroll 监听。
  const threadScrollRefNode = React.useRef<HTMLDivElement | null>(null)
  // 观察线程内容容器尺寸变化：轮次卡片新增、未闭合轮流式输出、轮详情展开等都会
  // 异步撑高内容。用 ResizeObserver 跟随内容真实尺寸,只要自动跟随开启
  // (userControll=false)就立即贴底。另配 MutationObserver 捕获流式文本逐字符增长
  // 等「尺寸未变但 DOM 已变」的场景,双保险确保流式输出时刻滚动到位。
  const threadContentResizeObserverRef = React.useRef<ResizeObserver | null>(null)
  const threadContentMutationObserverRef = React.useRef<MutationObserver | null>(null)
  /**
   * 以输入框为锚点贴底：输入框（composer）固定在滚动容器下方,因此只要把滚动容器
   * 滚到最底（scrollTop = scrollHeight），最新内容就恰好落在输入框上方。
   * 仅在用户未手动接管滚动（userControll=false）时执行。
   */
  const scrollToBottomIfFollowing = React.useCallback(() => {
    const node = threadScrollRefNode.current
    if (!node || userControllRef.current) {
      return
    }
    node.scrollTop = node.scrollHeight
  }, [])
  // 「滚动到底部」按钮是否可见：用户远离底部时显示，回到底部自动隐藏。
  const [showScrollToBottom, setShowScrollToBottom] = React.useState(false)
  /** 用户手动滚动意图（wheel / touchstart / pointerdown）：立即接管滚动，暂停自动跟随。 */
  const armUserScrollIntent = React.useCallback(() => {
    userControllRef.current = true
  }, [])
  const handleThreadScroll = React.useCallback(() => {
    const node = threadScrollRefNode.current
    if (!node) {
      return
    }
    const distanceFromBottom = getDistanceFromBottom(node)
    // 「滚动到底部」按钮显隐：独立于 userControll，远离底部即显示、回到底部即隐藏。
    //（相同布尔值 setState 时 React 会 bail out，不产生多余重渲染。）
    setShowScrollToBottom(distanceFromBottom > THREAD_SCROLL_TO_BOTTOM_THRESHOLD)
    // userControll 自动跟随：滚回底部 → 恢复自动跟随。
    // 这里只做「到底部 → false」，绝不因 scroll 事件置 true ——
    // 置 true 只发生在用户意图（wheel/touchstart/pointerdown）上，程序化 scrollTo 不会把跟随误关。
    if (distanceFromBottom <= THREAD_SCROLL_TO_BOTTOM_THRESHOLD) {
      userControllRef.current = false
    }
  }, [])
  const threadScrollRef = React.useCallback((node: HTMLDivElement | null) => {
    const prev = threadScrollRefNode.current
    if (node === prev) {
      return
    }
    if (prev) {
      prev.removeEventListener('scroll', handleThreadScroll)
      prev.removeEventListener('wheel', armUserScrollIntent)
      prev.removeEventListener('touchstart', armUserScrollIntent)
      prev.removeEventListener('pointerdown', armUserScrollIntent)
      if (threadContentResizeObserverRef.current) {
        threadContentResizeObserverRef.current.disconnect()
        threadContentResizeObserverRef.current = null
      }
      if (threadContentMutationObserverRef.current) {
        threadContentMutationObserverRef.current.disconnect()
        threadContentMutationObserverRef.current = null
      }
    }
    threadScrollRefNode.current = node
    if (node) {
      node.addEventListener('wheel', armUserScrollIntent, { passive: true })
      node.addEventListener('touchstart', armUserScrollIntent, { passive: true })
      node.addEventListener('pointerdown', armUserScrollIntent, { passive: true })
      node.addEventListener('scroll', handleThreadScroll, { passive: true })
      // 首屏滚到底部交给下方 isActive 布局 effect。
      // 观察线程内容容器（滚动容器的唯一子节点 .nagent-thread）的尺寸变化。
      // 该子节点高度随轮次内容新增、未闭合轮流式输出、轮过程展开等异步变化，
      // 每次变高都在自动跟随开启时立即贴底。
      const content = node.firstElementChild as HTMLElement | null
      if (content && typeof ResizeObserver !== 'undefined') {
        const observer = new ResizeObserver(() => {
          scrollToBottomIfFollowing()
        })
        observer.observe(content)
        threadContentResizeObserverRef.current = observer
      }
      // 流式输出时 assistant 正文/思考链逐字符增长，某些节点（如 Markdown 代码块
      // 或富文本）尺寸变化可能滞后，但 DOM 一定在变。MutationObserver 保证任何
      // 内容推进都触发一次贴底,彻底解决「流式输出不滚动」问题。
      if (typeof MutationObserver !== 'undefined') {
        const mutationObserver = new MutationObserver(() => {
          scrollToBottomIfFollowing()
        })
        mutationObserver.observe(node, {
          childList: true,
          characterData: true,
          subtree: true,
        })
        threadContentMutationObserverRef.current = mutationObserver
      }
    }
  }, [armUserScrollIntent, handleThreadScroll, scrollToBottomIfFollowing])
  /** 点击「滚动到底部」：回到最新消息并恢复自动跟随（userControll=false，后续流式继续贴底）。 */
  const handleScrollToBottom = React.useCallback(() => {
    const node = threadScrollRefNode.current
    if (!node) {
      return
    }
    node.scrollTop = node.scrollHeight
    userControllRef.current = false
    setShowScrollToBottom(false)
  }, [])
  React.useEffect(() => {
    return () => {
      if (threadContentResizeObserverRef.current) {
        threadContentResizeObserverRef.current.disconnect()
        threadContentResizeObserverRef.current = null
      }
      if (threadContentMutationObserverRef.current) {
        threadContentMutationObserverRef.current.disconnect()
        threadContentMutationObserverRef.current = null
      }
    }
  }, [])

  const [draft, setDraft] = React.useState<ChatComposerDraftState>({
    text: '',
    rawContent: '',
    tokens: [],
  })
  // 任务级底部 token（胶囊）：草稿态本地持有；真实任务从 slash.taskTokens.list
  // 拉取 + apply/cancel RPC 返回值 + slash.tokens.changed stream 事件更新。
  const [scopeTokens, setScopeTokens] = React.useState<ChatComposerToken[]>([])
  // 队列面板「编辑」的回填不走这里:插件经自己的 ctx.ui.appendComposerText /
  // setComposerRawContent 回填(宿主已实现该 ui 契约,与 task-edit-resend 同一条路)。
  // 曾在此留一个 handleEditQueuedDraft 纯文本回调,但从未接线到 ComposerPanelCtx——
  // 注释让人误以为宿主已代劳,面板于是只删不回填;死代码已随该 bug 修复移除。
  const [submitting, setSubmitting] = React.useState(false)
  const [stopping, setStopping] = React.useState(false)
  const [error, setError] = React.useState('')
  // agent 选中态(点击输入框上方 agent 长条切换):非空时轮次视图按该 agent 过滤
  // (仅过滤已加载内容,不触发拉取,见 TaskRoundsPanel matches 谓词)。
  const [filterAgentId, setFilterAgentId] = React.useState('')

  // 插件提交贡献（如编辑重发的 editSeq metadata）：订阅各 provider 的变化通知，
  // 贡献变化（进入/退出编辑模式等）即重渲染以刷新提交按钮文案/样式。
  const [submitContributionVersion, setSubmitContributionVersion] = React.useState(0)
  React.useEffect(() => {
    const providers = pluginDispatcher.listRegisteredTaskRunSubmitContributionProviders()
    const unsubs = providers.map((provider) => provider.subscribe(() => setSubmitContributionVersion((v) => v + 1)))
    return () => {
      for (const unsub of unsubs) unsub()
    }
  }, [])
  // 提交按钮展示覆盖：取首个声明了 submitLabel/submitDanger 的贡献（核心不感知业务语义）。
  const submitOverride = React.useMemo(() => {
    if (isDraft) return null
    void submitContributionVersion
    for (const provider of pluginDispatcher.listRegisteredTaskRunSubmitContributionProviders()) {
      const contribution = provider.getContribution(effectiveTaskId)
      if (contribution && (contribution.submitLabel || contribution.submitDanger)) {
        return contribution
      }
    }
    return null
  }, [isDraft, effectiveTaskId, submitContributionVersion])

  // 实时信号链:订阅 taskStream(agentStates/contextUsage/taskModel/ask 等状态信号
  // 折叠推进即重渲染)。items 不再驱动线程渲染(旧首拉渲染路径已删除),只用于
  // 派生 agent 列表(子 agent 首次出现顺序与标题)。
  const [stream, items] = useTaskStream(isDraft ? null : effectiveTaskId)
  // 任务状态/标题：订阅 taskStore 镜像。
  const entry = useTaskEntry(isDraft ? null : effectiveTaskId)

  // 多 worker 定向:草稿态由用户显式选择 worker;老任务按任务归属 worker 定向。
  // activeWorkerId 决定模型列表/工作区等数据源;ownerWorkerId 决定老任务 slash/续跑定向。
  // 「在此工作区新建任务」会预置所属 worker,草稿挂载时直接读取预设。
  const [draftWorkerId, setDraftWorkerId] = React.useState(getDraftPreset().workerId)
  const activeWorkerId = isDraft ? draftWorkerId : (entry?.workerId ?? '')
  const ownerWorkerId = !isDraft ? (entry?.workerId ?? taskStore.get(effectiveTaskId)?.workerId ?? '') : ''

  // 任务级底部 token 回显：草稿态清空（草稿只本地持有）；真实任务从
  // slash.taskTokens.list 拉取初始值，apply/cancel RPC 返回值更新，
  // slash.tokens.changed stream 事件实时同步。
  React.useEffect(() => {
    if (isDraft) {
      setScopeTokens([])
      return
    }
    // 优先用 stream 推送的 slashTokens（slash.tokens.changed 事件）；
    // stream 尚未推送时从 listTaskTokens RPC 拉取初始值。
    if (stream?.state.slashTokens) {
      setScopeTokens(parseTaskScopeTokens(stream.state.slashTokens))
      return
    }
    let cancelled = false
    void listTaskTokens(effectiveTaskId).then((tokens) => {
      if (cancelled) return
      // stream 推送可能在此期间到达并已更新，避免覆盖
      if (!stream?.state.slashTokens) {
        setScopeTokens(parseTaskScopeTokens(tokens))
      }
    }).catch((error) => {
      console.warn('[slash] listTaskTokens 失败', error)
    })
    return () => { cancelled = true }
  }, [isDraft, effectiveTaskId, stream?.state.slashTokens])

  /**
   * 新建任务时默认选中的 `/` 项（defaultSelected=true）：草稿进入 / 切换 worker 时
   * 按 slash 候选的该字段预置任务级底部 token（bottom 结果进 scopeTokens，用户仍可 ✕ 取消）。
   * 走与用户点击相同的 item.select(item)（草稿态无 taskId → 不触发业务 onSelect 置位），
   * 提交时随 task.run 建后逐个 apply（slash 层自管存储）。
   */
  React.useEffect(() => {
    if (!isDraft || !draftWorkerId) {
      return
    }
    let cancelled = false
    void (async () => {
      try {
        const items = await slashCommandRegistry.list(draftWorkerId)
        if (cancelled) {
          return
        }
        for (const item of items.filter((it) => it.defaultSelected === true)) {
          const raw = await item.select(item)
          if (cancelled) {
            return
          }
          const results = Array.isArray(raw) ? raw : [raw]
          for (const result of results) {
            if (result.position !== 'bottom') {
              continue
            }
            const scopeToken = buildScopeToken(result.token, result.id ?? item.id)
            if (scopeToken) {
              setScopeTokens((cur) => upsertTaskToken(cur, scopeToken))
            }
          }
        }
      } catch (error) {
        console.warn('[slash] 默认选中项预置失败', error)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [isDraft, draftWorkerId])

  /**
   * 选中 bottom 项 → 底部胶囊（乐观） + applyTaskToken 落盘（草稿仅本地持有）。
   * 返回 applyTaskToken 的 Promise（catch 后不 reject,只 setError）：TaskComposerSurface
   * 对联动多胶囊（无人值守 + AI 审议）会逐个 await——先落盘前者再 apply 后者,消除并行
   * RPC 的 meta 写竞态（worker 端 SlashTaskScopeStore 亦按任务串行化,双保险）。
   */
  const handleAddTaskScopeToken = React.useCallback(({ id, token }: { id: string; token: string }) => {
    const scopeToken = buildScopeToken(token, id)
    if (!scopeToken) {
      return
    }
    if (isDraft) {
      setScopeTokens((cur) => upsertTaskToken(cur, scopeToken))
      return
    }
    setScopeTokens((cur) => upsertTaskToken(cur, scopeToken))
    return applyTaskToken({ taskId: effectiveTaskId, id, token }).then((tokens) => {
      // RPC 返回最新 token 列表，直接同步（覆盖乐观更新）
      setScopeTokens(parseTaskScopeTokens(tokens))
    }).catch((applyError) => {
      setError(applyError instanceof Error ? applyError.message : '应用任务级 token 失败')
    })
  }, [isDraft, effectiveTaskId])

  /** 底部 ✕ → 移除胶囊（乐观）+ cancelTaskToken；草稿不传 taskId（仅业务 onCancel）。 */
  const handleRemoveTaskScopeToken = React.useCallback(({ id, token }: { id?: string; token: string }) => {
    setScopeTokens((cur) => removeTaskToken(cur, { id, opaque: token }))
    if (isDraft) {
      void cancelTaskToken({ id, token })
      return
    }
    void cancelTaskToken({ id, token, taskId: effectiveTaskId }).then(({ tokens }) => {
      // RPC 返回最新 token 列表，直接同步（覆盖乐观更新）
      setScopeTokens(parseTaskScopeTokens(tokens))
    })
  }, [isDraft, effectiveTaskId])

  // 模型配置列表(worker config.get 校准 + config.changed 感知;apiKey 恒为掩码)。
  // 多 worker:按 activeWorkerId 定向订阅(草稿=所选 worker,老任务=任务归属 worker)。
  const [modelsVersion, setModelsVersion] = React.useState(0)
  React.useEffect(() => {
    if (!draftWorkerId) return
    return modelConfigs.subscribeFor(draftWorkerId, () => setModelsVersion((v) => v + 1))
  }, [draftWorkerId])
  React.useEffect(() => {
    if (!ownerWorkerId) return
    return modelConfigs.subscribeFor(ownerWorkerId, () => setModelsVersion((v) => v + 1))
  }, [ownerWorkerId])
  void modelsVersion
  const llmProfiles = React.useMemo(
    () => modelConfigs.currentFor(activeWorkerId).map(toModelProfile),
    [modelsVersion, activeWorkerId],
  )
  const selectedLlmConfigId = isDraft ? modelConfigs.selectedConfigIdFor(draftWorkerId) : ''

  const handleSelectLlmConfig = React.useCallback((configId: string) => {
    modelConfigs.selectConfig(configId)
  }, [])

  // 已创建(旧)任务:模型下拉直接取后端下发的「全部可用模型」(modelConfigs.current,
  // 源自 worker config.get),不再用 task 持久化的单一冻结模型当列表——这样旧任务也能
  // 切到其他模型。选中项默认跟随任务冻结的 configId(只要该模型仍在可用列表内);
  // 用户一旦在下拉里切换,即以所选模型用于下一轮续跑。
  const taskModel = stream?.state.taskModel ?? null
  const frozenConfigId = entry?.configId || taskModel?.configId || ''

  // 用户显式切换后的模型;为空代表沿用任务冻结模型。切换任务时清空(回归跟随冻结模型)。
  const [taskModelOverride, setTaskModelOverride] = React.useState('')
  React.useEffect(() => {
    setTaskModelOverride('')
  }, [effectiveTaskId])

  // 下拉实际选中项:用户切换 > 任务冻结模型(仍在列表内) > 列表首个。
  const selectedForTask = React.useMemo(() => {
    if (taskModelOverride) {
      return taskModelOverride
    }
    if (frozenConfigId && llmProfiles.some((p) => p.id === frozenConfigId)) {
      return frozenConfigId
    }
    return llmProfiles[0]?.id ?? ''
  }, [taskModelOverride, frozenConfigId, llmProfiles])

  // 选中模型的思考强度(仅展示用;旧任务不渲染思考强度下拉,见下方 footer 未传 options)。
  const selectedTaskReasoningEffort = React.useMemo(
    () => llmProfiles.find((p) => p.id === selectedForTask)?.reasoningEffort ?? '',
    [selectedForTask, llmProfiles],
  )

  const status = entry?.status ?? 'idle'
  // 主 agent 稳定 Id(taskStore 透传 worker TaskSummary.mainAgentId):agent 列表首项标识、
  // 归一线程内主 agent 消息的映射键(线程内主 agent 消息 agentId 为空串,见 agents 派生)。
  const mainAgentId = entry?.mainAgentId ?? ''
  /**
   * 派生用主 agent id:记住最近一次解析到的非空值。
   *
   * 任务生命周期内主 agent id 恒定(worker meta 里的 stable id),但 taskStore 镜像每次全量
   * 校准(`refresh()` 先 `tasks.clear()` 再回填;worker presence / 工作区注册表变更都会触发)
   * 都可能让 `entry` 瞬间缺失 → mainAgentId 塌成空串。agent 名单与「只看该 agent」的选中键
   * 都以此为锚:一旦塌空,主胶囊连带消失、名单长度跌破 2 → 整行卸载重建,选中描边一闪而逝
   * (2026-10 排查:运行中子 agent 胶囊点不出稳定选中态)。这里只做展示/过滤键的兜底,
   * 不改写镜像。
   */
  const mainAgentIdRef = React.useRef('')
  if (mainAgentId) {
    mainAgentIdRef.current = mainAgentId
  }
  const stableMainAgentId = mainAgentId || mainAgentIdRef.current
  // 当前任务是否处于活动态(running/waiting-user)：决定右下角按钮是「停止」还是「发送」（两者合并为同一按钮位）。
  // 终态任务直接发送即可继续对话(worker 冷启动再运行,状态自动翻回 running)。
  const isTaskRunning = isTaskActive(status)

  // ── 单一真相源消息区 ────────────────────────────────────────────────
  // 线程渲染数据只来自 useTaskStream 返回的 items(store.state.items 实时快照),
  // 已闭合轮的合成 user/final、未闭合尾轮尾段事件、worker 定向推送的流式事件
  // 全部由 taskStream 折入同一份 items 并按 seq 去重排序。

  // 续跑校准:任务状态从非活动态翻回活动态(发送续跑 / 他端续跑)时——
  // 实时信号链重开(stream.resync:重拉 rounds + 重新订阅推送,续喂
  // agentStates/usage/ask 等状态信号,并按 live 续轮询)。消息区只消费
  // useTaskStream 返回的 items 单一真相源,resync 会让 store 重新建齐 items。
  // running ⇄ waiting-user 属于活动态内部翻转(ask 挂起/回答),不触发 resync。
  const prevStatusRef = React.useRef(status)
  React.useEffect(() => {
    if (!isTaskActive(prevStatusRef.current) && isTaskActive(status)) {
      void stream?.resync().catch(() => {
        // 校准失败不打断界面:后续事件驱动刷新会自然收敛。
      })
    }
    prevStatusRef.current = status
  }, [status, stream])

  // 外部透传的会话参数入口：复用同一任务标签（id 不变）时，传入不同 agentId 即选中
  // 该 agent(轮次视图下仅高亮);组件实例不卸载，内部状态（滚动/草稿）保留。
  // agentId 未提供时不强制覆盖用户手动选择的会话。
  React.useEffect(() => {
    if (agentId) {
      setFilterAgentId(agentId)
    }
  }, [agentId])

  // 进入任务 / 切回任务页：
  // - 首次打开该任务标签时贴底并恢复自动跟随（userControll=false），
  //   让用户直接看到最新消息。
  // - 之后切走到其他标签再切回，不动滚动条——原位置保持。
  const hasActivatedRef = React.useRef(false)
  React.useLayoutEffect(() => {
    if (!isActive) {
      return
    }
    if (hasActivatedRef.current) {
      // 已激活过，切回不动滚动。
      return
    }
    hasActivatedRef.current = true
    userControllRef.current = false
    const node = threadScrollRefNode.current
    if (!node) {
      return
    }
    requestAnimationFrame(() => {
      const current = threadScrollRefNode.current
      if (!current || !isActive) {
        return
      }
      current.scrollTo({ top: current.scrollHeight, behavior: 'auto' })
    })
  }, [isActive])

  // 流式数据驱动贴底补帧：每次收到 worker 推送（items 引用变更，含未闭合轮流式
  // delta/thinking 折叠推进）且用户未接管滚动（userControll=false）时立即贴底，
  // 确保最新内容稳定落在输入框上方。与 ResizeObserver / MutationObserver 互为冗余，
  // 三者都只做幂等的 scrollTop=scrollHeight，不会互相干扰。
  React.useLayoutEffect(() => {
    if (userControllRef.current) {
      return
    }
    const node = threadScrollRefNode.current
    if (!node) {
      return
    }
    scrollToBottomIfFollowing()
  }, [items, scrollToBottomIfFollowing])

  /**
   * 草稿内容变化（内联编辑器上报：输入 / 插入 / 移除胶囊）。
   */
  const handleChangeDraft = React.useCallback((next: ComposerDraftChange) => {
    setDraft((current) => applyComposerDraftChange(current, next))
  }, [])

  // 工作区注册表镜像:草稿选择器数据源(多工作区并行,新任务必须挂靠某个目录)。
  const [, setRegistryVersion] = React.useState(0)
  React.useEffect(() => workspaceRegistry.subscribe(() => setRegistryVersion((v) => v + 1)), [])
  // 草稿显式选择的工作区:初始/切换取任务页组"+"落下的预设(草稿标签常驻复用,
  // 点其他组的"+"也能即时切换,草稿文本保留);未选时默认注册表首选根。
  const [draftWorkspaceOverride, setDraftWorkspaceOverride] = React.useState(getDraftPreset().workspace)
  React.useEffect(() => subscribeDraftPreset((preset) => {
    if (preset.workerId) setDraftWorkerId(preset.workerId)
    if (preset.workspace) setDraftWorkspaceOverride(preset.workspace)
  }), [])
  // 草稿可用 worker 候选:已连接 + 启用 + 已填 apiKey + 无连接错误 + 未在连接中。
  const availableWorkers: WorkerInfo[] = hub.directory.filter(
    (worker) => worker.online && worker.enabled && worker.hasApiKey && !worker.error && !worker.connecting,
  )
  // 草稿态工作区按所选 worker 定向;老任务用任务归属 worker,此处不参与。
  const draftWorkspaces: WorkspaceEntry[] = draftWorkerId ? workspaceRegistry.workspacesOf(draftWorkerId) : []
  // 有效草稿工作区:显式选择 > 所选 worker 的首个工作区 > 全局首选根(未选 worker 时兜底)。
  const effectiveDraftWorkspace = draftWorkspaceOverride
    || (draftWorkerId ? workspaceRegistry.workspacesOf(draftWorkerId)[0]?.root ?? '' : workspaceRegistry.primaryRoot() || '')
  const handleSelectDraftWorker = React.useCallback((workerId: string) => {
    setDraftWorkerId(workerId)
    // 切换 worker 时清空旧工作区残留:否则旧 worker 的目录会沿用过来。
    setDraftWorkspaceOverride('')
  }, [])
  const handleSelectDraftWorkspace = React.useCallback((root: string) => {
    setDraftWorkspaceOverride(root)
  }, [])
  const handlePickDraftWorkspace = React.useCallback((path: string) => {
    // 延迟注册:浏览确认只记待选目录,不上报 workspaces.add(多次浏览不再留空工作区);
    // 真正注册发生在 handleSubmit 的 task.run(worker 端 resolve 即注册并广播)。
    setDraftWorkspaceOverride(path)
  }, [])

  /**
   * 提交当前 Task 的下一轮输入。
   */
  const handleSubmit = React.useCallback(() => {
    if (submitting) {
      return
    }
    // 提交给 AI 前，把内联占位（opaque token，如 `@` 文件引用）替换成可读文本；
    // 展示用的 rawContent 仍保留内联胶囊（opaque 原样），随输入一并持久化，
    // 供 worker 在 user.message 事件/轮次摘要里带回，前端回放据此还原文件胶囊。
    const aiText = replaceComposerTokensForSubmission(draft.rawContent, draft.tokens).trim()
    const rawContent = draft.rawContent
    if (!aiText) {
      return
    }
    if (isDraft && !draftWorkerId) {
      setError('请先选择 worker')
      return
    }
    if (isDraft && !effectiveDraftWorkspace) {
      setError('请先选择工作区:新建任务必须挂靠某个目录')
      return
    }
    if (!isDraft && !stream) {
      setError('任务流不可用，请检查 hub 连接')
      return
    }

    setError('')
    setSubmitting(true)
    // 发送即恢复自动跟随：用户可能此前手动上滚接管了滚动（userControll=true）。
    // 一旦发送新消息，立即置回 false，确保本轮用户消息与后续回复都能自动滚入视图。
    userControllRef.current = false
    // 插件提交贡献（如编辑重发的 editSeq metadata）：提交时实时收集，核心透传不解释。
    const submitContributions = isDraft ? [] : collectTaskRunSubmitContributions(effectiveTaskId)
    const mergedMetadata = Object.assign(
      {},
      ...submitContributions.map(({ contribution }) => contribution.metadata ?? {}),
    ) as Record<string, unknown>
    const contributionMetadata = Object.keys(mergedMetadata).length > 0 ? mergedMetadata : undefined
    void (async () => {
      try {
        if (isDraft) {
          // 草稿首次发送：建任务（worker 立即开跑），替换草稿标签。
          // workspace 必填:草稿选择器的有效值(显式选择/预设 > 注册表首选根)。
          // 任务级底部 token 在任务创建后通过 slash.taskTokens.apply 逐个写入
          // (slash 层自管 slash-tokens.json,不走 task.run 参数)。
          const newTaskId = await taskQueryService.runTask(aiText, {
            title: aiText.slice(0, 40),
            configId: selectedLlmConfigId || undefined,
            workspace: effectiveDraftWorkspace || undefined,
            workerId: draftWorkerId,
            rawContent,
          })
          // 任务创建后，把草稿态持有的 scope token 逐个 apply 到新任务
          for (const t of scopeTokens) {
            if (t.opaqueText && t.opaqueText.length > 0) {
              try {
                await applyTaskToken({ taskId: newTaskId, id: t.slashId ?? extractSlashId(t.opaqueText) ?? '', token: t.opaqueText })
              } catch (e) {
                console.warn('[slash] 建后 apply 任务级 token 失败', e)
              }
            }
          }
          setDraft({ text: '', rawContent: '', tokens: [], activeTokenId: undefined })
          // 新任务页回显由其任务页的 listTaskTokens / slash.tokens.changed 负责。
          setScopeTokens([])
          // 先关草稿标签、再打开真实任务标签并激活:顺序不能反——若先 openTaskChatTab,
          // 紧接着 closeWorkspaceTab 关闭草稿时,内部按旧闭包 activeWorkspaceTabId(草稿)
          // 重算 next-active 并覆盖激活,新任务标签刚被激活就被切走。
          shell.closeWorkspaceTab(`task:${DRAFT_TASK_ID}` as const)
          shell.openTaskChatTab({ taskId: newTaskId, title: aiText.slice(0, 40) })
          return
        }
        if (isTaskRunning) {
          // 运行中:task.run{taskId} 入队(输入框上方队列面板实时可见,轮次间消费)。
          await taskQueryService.runTask(aiText, {
            taskId: effectiveTaskId,
            configId: selectedForTask || undefined,
            rawContent,
            metadata: contributionMetadata,
          })
        } else {
          // 终态:task.run{taskId} 冷启动续跑(载入历史,状态翻回 running)。
          // 带上当前输入框选定的模型,使旧任务也能切换到其他模型。
          await taskQueryService.runTask(aiText, {
            taskId: effectiveTaskId,
            configId: selectedForTask || undefined,
            rawContent,
            metadata: contributionMetadata,
          })
        }
        // 提交成功：通知各贡献 provider（如编辑重发清除编辑目标）。
        for (const { provider } of submitContributions) {
          provider.onSubmitted?.(effectiveTaskId)
        }
        setDraft({ text: '', rawContent: '', tokens: [], activeTokenId: undefined })
      } catch (submitError) {
        setError(submitError instanceof Error ? submitError.message : 'Task 提交失败')
      } finally {
        setSubmitting(false)
      }
    })()
  }, [draft, isDraft, draftWorkerId, selectedLlmConfigId, submitting, stream, shell, effectiveDraftWorkspace, isTaskRunning, effectiveTaskId, selectedForTask, scopeTokens])

  /**
   * 停止当前 Task。
   */
  const handleStop = React.useCallback(() => {
    if (!stream || !isTaskRunning || stopping) {
      return
    }
    setError('')
    setStopping(true)
    void stream.cancel()
      .catch((stopError) => {
        setError(stopError instanceof Error ? stopError.message : '停止 Task 失败')
      })
      .finally(() => {
        setStopping(false)
      })
  }, [isTaskRunning, stopping, stream])

  /**
   * 输入框草稿桥接：提供给插件（如编辑重发按钮）回填草稿的通用能力，
   * 核心不感知具体业务。
   */
  const composerDraftBridge = React.useMemo<ComposerDraftBridgeValue>(() => ({
    appendText: (text: string) => {
      setError('')
      setDraft((current) => {
        const next = (current.rawContent ?? '') + (text || '')
        return { text: next, rawContent: next, tokens: [], activeTokenId: undefined }
      })
    },
    setRawContent: (rawContent: string) => {
      setError('')
      setDraft({ text: rawContent, rawContent, tokens: [], activeTokenId: undefined })
    },
  }), [])

  // 插件草稿桥接：把输入框草稿写能力注入非 React 模块 holder，
  // 供插件 ctx.ui.appendComposerText 同步调用（替代 ComposerDraftBridgeContext 直接读取）。
  // 仅在标签页激活时注入：多个任务标签页并存时，composerBridge 是模块级单例，
  // 只有当前激活的 TaskChat 才应持有写权——否则切换标签后 bridge 仍指向旧实例，
  // 编辑重发回填会写入非激活标签的 draft，激活标签的输入框不更新。
  // 无 cleanup：不活跃时不清空 bridge，而是让下一个激活标签的 effect 覆盖写入，
  // 避免「A 清空→B 写入」与「B 写入→A 清空」两种树序下 bridge 结果不一致的竞态。
  React.useEffect(() => {
    if (!isActive) return
    setComposerBridge(composerDraftBridge)
  }, [composerDraftBridge, isActive])

  // 从线程派生 agent 列表:主 agent(mainAgentId)恒在首位,子 agent 按首次出现顺序。
  // 线程内主 agent 消息 agentId 为空串(缺省=主线程),此处归一到 mainAgentId 供列表/过滤使用。
  // 除 items 外还并入 agentMeta 的子 agent 键(task.agents 台账 seed):历史任务过程内容
  // 未懒加载、items 尚无子 agent 消息时,胶囊列表也能显示全部子 agent(items 派生优先,
  // meta 补齐追加在后;title 取 resolveAgentTitle 优先,兜底 meta.title)。
  //
  // ── 名单「只增不减」 ──────────────────────────────────────────
  // 上面两个数据源在运行中会**整瞬间清空**:任务状态从非活动翻回活动时聊天页会 stream.resync()
  // (= open → folder.reset(),items/agentStates/agentMeta 全清后重拉),task.agents 台账也是
  // 异步补回。名单一旦塌成只剩主 agent,AgentListPanel 按「≤1 个 agent 不渲染」整行卸载,
  // 胶囊连同刚点上的选中描边一起消失、DOM 节点被拆掉后点击还会落空——这正是「选中效果一闪
  // 而逝」。一个任务的 agent 集合只会新增、不会消失,故此处按 agentId 单调累积名单:
  // **成员与顺序冻结**,标题/状态/用量仍每帧取实时值(见 agentListItems)。
  const agentMeta = stream?.state.agentMeta
  const agentRosterRef = React.useRef<Map<string, string>>(new Map())
  const agents = React.useMemo(() => {
    const seen = new Map<string, string>()
    if (stableMainAgentId) {
      seen.set(stableMainAgentId, '主 agent')
    }
    for (const item of items) {
      const rawKey = item.type === 'agent_message' ? item.message.agentId : (item.trace.agentId ?? '')
      const agentKey = rawKey || stableMainAgentId
      if (agentKey && !seen.has(agentKey)) {
        seen.set(agentKey, stream?.resolveAgentTitle(rawKey) ?? rawKey)
      }
    }
    if (agentMeta) {
      for (const [agentId, meta] of Object.entries(agentMeta)) {
        // 键语义:主 agent = ''(已由 mainAgentId 占位,跳过);子 = 子 id。
        if (!agentId || seen.has(agentId)) {
          continue
        }
        seen.set(agentId, stream?.resolveAgentTitle(agentId) ?? meta.title ?? agentId)
      }
    }
    // 并入既往已见但本轮数据缺席的 agent(resync reset / 台账晚到的空窗期)。
    for (const [agentId, title] of agentRosterRef.current) {
      if (!seen.has(agentId)) {
        seen.set(agentId, title)
      }
    }
    agentRosterRef.current = seen
    return [...seen.entries()].map(([agentId, title]) => ({ agentId, title }))
  }, [items, stream, stableMainAgentId, agentMeta])

  // agent 长条列表项:状态来自事件折叠器的 agentStates(主 agent 键为空串 '';子 agent 键 = 子 id);
  // meta 同源(键:主 = '',子 = 子 id)→ 悬停信息卡数据 + 子 agent 胶囊底部上下文用量线
  // (contextRatio = contextUsed / contextWindow,clamp 到 0~1;无数据 undefined)。
  const agentListItems = React.useMemo<AgentListItem[]>(() => {
    const states = stream?.state.agentStates ?? {}
    const metas = agentMeta ?? {}
    const entryStatus = entry?.status ?? 'idle'
    // 任务终态判定:completed/stopped/error 时,任务状态是主 agent 的权威状态。
    const entryStatusTerminaled = entryStatus === 'completed' || entryStatus === 'stopped' || entryStatus === 'error'
    return agents.map((agent) => {
      const isMain = agent.agentId === stableMainAgentId
      const meta = metas[isMain ? '' : agent.agentId]
      const contextUsed = meta?.contextUsed
      const contextWindow = meta?.contextWindow
      const contextRatio = contextUsed != null && contextWindow != null && contextWindow > 0
        ? Math.min(1, Math.max(0, contextUsed / contextWindow))
        : undefined
      return {
        agentId: agent.agentId,
        title: agent.title,
        status: isMain
          // 主 agent:任务终态时,任务状态(entry.status)是权威——流事件 agentStates['']
          // 可能因 resync reset 后丢失、或被 cancelled 等事件设为 stopped 而与实际任务
          // 终态(completed)不一致;非终态时流事件优先(反映 running/waiting-user 实时状态)。
          ? (entryStatusTerminaled
            ? entryStatus
            : (states[''] ?? (entryStatus ?? 'idle')))
          : (states[agent.agentId] ?? 'idle'),
        isMain,
        meta,
        contextRatio,
      }
    })
  }, [agents, stableMainAgentId, stream, agentMeta, entry?.status])

  // 电池详情卡数据(任务级汇总):任务下全部 agent(主 + 子)聚合——累计 tokens
  // (输入/输出/合计,每 agent 累计值)与上下文占用/窗口(每 agent 最近一轮)逐 agent
  // 累加;创建时间用任务创建时间(entry.createdAt);模型取各 agent 快照去重拼接。
  // 上下文占用优先取 worker 聚合快照(entry.contextUsage,与电池填充同源同值),
  // 缺省(未建流/旧数据)时回退 agentMeta 逐项求和;无任何数据时返回 null,电池内部
  // 由 monitor 快照兜底构造。
  const batteryItem = React.useMemo<AgentListItem | null>(() => {
    const mainItem = agentListItems.find((item) => item.isMain) ?? null
    const metas = agentListItems
      .map((item) => item.meta)
      .filter((meta): meta is AgentMetaSnapshot => Boolean(meta))
    const usage = entry?.contextUsage
    if (metas.length === 0 && !usage) {
      return null
    }
    const sumMeta = (pick: (meta: AgentMetaSnapshot) => number | undefined): number | undefined => {
      let total = 0
      let hasValue = false
      for (const meta of metas) {
        const value = pick(meta)
        if (value != null) {
          total += value
          hasValue = true
        }
      }
      return hasValue ? total : undefined
    }
    const models: string[] = []
    for (const meta of metas) {
      if (meta.model && !models.includes(meta.model)) {
        models.push(meta.model)
      }
    }
    const contextUsed = usage
      ? (usage.promptTokens ?? usage.totalTokens ?? 0)
      : sumMeta((meta) => meta.contextUsed)
    const contextWindow = usage && usage.maxTokens > 0
      ? usage.maxTokens
      : sumMeta((meta) => meta.contextWindow)
    const agentId = mainItem?.agentId ?? stableMainAgentId ?? ''
    return {
      agentId,
      title: '全部 agent',
      status: mainItem?.status ?? 'idle',
      isMain: true,
      meta: {
        agentId,
        createdAt: entry?.createdAt && entry.createdAt > 0 ? entry.createdAt : undefined,
        model: models.length > 0 ? models.join(' / ') : (usage?.model || undefined),
        inputTokens: sumMeta((meta) => meta.inputTokens),
        outputTokens: sumMeta((meta) => meta.outputTokens),
        totalTokens: sumMeta((meta) => meta.totalTokens),
        contextUsed,
        contextWindow,
        updatedAt: usage?.lastUpdatedAt ?? sumMeta((meta) => meta.updatedAt),
      },
      contextRatio: contextUsed != null && contextWindow != null && contextWindow > 0
        ? Math.min(1, Math.max(0, contextUsed / contextWindow))
        : undefined,
    }
  }, [agentListItems, entry, stableMainAgentId])

  /** 点击 agent 长条:切换选中态(再点同一 agent 由面板回传 '' 恢复全部;轮次视图下仅高亮)。 */
  const handleSelectAgent = React.useCallback((agentId: string) => {
    setFilterAgentId(agentId)
  }, [])

  // 插件扩展点版本：注册/注销时递增，驱动插件面板重渲染。
  const pluginExtVersion = React.useSyncExternalStore(
    pluginDispatcher.subscribeExtensionsChanged,
    pluginDispatcher.getExtensionsVersion,
  )
  // 插件注册的输入框上方面板（每个 Component 接收 ComposerPanelCtx）。
  const composerAbovePanels = React.useMemo(
    () => pluginDispatcher.listRegisteredComposerAbovePanels(),
    // pluginExtVersion 变化时重新读取快照（注册/注销后立即出现/消失）。
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [pluginExtVersion],
  )
  // 为插件面板构建 ComposerPanelCtx（每次渲染新建，保持引用最新）。
  // 公共上下文不携带任何插件专有数据（如队列插件的 pendingInputs）——
  // 插件面板自持数据源（经 ctx.rpc 拉取 + subscribeTaskEvents 信号刷新）。
  const composerPanelCtx = React.useMemo<ComposerPanelCtx>(() => ({
    taskId: effectiveTaskId,
    draft: draft as ComposerPanelCtx['draft'],
    isRunning: isTaskActive(entry?.status ?? 'idle'),
    selectAgent: (agentId: string | null) => {
      if (agentId) handleSelectAgent(agentId)
    },
    rpc: (method: string, params: Record<string, unknown>) => {
      const ownerW = ownerWorkerId || taskStore.get(effectiveTaskId)?.workerId || ''
      return hubSession.rpcTo(ownerW, method, params)
    },
    subscribeTaskEvents: (handler) => {
      // taskStore 变更（task.updated 广播：status/usage 及插件附带的扩展字段等）
      const unsubStore = taskStore.subscribe(() => {
        handler('task.updated', null, null)
      })
      // task stream 变更（agent.*、usage 等流式事件）
      const unsubStream = stream?.subscribe(() => {
        handler('task.stream', null, null)
      })
      return () => {
        unsubStore()
        unsubStream?.()
      }
    },
  }), [effectiveTaskId, draft, entry?.status, handleSelectAgent, hubSession, ownerWorkerId, stream])

  const submitDisabled = Boolean(!draft.text.trim() || submitting || (isDraft && !draftWorkerId) || (isDraft && !effectiveDraftWorkspace))

  // 草稿态：渲染与 n 版启动台一致的草稿面板（BrandMark 顶栏 + 空线程 + 输入区）。
  // 模型配置来自 worker(config.get),选中项经 task.create 的 configId 冻结进任务快照。
  if (isDraft) {
    return (
      <TaskDraftComposerPanel
        isMobile={isMobile}
        selectedLlmConfigId={selectedLlmConfigId}
        llmProfiles={llmProfiles}
        draft={draft}
        workers={availableWorkers}
        selectedWorker={draftWorkerId}
        onSelectWorker={handleSelectDraftWorker}
        workerId={draftWorkerId}
        workspaces={draftWorkspaces}
        selectedWorkspace={effectiveDraftWorkspace}
        onSelectWorkspace={handleSelectDraftWorkspace}
        onPickWorkspace={handlePickDraftWorkspace}
        submitLabel={submitting ? '发送中...' : '发送'}
        submitDisabled={submitDisabled}
        error={error}
        onChangeDraft={handleChangeDraft}
        onSelectLlmConfig={handleSelectLlmConfig}
        taskScopeTokens={scopeTokens}
        onAddTaskScopeToken={handleAddTaskScopeToken}
        onRemoveTaskScopeToken={handleRemoveTaskScopeToken}
        onSubmitTask={handleSubmit}
      />
    )
  }

  return (
    <ComposerDraftBridgeContext.Provider value={composerDraftBridge}>
    <>
    <ChatShell
      // 工作区 chip 已迁移至输入框底部(电池图标左侧),顶部不再重复展示。
      header={undefined}
      threadScrollRef={threadScrollRef}
      overlay={showScrollToBottom ? (
        <button
          type="button"
          className="nagent-chat__scroll-to-bottom"
          onClick={handleScrollToBottom}
          title="滚动到底部"
          aria-label="滚动到底部"
        >
          <ArrowDownIcon size={16} />
        </button>
      ) : null}
      thread={(
        <TaskRoundsPanel
          taskId={effectiveTaskId}
          stream={stream}
          items={items}
          isGenerating={isTaskRunning}
          scrollRoot={threadScrollRefNode.current}
          filterAgentId={filterAgentId}
          mainAgentId={stableMainAgentId}
        />
      )}
      composer={(
        <div className="nagent-composer-dock">
          {status === 'error' ? (
            <div className="ui-notice ui-notice--warn nagent-composer__truncation" role="status">
              <div className="ui-notice__body">
                <div className="ui-notice__title">本轮运行被提前结束</div>
                <div>{entry?.error || 'worker 运行出错，任务被提前结束。'}</div>
              </div>
            </div>
          ) : null}
          <TaskComposerSurface
            draft={draft}
            placeholder="继续输入，让当前 Task 创建新的 Agent 执行下一轮"
            taskId={effectiveTaskId}
            workerId={ownerWorkerId}
            workspace={entry?.workspace}
            isMobile={isMobile}
            abovePanel={(
              <div className="nagent-composer__above-stack">
                <AgentListPanel
                  agents={agentListItems}
                  filterAgentId={filterAgentId}
                  onSelect={handleSelectAgent}
                />
                {composerAbovePanels.map((def) => {
                  const Component = def.Component as React.ComponentType<ComposerPanelCtx>
                  return <Component key={def.id} {...composerPanelCtx} />
                })}
              </div>
            )}
            submitLabel={submitting ? '发送中...' : '发送'}
            submitDisabled={submitDisabled}
            showSendButton={false}
            error={error}
            footerControls={(
              <div className="task-composer-footer">
                <HScrollArea className="task-composer-footer__scroll">
                  <div className="task-composer-footer__left">
                    {/* 任务级底部 token 胶囊（slash 选中后持久化回显）：放在电池左侧，
                        远离右侧发送/停止按钮，避免点 ✕ 关闭胶囊时误触停止按钮。 */}
                    {scopeTokens.map((token) => (
                      <span key={token.id} className="nagent-inline-chip nagent-inline-chip--scope">
                        <span className="nagent-inline-chip__label">{token.label}</span>
                        <span
                          className="nagent-inline-chip__close"
                          contentEditable={false}
                          data-close="1"
                          aria-label="取消"
                          onClick={() => handleRemoveTaskScopeToken({ id: extractSlashId(token.opaqueText), token: token.opaqueText })}
                        >✕</span>
                      </span>
                    ))}
                    {/* 上下文电池统一消费 taskStore 的 TaskSummary.usage(worker usage
                        投影器聚合任务下所有 agent 最近一轮占用,task.updated 每轮实时推送;
                        聊天页与任务列表同源同值,流内不再单独维护)。
                        详情卡数据 = 任务级汇总项(任务下全部 agent 聚合,创建时间用任务
                        创建时间,上下文占用/窗口逐 agent 累加)。 */}
                    <ContextBattery
                      taskId={effectiveTaskId}
                      monitor={entry?.contextUsage ?? null}
                      agentItem={batteryItem}
                    />
                  </div>
                  <div className="task-composer-footer__controls">
                    <TaskModelControls
                      selectedLlmConfigId={selectedForTask}
                      selectedReasoningEffort={selectedTaskReasoningEffort}
                      llmProfiles={llmProfiles}
                      isMobile={isMobile}
                      onSelectLlmConfig={setTaskModelOverride}
                    />
                  </div>
                </HScrollArea>
                {isTaskRunning && !draft.text.trim() ? (
                  <Button
                    variant="danger"
                    size="sm"
                    onClick={handleStop}
                    disabled={stopping}
                    className="nagent-composer__send task-composer-footer__send"
                  >
                    <StopIcon size={14} />
                    <span className="task-composer-footer__send-label">
                      {stopping ? '停止中...' : '停止'}
                    </span>
                  </Button>
                ) : (
                  <Button
                    variant={submitOverride?.submitDanger ? 'danger' : 'primary'}
                    size="sm"
                    onClick={handleSubmit}
                    disabled={submitDisabled}
                    className="nagent-composer__send task-composer-footer__send"
                  >
                    {submitting ? (
                      <InlineSpinner size={14} color="currentColor" trackColor="transparent" />
                    ) : (
                      <ArrowRightIcon size={14} />
                    )}
                    <span className="task-composer-footer__send-label">
                      {submitting ? (submitOverride?.submittingLabel ?? '发送中...') : (submitOverride?.submitLabel ?? '发送')}
                    </span>
                  </Button>
                )}
              </div>
            )}
            onAddTaskScopeToken={handleAddTaskScopeToken}
            onChangeDraft={handleChangeDraft}
            onSubmit={handleSubmit}
          />
        </div>
      )}
    />
    </>
    </ComposerDraftBridgeContext.Provider>
  )
}

/**
 * 订阅任务实时流(折叠推进触发重渲染)。
 *
 * 返回 [stream, items]:stream 是共享句柄(含可变 state,供 taskModel/contextUsage/
 * agentStates/resolveAgentTitle/cancel 等消费);items 是**每次折叠推进都
 * 换引用的线程项副本**——既供 TaskRoundsPanel 消息区,也供派生 agent 列表(agents)。
 *
 * 关键:hub 的 TaskEventFolder.fold 对 state.items 做原地 push / 原地 mutate
 * (可变引用,见 eventFolder.ts),引用永不改变。这里每次 notify 返回 [..items]
 * 新引用,保证下游 memo / effect 的 [items] 依赖能感知折叠推进(新子 agent
 * 出现/标题更新/流式 delta 到达时刷新)。
 */
function useTaskStream(taskId: string | null): [TaskStreamHandle | null, TaskThreadItem[]] {
  const [version, setVersion] = React.useState(0)
  const stream = React.useMemo(
    () => (taskId ? taskStreamManager.get(taskId) : null),
    [taskId],
  )
  React.useEffect(() => {
    if (!stream) return
    const unsubscribe = stream.subscribe(() => setVersion((v) => v + 1))
    return unsubscribe
  }, [stream])
  // 每次 notify(version 变)都产生新的 items 引用,驱动下游 memo / effect 刷新。
  const items = React.useMemo<TaskThreadItem[]>(
    () => (stream ? [...stream.state.items] : []),
    [stream, version],
  )
  return [stream, items]
}

/** 订阅任务列表条目(状态/标题变化触发重渲染)。 */
function useTaskEntry(taskId: string | null) {
  const [entry, setEntry] = React.useState(taskId ? taskStore.get(taskId) : undefined)
  React.useEffect(() => {
    if (!taskId) {
      setEntry(undefined)
      return
    }
    const syncEntry = () => {
      setEntry(taskStore.get(taskId))
      // 任务列表默认只加载最近一页:镜像缺失(重连后仍开的旧标签/分页窗口外的老任务)时定向补齐。
      if (!taskStore.get(taskId)) {
        void taskStore.ensureLoaded(taskId)
      }
    }
    syncEntry()
    return taskStore.subscribe(syncEntry)
  }, [taskId])
  return entry
}

/**
 * 计算滚动容器底部还剩多少距离。
 * 返回值越大，表示离底部越远。
 */
function getDistanceFromBottom(target: HTMLElement): number {
  return Math.max(0, target.scrollHeight - (target.scrollTop + target.clientHeight))
}

/**
 * 收集某任务当前的 task.run 提交贡献（扩展点 `task.submit_contributions`）。
 * 返回 provider 与贡献的配对（提交成功后需按 provider 回调 onSubmitted）。
 */
function collectTaskRunSubmitContributions(taskId: string): Array<{
  provider: TaskRunSubmitContributionProvider
  contribution: TaskRunSubmitContribution
}> {
  const result: Array<{ provider: TaskRunSubmitContributionProvider; contribution: TaskRunSubmitContribution }> = []
  for (const provider of pluginDispatcher.listRegisteredTaskRunSubmitContributionProviders()) {
    const contribution = provider.getContribution(taskId)
    if (contribution) {
      result.push({ provider, contribution })
    }
  }
  return result
}

/**
 * 把 opaque token 串解析为任务级底部 token（ChatComposerToken）。
 * 非法串（非 opaque token）返回 null，由调用方跳过。
 */
function buildScopeToken(opaque: string, slashId?: string): ChatComposerToken | null {
  const parsed = parseOpaqueTokenText(opaque)
  if (!parsed) {
    return null
  }
  return {
    id: createSnowflakeId('composer_token'),
    kind: parsed.kind,
    label: parsed.label ?? '',
    summary: parsed.summary,
    opaqueText: opaque,
    slashId,
  }
}

/**
 * worker ModelConfig → 前端 LLMConfigProfile。
 * 控件只消费 id/provider/model/name/reasoningEffort;apiKey 恒为掩码,仅展示不回传。
 */
function toModelProfile(config: ModelConfigInfo): LLMConfigProfile {
  const params = (config.params ?? {}) as Record<string, unknown>
  return {
    id: config.configId,
    name: config.configId,
    provider: (config.provider || 'custom') as LLMConfigProfile['provider'],
    apiKey: config.apiKey ?? '',
    baseUrl: config.baseUrl ?? undefined,
    model: config.model,
    reasoningEffort: typeof params.reasoningEffort === 'string' ? params.reasoningEffort : undefined,
    temperature: typeof params.temperature === 'number' ? params.temperature : 0,
    contextWindowTokens: typeof params.contextWindowTokens === 'number' ? params.contextWindowTokens : undefined,
    maxTokens: typeof params.maxTokens === 'number' ? params.maxTokens : 0,
    createdAt: 0,
    updatedAt: 0,
  }
}
