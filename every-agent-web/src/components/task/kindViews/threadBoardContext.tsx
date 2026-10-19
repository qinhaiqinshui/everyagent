/**
 * 画板 context(「任务线程统一条目与画板纯化」步骤 9 基建,纯增量)。
 *
 * 为 kind 视图提供**兄弟行访问**:KindViewProps 保持最小集(item/payload/taskId/
 * mainAgentId),视图需要同画板内其他线程条目(如步骤 10 tool 域 resultsFor/
 * absorbedIntoMessage 的 tool.result 吸收判定)时经 useThreadBoard() 取,而非往
 * props 塞 items/回调。步骤 10 分发切换时由线程渲染根挂载 Provider。
 *
 * 缺省 Provider 时 hook 返回空 items 并 console.warn 一次(模块级 once 标记,
 * 防误用不刷屏)——兄弟行访问将不可用,但不炸渲染。
 */
import React from 'react'
import type { ThreadItem } from '@/task/threadItem'

/** 画板上下文值(items = 当前画板内的全部线程项,按 seq 升序)。 */
export interface ThreadBoardValue {
  items: readonly ThreadItem[]
}

const EMPTY_BOARD: ThreadBoardValue = { items: [] }

export const ThreadBoardContext = React.createContext<ThreadBoardValue | undefined>(undefined)

let warnedNoProvider = false

/** 提供画板上下文(线程渲染根包裹;items 引用稳定时 context value 保持稳定)。 */
export function ThreadBoardProvider({
  items,
  children,
}: {
  items: readonly ThreadItem[]
  children: React.ReactNode
}) {
  const value = React.useMemo<ThreadBoardValue>(() => ({ items }), [items])
  return <ThreadBoardContext.Provider value={value}>{children}</ThreadBoardContext.Provider>
}

/** 读取画板上下文(兄弟行访问)。缺省 Provider 返回空 items 并 console.warn 一次。 */
export function useThreadBoard(): ThreadBoardValue {
  const value = React.useContext(ThreadBoardContext)
  if (value === undefined) {
    if (!warnedNoProvider) {
      warnedNoProvider = true
      // eslint-disable-next-line no-console
      console.warn(
        'useThreadBoard() 在没有 ThreadBoardProvider 的渲染树上调用,返回空 items;兄弟行访问(tool.result 吸收判定等)将不可用。',
      )
    }
    return EMPTY_BOARD
  }
  return value
}
