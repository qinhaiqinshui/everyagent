import React from 'react'
import type {
  FileTabOpenMode,
  OpenWorkspaceFileOptions,
  SidebarPanelId,
  TaskChatTabInput,
  WorkspaceFileTab,
  WorkspaceTab,
  WorkspaceTaskChatTab,
} from '@/types'

/**
 * 工作区壳层上下文。
 * 只管理界面结构态，不承载业务数据读取职责。
 */
export interface WorkspaceShellState {
  /** 侧边栏是否展开。 */
  sidebarOpen: boolean
  /** 当前激活的侧边栏面板。 */
  activeSidebarPanelId: SidebarPanelId
  /** 当前已打开的统一顶层标签。 */
  workspaceTabs: WorkspaceTab[]
  /** 当前激活的统一顶层标签 ID。 */
  activeWorkspaceTabId: WorkspaceTab['id'] | null
  /** 当前激活的统一顶层标签。 */
  activeWorkspaceTab: WorkspaceTab | null
  /** 当前已打开的文件标签。 */
  workspaceFileTabs: WorkspaceFileTab[]
  /** 当前选中的文件路径。 */
  selectedFilePath: string | null
  /** 侧边栏文件定位请求(workspaceRoot 为空时由面板自行选组)。 */
  workspaceFileLocateRequest: { filePath: string; workspaceRoot: string | null; requestedAt: number } | null
}

/**
 * 工作区壳层动作集合。
 * 供各层组件直接表达 UI 导航意图。
 */
export interface WorkspaceShellActions {
  /** 设置侧边栏显隐。 */
  setSidebarOpen: (open: boolean) => void
  /** 设置当前激活的侧边栏面板。 */
  setActiveSidebarPanel: (panelId: SidebarPanelId) => void
  /** 打开顶层文件标签(多工作区并行,须指明所属工作区根)。 */
  openGlobalFileTab: (
    target: {
      workspaceRoot: string
      filePath: string
    },
    options?: OpenWorkspaceFileOptions,
  ) => `file:${string}` | null
  /** 关闭顶层文件标签。 */
  closeGlobalFileTab: (fileTabId: `file:${string}`) => void
  /** 激活顶层文件标签。 */
  setActiveGlobalFileTab: (fileTabId: `file:${string}`) => void
  /** 更新顶层文件标签打开模式。 */
  setGlobalFileTabMode: (fileTabId: `file:${string}`, mode: FileTabOpenMode) => void
  /** 激活统一顶层标签。 */
  setActiveWorkspaceTab: (tabId: WorkspaceTab['id']) => void
  /** 关闭统一顶层标签。 */
  closeWorkspaceTab: (tabId: WorkspaceTab['id']) => void
  /** 批量重命名已打开文件标签中匹配旧路径的标签为新路径(限指定工作区)。 */
  renameFileTabs: (workspaceRoot: string, oldPath: string, newPath: string) => void
  /** 请求工作区文件标签进入改名态。 */
  setWorkspaceFileNameEditRequested: (fileTabId: `file:${string}`, requestedAt?: number) => void
  /** 请求工作区文件标签定位到指定行。 */
  setWorkspaceFileLineLocateRequested: (fileTabId: `file:${string}`, requestedAt?: number, lineNumber?: number) => void
  /** 请求侧边栏定位到某个文件(workspaceRoot 缺省时面板选首个分组)。 */
  requestWorkspaceFileLocate: (filePath: string, workspaceRoot?: string, requestedAt?: number) => void
  /** 清空侧边栏文件定位请求。 */
  clearWorkspaceFileLocateRequest: () => void
  /** 打开 Task 聊天标签。 */
  openTaskChatTab: (tab: TaskChatTabInput) => WorkspaceTaskChatTab['id']
  /** 打开插件自定义标签。 */
  openPluginTab: (pluginTabType: string, data: Record<string, string>, title?: string) => string | null
  /** 打开顶级文件差异对比标签（diff 面板，接受展示参数）。 */
  openDiffTab: (input: {
    /** 文件路径。 */
    filePath: string
    /** 文件名。 */
    fileName: string
    /** 变更类型。 */
    changeType: 'created' | 'updated'
    /** 变更前内容。 */
    beforeContent: string
    /** 变更后内容。 */
    afterContent: string
    /** 差异所属的工作区根(「打开文件」定位用;缺省时回退注册表首项)。 */
    workspaceRoot?: string
    /** 标签标题（缺省为「变更对比：${fileName}」）。 */
    title?: string
  }) => string
}

type WorkspaceShellContextValue = WorkspaceShellState & WorkspaceShellActions

const WorkspaceShellContext = React.createContext<WorkspaceShellContextValue | null>(null)

/**
 * 提供工作区壳层上下文。
 */
export function WorkspaceShellProvider({
  value,
  children,
}: {
  value: WorkspaceShellContextValue
  children: React.ReactNode
}) {
  return (
    <WorkspaceShellContext.Provider value={value}>
      {children}
    </WorkspaceShellContext.Provider>
  )
}

/**
 * 读取工作区壳层上下文。
 */
export function useWorkspaceShell(): WorkspaceShellContextValue {
  const context = React.useContext(WorkspaceShellContext)
  if (!context) {
    throw new Error('useWorkspaceShell 必须在 WorkspaceShellProvider 内使用')
  }
  return context
}
