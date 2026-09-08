/**
 * 应用根布局(hub 版,自 n 分支裁剪)。
 *
 * 职责保持 n 的边界:维护壳层 UI 状态(标签/侧边栏/滚动保持),业务数据
 * 读取留给子组件。相比 n 的变化:
 * - 启动台/日志/扩展页移除(运行时已下沉 worker);顶层页只剩 设置/Git;
 * - 任务入口从「启动台聚焦」改为直接打开任务聊天标签;
 * - 新建任务 = 打开草稿标签(见 TaskChat 的草稿态);
 * - 启动引导从浏览器运行时初始化(zero-FS bootstrap)换成 hub 连接。
 */
import React from 'react'
import { App as AntApp, ConfigProvider } from 'antd'
import type {
  OpenWorkspaceFileOptions,
  SidebarPanelId,
  TaskChatTabInput,
  ThemeMode,
  TopLevelPageId,
  WorkspaceTab,
  WorkspaceTaskChatTab,
} from '@/types'
import { getAntdTheme } from '@/theme/antdTheme'
import {
  FilesIcon,
  GitIcon,
  SettingsIcon,
  TaskChatIcon,
} from '@/components/icon'
import SidebarActivityBar from './SidebarActivityBar'
import ResizableSidebarContainer from './ResizableSidebarContainer'
import TitleBar from './TitleBar'
import AntdAppBridge from './AntdAppBridge'
import ToastHost from './ToastHost'
import NotificationHost from './NotificationHost'
import UserInteractionHost from './UserInteractionHost'
import PendingUserInteractionIndicator from './PendingUserInteractionIndicator'
import BrowserNotificationHost from './BrowserNotificationHost'
import BrowserNotificationGuide from './BrowserNotificationGuide'
import { WorkspaceShellProvider } from './WorkspaceShellContext'
import { domainEventBus, DOMAIN_EVENTS } from '@/events/eventBus'
import { AppUiProvider, useAppUi } from './AppUiContext'
import { useThemeMode } from '@/hooks/useThemeMode'
import { useResponsiveViewport } from '@/hooks/useResponsiveViewport'
import type { WorkspaceTabRenderContext } from '@/plugin/types'
import { getTabDefinition, getWorkspaceTabTypeDefinition, loadWorkspaceTabTypeDefinitions } from '@/plugin/workspaceTabTypes'
import {
  setWorkspaceFileTabMode,
  setWorkspaceFileTabNameEditRequested,
  createWorkspaceTaskChatTab,
  createWorkspaceFileTab,
  createWorkspacePluginTab,
  createWorkspaceDiffTab,
  createWorkspacePageTab,
  filterWorkspaceTabs,
  removeWorkspaceTab,
  upsertWorkspaceTab,
} from './workspaceShellState'
import { BrandMark } from '../shared/BrandLoadingBlock'
import { createLazyRouteComponent, scheduleLazyRoutePreload } from '@/components/shared/LazyRouteView'
import { useHub } from '@/hub/HubProvider'
import { hubSession } from '@/hub/session'
import { taskStore } from '@/hub/taskStore'
import { taskStreamManager } from '@/hub/taskStream'
import { workspaceRegistry } from '@/hub/workspaceRegistry'
import { gitGateway } from '@/platform/git/gitGateway'
import { DRAFT_TASK_ID, setDraftPreset } from '@/components/task/taskChatDraft'

const LazyTasksPanel = createLazyRouteComponent(() => import('@/components/task/TasksPanel'))
const LazyOpenFilesSidebarPanel = createLazyRouteComponent(() => import('@/components/files/OpenFilesSidebarPanel'))
const LazyGitSidebarPanel = createLazyRouteComponent(() => import('@/components/git/GitSidebarPanel'))

/**
 * 计算移动端侧边栏允许的最大高度。
 */
function getMobileSidebarMaxHeight(): number {
  const viewportHeight = typeof window === 'undefined' ? 900 : window.innerHeight
  return Math.max(220, Math.floor(viewportHeight * 0.72) - 48)
}

/**
 * 移动端侧边栏默认高度：屏幕高度的 60%，且不超过允许的最大高度。
 */
function getMobileSidebarDefaultHeight(): number {
  const viewportHeight = typeof window === 'undefined' ? 900 : window.innerHeight
  return Math.min(Math.floor(viewportHeight * 0.6), getMobileSidebarMaxHeight())
}

/**
 * 应用根布局。
 * 负责维护壳层 UI 状态，并将业务数据读取留给子组件自行处理。
 */
export default function Layout({ initialThemeMode = 'light' }: { initialThemeMode?: ThemeMode }) {
  return (
    <AppUiProvider>
      <LayoutContent initialThemeMode={initialThemeMode} />
    </AppUiProvider>
  )
}

/**
 * 布局内部实现。
 * 放在 AppUiProvider 内部，确保启动期错误可直接走本地 UI 提示。
 */
function LayoutContent({ initialThemeMode }: { initialThemeMode: ThemeMode }) {
  const { showToast } = useAppUi()
  const hub = useHub()

  const themeMode = useThemeMode(initialThemeMode)
  const { isMobile } = useResponsiveViewport()
  const mobileBottomBarHeight = 'var(--mobile-bottom-bar-height)'
  const mobileSidebarMaxHeight = getMobileSidebarMaxHeight()

  const connected = hub.state === 'open'
  const hasWorker = hub.directory.some((w) => w.online && w.enabled && w.hasApiKey && !w.error && !w.connecting)

  const [sidebarOpen, setSidebarOpen] = React.useState(false)
  const [activeSidebarPanelId, setActiveSidebarPanelId] = React.useState<SidebarPanelId>('tasks')
  const [gitChangeCount, setGitChangeCount] = React.useState(0)
  const [desktopSidebarWidth, setDesktopSidebarWidth] = React.useState(312)
  const [mobileSidebarHeight, setMobileSidebarHeight] = React.useState(() => getMobileSidebarDefaultHeight())
  const [workspaceTabs, setWorkspaceTabs] = React.useState<WorkspaceTab[]>([])
  const [activeWorkspaceTabId, setActiveWorkspaceTabId] = React.useState<WorkspaceTab['id'] | null>(null)
  const [selectedFilePath, setSelectedFilePath] = React.useState<string | null>(null)
  const [workspaceFileLocateRequest, setWorkspaceFileLocateRequest] = React.useState<{ filePath: string; workspaceRoot: string | null; requestedAt: number } | null>(null)
  const prevIsMobileRef = React.useRef(isMobile)

  /**
   * hub 引导:连接(配置来自 localStorage)并启动任务列表镜像。
   * 失败不阻断 UI(设置页可重配),连接态经 HubProvider 消费展示。
   */
  React.useEffect(() => {
    void hubSession.ensureConnected().catch((error) => {
      console.warn('[layout] hub 连接失败:', error)
    })
    taskStore.start()
  }, [])

  /**
   * 致命连接错误(鉴权失败/版本不一致等)全局提示:不依赖用户停留在设置页,
   * 发生即 toast,详细信息可在设置页的 Hub 连接区查看并修正配置后重连。
   */
  React.useEffect(() => {
    if (hub.fatalError) {
      showToast(
        `连接失败:${hub.fatalError.code} — ${hub.fatalError.detail || '请检查 API Key / Hub 地址'}`,
        'error',
      )
    }
  }, [hub.fatalError, showToast])

  /**
   * 统一派生当前壳层标签视图，避免在 Layout 内分散维护多份同源计算。
   */
  const workspaceTabView = React.useMemo(() => {
    const activeTab = workspaceTabs.find((item) => item.id === activeWorkspaceTabId) ?? null
    const taskTabs = filterWorkspaceTabs(workspaceTabs, 'task')
    const fileTabs = filterWorkspaceTabs(workspaceTabs, 'file')
    const pageTabs = filterWorkspaceTabs(workspaceTabs, 'page')

    return {
      activeWorkspaceTab: activeTab,
      workspaceTaskChatTabs: taskTabs,
      workspaceFileTabs: fileTabs,
      openTopLevelPageIds: pageTabs.map((item) => item.pageId),
    }
  }, [activeWorkspaceTabId, workspaceTabs])
  const {
    activeWorkspaceTab,
    workspaceFileTabs,
    openTopLevelPageIds,
  } = workspaceTabView

  /** 侧边栏任务高亮 = 当前激活的任务聊天标签。 */
  const activeTaskId = activeWorkspaceTab?.tabType === 'task' && activeWorkspaceTab.taskId !== DRAFT_TASK_ID
    ? activeWorkspaceTab.taskId
    : null

  React.useEffect(() => {
    document.documentElement.setAttribute('data-theme', themeMode)
    document.body.setAttribute('data-theme', themeMode)
  }, [themeMode])

  React.useEffect(() => {
    if (prevIsMobileRef.current && !isMobile && !sidebarOpen) {
      setSidebarOpen(true)
    }
    if (!prevIsMobileRef.current && isMobile && sidebarOpen) {
      setSidebarOpen(false)
    }
    prevIsMobileRef.current = isMobile
  }, [isMobile, sidebarOpen])

  React.useEffect(() => {
    /**
     * 移动端最大高度随视口变化而变化，当前高度需要同步收敛到新上限内。
     */
    const handleResize = () => {
      const nextMaxHeight = getMobileSidebarMaxHeight()
      setMobileSidebarHeight((current) => Math.min(current, nextMaxHeight))
    }
    window.addEventListener('resize', handleResize, { passive: true })
    return () => {
      window.removeEventListener('resize', handleResize)
    }
  }, [])

  React.useEffect(() => {
    void loadWorkspaceTabTypeDefinitions()
  }, [])

  /**
   * 活动栏「源代码管理」徽标:全部注册工作区变更数之和(D16 无"当前工作区";
   * 未初始化/未连 worker 归零,单根失败按 0)。面板自身持有完整 status,
   * 此处只取计数供活动栏角标,不重复渲染 diff。
   */
  React.useEffect(() => {
    let cancelled = false
    const refreshGitBadge = async () => {
      const roots = workspaceRegistry.current?.workspaces.map((entry) => entry.root) ?? []
      if (roots.length === 0 || !connected || !hasWorker) {
        if (!cancelled) setGitChangeCount(0)
        return
      }
      try {
        const counts = await Promise.all(roots.map(async (root) => {
          try {
            const status = await gitGateway.status(root)
            return (['added', 'changed', 'modified', 'removed', 'missing', 'untracked', 'conflicting'] as const)
              .reduce((sum, key) => sum + (status[key]?.length ?? 0), 0)
          } catch {
            return 0
          }
        }))
        if (cancelled) return
        setGitChangeCount(counts.reduce((sum, count) => sum + count, 0))
      } catch {
        if (!cancelled) setGitChangeCount(0)
      }
    }
    void refreshGitBadge()
    const unsubRegistry = domainEventBus.subscribe(DOMAIN_EVENTS.WORKSPACE_REGISTRY_CHANGED, () => {
      void refreshGitBadge()
    })
    return () => {
      cancelled = true
      unsubRegistry()
    }
  }, [connected, hasWorker, hub.resyncVersion])

  /** 首屏渲染后空闲预加载后续页面资源。 */
  React.useEffect(() => (
    scheduleLazyRoutePreload([
      LazyTasksPanel.preload,
      LazyOpenFilesSidebarPanel.preload,
      () => import('@/components/system/SettingsPanel'),
      () => import('@/components/git/GitSidebarPanel'),
      () => import('@/components/files/FileTabPage'),
      () => import('@/components/task/TaskChat'),
    ])
  ), [])

  React.useEffect(() => {
    const validPanelIds = new Set<SidebarPanelId>(['tasks', 'files', 'git'])
    if (!validPanelIds.has(activeSidebarPanelId)) {
      setActiveSidebarPanelId('tasks')
    }
  }, [activeSidebarPanelId])

  const setActiveSidebarPanel = React.useCallback((panelId: SidebarPanelId) => {
    setActiveSidebarPanelId(panelId)
    setSidebarOpen(true)
  }, [])

  const updateSelectedFilePath = React.useCallback((filePath: string | null) => {
    setSelectedFilePath(filePath)
  }, [])

  /** 标签渲染 / 生命周期上下文（壳层统一提供，内容不感知）。 */
  const closeWorkspaceTabNowRef = React.useRef<(tabId: WorkspaceTab['id']) => void>(() => {})
  const tabRenderCtx = React.useMemo<WorkspaceTabRenderContext>(() => ({
    closeTab: (id) => closeWorkspaceTabNowRef.current(id),
    setSelectedFilePath: (path) => updateSelectedFilePath(path),
    showToast: (message, type) => showToast(message, type),
  }), [updateSelectedFilePath, showToast])

  const tabPanelNodes = React.useRef<Map<string, HTMLDivElement>>(new Map())
  const tabScrollPositions = React.useRef<Map<string, { top: number; left: number }>>(new Map())
  const activeWorkspaceTabIdRef = React.useRef(activeWorkspaceTabId)
  React.useEffect(() => {
    activeWorkspaceTabIdRef.current = activeWorkspaceTabId
  }, [activeWorkspaceTabId])

  const findTabScrollContainer = React.useCallback((tabId: string): HTMLElement | null => {
    const root = tabPanelNodes.current.get(tabId)
    if (!root) return null
    let best: HTMLElement | null = null
    let bestScrollHeight = -1
    const walk = (element: HTMLElement) => {
      const computed = getComputedStyle(element)
      const canScrollY = computed.overflowY === 'auto' || computed.overflowY === 'scroll' || computed.overflow === 'auto' || computed.overflow === 'scroll'
      if (canScrollY && element.scrollHeight > element.clientHeight + 1) {
        if (element.scrollHeight > bestScrollHeight) {
          bestScrollHeight = element.scrollHeight
          best = element
        }
      }
      for (const child of Array.from(element.children) as HTMLElement[]) {
        walk(child)
      }
    }
    walk(root)
    return best
  }, [])

  const recordTabScroll = React.useCallback((tabId: string) => {
    const container = findTabScrollContainer(tabId)
    if (container) {
      tabScrollPositions.current.set(tabId, { top: container.scrollTop, left: container.scrollLeft })
    }
  }, [findTabScrollContainer])

  const restoreTabScroll = React.useCallback((tabId: string) => {
    const position = tabScrollPositions.current.get(tabId)
    if (!position) return
    const container = findTabScrollContainer(tabId)
    if (!container) return
    const maxTop = Math.max(0, container.scrollHeight - container.clientHeight)
    container.scrollTop = Math.min(position.top, maxTop)
    container.scrollLeft = position.left
  }, [findTabScrollContainer])

  React.useLayoutEffect(() => {
    if (activeWorkspaceTabId) {
      restoreTabScroll(activeWorkspaceTabId)
    }
  }, [activeWorkspaceTabId, restoreTabScroll])

  const activateWorkspaceTab = React.useCallback((tab: WorkspaceTab | null, options?: { collapseSidebar?: boolean }) => {
    if (!tab) return
    const leavingId = activeWorkspaceTabIdRef.current
    if (leavingId && leavingId !== tab.id) {
      recordTabScroll(leavingId)
    }
    setActiveWorkspaceTabId(tab.id)
    getTabDefinition(tab)?.onActivate?.(tab, tabRenderCtx)
    if (options?.collapseSidebar && isMobile) {
      setSidebarOpen(false)
    }
  }, [isMobile, tabRenderCtx])

  /**
   * 统一「打开 / 复用」入口。
   *
   * 标签 `id` 即身份：打开方在调用处已用内容参数构造好稳定的 `id`
   * （如文件页 `file:${filePath}`、任务页 `task:${taskId}`、页面 `page:${pageId}`），
   * 本函数只负责：按 `id` 精确匹配——已存在则以其整体（含最新参数）替换、
   * 不存在则新增，两种情况下都激活该标签。
   *
   * 复用同一 `id` 时壳层只切换 `display:none`（激活），不卸载内容组件，
   * 滚动 / 草稿 / 流式进度等内部状态保留。
   */
  const openWorkspaceTab = React.useCallback((
    tab: WorkspaceTab,
    options?: { collapseSidebar?: boolean },
  ) => {
    if (!tab) return
    setWorkspaceTabs((current) => upsertWorkspaceTab(current, tab))
    activateWorkspaceTab(tab, { collapseSidebar: options?.collapseSidebar ?? true })
  }, [activateWorkspaceTab])

  const closeWorkspaceTabNow = React.useCallback((tabId: WorkspaceTab['id']) => {
    // 关闭任务标签页:退订该任务 stream 频道(hub 通知 worker 销毁 DataPusher,释放资源)。
    if (tabId.startsWith('task:')) {
      taskStreamManager.close(tabId.slice('task:'.length))
    }
    setWorkspaceTabs((current) => {
      const index = current.findIndex((item) => item.id === tabId)
      if (index < 0) return current
      const nextTabs = removeWorkspaceTab(current, tabId)
      const nextActiveTab = resolveNextActiveWorkspaceTab(current, nextTabs, tabId)
      if (activeWorkspaceTabId === tabId) {
        setActiveWorkspaceTabId(nextActiveTab?.id ?? null)
        if (nextActiveTab) {
          getTabDefinition(nextActiveTab)?.onActivate?.(nextActiveTab, tabRenderCtx)
        }
      }
      return nextTabs
    })
  }, [activeWorkspaceTabId, tabRenderCtx])
  closeWorkspaceTabNowRef.current = closeWorkspaceTabNow

  /**
   * 任务被删除后：若该任务的任务聊天标签已打开，则直接关闭。
   * 任务已不存在，须绕过任务标签 `onClose` 的运行中守卫（否则会因查不到任务而关闭失败）。
   */
  const closeDeletedTaskTabs = React.useCallback((taskIds: string[]) => {
    for (const taskId of taskIds) {
      closeWorkspaceTabNow(`task:${taskId}`)
    }
  }, [closeWorkspaceTabNow])

  const openGlobalFileTab = React.useCallback((
    target: {
      workspaceRoot: string
      filePath: string
    },
    options?: OpenWorkspaceFileOptions,
  ) => {
    const fileTabId = buildFileTabId(target)
    const nextFileTab = createWorkspaceFileTab({
      id: fileTabId,
      workspaceRoot: target.workspaceRoot,
      filePath: target.filePath,
      fileName: getFileNameFromPath(target.filePath),
      mode: options?.mode ?? 'readonly',
      reloadKey: Date.now(),
      nameEditRequestedAt: options?.startNameEditing ? Date.now() : undefined,
      lineNumber: options?.lineNumber,
      lineLocateRequestedAt: options?.lineNumber !== undefined ? Date.now() : undefined,
    })
    openWorkspaceTab(nextFileTab)
    return fileTabId
  }, [openWorkspaceTab])

  const openDiffTab = React.useCallback((
    input: {
      filePath: string
      fileName: string
      changeType: 'created' | 'updated'
      beforeContent: string
      afterContent: string
      workspaceRoot?: string
      title?: string
    },
  ): string => {
    const tabTitle = input.title && input.title.trim()
      ? input.title.trim()
      : `变更对比：${input.fileName}`
    const nextDiffTab = createWorkspaceDiffTab({
      filePath: input.filePath,
      fileName: input.fileName,
      title: tabTitle,
      changeType: input.changeType,
      beforeContent: input.beforeContent,
      afterContent: input.afterContent,
      workspaceRoot: input.workspaceRoot,
    })
    openWorkspaceTab(nextDiffTab)
    return nextDiffTab.id
  }, [openWorkspaceTab])

  const openTopLevelPage = React.useCallback((pageId: TopLevelPageId) => {
    const nextPageTab = createWorkspacePageTab(pageId)
    openWorkspaceTab(nextPageTab)
  }, [openWorkspaceTab])

  const openTaskChatTab = React.useCallback((tab: TaskChatTabInput): WorkspaceTaskChatTab['id'] => {
    const nextTaskTab = createWorkspaceTaskChatTab(tab)
    openWorkspaceTab(nextTaskTab)
    return nextTaskTab.id
  }, [openWorkspaceTab])

  /** 新建任务:复用唯一草稿标签(已存在则激活);带预设工作区/worker 先切草稿预设(草稿文本保留)。
   *  无预设(左侧栏/空首页通用新建入口)时清空预设,保持手动选择 worker/工作区。 */
  const openDraftTaskTab = React.useCallback((
    preset?: { workspace?: string; workerId?: string },
  ): WorkspaceTaskChatTab['id'] => {
    if (preset && (preset.workspace || preset.workerId)) {
      setDraftPreset(preset)
    } else {
      setDraftPreset({ workspace: '', workerId: '' })
    }
    return openTaskChatTab({ taskId: DRAFT_TASK_ID, title: '新任务' })
  }, [openTaskChatTab])

  const openPluginTab = React.useCallback((pluginTabType: string, data: Record<string, string>, title?: string): string | null => {
    const def = getWorkspaceTabTypeDefinition(pluginTabType)
    const pluginId = def?.pluginId ?? pluginTabType
    const key = data.id ?? Object.values(data)[0] ?? pluginTabType
    const tabTitle = title && title.trim() ? title.trim() : (data.title ?? key)
    const nextTab = createWorkspacePluginTab({
      id: `${pluginTabType}:${key}` as const,
      pluginTabType,
      pluginId,
      data,
      title: tabTitle,
    })
    openWorkspaceTab(nextTab)
    return nextTab.id
  }, [openWorkspaceTab])

  const closeGlobalFileTab = React.useCallback((fileTabId: `file:${string}`) => {
    closeWorkspaceTabNow(fileTabId)
  }, [closeWorkspaceTabNow])

  const setActiveWorkspaceTab = React.useCallback((tabId: WorkspaceTab['id']) => {
    const nextTab = workspaceTabs.find((item) => item.id === tabId) ?? null
    if (!nextTab) return
    activateWorkspaceTab(nextTab, { collapseSidebar: true })
  }, [activateWorkspaceTab, workspaceTabs])

  const setActiveGlobalFileTab = React.useCallback((fileTabId: `file:${string}`) => {
    const nextTab = workspaceFileTabs.find((item) => item.id === fileTabId) ?? null
    if (!nextTab) return
    activateWorkspaceTab(nextTab, { collapseSidebar: true })
  }, [activateWorkspaceTab, workspaceFileTabs])

  const setGlobalFileTabMode = React.useCallback((fileTabId: `file:${string}`, mode: 'readwrite' | 'readonly') => {
    setWorkspaceTabs((current) => setWorkspaceFileTabMode(current, fileTabId, mode))
  }, [])

  const renameFileTabs = React.useCallback((
    workspaceRoot: string,
    oldPath: string,
    newPath: string,
  ) => {
    const resolveRenamedPath = (currentPath: string): string | null => {
      if (currentPath === oldPath) return newPath
      if (currentPath.startsWith(`${oldPath}/`)) return `${newPath}${currentPath.slice(oldPath.length)}`
      return null
    }
    setWorkspaceTabs((current) => current.map((item) => {
      if (item.tabType !== 'file' || item.workspaceRoot !== workspaceRoot) {
        return item
      }
      const replacedPath = resolveRenamedPath(item.filePath)
      if (!replacedPath) {
        return item
      }
      return {
        ...item,
        id: buildFileTabId({ ...item, filePath: replacedPath }),
        filePath: replacedPath,
        fileName: getFileNameFromPath(replacedPath),
        reloadKey: Date.now(),
      }
    }))
    setActiveWorkspaceTabId((prev) => {
      if (!prev) return prev
      const activeTab = workspaceTabs.find((item) => item.id === prev) ?? null
      if (!activeTab || activeTab.tabType !== 'file' || activeTab.workspaceRoot !== workspaceRoot) return prev
      const replacedPath = resolveRenamedPath(activeTab.filePath)
      if (!replacedPath) return prev
      return buildFileTabId({ ...activeTab, filePath: replacedPath })
    })
    if (selectedFilePath === oldPath || selectedFilePath?.startsWith(`${oldPath}/`)) {
      const nextSelectedPath = selectedFilePath === oldPath
        ? newPath
        : selectedFilePath
          ? `${newPath}${selectedFilePath.slice(oldPath.length)}`
          : null
      updateSelectedFilePath(nextSelectedPath)
    }
  }, [selectedFilePath, updateSelectedFilePath, workspaceTabs])

  const setWorkspaceFileNameEditRequested = React.useCallback((
    fileTabId: `file:${string}`,
    requestedAt?: number,
  ) => {
    setWorkspaceTabs((current) => setWorkspaceFileTabNameEditRequested(current, fileTabId, requestedAt))
  }, [])

  const setWorkspaceFileLineLocateRequested = React.useCallback((
    fileTabId: `file:${string}`,
    requestedAt?: number,
    lineNumber?: number,
  ) => {
    setWorkspaceTabs((current) => current.map((item) => (
      item.id === fileTabId && item.tabType === 'file'
        ? { ...item, lineNumber, lineLocateRequestedAt: requestedAt }
        : item
    )))
  }, [])

  const requestWorkspaceFileLocate = React.useCallback((filePath: string, workspaceRoot?: string, requestedAt?: number) => {
    setWorkspaceFileLocateRequest({
      filePath,
      workspaceRoot: workspaceRoot ?? null,
      requestedAt: requestedAt ?? Date.now(),
    })
    setSidebarOpen(true)
    setActiveSidebarPanelId('files')
    updateSelectedFilePath(filePath)
  }, [updateSelectedFilePath])

  const clearWorkspaceFileLocateRequest = React.useCallback(() => {
    setWorkspaceFileLocateRequest(null)
  }, [])

  /**
   * 统一请求所有已打开文件页重新读取底层文件内容。
   * fs.changed 事件风暴时由壳层集中 bump reloadKey(资源管理器经事件直接刷新)。
   * 当前正在查看/编辑的标签页跳过 bump,避免重新读盘造成正文闪烁。
   */
  const reloadAllOpenFileTabs = React.useCallback(() => {
    setWorkspaceTabs((current) => current.map((item) => {
      if (item.tabType !== 'file') return item
      if (item.id === activeWorkspaceTabId) return item
      return {
        ...item,
        reloadKey: Date.now() + Math.random(),
      }
    }))
  }, [activeWorkspaceTabId])

  const closeWorkspaceTab = React.useCallback((tabId: WorkspaceTab['id']) => {
    const tab = workspaceTabs.find((item) => item.id === tabId) ?? null
    if (!tab) return
    const def = getTabDefinition(tab)
    if (def?.onClose) {
      def.onClose(tab, tabRenderCtx)
      return
    }
    closeWorkspaceTabNow(tab.id)
  }, [workspaceTabs, closeWorkspaceTabNow, tabRenderCtx])

  React.useEffect(() => {
    const unsubscribeRuntimeError = domainEventBus.subscribe(DOMAIN_EVENTS.RUNTIME_CONFIG_ERROR, ({ message }) => {
      showToast(message, 'error')
    })
    const unsubscribeFileOpenRequested = domainEventBus.subscribe(DOMAIN_EVENTS.WORKSPACE_OPEN_FILE_REQUESTED, ({
      filePath,
      workspaceRoot,
      startNameEditing,
      mode,
      lineNumber,
    }) => {
      openGlobalFileTab({
        // 事件可显式带工作区根;未带(旧发射方)时落默认工作区(D16 无"当前工作区")。
        workspaceRoot: workspaceRoot ?? workspaceRegistry.primaryRoot() ?? workspaceRegistry.current?.defaultRoot ?? '',
        filePath,
      }, {
        startNameEditing,
        mode,
        lineNumber,
      })
    })
    const unsubscribeFileCloseRequested = domainEventBus.subscribe(DOMAIN_EVENTS.WORKSPACE_CLOSE_FILE_REQUESTED, ({
      filePath,
      fileTabId,
      force,
    }) => {
      setWorkspaceTabs((current) => {
        const removedTabIds = new Set<WorkspaceTab['id']>()
        for (const item of current) {
          if (item.tabType !== 'file') continue
          if (fileTabId && item.id === fileTabId) {
            removedTabIds.add(item.id)
            continue
          }
          if (!filePath) {
            continue
          }
          const isPathMatched = item.filePath === filePath
            || (force ? item.filePath.startsWith(`${filePath}/`) : false)
          if (!isPathMatched) {
            continue
          }
          removedTabIds.add(item.id)
        }
        if (removedTabIds.size === 0) return current
        const nextTabs = current.filter((item) => !removedTabIds.has(item.id))
        if (activeWorkspaceTabId && removedTabIds.has(activeWorkspaceTabId)) {
          const nextActiveTab = resolveNextActiveWorkspaceTab(current, nextTabs, activeWorkspaceTabId)
          setActiveWorkspaceTabId(nextActiveTab?.id ?? null)
          if (nextActiveTab) {
            getTabDefinition(nextActiveTab)?.onActivate?.(nextActiveTab, tabRenderCtx)
          } else {
            updateSelectedFilePath(null)
          }
        }
        return nextTabs
      })
    })
    const unsubscribeTaskFocus = domainEventBus.subscribe(DOMAIN_EVENTS.WORKSPACE_FOCUS_TASK_REQUESTED, ({ taskId }) => {
      openTaskChatTab({ taskId, title: taskStore.get(taskId)?.title ?? `任务 ${taskId.slice(0, 8)}` })
    })
    const unsubscribeReloadAllFilesRequested = domainEventBus.subscribe(DOMAIN_EVENTS.WORKSPACE_RELOAD_ALL_FILES_REQUESTED, () => {
      reloadAllOpenFileTabs()
    })

    return () => {
      unsubscribeRuntimeError()
      unsubscribeFileOpenRequested()
      unsubscribeFileCloseRequested()
      unsubscribeTaskFocus()
      unsubscribeReloadAllFilesRequested()
    }
  }, [
    openGlobalFileTab,
    openTaskChatTab,
    reloadAllOpenFileTabs,
    showToast,
    tabRenderCtx,
  ])

  React.useEffect(() => {
    if (activeWorkspaceTabId && !workspaceTabs.some((item) => item.id === activeWorkspaceTabId)) {
      setActiveWorkspaceTabId(workspaceTabs[0]?.id ?? null)
    }
  }, [activeWorkspaceTabId, workspaceTabs])

  React.useEffect(() => {
    if (!activeWorkspaceTabId) {
      updateSelectedFilePath(null)
    }
  }, [activeWorkspaceTabId, updateSelectedFilePath])

  const shellValue = React.useMemo(() => ({
    sidebarOpen,
    activeSidebarPanelId,
    workspaceTabs,
    activeWorkspaceTabId,
    activeWorkspaceTab,
    workspaceFileTabs,
    selectedFilePath,
    workspaceFileLocateRequest,
    setSidebarOpen,
    setActiveSidebarPanel,
    openGlobalFileTab,
    closeGlobalFileTab,
    setActiveGlobalFileTab,
    setGlobalFileTabMode,
    setActiveWorkspaceTab,
    closeWorkspaceTab,
    renameFileTabs,
    setWorkspaceFileNameEditRequested,
    setWorkspaceFileLineLocateRequested,
    requestWorkspaceFileLocate,
    clearWorkspaceFileLocateRequest,
    openTaskChatTab,
    openPluginTab,
    openDiffTab,
  }), [
    activeSidebarPanelId,
    activeWorkspaceTab,
    activeWorkspaceTabId,
    closeGlobalFileTab,
    closeWorkspaceTab,
    openTaskChatTab,
    openGlobalFileTab,
    openPluginTab,
    openDiffTab,
    renameFileTabs,
    selectedFilePath,
    workspaceFileLocateRequest,
    setActiveGlobalFileTab,
    setGlobalFileTabMode,
    setActiveSidebarPanel,
    setActiveWorkspaceTab,
    setWorkspaceFileNameEditRequested,
    setWorkspaceFileLineLocateRequested,
    requestWorkspaceFileLocate,
    clearWorkspaceFileLocateRequest,
    sidebarOpen,
    workspaceFileTabs,
    workspaceTabs,
  ])

  const activeActivityItemIds = React.useMemo(() => {
    const nextIds: Array<SidebarPanelId | 'git' | 'settings'> = []
    if (sidebarOpen) {
      nextIds.push(activeSidebarPanelId)
    }
    const workspaceActivityItemId = activeWorkspaceTab
      ? getTabDefinition(activeWorkspaceTab)?.getSidebarActivityId?.(activeWorkspaceTab) ?? null
      : null
    if (workspaceActivityItemId && !nextIds.includes(workspaceActivityItemId)) {
      nextIds.push(workspaceActivityItemId)
    }
    return nextIds
  }, [activeSidebarPanelId, activeWorkspaceTab, sidebarOpen])

  return (
    <ConfigProvider theme={getAntdTheme(themeMode)}>
      <AntApp className="antd-root" style={{ height: '100%', display: 'flex' }}>
        <AntdAppBridge />
        <WorkspaceShellProvider value={shellValue}>
          <div
            className={`workspace-layout${isMobile ? ' is-mobile' : ' is-desktop'}`}
            style={{
              colorScheme: themeMode,
              display: 'flex',
              flexDirection: isMobile ? 'column' : 'row',
              height: '100dvh',
              background: 'var(--bg-primary)',
              overflow: 'hidden',
              position: 'relative',
              flex: 1,
              minWidth: 0,
              minHeight: 0,
            }}
            data-theme={themeMode}
          >
            {isMobile && (
              <button
                type="button"
                className={`layout-sidebar-overlay titlebar-no-drag${sidebarOpen ? ' is-open' : ''}`}
                aria-label="关闭侧边栏"
                onClick={() => setSidebarOpen(false)}
                style={{ border: 'none', padding: 0, cursor: 'pointer' }}
              />
            )}
        <div
          className={`workspace-layout__sidebar-rail${isMobile ? ' is-mobile' : ' is-desktop'}${sidebarOpen ? ' is-open' : ''}`}
          style={{
            ...leftSidebarRailStyle,
            height: isMobile ? mobileBottomBarHeight : leftSidebarRailStyle.height,
            minHeight: isMobile ? mobileBottomBarHeight : leftSidebarRailStyle.minHeight,
            position: isMobile ? 'fixed' : leftSidebarRailStyle.position,
            inset: isMobile ? 'auto 0 0 0' : undefined,
            width: isMobile ? '100%' : undefined,
            zIndex: isMobile ? 40 : leftSidebarRailStyle.zIndex,
            pointerEvents: isMobile ? 'none' : leftSidebarRailStyle.pointerEvents,
          }}
        >
          <SidebarActivityBar
            items={buildSidebarActivityItems(openTopLevelPageIds)}
            activeItemIds={activeActivityItemIds}
            panelOpen={sidebarOpen}
            onBrandClick={() => {
              openDraftTaskTab()
            }}
            onSelect={(itemId) => {
              if (itemId === 'settings') {
                openTopLevelPage('settings')
                return
              }
              // git 与 tasks/files 一样是侧边栏面板(源代码管理),点击切换面板而非开页面。
              const nextPanelId = itemId as SidebarPanelId
              if (activeSidebarPanelId === nextPanelId) {
                setSidebarOpen(!sidebarOpen)
                return
              }
              setActiveSidebarPanel(nextPanelId)
            }}
          />
          <ResizableSidebarContainer
            isMobile={isMobile}
            open={sidebarOpen}
            desktopSize={desktopSidebarWidth}
            mobileSize={mobileSidebarHeight}
            mobileMaxSize={mobileSidebarMaxHeight}
            onDesktopSizeChange={setDesktopSidebarWidth}
            onMobileSizeChange={setMobileSidebarHeight}
          >
            <div style={sidebarPanelSlotStyle}>
              <SidebarPanelHost panelId="tasks" visible={activeSidebarPanelId === 'tasks'}>
                <LazyTasksPanel
                  embedded
                  activeTaskId={activeTaskId ?? undefined}
                  onCreateNewTask={(preset) => {
                    openDraftTaskTab(preset)
                  }}
                  onSelect={(task) => {
                    openTaskChatTab({ taskId: task.taskId, title: task.displayTitle })
                  }}
                  onDeletedTasks={closeDeletedTaskTabs}
                />
              </SidebarPanelHost>
              <SidebarPanelHost panelId="files" visible={activeSidebarPanelId === 'files'}>
                <LazyOpenFilesSidebarPanel />
              </SidebarPanelHost>
              <SidebarPanelHost panelId="git" visible={activeSidebarPanelId === 'git'}>
                <LazyGitSidebarPanel embedded />
              </SidebarPanelHost>
            </div>
          </ResizableSidebarContainer>
        </div>

        <div
          className="workspace-layout__main"
          style={{
            ...mainWorkspaceStyle,
            marginLeft: 0,
            paddingBottom: isMobile ? mobileBottomBarHeight : 0,
            borderRadius: isMobile ? 'var(--radius-xl) var(--radius-xl) 0 0' : 'var(--radius-xl) 0 0 var(--radius-xl)',
            borderLeft: isMobile ? 'none' : '1px solid var(--border)',
          }}
        >
          <TitleBar />
          <div
            style={{
              ...workspaceBodyStyle,
              display: activeWorkspaceTab ? 'none' : 'flex',
            }}
          >
            <EmptyWorkspaceHome onNewTask={() => openDraftTaskTab()} />
          </div>

          {workspaceTabs.map((tab) => (
            <div
              key={tab.id}
              ref={(node) => {
                if (node) {
                  tabPanelNodes.current.set(tab.id, node)
                } else {
                  tabPanelNodes.current.delete(tab.id)
                }
              }}
              style={{
                ...workspaceBodyStyle,
                display: tab.id === activeWorkspaceTabId ? 'flex' : 'none',
              }}
            >
              <div style={contentPageStyle}>
                {renderWorkspaceTabContent(tab, { ...tabRenderCtx, isActiveTab: tab.id === activeWorkspaceTabId })}
              </div>
            </div>
          ))}
        </div>
        <ToastHost />
        <NotificationHost />
        <UserInteractionHost />
        <PendingUserInteractionIndicator />
        <BrowserNotificationHost />
        <BrowserNotificationGuide />
          </div>
        </WorkspaceShellProvider>
      </AntApp>
    </ConfigProvider>
  )
}

function renderWorkspaceTabContent(
  tab: WorkspaceTab,
  ctx: WorkspaceTabRenderContext,
) {
  const def = getTabDefinition(tab)
  if (!def) return null
  return def.renderTab(tab, ctx)
}

function buildSidebarActivityItems(openTopLevelPageIds: TopLevelPageId[]) {
  return [
    { id: 'tasks', label: '任务', icon: <TaskChatIcon /> },
    { id: 'files', label: '文件', icon: <FilesIcon /> },
    { id: 'git', label: '源代码管理', icon: <GitIcon /> },
    { id: 'settings', label: '设置', icon: <SettingsIcon />, badgeCount: openTopLevelPageIds.includes('settings') ? 1 : undefined },
  ]
}

/**
 * 侧边栏面板槽位：所有面板常驻内存，仅在激活态显示、非激活态用 display:none 隐藏，
 * 切换面板不卸载组件（保留滚动位置、内部草稿等状态）。
 * 面板从隐藏变为显示时，广播 `SIDEBAR_PANEL_SHOWN` 事件（携带 panelId），供面板按需刷新。
 */
function SidebarPanelHost({
  panelId,
  visible,
  children,
}: {
  panelId: string
  visible: boolean
  children: React.ReactNode
}) {
  const prevVisibleRef = React.useRef(visible)

  React.useEffect(() => {
    const wasVisible = prevVisibleRef.current
    prevVisibleRef.current = visible
    if (!wasVisible && visible) {
      domainEventBus.emit(DOMAIN_EVENTS.SIDEBAR_PANEL_SHOWN, { panelId })
    }
  }, [panelId, visible])

  return (
    <div
      style={{
        position: 'absolute',
        inset: 0,
        display: visible ? 'flex' : 'none',
        flexDirection: 'column',
        minWidth: 0,
        minHeight: 0,
        overflow: 'hidden',
      }}
    >
      {children}
    </div>
  )
}

function resolveNextActiveWorkspaceTab(
  currentTabs: WorkspaceTab[],
  nextTabs: WorkspaceTab[],
  closingTabId: WorkspaceTab['id'],
) {
  const index = currentTabs.findIndex((item) => item.id === closingTabId)
  if (index < 0) return null
  return nextTabs[Math.max(0, index - 1)] ?? nextTabs[index] ?? nextTabs[0] ?? null
}

function buildFileTabId(target: {
  workspaceRoot: string
  filePath: string
}): `file:${string}` {
  // 多工作区并行:同相对路径分属不同工作区的文件是两个标签。
  return `file:${target.workspaceRoot}::${target.filePath}` as const
}

function getFileNameFromPath(filePath: string): string {
  const slashIndex = filePath.lastIndexOf('/')
  return slashIndex >= 0 ? filePath.slice(slashIndex + 1) : filePath
}

function EmptyWorkspaceHome({ onNewTask }: { onNewTask: () => void }) {
  return (
    <div style={emptyWorkspaceStyle}>
      <div
        role="button"
        tabIndex={0}
        title="双击新建任务"
        onDoubleClick={onNewTask}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault()
            onNewTask()
          }
        }}
        style={emptyWorkspaceBrandMarkHitStyle}
      >
        <BrandMark size={108} animated={false} />
      </div>
    </div>
  )
}

const contentPageStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  minHeight: 0,
  overflow: 'hidden',
  display: 'flex',
  flexDirection: 'column',
}

const emptyWorkspaceStyle: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  justifyContent: 'center',
  gap: 18,
  padding: 28,
}

const emptyWorkspaceBrandMarkHitStyle: React.CSSProperties = {
  display: 'inline-flex',
  borderRadius: 28,
  padding: 6,
  cursor: 'pointer',
  userSelect: 'none',
}

const emptyWorkspaceHintStyle: React.CSSProperties = {
  fontSize: 'var(--text-sm)',
  color: 'var(--text-muted)',
  letterSpacing: 0.2,
}

const leftSidebarRailStyle: React.CSSProperties = {
  display: 'flex',
  height: '100%',
  flexShrink: 0,
  minHeight: 0,
  position: 'relative',
  zIndex: 3,
  pointerEvents: 'auto',
}

const mainWorkspaceStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
  overflow: 'hidden',
  background: 'var(--bg-primary)',
}

const workspaceBodyStyle: React.CSSProperties = {
  flex: 1,
  minWidth: 0,
  minHeight: 0,
  overflow: 'hidden',
}

const sidebarPanelSlotStyle: React.CSSProperties = {
  position: 'absolute',
  inset: 0,
  minWidth: 0,
  minHeight: 0,
  overflow: 'hidden',
  width: '100%',
  height: '100%',
  display: 'flex',
  flexDirection: 'column',
}
