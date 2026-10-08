/**
 * 搜索目标 · 任务内容(tasks)。
 *
 * 数据源:worker 侧 task.search(rg + 后处理),不走文件 walk/fs.search;范围沿用
 * 当前 worker + 当前工作区(workspaceId),文件语义的搜索范围/glob 过滤器/文件名
 * 模式均不适用(面板已按 supports 隐藏,这里也不读取)。
 */
import { searchTasksContent, type TaskContentSearchResult } from '@/query/taskContentSearch'
import type { WorkspaceSearchResultShape } from '../useWorkspaceSearch'
import type { SearchTargetDefinition } from './types'

/** 任务搜索命中上限(与既有行为一致)。 */
const MAX_RESULTS = 500

export const tasksTarget: SearchTargetDefinition = {
  id: 'tasks',
  label: '搜索任务内容',
  supports: { regex: true, wholeWord: true, fileNameMode: false, globs: false, scope: false },
  highlightMenuWhenCurrent: true,
  resultTree: 'tasks',
  placeholder: () => '搜索任务内容（支持正则）',
  introHint: () => '输入关键词搜索当前工作区的任务内容',
  buildParams(state) {
    // 前置校验:任务内容搜索必须显式归属 worker 与工作区(缺一不发起,提示与既有文案一致)。
    if (!state.workerId) {
      return { error: '请先选择 worker' }
    }
    if (!state.workspaceId) {
      return { error: '当前工作区不支持任务内容搜索（缺少 workspaceId）' }
    }
    return {
      kind: 'tasks',
      workerId: state.workerId,
      params: {
        workspaceId: state.workspaceId,
        pattern: state.pattern.trim(),
        isRegex: state.useRegex,
        caseSensitive: state.caseSensitive,
        wholeWord: state.wholeWord,
        maxResults: MAX_RESULTS,
      },
    }
  },
  async execute(execution): Promise<WorkspaceSearchResultShape> {
    if (execution.kind !== 'tasks') {
      throw new Error(`tasks 搜索目标收到非法执行参数：${execution.kind}`)
    }
    return searchTasksContent(execution.workerId, execution.params)
  },
  summarize(result) {
    // 任务结果与文件结果同构(files 语义 = 任务),摘要沿用「N 个结果 · M 个文件」表述。
    const taskResult = result as TaskContentSearchResult
    const truncatedSuffix = taskResult.truncated ? '（已达上限，结果被截断）' : ''
    return `${taskResult.matchCount} 个结果 · ${taskResult.files.length} 个文件${truncatedSuffix}`
  },
  resultGroups(result) {
    return (result as TaskContentSearchResult).files.map((task) => ({
      key: task.taskId,
      matches: task.matches?.length ?? 0,
    }))
  },
}
