import React from 'react'
import type { RoundSummary } from '@/types'
import type { TaskThreadItem } from '@/task/eventFolder'
import { InlineSpinner } from '@/components/shared/ui'
import TaskThread from './TaskThread'

/**
 * 轮详情（纯视图）：按轮的 startSeq/endSeq 从「单一线程真相源 items」中切片渲染该轮过程内容。
 *
 * 不再自行 fetch / 建 folder / 轮询——数据只来自父层传入的 items（由 taskStream 统一折叠，
 * 展开某轮时父层懒加载分页把该轮过程事件折入同一份 items，本组件据此切片渲染）。
 *
 * 切片口径：
 * - 起始端点（seq=startSeq 的 user.message）不包含：外层 TaskRoundsPanel 已渲染 user 气泡。
 * - 终止端点（seq=endSeq 的最终回复）包含：展开态要展示带 thinking/reasoning 的最终回复。
 * - endSeq 为空（未闭合尾轮）时拉到 items 末尾（此场景由外层按尾轮处理，本组件通常只用于闭合轮）。
 *
 * 切片依赖 messageId（形如 `m-<seq>`）定位端点，items 恒按 seq 升序，故中间即为该轮过程内容。
 *
 * agent 过滤（「只看该 agent」）：matches 谓词非空时切片后再按归属过滤，仅显示该 agent 的
 * 过程项。纯渲染派生——只过滤已加载内容，不触发任何拉取；用户展开/滚动续拉折入的新内容
 * 经 items 引用变化自动纳入过滤。过滤后为空则不渲染线程（折叠条在外层照常）。
 */
export interface RoundDetailProps {
  /** 该轮摘要（rounds.jsonl 行）。 */
  round: RoundSummary
  /** 单一线程真相源（按 seq 升序）。 */
  items: TaskThreadItem[]
  /** 任务 ID。 */
  taskId: string
  /** 是否仍在生成（驱动 TaskThread 底部运行状态横幅）。 */
  isGenerating?: boolean
  /** 外部正在一次性拉取该轮区间（显示加载提示）。 */
  loading?: boolean
  /** agent 过滤谓词（「只看该 agent」）：仅放行归属该 agent 的线程项；缺省不过滤。 */
  matches?: (item: TaskThreadItem) => boolean
}

/** 加载指示延迟展示阈值(ms):本地 worker / 缓存命中时轮内容几十毫秒内即达,立即渲染
 *  指示块会在内容到达前闪现一帧大空盒再被内容顶掉(首次展开的「闪烁」),短加载不显示指示器。 */
const LOADING_HINT_DELAY_MS = 200

export default function RoundDetail({
  round,
  items,
  taskId,
  isGenerating = false,
  loading = false,
  matches,
}: RoundDetailProps): React.ReactNode {
  const slice = React.useMemo<TaskThreadItem[]>(
    () => (matches ? sliceRound(items, round).filter(matches) : sliceRound(items, round)),
    [items, round.startSeq, round.endSeq, matches],
  )

  /** 延迟加载指示:loading 持续超过阈值才显示;内容优先——分页续拉(loading 中)已有
   *  内容照常渲染,不再被加载块整体顶掉(旧版滚动续拉也会闪一下,同根因)。 */
  const [showLoading, setShowLoading] = React.useState(false)
  React.useEffect(() => {
    if (!loading) {
      setShowLoading(false)
      return
    }
    const timer = window.setTimeout(() => setShowLoading(true), LOADING_HINT_DELAY_MS)
    return () => window.clearTimeout(timer)
  }, [loading])

  return (
    <div className="nagent-round-detail nagent-round-collapse__process">
      {slice.length > 0 ? (
        <TaskThread taskId={taskId} items={slice} isGenerating={isGenerating} />
      ) : showLoading ? (
        <div className="nagent-round-detail__loading" role="status">
          <InlineSpinner size={14} />
          <span>正在加载第 {round.index} 轮过程内容…</span>
        </div>
      ) : null}
    </div>
  )
}

/** 从 items 切出 [startSeq 之后, endSeq]（含终点、不含起点）的线程项，并剥掉 foldRole。 */
function sliceRound(items: TaskThreadItem[], round: RoundSummary): TaskThreadItem[] {
  const startIdx = items.findIndex(
    (it) => it.type === 'agent_message' && it.message.messageId === `m-${round.startSeq}`,
  )
  let endIdx = -1
  if (round.endSeq) {
    endIdx = items.findIndex(
      (it) => it.type === 'agent_message' && it.message.messageId === `m-${round.endSeq}`,
    )
  }
  const from = startIdx >= 0 ? startIdx + 1 : 0
  const to = endIdx >= 0 ? endIdx + 1 : items.length
  return items.slice(from, to).map(stripFoldRole)
}

/** 剥掉 foldRole 折叠窗口标记：轮详情要求完整过程直接展开，不再被 TaskThread 折叠。 */
function stripFoldRole(item: TaskThreadItem): TaskThreadItem {
  return item.type === 'agent_message' && item.foldRole
    ? { ...item, foldRole: undefined }
    : item
}
