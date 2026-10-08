/**
 * 任务搜索结果树(薄适配器):TaskContentSearchResult → SearchResultGroup[]
 * 交给通用 SearchResultTreeView(resultTree/)渲染。
 * 一个任务一组(组头 = 任务标题 + 状态标签,折叠键 = 任务 ID),命中行 =
 * 「#轮次 用户输入/AI 回复」前缀 + 高亮正文,点击打开对应任务聊天页。
 * 对外 props 与渲染结果保持不变(SearchPanel 接线零改动);高亮 / 截断 /
 * 折叠 / 组头样式等公共实现单点在 resultTree/。
 */
import React from 'react'
import SearchResultTreeView from './resultTree/SearchResultTreeView'
import type { SearchResultGroup } from './resultTree/model'
import type {
  TaskContentSearchHit,
  TaskContentSearchResult,
  TaskContentSearchTaskResult,
} from '@/query/taskContentSearch'

export interface TaskSearchResultsTreeProps {
  /** 任务搜索结果(按任务聚合)。 */
  result: TaskContentSearchResult
  /** 处于折叠态的任务 ID 集合(不在集合内 = 展开),由面板统一持有。 */
  collapsedTasks: ReadonlySet<string>
  /** 切换某任务分组的折叠态。 */
  onToggleTask: (taskId: string) => void
  /** 点击命中行:打开任务聊天页。 */
  onOpenTask: (task: TaskContentSearchTaskResult) => void
}

/** 命中字段展示名。 */
function fieldLabel(field: TaskContentSearchHit['field']): string {
  return field === 'user' ? '用户输入' : 'AI 回复'
}

/** worker 状态串 → 短标签(仅展示;未知原样显示)。 */
function statusLabel(status: string): string {
  switch (status) {
    case 'done':
      return '完成'
    case 'running':
      return '运行中'
    case 'waiting-user':
      return '等待用户'
    case 'failed':
      return '失败'
    case 'cancelled':
      return '已取消'
    default:
      return status
  }
}

/** 任务结果 → 通用分组(组头 = 标题 + 状态 + 命中数;命中行前缀 = 轮次 + 字段)。 */
function toTaskGroups(
  result: TaskContentSearchResult,
  onOpenTask: (task: TaskContentSearchTaskResult) => void,
): SearchResultGroup[] {
  return result.files.map((task) => {
    const matches = task.matches ?? []
    return {
      key: task.taskId,
      header: {
        title: task.title,
        name: task.title,
        detail: task.status ? { text: statusLabel(task.status) } : undefined,
        // 来源 provider id 透传(缺省/内置 rg 由通用视图静默,外部 provider 显示来源标记)。
        ...(task.providerId ? { providerId: task.providerId } : {}),
      },
      hits: matches.map((hit) => ({
        label: hit.line,
        prefix: { text: `#${hit.roundIndex} ${fieldLabel(hit.field)}`, minWidth: 84 },
        matchIndex: hit.matchIndex ?? -1,
        matchText: hit.matchText ?? '',
        title: `${task.title} · 第 ${hit.roundIndex} 轮 ${fieldLabel(hit.field)}`,
        onOpen: () => onOpenTask(task),
      })),
    }
  })
}

/** 任务搜索结果树:按任务分组的通用结果树渲染。 */
export default function TaskSearchResultsTree({
  result,
  collapsedTasks,
  onToggleTask,
  onOpenTask,
}: TaskSearchResultsTreeProps) {
  const groups = React.useMemo(() => toTaskGroups(result, onOpenTask), [result, onOpenTask])
  return (
    <SearchResultTreeView
      groups={groups}
      collapsedKeys={collapsedTasks}
      onToggleGroup={onToggleTask}
    />
  )
}
