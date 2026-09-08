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
}

export default function RoundDetail({
  round,
  items,
  taskId,
  isGenerating = false,
  loading = false,
}: RoundDetailProps): React.ReactNode {
  const slice = React.useMemo<TaskThreadItem[]>(
    () => sliceRound(items, round),
    [items, round.startSeq, round.endSeq],
  )

  return (
    <div className="nagent-round-detail nagent-round-collapse__process">
      {loading ? (
        <div className="nagent-empty">
          <InlineSpinner size={14} />
          <span>正在加载第 {round.index} 轮过程内容…</span>
        </div>
      ) : slice.length > 0 ? (
        <TaskThread taskId={taskId} items={slice} isGenerating={isGenerating} />
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
