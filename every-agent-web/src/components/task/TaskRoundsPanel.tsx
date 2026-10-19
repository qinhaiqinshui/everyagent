import React from 'react'
import type { RoundSummary, TaskRoundsResult } from '@/task/types'
import type { TaskStreamHandle } from '@/task/taskStream'
import type { TaskThreadItem } from '@/task/eventFolder'
import { BrandMark } from '@/components/shared/BrandLoadingBlock'
import { ChevronDownIcon, ChevronRightIcon } from '../shared/AppGlyphs'
import { InlineSpinner } from '@/components/shared/ui'
import AgentMessageThread from './AgentMessageThread'
import TaskThread from './TaskThread'
import RoundDetail from './RoundDetail'
import LazyLoadSentinel from './LazyLoadSentinel'
import { pluginDispatcher } from '@/plugin/PluginDispatcher'
import { taskStore } from '@/task/taskStore'
import { TaskWorkspaceProvider } from './TaskWorkspaceContext'
import {
  closedRoundsOf,
  firstTailEventSeq,
  matchesAgentFilter,
  sliceTailItems,
  syntheticFinalRecord,
  syntheticUserRecord,
  tailItemsAfterUser,
  terminalTailOf,
} from './roundsView'
import './TaskRoundsPanel.css'

/**
 * 任务线程面板（懒加载形态）：
 *
 * - 轮次骨架（user / final / 折叠标记 / 耗时）来自 taskStream 的 rounds 快照（task.rounds 索引），
 *   user 气泡直接用 rounds.jsonl 的 userMessage 折入 items，不再单独补拉；
 * - 闭合轮保持「user 气泡 + 折叠条 + final 常显」，点击展开后才懒加载过程内容
 *   （前向分页，滚动到底部 forward sentinel 续拉，拉到 endSeq 停）；
 * - 终态未闭合尾轮：常开视图，从 startSeq 起前向懒加载（底部 forward sentinel）；
 * - 运行中未闭合尾轮：原样订阅实时流 + 自动滚底，初始为「最后一页」（roundTail 200）与
 *   流式增量接上，顶部（user 之后）backward sentinel 支持往上翻历史（到达 startSeq 停）；
 * - 轮末展示区：通用扩展点（ui.round_tail_panels），折叠/展开两态均由插件自行在该轮末尾渲染，
 *   轮行不携带任何插件业务字段（旁路数据如文件变更由插件自持，架构 §7.15.2）。
 * - agent 过滤（「只看该 agent」，filterAgentId 非空）：闭合轮展开态（RoundDetail）与尾轮
 *   过程流仅显示归属该 agent 的项；轮骨架（user 气泡/折叠条/final 摘要/轮末展示区）不过滤，
 *   避免未加载轮被误判「消失」。纯渲染派生——只过滤已加载内容，不新增任何拉取触发；
 *   用户展开/滚动加载折入的新内容经 items 引用变化自动纳入过滤，其他 agent 的流式增量
 *   被挡在视图外（DOM 不变 → MutationObserver 不触发 → 不会误贴底）。
 */
export interface TaskRoundsPanelProps {
  /** 当前任务 ID。 */
  taskId: string
  /** 任务流句柄（取 rounds 快照、触发懒加载分页 / 重试）。 */
  stream: TaskStreamHandle | null
  /** 单一线程真相源（taskStream 折叠产物，按 seq 升序）。 */
  items: TaskThreadItem[]
  /** 本轮生成是否在飞（驱动尾轮运行状态横幅）。 */
  isGenerating?: boolean
  /** 懒加载占位的 IntersectionObserver root（滚动容器；缺省用视口）。 */
  scrollRoot?: HTMLElement | null
  /** 空任务提示文案。 */
  emptyText?: string
  /** agent 过滤（「只看该 agent」）：'' = 不过滤；非空 = 仅显示归属该 agent 的已加载内容。 */
  filterAgentId?: string
  /** 主 agent 稳定 Id（线程内主 agent 消息 agentId 为空串，过滤前归一用）。 */
  mainAgentId?: string
}

export default function TaskRoundsPanel({
  taskId,
  stream,
  items,
  isGenerating = false,
  scrollRoot,
  emptyText,
  filterAgentId = '',
  mainAgentId = '',
}: TaskRoundsPanelProps): React.ReactNode {
  /** 已展开的轮 roundId 集合（多轮可同时展开）。 */
  const [expandedSet, setExpandedSet] = React.useState<ReadonlySet<string>>(() => new Set())
  /** 每轮懒加载分页状态（key：闭合轮/终态尾轮 = roundId；运行中尾轮 = 'open'）。 */
  const [pageStates, setPageStates] = React.useState<Record<string, RoundPageState>>({})
  const pageStatesRef = React.useRef(pageStates)
  pageStatesRef.current = pageStates

  const roundsResult: TaskRoundsResult | null = stream?.rounds() ?? null
  const roundsError = stream?.roundsError() ?? null
  const rounds = roundsResult?.rounds ?? []
  const open = roundsResult?.open ?? null
  const live = roundsResult?.live ?? false
  const openStartSeq = open?.startSeq ?? ''
  const workspaceRoot = taskStore.get(taskId)?.workspace || undefined
  const workerId = taskStore.get(taskId)?.workerId || undefined

  // 切换任务：清空展开/分页状态。
  React.useEffect(() => {
    setExpandedSet(new Set())
    setPageStates({})
  }, [taskId])

  /** 更新某轮分页状态（合并 patch）。 */
  const updatePageState = React.useCallback((key: string, patch: Partial<RoundPageState>) => {
    setPageStates((cur) => {
      const prev = cur[key] ?? { dir: 'forward' as const, cursor: '', loading: false, done: false }
      const next = { ...prev, ...patch }
      return { ...cur, [key]: next }
    })
  }, [])

  /** 前向续拉一轮过程（闭合轮/终态尾轮）。 */
  const loadForward = React.useCallback(
    async (round: RoundSummary, afterSeq: string) => {
      const key = round.roundId
      const cur = pageStatesRef.current[key]
      if (!stream || cur?.loading || cur?.done) return
      updatePageState(key, { loading: true })
      try {
        const res = await stream.loadRoundForward({
          startSeq: round.startSeq,
          endSeq: round.endSeq || null,
          afterSeq,
        })
        updatePageState(key, { cursor: String(res.lastSeq), loading: false, done: !res.hasMore })
      } catch (error) {
        console.warn(`[TaskRoundsPanel] 前向续拉轮 ${round.index} 失败(${taskId}):`, error)
        updatePageState(key, { loading: false })
      }
    },
    [stream, taskId, updatePageState],
  )

  /** 开始/继续前向拉取：渲染层只负责「要这页数据」的时机，缓存判断在拉取层
   *  （taskStream.loadRoundForward 会先查 items、缺才 RPC）。首次初始化游标 = startSeq-1，
   *  之后滚动 sentinel 用已存 cursor 续拉。 */
  const startForward = React.useCallback(
    (round: RoundSummary) => {
      const key = round.roundId
      const cur = pageStatesRef.current[key]
      if (!cur) {
        const firstAfter = String(BigInt(round.startSeq) - 1n)
        updatePageState(key, { dir: 'forward', cursor: firstAfter, loading: false, done: false })
        void loadForward(round, firstAfter)
      } else if (!cur.loading && !cur.done) {
        void loadForward(round, cur.cursor)
      }
    },
    [loadForward, updatePageState],
  )

  /** 后向续拉运行中尾轮历史。 */
  const loadBackward = React.useCallback(
    async (startSeq: string, beforeSeq: string) => {
      const key = 'open'
      const cur = pageStatesRef.current[key]
      if (!stream || cur?.loading || cur?.done) return
      updatePageState(key, { loading: true })
      try {
        const res = await stream.loadRoundBackward({ startSeq, beforeSeq })
        updatePageState(key, { cursor: String(res.firstSeq), loading: false, done: res.reachedStart })
      } catch (error) {
        console.warn(`[TaskRoundsPanel] 后向续拉运行中尾轮失败(${taskId}):`, error)
        updatePageState(key, { loading: false })
      }
    },
    [stream, taskId, updatePageState],
  )

  /** 开始/继续后向拉取：首次需当前最早尾事件 seq 作为 beforeSeq。 */
  const startBackward = React.useCallback(
    (startSeq: string, beforeSeq: string) => {
      const key = 'open'
      const cur = pageStatesRef.current[key]
      if (!cur) {
        updatePageState(key, { dir: 'backward', cursor: beforeSeq, loading: false, done: false })
        void loadBackward(startSeq, beforeSeq)
      } else if (!cur.loading && !cur.done) {
        void loadBackward(startSeq, cur.cursor)
      }
    },
    [loadBackward, updatePageState],
  )

  const toggleExpanded = React.useCallback((roundId: string) => {
    setExpandedSet((cur) => {
      const next = new Set(cur)
      if (next.has(roundId)) next.delete(roundId)
      else next.add(roundId)
      return next
    })
  }, [])

  /** 点击折叠条：首次展开时若该轮分页状态尚未建立，才发起首页拉取（拉取层会先查 items 缓存，
   *  若已有则不 RPC）。渲染层不再自行查 items。 */
  const handleToggle = React.useCallback(
    (round: RoundSummary) => {
      const wasExpanded = expandedSet.has(round.roundId)
      if (!wasExpanded) {
        const cur = pageStatesRef.current[round.roundId]
        if (!cur) startForward(round)
      }
      toggleExpanded(round.roundId)
    },
    [expandedSet, startForward, toggleExpanded],
  )

  /** 已闭合轮（endSeq 非空）。 */
  const closedRounds = React.useMemo(() => closedRoundsOf(rounds), [rounds])

  /** 终态遗留的未闭合尾轮（open 为 null 且末行 endSeq=''）。 */
  const terminalTail = React.useMemo<RoundSummary | null>(
    () => terminalTailOf(rounds, live),
    [rounds, live],
  )

  const tailStartSeq = openStartSeq || terminalTail?.startSeq || ''

  // 终态未闭合尾轮：常开视图，进入即前向首页拉取（不再有实时增量）。
  React.useEffect(() => {
    if (!tailStartSeq || live || !terminalTail) return
    if (pageStatesRef.current[terminalTail.roundId]) return
    startForward(terminalTail)
  }, [tailStartSeq, live, terminalTail, startForward])

  // 运行中尾轮：原样订阅流 + 尾页起步；仅初始化 backward 分页状态（cursor=当前最早尾事件
  // seq），**不触发拉取**——真正的历史续拉等用户上滚把顶部 backward sentinel 滚入可视区
  // 经 onLoadMoreBackward 触发（懒加载语义，方案第 3 点）。
  React.useEffect(() => {
    if (!tailStartSeq || !live) return
    const cur = pageStatesRef.current['open']
    if (cur) return
    const firstEventSeq = firstTailEventSeq(items, tailStartSeq)
    if (!firstEventSeq) {
      // 尚无过程事件(刚发送 / 首 token 前):不初始化,等流式增量折入 items 后重试。
      return
    }
    updatePageState('open', { dir: 'backward', cursor: firstEventSeq, loading: false, done: false })
  }, [tailStartSeq, live, items, updatePageState])

  /** agent 过滤谓词：'' = 不过滤（原样全过）；否则仅放行归属该 agent 的线程项。
   * 纯渲染派生，不触发任何拉取——被过滤掉的流式增量不产生 DOM 变化，不影响自动贴底。 */
  const matches = React.useCallback(
    (it: TaskThreadItem) => matchesAgentFilter(it, filterAgentId, mainAgentId),
    [filterAgentId, mainAgentId],
  )

  /** 尾轮线程项：从 items 中 tailStartSeq 起点开始切片（含 user 气泡，剥 foldRole 防折叠）。
   * 过滤态：切片后按归属过滤（子 agent 过滤会滤掉 user 气泡，但外层 tailUserItem 恒显，
   * 视觉上 user 气泡不丢）。 */
  const tailItems = React.useMemo<TaskThreadItem[]>(
    () => sliceTailItems(items, tailStartSeq, matches),
    [items, tailStartSeq, matches],
  )

  /** 尾轮 user 项（items 中本条；foldRound 已由 rounds.jsonl userMessage 插入）。 */
  const tailUserItem = React.useMemo<TaskThreadItem | undefined>(() => {
    if (!tailStartSeq) return undefined
    return items.find(
      (it) => it.type === 'agent_message' && it.message.messageId === `m-${tailStartSeq}`,
    )
  }, [items, tailStartSeq])

  const errorBar = roundsError ? (
    <div className="task-rounds__error" role="alert">
      <span className="task-rounds__error-text">{roundsError}</span>
      <button
        type="button"
        className="task-rounds__retry"
        onClick={() => void stream?.reloadRounds()}
      >
        重试
      </button>
    </div>
  ) : null

  const loadingBlock = !roundsResult && !roundsError ? (
    <div className="task-rounds__loading" role="status">
      <InlineSpinner size={14} />
      <span>正在加载任务线程…</span>
    </div>
  ) : null

  const emptyBlock =
    roundsResult && closedRounds.length === 0 && !tailStartSeq && !roundsError ? (
      <div className="nagent-empty">{emptyText ?? '当前 Task 还没有对话轮次'}</div>
    ) : null

  return (
    <TaskWorkspaceProvider workspaceRoot={workspaceRoot}>
      <div className="task-rounds">
        {loadingBlock}
        {roundsError ? errorBar : null}
        {emptyBlock}
        {filterAgentId ? (
          <div className="task-rounds__filter-hint" role="status">
            仅显示已加载内容中该 agent 的消息，展开轮次可加载更多
          </div>
        ) : null}
        {closedRounds.map((round) => {
          const expanded = expandedSet.has(round.roundId)
          const pageState = pageStates[round.roundId]
          return (
            <ClosedRoundView
              key={round.roundId}
              round={round}
              expanded={expanded}
              items={items}
              taskId={taskId}
              workerId={workerId}
              workspaceRoot={workspaceRoot}
              mainAgentId={mainAgentId}
              pageState={pageState}
              scrollRoot={scrollRoot}
              matches={matches}
              onToggle={() => handleToggle(round)}
              onLoadMore={() => startForward(round)}
            />
          )
        })}
        {tailStartSeq ? (
          <TailRoundView
            taskId={taskId}
            userItem={tailUserItem}
            tailItems={tailItems}
            isGenerating={isGenerating}
            live={live}
            mainAgentId={mainAgentId}
            pageState={live ? pageStates['open'] : (terminalTail ? pageStates[terminalTail.roundId] : undefined)}
            scrollRoot={scrollRoot}
            onLoadMoreBackward={() => {
              const cur = pageStatesRef.current['open']
              if (cur && cur.cursor) startBackward(tailStartSeq, cur.cursor)
            }}
            onLoadMoreForward={() => terminalTail && startForward(terminalTail)}
          />
        ) : null}
      </div>
    </TaskWorkspaceProvider>
  )
}

/** 懒加载分页状态（每轮/尾轮一份）。 */
interface RoundPageState {
  /** 续拉方向。 */
  dir: 'forward' | 'backward'
  /** 下一拉取游标 seq（forward=上次批尾 seq；backward=上次批首 seq）。 */
  cursor: string
  /** 是否正在拉取。 */
  loading: boolean
  /** 是否已无更多。 */
  done: boolean
}

/** 单个已闭合轮的视图：user 气泡 + 折叠标记（有过程内容时）+ 折叠态最终回复 / 展开态过程。 */
function ClosedRoundView({
  round,
  expanded,
  items,
  taskId,
  workerId,
  workspaceRoot,
  mainAgentId,
  pageState,
  scrollRoot,
  matches,
  onToggle,
  onLoadMore,
}: {
  round: RoundSummary
  expanded: boolean
  items: TaskThreadItem[]
  taskId: string
  workerId?: string
  workspaceRoot?: string
  mainAgentId?: string
  pageState?: RoundPageState
  scrollRoot?: HTMLElement | null
  /** agent 过滤谓词（「只看该 agent」）：透传 RoundDetail，只作用于展开态过程内容。 */
  matches: (item: TaskThreadItem) => boolean
  onToggle: () => void
  onLoadMore: () => void
}): React.ReactNode {
  // 折叠态恒用 rounds.jsonl 摘要(finalReply 只含正文,不含 thinking):
  // 旧版同款行为。懒加载虽然会把 endSeq 权威 message(含 thinking/toolCalls)折入 items,
  // 但折叠态渲染不看 items,避免思考内容泄漏;展开态由 RoundDetail 从 items 切片渲染
  // 权威 message(完整 thinking + toolCalls)。
  const userMessage = syntheticUserRecord(round)
  const finalMessage = syntheticFinalRecord(round)
  const roundTailPanels = pluginDispatcher.listRegisteredRoundTailPanels()
  // 折叠条对「已闭合轮」恒显(与旧版一致,决策 1「user+折叠条+final 常显」);
  // 展开态由 RoundDetail 从 items 切片渲染,即使无工具调用也能看到最终轮的思考内容。
  return (
    <>
      <AgentMessageThread message={userMessage} taskId={taskId} mainAgentId={mainAgentId} />
      <div className="nagent-round-collapse">
        <button
          type="button"
          className="nagent-round-collapse__marker"
          aria-expanded={expanded}
          onClick={onToggle}
          title={expanded ? '收起 AI 过程内容' : '展开 AI 过程内容'}
        >
          <span className="nagent-round-collapse__brand">
            <BrandMark size={12} animated={false} />
            <span className="nagent-round-collapse__brand-name">Every Agent</span>
          </span>
          {/* 折叠/展开态均在折叠图标左侧显示本轮耗时（rounds.jsonl durationMs 回填）。 */}
          {(round.durationMs ?? 0) > 0 ? (
            <span className="nagent-round-collapse__duration">
              Done in {formatRoundDuration(round.durationMs ?? 0)}
            </span>
          ) : null}
          <span className="nagent-round-collapse__marker-chevron" aria-hidden="true">
            {expanded ? <ChevronDownIcon size={12} /> : <ChevronRightIcon size={12} />}
          </span>
        </button>
        {expanded ? (
          <>
            <RoundDetail
              round={round}
              items={items}
              taskId={taskId}
              loading={pageState?.loading ?? false}
              mainAgentId={mainAgentId}
              matches={matches}
            />
            {pageState && !pageState.done ? (
              <LazyLoadSentinel
                dir="forward"
                seq={pageState.cursor}
                loading={pageState.loading}
                onVisible={onLoadMore}
                root={scrollRoot}
              />
            ) : null}
          </>
        ) : (
          <div className="nagent-round-collapse__final">
            <AgentMessageThread message={finalMessage} taskId={taskId} mainAgentId={mainAgentId} />
          </div>
        )}
        {/* 轮末工具栏：`ui.round_tail_panels` 的全部贡献者（含核心内置的「复制」按钮）并排一行，
            折叠/展开两态共用、恒在该轮最后。核心「复制」与插件面板同走这一条渲染路径，
            不在此硬编码任何一块内容。
            栅格：本行是一条 wrap flex 行，贡献者的**根元素直接落进本行**（宿主不再包一层
            中立包装）——面板因此能把「汇总头」与「展开详情」拆成两个并列项：汇总头按内容宽度
            留在标签行，详情写 `flex: 1 1 100%` 必然另起一行、通栏占满整行宽度（标签页式：
            上行为标签、下行详情区）。宿主只兜底 `min-width: 0`，防 nowrap 长内容撑破行宽。 */}
        <div className="nagent-round-collapse__tail">
          {roundTailPanels.map((panel) => {
            const Component = panel.Component
            return (
              <Component
                key={panel.pluginId}
                taskId={taskId}
                roundId={round.roundId}
                workerId={workerId}
                workspaceRoot={workspaceRoot}
                round={round}
              />
            )
          })}
        </div>
      </div>
    </>
  )
}

/**
 * 尾轮视图（运行中未闭合尾轮 / 终态遗留未闭合尾轮）：
 * - 运行中：user 气泡 + 顶部 backward sentinel（上翻历史）+ 过程平铺 + 运行状态横幅；
 * - 终态：user 气泡 + 过程平铺 + 底部 forward sentinel（向下续拉）。
 */
function TailRoundView({
  taskId,
  userItem,
  tailItems,
  isGenerating,
  live,
  mainAgentId,
  pageState,
  scrollRoot,
  onLoadMoreBackward,
  onLoadMoreForward,
}: {
  taskId: string
  userItem?: TaskThreadItem
  tailItems: TaskThreadItem[]
  isGenerating: boolean
  live: boolean
  mainAgentId?: string
  pageState?: RoundPageState
  scrollRoot?: HTMLElement | null
  onLoadMoreBackward: () => void
  onLoadMoreForward: () => void
}): React.ReactNode {
  // 剥掉尾轮起点 user 气泡：它已由上层 userItem(tailUserItem) 单独渲染，留在 tailItems
  // 里会重复。但只剥「本轮起点那条」(messageId === userItem)，不能只看 role==='user' ——
  // agent 过滤态下起点 user 气泡(主 agent)被 matches 滤出 tailItems，此时 tailItems[0]
  // 可能是子 agent 的首条 user.message(刚启动时给该 agent 的指令)，按 role 误剥就会
  // 把它丢掉(后续续跑的 user.message 不在 [0] 故不受影响、正常显示)。
  const afterUser = tailItemsAfterUser(tailItems, userItem)
  return (
    <div className="nagent-tail-round">
      {userItem && userItem.type === 'agent_message' ? (
        <AgentMessageThread message={userItem.message} taskId={taskId} mainAgentId={mainAgentId} />
      ) : null}
      {live && pageState && !pageState.done ? (
        <LazyLoadSentinel
          dir="backward"
          seq={pageState.cursor}
          loading={pageState.loading}
          onVisible={onLoadMoreBackward}
          root={scrollRoot}
        />
      ) : null}
      {afterUser.length > 0 ? (
        <TaskThread taskId={taskId} items={afterUser} isGenerating={isGenerating} mainAgentId={mainAgentId} />
      ) : isGenerating ? (
        <TaskThread taskId={taskId} items={[]} isGenerating={isGenerating} mainAgentId={mainAgentId} />
      ) : null}
      {!live && pageState && !pageState.done ? (
        <LazyLoadSentinel
          dir="forward"
          seq={pageState.cursor}
          loading={pageState.loading}
          onVisible={onLoadMoreForward}
          root={scrollRoot}
        />
      ) : null}
    </div>
  )
}

/** 毫秒格式化为紧凑耗时文案（如 3s、1m5s），折叠标记旁展示。 */
function formatRoundDuration(ms: number): string {
  const totalSeconds = Math.max(1, Math.round(ms / 1000))
  if (totalSeconds < 60) {
    return `${totalSeconds}s`
  }
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  if (hours > 0) {
    const parts: string[] = [`${hours}h`]
    if (minutes > 0) parts.push(`${minutes}m`)
    if (seconds > 0) parts.push(`${seconds}s`)
    return parts.join('')
  }
  return seconds === 0 ? `${minutes}m` : `${minutes}m${seconds}s`
}
