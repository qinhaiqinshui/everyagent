/**
 * 任务工作区上下文。
 *
 * 承载「当前任务挂靠的工作区根」（worker 机器绝对路径），供任务线程深层组件
 * （如文件类工具美化视图 FileToolEntry）把工具相对 path 解析为业务绝对路径后
 * 打开文件标签页，而无需沿 TaskRoundsPanel → RoundDetail → TaskThread →
 * AgentMessageThread → ToolCallView 多层 prop 透传（该链有多条内部 render 函数）。
 *
 * 由 TaskRoundsPanel 提供（它从 taskStore 取 workspace），是任务线程渲染的唯一根。
 * 未提供（如非任务场景）时返回 undefined，消费方据此降级（路径 chip 不可点）。
 */

import React from 'react'

const TaskWorkspaceContext = React.createContext<string | undefined>(undefined)

/** 提供当前任务的工作区根。 */
export function TaskWorkspaceProvider({
  workspaceRoot,
  children,
}: {
  workspaceRoot?: string
  children: React.ReactNode
}) {
  return (
    <TaskWorkspaceContext.Provider value={workspaceRoot}>
      {children}
    </TaskWorkspaceContext.Provider>
  )
}

/** 读取当前任务的工作区根；未提供时返回 undefined。 */
export function useTaskWorkspaceRoot(): string | undefined {
  return React.useContext(TaskWorkspaceContext)
}
