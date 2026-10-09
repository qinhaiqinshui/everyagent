/**
 * 统一结果渲染视图。
 *
 * 按「类型是否有 `ResultView`」二选一：
 * - 缺省 → 走既有 `SearchResultTreeView`（默认适配器把 items 按 `(kind, groupKey)` 分组）；
 * - 提供 `ResultView` 的类型 → 该类型区块交给插件组件渲染（组件只依赖 plugin-api 与 props）。
 *
 * 「全部」模式下多个 kind 各成区块（按类型 `order` 排序，平铺拼接）。
 */
import React from 'react'
import SearchResultTreeView from './resultTree/SearchResultTreeView'
import { buildSearchResultGroups } from './searchTypes/groupAdapter'
import { mapKindsToTypes } from './searchTypes/registry'
import type { UiSearchTypeDefinition } from '@/plugin/types'
import type { UnifiedSearchItem } from '@/query/search'
import type { SearchResultGroup } from './resultTree/model'

export interface SearchResultsViewProps {
  /** 平铺命中项。 */
  items: UnifiedSearchItem[]
  /** 全部已注册类型（用于 kind → 类型归属与 `ResultView` 派发）。 */
  types: UiSearchTypeDefinition[]
  /** 处于折叠态的 groupKey 集合（默认树用）。 */
  collapsedKeys: ReadonlySet<string>
  /** 切换某分组的折叠态。 */
  onToggleGroup: (key: string) => void
  /** 打开文件（工作区相对/业务路径 + 可选行号）。 */
  openFile: (path: string, lineNumber?: number) => void
  /** 打开任务聊天页。 */
  openTask: (taskId: string, title?: string) => void
  /** 当前搜索词。 */
  pattern: string
  /** 当前选中工作区根。 */
  workspaceRoot: string
}

/** 按 (kind, groupKey) 归组，并按 kind 分桶（供默认树按类型分块渲染）。 */
function groupByKind(items: UnifiedSearchItem[], openFile: SearchResultsViewProps['openFile'], openTask: SearchResultsViewProps['openTask']): Map<string, SearchResultGroup[]> {
  const groups = buildSearchResultGroups(items, { openFile, openTask })
  const byKind = new Map<string, SearchResultGroup[]>()
  for (const group of groups) {
    const kind = group.kind ?? ''
    const list = byKind.get(kind)
    if (list) {
      list.push(group)
    } else {
      byKind.set(kind, [group])
    }
  }
  return byKind
}

/** 命中项按 kind 分桶（保持首现顺序，供插件 `ResultView` 消费）。 */
function itemsByKind(items: UnifiedSearchItem[]): Map<string, UnifiedSearchItem[]> {
  const byKind = new Map<string, UnifiedSearchItem[]>()
  for (const item of items) {
    const list = byKind.get(item.kind)
    if (list) {
      list.push(item)
    } else {
      byKind.set(item.kind, [item])
    }
  }
  return byKind
}

/**
 * 统一结果视图：按类型逐块渲染（默认树 / 插件 ResultView）。
 */
export default function SearchResultsView({
  items,
  types,
  collapsedKeys,
  onToggleGroup,
  openFile,
  openTask,
  pattern,
  workspaceRoot,
}: SearchResultsViewProps) {
  const typeByKind = React.useMemo(() => mapKindsToTypes(types), [types])
  const groupsByKind = React.useMemo(() => groupByKind(items, openFile, openTask), [items, openFile, openTask])
  const kindItems = React.useMemo(() => itemsByKind(items), [items])

  /** 区块顺序：按所属类型 order 升序（未知 kind 落後），同 order 保持首现顺序（稳定排序）。 */
  const orderedKinds = React.useMemo(() => {
    const kinds = [...kindItems.keys()]
    return kinds.sort((a, b) => (
      (typeByKind.get(a)?.order ?? 100) - (typeByKind.get(b)?.order ?? 100)
    ))
  }, [kindItems, typeByKind])

  return (
    <>
      {orderedKinds.map((kind) => {
        const type = typeByKind.get(kind)
        const kindItemsForBlock = kindItems.get(kind) ?? []
        if (type?.ResultView) {
          const ResultView = type.ResultView
          return (
            <ResultView
              key={kind}
              items={kindItemsForBlock}
              pattern={pattern}
              workspaceRoot={workspaceRoot}
              openFile={openFile}
              openTask={openTask}
            />
          )
        }
        return (
          <SearchResultTreeView
            key={kind}
            groups={groupsByKind.get(kind) ?? []}
            collapsedKeys={collapsedKeys}
            onToggleGroup={onToggleGroup}
          />
        )
      })}
    </>
  )
}